"""Sandbox-only feasibility probe. Does not authorize/ship goods or record a payment.
Run inside the existing gateway container, which supplies credentials privately.
A durable marker prevents repeating a write when a network result is ambiguous.
"""
import copy
import hashlib
import json
import os
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

REPORT = Path('/data/sandbox-credit-probe.json')
BASE = 'https://dev-api.jushuitan.com'


def call(path, biz):
    assert os.getenv('JST_ENV') == 'sandbox', 'Sandbox required'
    p = {'app_key': os.environ['JST_APP_KEY'], 'access_token': os.environ['JST_ACCESS_TOKEN'],
         'timestamp': str(int(time.time())), 'charset': 'utf-8', 'version': '2',
         'biz': json.dumps(biz, ensure_ascii=False, separators=(',', ':'))}
    p['sign'] = hashlib.md5((os.environ['JST_APP_SECRET'] + ''.join(k + p[k] for k in sorted(p))).encode()).hexdigest()
    request = urllib.request.Request(BASE + path, data=urllib.parse.urlencode(p).encode(),
                                     headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=25) as r:
        return json.load(r)


def save(report):
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2))


def query(report):
    r = call('/open/orders/single/query', {'shop_id': report['shop_id'], 'so_ids': [report['so_id']],
                                         'page_index': 1, 'page_size': 10})
    assert r.get('code') == 0, ('Query failed', r.get('code'))
    return [{k: row.get(k) for k in ['so_id', 'o_id', 'status', 'shop_status', 'pay_amount',
                                     'paid_amount', 'is_order_pay', 'question_desc']}
            for row in r.get('data', {}).get('orders', [])]


def run():
    assert os.getenv('JST_ENV') == 'sandbox'
    if REPORT.exists():
        report = json.loads(REPORT.read_text())
    else:
        source = json.loads(Path('/data/sandbox-valid-sku-test.json').read_text())
        order = copy.deepcopy(source['request'])
        order_id = 'EMIE-CREDIT-' + datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d%H%M%S')
        now = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S')
        order.update({'so_id': order_id, 'order_date': now, 'shop_buyer_id': order_id,
                      'shop_status': 'WAIT_BUYER_PAY', 'pay_amount': 0.01,
                      'remark': 'CRM账期可行性测试：应收0.01，实收0。请勿审核、勿实际发货。',
                      'question_desc': 'CRM账期接口测试，禁止实际发货'})
        order.pop('pay', None)
        order['items'][0]['outer_oi_id'] = order_id + '-1'
        report = {'environment': 'sandbox', 'so_id': order_id, 'shop_id': order['shop_id'],
                  'order': order, 'attempted': False, 'steps': []}
        save(report)
    if report['attempted']:
        report['current'] = query(report)
        save(report)
        print(json.dumps({'reused': True, 'so_id': report['so_id'], 'steps': report['steps'], 'current': report['current']}, ensure_ascii=False))
        return
    report['attempted'] = True
    save(report)
    response = call('/open/jushuitan/orders/upload', [report['order']])
    step = {'case': 'WAIT_BUYER_PAY, no pay node', 'response': response, 'orders': query(report)}
    report['steps'].append(step)
    save(report)
    print(json.dumps(step, ensure_ascii=False), flush=True)
    # Updating the same order preserves identity; amount zero records no received money.
    order = copy.deepcopy(report['order'])
    order['shop_status'] = 'WAIT_SELLER_SEND_GOODS'
    order['pay'] = {'outer_pay_id': report['so_id'] + '-ZERO', 'pay_date': order['order_date'],
                    'payment': '账期测试零实收', 'seller_account': 'EMIE-TEST', 'buyer_account': 'EMIE-TEST', 'amount': 0}
    report['zero_payment_update_attempted'] = True
    save(report)
    response = call('/open/jushuitan/orders/upload', [order])
    step = {'case': 'WAIT_SELLER_SEND_GOODS, pay.amount=0', 'response': response, 'orders': query(report)}
    report['steps'].append(step)
    report['current'] = step['orders']
    save(report)
    print(json.dumps(step, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    run()

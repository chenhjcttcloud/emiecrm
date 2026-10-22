"""Private fixed-egress JST gateway. No open proxy, no warehouse shipment actions."""
from contextlib import closing
import hashlib
import hmac
import json
import os
import signal
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import HTTPServer, BaseHTTPRequestHandler

READ_PATHS = {'/open/shops/query', '/open/inventory/query', '/open/orders/single/query',
              '/open/orders/out/simple/query', '/open/logistic/query', '/open/sku/query', '/open/skumap/query'}
WRITE_PATHS = {'/open/jushuitan/orderaddress/update', '/open/jushuitan/order/remark/upload',
               '/open/jushuitan/orderbyoid/cancel'}
DATABASE = os.getenv('GATEWAY_DATABASE', '/data/audit.sqlite3')


def configured():
    return all(os.getenv(k) for k in ('JST_APP_KEY', 'JST_APP_SECRET', 'JST_ACCESS_TOKEN'))


def environment():
    return os.getenv('JST_ENV', 'sandbox')


def sign(params, secret):
    return hashlib.md5((secret + ''.join(k + str(params[k]) for k in sorted(params)
                                       if k != 'sign' and params[k] != '')).encode()).hexdigest()


def upstream(path, biz):
    params = {'app_key': os.environ['JST_APP_KEY'], 'access_token': os.environ['JST_ACCESS_TOKEN'],
              'timestamp': str(int(time.time())), 'charset': 'utf-8', 'version': '2',
              'biz': json.dumps(biz, ensure_ascii=False, separators=(',', ':'))}
    params['sign'] = sign(params, os.environ['JST_APP_SECRET'])
    origin = 'https://dev-api.jushuitan.com' if environment() == 'sandbox' else 'https://openapi.jushuitan.com'
    request = urllib.request.Request(origin + path, data=urllib.parse.urlencode(params).encode(),
                                     headers={'Content-Type': 'application/x-www-form-urlencoded'})
    # Do not inherit host proxy environment variables: preserve fixed IP egress.
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=25) as response:
        raw = response.read(2097153)
        if len(raw) > 2097152:
            raise ValueError('Response too large')
        result = json.loads(raw)
        if not isinstance(result, dict) or 'code' not in result:
            raise ValueError('Invalid response')
        return result


def initialize():
    if environment() not in ('sandbox', 'production'):
        raise ValueError('Invalid JST_ENV')
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute('CREATE TABLE IF NOT EXISTS calls(created_at INTEGER,path TEXT,http_status INTEGER)')
        db.execute('CREATE TABLE IF NOT EXISTS mutations(env TEXT,id TEXT,digest TEXT,response TEXT,PRIMARY KEY(env,id))')
        db.execute('CREATE TABLE IF NOT EXISTS uploads(env TEXT,shop TEXT,so TEXT,digest TEXT,status TEXT,response TEXT,updated INTEGER,PRIMARY KEY(env,shop,so))')


def upload_once(order):
    key = (environment(), str(order['shop_id']), order['so_id'])
    digest = hashlib.sha256(json.dumps(order, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT digest,status,response,updated FROM uploads WHERE env=? AND shop=? AND so=?', key).fetchone()
        if row:
            if not hmac.compare_digest(row[0], digest):
                return 409, {'error': 'Order payload differs from previous submission'}
            if row[1] == 'accepted':
                return 200, json.loads(row[2])
            if row[1] == 'unknown' and int(time.time()) - row[3] < 300:
                return 409, {'error': 'Outcome unknown; reconcile order before retry'}
        db.execute('INSERT OR REPLACE INTO uploads VALUES(?,?,?,?,?,?,?)', (*key, digest, 'unknown', None, int(time.time())))
    # Outcome is persisted as unknown BEFORE the outbound call. Never retry automatically.
    try:
        result = upstream('/open/jushuitan/orders/upload', [order])
    except Exception:
        return 502, {'error': 'Outcome unknown; reconcile order before retry'}
    rows = result.get('data', {}).get('datas', []) if isinstance(result.get('data'), dict) else []
    rows = rows or result.get('datas', [])
    match = next((r for r in rows if r.get('so_id') == order['so_id']), None)
    state = 'unknown'
    if match and match.get('issuccess') and match.get('o_id'):
        state = 'accepted'
    elif match and match.get('issuccess') is False and not match.get('o_id'):
        state = 'rejected'
    elif result.get('code') in (120, 130, 140, 190):
        state = 'rejected'
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute('UPDATE uploads SET status=?,response=?,updated=? WHERE env=? AND shop=? AND so=?',
                   (state, json.dumps(result, ensure_ascii=False), int(time.time()), *key))
    return 200, result


def mutate_once(body):
    path, biz, identity = body['path'], body['biz'], body['id']
    if path not in WRITE_PATHS or not isinstance(biz, dict) or not isinstance(identity, str) or not 1 <= len(identity) <= 80:
        raise ValueError('Invalid mutation')
    if path.endswith('/cancel'):
        if (set(biz) - {'o_ids', 'cancel_type', 'remark'} or not isinstance(biz.get('o_ids'), list)
                or len(biz['o_ids']) != 1 or type(biz['o_ids'][0]) is not int or biz['o_ids'][0] <= 0
                or not isinstance(biz.get('cancel_type'), str) or not biz['cancel_type'].strip()):
            raise ValueError('Invalid cancellation')
    else:
        allowed = ({'o_id', 'shop_id', 'receiver_name', 'receiver_mobile', 'receiver_phone', 'receiver_state',
                    'receiver_city', 'receiver_district', 'receiver_address'} if path.endswith('/update')
                   else {'o_id', 'remark'})
        if set(biz) - allowed or type(biz.get('o_id')) is not int or biz['o_id'] <= 0:
            raise ValueError('Invalid order')
        if path.endswith('/update') and (type(biz.get('shop_id')) is not int or biz['shop_id'] <= 0):
            raise ValueError('Invalid shop')
        if path.endswith('/upload') and (not isinstance(biz.get('remark'), str) or len(biz['remark']) > 500):
            raise ValueError('Invalid remark')
    digest = hashlib.sha256(json.dumps({'path': path, 'biz': biz}, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT digest,response FROM mutations WHERE env=? AND id=?', (environment(), identity)).fetchone()
        if row:
            if not hmac.compare_digest(row[0], digest):
                return 409, {'error': 'Mutation payload differs'}
            return (200, json.loads(row[1])) if row[1] else (409, {'error': 'Outcome unknown; reconcile before another mutation'})
        db.execute('INSERT INTO mutations VALUES(?,?,?,NULL)', (environment(), identity, digest))
    try:
        result = upstream(path, biz)
    except Exception:
        return 502, {'error': 'Outcome unknown; reconcile before another mutation'}
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute('UPDATE mutations SET response=? WHERE env=? AND id=?',
                   (json.dumps(result, ensure_ascii=False), environment(), identity))
    return 200, result


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *args):
        pass  # Never log headers, order bodies, or credentials.

    def reply(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self):
        key = os.getenv('GATEWAY_TOKEN', '')
        return bool(key) and hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + key)

    def do_GET(self):
        if self.path == '/health':
            return self.reply(200, {'status': 'ok', 'service': 'emie-jst'})
        if not self.authorized():
            return self.reply(401, {'error': 'unauthorized'})
        if self.path == '/ready':
            return self.reply(200 if configured() else 503, {'configured': configured(), 'environment': environment(),
                              'order_upload_enabled': os.getenv('JST_ALLOW_ORDER_UPLOAD') == '1',
                              'order_change_enabled': os.getenv('JST_ALLOW_ORDER_CHANGE') == '1', 'callbacks_enabled': False})
        self.reply(404, {'error': 'not_found'})

    def do_POST(self):
        if not self.authorized():
            return self.reply(401, {'error': 'unauthorized'})
        if self.path not in ('/v1/query', '/v1/orders', '/v1/mutations'):
            return self.reply(404, {'error': 'not_found'})
        expected = self.headers.get('X-CRM-JST-Environment')
        if expected and expected != environment():
            return self.reply(409, {'error': 'environment_mismatch'})
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, {'error': 'unsupported_encoding'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 262144:
                return self.reply(413, {'error': 'body_size'})
            body = json.loads(self.rfile.read(size))
            if self.path == '/v1/query':
                if not isinstance(body, dict) or not isinstance(body.get('biz', {}), dict):
                    raise ValueError()
                path = body.get('path')
                if not isinstance(path, str) or path not in READ_PATHS:
                    return self.reply(403, {'error': 'path_not_allowed'})
            elif self.path == '/v1/mutations':
                if os.getenv('JST_ALLOW_ORDER_CHANGE') != '1' or expected != environment():
                    return self.reply(403, {'error': 'order_change_disabled_or_environment_missing'})
                if not isinstance(body, dict) or body.get('path') not in WRITE_PATHS or not isinstance(body.get('biz'), dict) or not isinstance(body.get('id'), str):
                    raise ValueError()
                path = body['path']
            else:
                if os.getenv('JST_ALLOW_ORDER_UPLOAD') != '1' or expected != environment():
                    return self.reply(403, {'error': 'order_upload_disabled_or_environment_missing'})
                if not isinstance(body, list) or len(body) != 1 or not isinstance(body[0], dict):
                    raise ValueError()
                order = body[0]
                if (not isinstance(order.get('shop_id'), int) or order['shop_id'] <= 0 or
                    not isinstance(order.get('so_id'), str) or not order['so_id'].startswith('CRM-') or
                    len(order['so_id']) > 41 or order.get('shop_status') != 'WAIT_SELLER_SEND_GOODS' or
                    not isinstance(order.get('items'), list) or not order['items']):
                    raise ValueError()
                path = '/open/jushuitan/orders/upload'
        except (ValueError, TypeError, TimeoutError):
            return self.reply(400, {'error': 'invalid_request'})
        if not configured():
            return self.reply(503, {'error': 'jst_credentials_not_configured'})
        try:
            if self.path == '/v1/mutations':
                status, result = mutate_once(body)
            elif self.path == '/v1/orders':
                status, result = upload_once(body[0])
            else:
                result = upstream(path, body.get('biz', {}))
                status = 200
        except ValueError:
            status, result = 400, {'error': 'invalid_request'}
        except Exception:
            status, result = 502, {'error': 'upstream_failed'}
        with closing(sqlite3.connect(DATABASE)) as db, db:
            db.execute('INSERT INTO calls VALUES(?,?,?)', (int(time.time()), path, status))
            db.execute('DELETE FROM calls WHERE created_at < ?', (int(time.time()) - 30 * 86400,))
        self.reply(status, result)


if __name__ == '__main__':
    initialize()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    HTTPServer(('0.0.0.0', 8080), Handler).serve_forever()

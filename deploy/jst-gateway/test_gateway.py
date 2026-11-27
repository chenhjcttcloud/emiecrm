import importlib.util
from contextlib import closing, contextmanager
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0, str(Path(__file__).parent))

spec = importlib.util.spec_from_file_location('gateway', Path(__file__).with_name('server.py'))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = gateway.DATABASE
        gateway.DATABASE = str(Path(self.tmp.name) / 'test.sqlite')
        self.env = patch.dict(os.environ, {'JST_ENV': 'sandbox', 'JST_TOKEN_DIRECTORY':self.tmp.name+'/tokens', 'JST_APP_KEY':'fixture-app', 'JST_APP_SECRET':'fixture-secret', 'JST_ACCESS_TOKEN':'fixture-old'})
        self.env.start()
        gateway.initialize()
        self.order = {'shop_id': 1, 'so_id': 'CRM-unique', 'items': [{'sku_id': 'A', 'qty': 1}]}
        self.success = {'code': 0, 'data': {'datas': [{'so_id': 'CRM-unique', 'issuccess': True, 'o_id': 123}]}}

    def tearDown(self):
        self.env.stop()
        gateway.DATABASE = self.old
        self.tmp.cleanup()

    @contextmanager
    def order_endpoint(self):
        import threading
        import urllib.error
        import urllib.request
        from http.server import HTTPServer

        with patch.dict(os.environ, {'GATEWAY_TOKEN': 'fixture-bearer', 'JST_ALLOW_ORDER_UPLOAD': '1'}), \
                patch.object(gateway, 'configured', return_value=True):
            server = HTTPServer(('127.0.0.1', 0), gateway.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                def post(order, headers=None):
                    request_headers = {'Authorization': 'Bearer fixture-bearer', 'X-CRM-JST-Environment': 'sandbox',
                                       'Content-Type': 'application/json'}
                    if headers:
                        request_headers.update(headers)
                    request = urllib.request.Request('http://127.0.0.1:' + str(server.server_port) + '/v1/orders',
                                                     headers=request_headers, data=json.dumps([order]).encode())
                    try:
                        response = urllib.request.urlopen(request, timeout=3)
                    except urllib.error.HTTPError as failure:
                        response = failure
                    with response:
                        return response.status, json.load(response)
                yield post
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def numbered_order(self, number='N2611260001', shop=1):
        return {**self.order, 'shop_id': shop, 'so_id': number, 'shop_status': 'WAIT_SELLER_SEND_GOODS'}

    def accepted_number(self, path, orders):
        self.assertEqual('/open/jushuitan/orders/upload', path)
        self.assertEqual(1, len(orders))
        return {'code': 0, 'data': {'datas': [{'so_id': orders[0]['so_id'], 'issuccess': True, 'o_id': 123}]}}

    def test_external_number_predicate_matches_java_short_numbers_and_preserves_legacy_prefix_contract(self):
        for number in ['N2611260001', 'N26112610000', 'N0002290001', 'N9912319223372036854775807',
                       'N261126' + '0' * 24 + '1', 'CRM-', 'CRM-unique', 'CRM-' + 'x' * 37]:
            with self.subTest(number=number):
                self.assertTrue(gateway.valid_external_order_number(number))
        for number in [None, 2611260001, True, '', 'S2611260001', 'n2611260001', 'foreign-N2611260001',
                       'N261126000', 'N2611260000', 'N261126-001', 'N2611260001.0', 'N2602290001',
                       'N2613310001', 'N2611000001', 'N2611269223372036854775808', 'N261126' + '0' * 25 + '1',
                       'N2611260001\n', 'N26112600\x0001', 'N２６１１２６0001', 'CRM-' + 'x' * 38]:
            with self.subTest(number=number):
                self.assertFalse(gateway.valid_external_order_number(number))

    def test_valid_numbered_uploads_pass_the_http_route_without_changing_native_payload(self):
        with patch.object(gateway, 'upstream', side_effect=self.accepted_number) as upstream, self.order_endpoint() as post:
            for number in ['N2611260001', 'N26112610000', 'N0002290001', 'N9912319223372036854775807']:
                order = self.numbered_order(number)
                status, result = post(order)
                self.assertEqual(200, status)
                self.assertEqual(number, result['data']['datas'][0]['so_id'])
                upstream.assert_called_with('/open/jushuitan/orders/upload', [order])
            self.assertEqual(4, upstream.call_count)

    def test_malformed_numbered_uploads_are_rejected_before_upstream_or_idempotency_insert(self):
        with patch.object(gateway, 'upstream') as upstream, self.order_endpoint() as post:
            for number in ['N2602290001', 'N2611260000', 'N2611269223372036854775808',
                           'N261126' + '0' * 25 + '1', 'OTHER-2611260001', 'N2611260001\n', None]:
                with self.subTest(number=number):
                    self.assertEqual((400, {'error': 'invalid_request'}), post(self.numbered_order(number)))
            upstream.assert_not_called()
        import sqlite3
        with closing(sqlite3.connect(gateway.DATABASE)) as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM uploads').fetchone()[0])

    def test_native_order_labels_are_forwarded_and_cannot_change_an_existing_upload(self):
        with patch.object(gateway, 'upstream', side_effect=self.accepted_number) as upstream, self.order_endpoint() as post:
            for number, labels in [('N2611270001', '亿觅CRM推送'), ('N2611270002', None)]:
                order = self.numbered_order(number)
                if labels is not None:
                    order['labels'] = labels
                first = post(order)
                self.assertEqual(200, first[0])
                upstream.assert_called_with('/open/jushuitan/orders/upload', [order])
                self.assertEqual(first, post(json.loads(json.dumps(order))))
                self.assertEqual(409, post({**order, 'labels': '不同标签'})[0])
            self.assertEqual(2, upstream.call_count)

    def test_numbered_identity_replay_and_changed_payload_keep_existing_shop_scoped_idempotency(self):
        with patch.object(gateway, 'upstream', side_effect=self.accepted_number) as upstream, self.order_endpoint() as post:
            order = self.numbered_order()
            first = post(order)
            self.assertEqual(200, first[0])
            self.assertEqual(first, post(json.loads(json.dumps(order))))
            self.assertEqual(1, upstream.call_count)
            changed = {**order, 'items': [{'sku_id': 'B', 'qty': 1}]}
            self.assertEqual(409, post(changed)[0])
            self.assertEqual(1, upstream.call_count)
            self.assertEqual(200, post(self.numbered_order(shop=2))[0])
            self.assertEqual(2, upstream.call_count)

    def test_numbered_upload_keeps_environment_and_upload_authorization_gates(self):
        with patch.object(gateway, 'upstream') as upstream, self.order_endpoint() as post:
            self.assertEqual(401, post(self.numbered_order(), {'Authorization': 'Bearer wrong'})[0])
            self.assertEqual(409, post(self.numbered_order(), {'X-CRM-JST-Environment': 'production'})[0])
            self.assertEqual(403, post(self.numbered_order(), {'X-CRM-JST-Environment': ''})[0])
            with patch.dict(os.environ, {'JST_ALLOW_ORDER_UPLOAD': '0'}):
                self.assertEqual(403, post(self.numbered_order())[0])
            upstream.assert_not_called()

    def test_unknown_numbered_upload_is_not_repeated_while_gateway_outcome_is_uncertain(self):
        with patch.object(gateway, 'upstream', side_effect=TimeoutError) as upstream, self.order_endpoint() as post:
            order = self.numbered_order()
            self.assertEqual(502, post(order)[0])
            self.assertEqual(409, post(order)[0])
            self.assertEqual(1, upstream.call_count)

    def test_replay_returns_cached_acceptance_without_second_upload(self):
        with patch.object(gateway, 'upstream', return_value=self.success) as upstream:
            self.assertEqual(200, gateway.upload_once(self.order)[0])
            self.assertEqual(self.success, gateway.upload_once(self.order)[1])
            self.assertEqual(1, upstream.call_count)

    def test_different_payload_same_identity_is_rejected(self):
        with patch.object(gateway, 'upstream', return_value=self.success) as upstream:
            gateway.upload_once(self.order)
            changed = {**self.order, 'items': [{'sku_id': 'B', 'qty': 1}]}
            self.assertEqual(409, gateway.upload_once(changed)[0])
            self.assertEqual(1, upstream.call_count)

    def test_timeout_does_not_automatically_repeat_upload(self):
        with patch.object(gateway, 'upstream', side_effect=TimeoutError) as upstream:
            self.assertEqual(502, gateway.upload_once(self.order)[0])
            self.assertEqual(409, gateway.upload_once(self.order)[0])
            self.assertEqual(1, upstream.call_count)

    def test_known_rejection_can_be_retried_after_permissions_fixed(self):
        with patch.object(gateway, 'upstream', side_effect=[{'code': 190}, self.success]) as upstream:
            self.assertEqual(190, gateway.upload_once(self.order)[1]['code'])
            self.assertEqual(0, gateway.upload_once(self.order)[1]['code'])
            self.assertEqual(2, upstream.call_count)

    def mutation(self):
        return {'id': 'change-1', 'path': '/open/jushuitan/orderaddress/update',
                'biz': {'shop_id': 1, 'o_id': 123, 'receiver_address': '测试地址'}}

    def test_mutation_replay_does_not_repeat_external_write(self):
        body = self.mutation()
        with patch.object(gateway, 'upstream', return_value={'code': 0}) as upstream:
            self.assertEqual(200, gateway.mutate_once(body)[0])
            self.assertEqual(200, gateway.mutate_once(body)[0])
            self.assertEqual(1, upstream.call_count)
            body['biz']['receiver_address'] = '另一个地址'
            self.assertEqual(409, gateway.mutate_once(body)[0])
            self.assertEqual(1, upstream.call_count)

    def test_unknown_mutation_is_never_blindly_retried(self):
        with patch.object(gateway, 'upstream', side_effect=TimeoutError) as upstream:
            self.assertEqual(502, gateway.mutate_once(self.mutation())[0])
            self.assertEqual(409, gateway.mutate_once(self.mutation())[0])
            self.assertEqual(1, upstream.call_count)

    def test_mutations_reject_unlisted_paths_fields_and_invalid_ids(self):
        for path, biz in [('/open/orders/wms/sent/upload', {'o_id': 123}),
                          ('/open/jushuitan/orderaddress/update', {'o_id': 123, 'shop_id': 1, 'items': []}),
                          ('/open/jushuitan/orderbyoid/cancel', {'o_ids': [123, 456], 'cancel_type': '不需要了'}),
                          ('/open/jushuitan/order/remark/upload', {'o_id': True, 'remark': 'test'})]:
            with self.assertRaises(ValueError), patch.object(gateway, 'upstream') as upstream:
                gateway.mutate_once({'id': 'invalid', 'path': path, 'biz': biz})
            upstream.assert_not_called()

    def test_business_requests_use_persisted_rotated_token(self):
        with gateway.token_store.locked():
            state=gateway.token_store.load();state['access_token']='rotated';gateway.token_store.save(state)
        with patch.object(gateway,'send_form',return_value={'code':0}) as send:
            gateway.upstream('/open/sku/query',{'sku_ids':['fixture']})
        self.assertEqual('rotated',send.call_args.args[1]['access_token'])

    def test_ready_endpoint_authenticates_and_never_returns_credentials(self):
        import threading
        import urllib.request
        from http.server import HTTPServer
        with patch.dict(os.environ,{'GATEWAY_TOKEN':'fixture-bearer'}):
            server=HTTPServer(('127.0.0.1',0),gateway.Handler)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                url='http://127.0.0.1:'+str(server.server_port)+'/ready'
                with self.assertRaises(urllib.error.HTTPError) as err:urllib.request.urlopen(url)
                self.assertEqual(401,err.exception.code)
                err.exception.close()
                request=urllib.request.Request(url,headers={'Authorization':'Bearer fixture-bearer'})
                with urllib.request.urlopen(request) as response:body=response.read().decode()
                report=json.loads(body)
                self.assertIn('authorization',report)
                for secret in ['fixture-old','fixture-secret','fixture-bearer']:self.assertNotIn(secret,body)
            finally:
                server.shutdown();server.server_close();thread.join()

    def test_refresh_routes_are_environment_specific_and_not_business_paths(self):
        self.assertNotIn('/openWeb/auth/refreshToken',gateway.READ_PATHS | gateway.WRITE_PATHS)
        with patch.object(gateway,'send_form',return_value={'code':0}) as send:
            gateway.refresh_token({'refresh_token':'fixture'})
            self.assertEqual('/openWebIsv/auth/refreshToken',send.call_args.args[0])
            with patch.dict(os.environ,{'JST_ENV':'production'}):gateway.refresh_token({})
            self.assertEqual('/openWeb/auth/refreshToken',send.call_args.args[0])

    def test_query_sign_is_lowercase_md5_and_ignores_sign_itself(self):
        import hashlib
        self.assertEqual(hashlib.md5('secretapp_keyAbiz{}'.encode()).hexdigest(),
                         gateway.sign({'biz': '{}', 'app_key': 'A', 'sign': 'ignored'}, 'secret'))

    def test_logistics_company_query_is_read_only_and_requires_an_exact_warehouse(self):
        import threading
        import urllib.request
        from http.server import HTTPServer

        path = '/open/logisticscompany/query'
        self.assertIn(path, gateway.READ_PATHS)
        self.assertNotIn(path, gateway.WRITE_PATHS)
        result = {'code': 0, 'data': {'datas': [{'lc_id': 'SF.5', 'lc_name': '顺丰到付'}], 'has_next': False}}
        with patch.dict(os.environ, {'GATEWAY_TOKEN': 'fixture-bearer', 'JST_ALLOW_ORDER_UPLOAD': '0'}), \
                patch.object(gateway, 'upstream', return_value=result) as upstream:
            server = HTTPServer(('127.0.0.1', 0), gateway.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                def query(biz):
                    request = urllib.request.Request(
                        'http://127.0.0.1:' + str(server.server_port) + '/v1/query',
                        headers={'Authorization': 'Bearer fixture-bearer', 'X-CRM-JST-Environment': 'sandbox'},
                        data=json.dumps({'path': path, 'biz': biz}).encode())
                    return urllib.request.urlopen(request, timeout=3)

                for invalid in ({}, *({'wms_co_id': value} for value in
                        [0, -1, True, False, None, '15136229', 15136229.0, 15136229.9, 9223372036854775808])):
                    with self.subTest(invalid=invalid), self.assertRaises(urllib.error.HTTPError) as caught:
                        query(invalid)
                    with caught.exception as response:
                        self.assertEqual(400, response.code)
                upstream.assert_not_called()
                biz = {'wms_co_id': 15136229, 'page_index': 1, 'page_size': 50}
                with query(biz) as response:
                    self.assertEqual(200, response.status)
                    self.assertEqual(result, json.load(response))
                upstream.assert_called_once_with(path, biz)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == '__main__':
    unittest.main()

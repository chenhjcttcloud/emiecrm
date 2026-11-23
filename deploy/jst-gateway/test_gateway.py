import importlib.util
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


if __name__ == '__main__':
    unittest.main()

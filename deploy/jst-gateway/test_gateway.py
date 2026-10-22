import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gateway', Path(__file__).with_name('server.py'))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = gateway.DATABASE
        gateway.DATABASE = str(Path(self.tmp.name) / 'test.sqlite')
        self.env = patch.dict(os.environ, {'JST_ENV': 'sandbox'})
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

    def test_query_sign_is_lowercase_md5_and_ignores_sign_itself(self):
        import hashlib
        self.assertEqual(hashlib.md5('secretapp_keyAbiz{}'.encode()).hexdigest(),
                         gateway.sign({'biz': '{}', 'app_key': 'A', 'sign': 'ignored'}, 'secret'))


if __name__ == '__main__':
    unittest.main()

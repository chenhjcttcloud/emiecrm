import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('tokens', Path(__file__).with_name('token_store.py'))
tokens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokens)


class TokenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1800000000
        self.env = patch.dict(os.environ, dict(JST_TOKEN_DIRECTORY=self.tmp.name, JST_ENV='sandbox',
            JST_APP_KEY='test-app', JST_APP_SECRET='test-secret', JST_ACCESS_TOKEN='old-access',
            JST_REFRESH_TOKEN='old-refresh', JST_AUTO_REFRESH='1',
            JST_TOKEN_ISSUED_AT=str(self.now-170*tokens.DAY), JST_TOKEN_EXPIRES_AT=str(self.now+2*tokens.DAY)))
        self.env.start()
        self.clock = patch.object(tokens.time, 'time', return_value=self.now)
        self.clock.start()
        self.reply = {'code': 0, 'data': {'access_token': 'new-access', 'refresh_token': 'new-refresh', 'expires_in': 180*tokens.DAY}}

    def tearDown(self):
        self.clock.stop(); self.env.stop(); self.tmp.cleanup()

    def test_rotation_persists_both_tokens_and_actual_lifetime(self):
        from unittest.mock import Mock
        send = Mock(return_value=self.reply)
        tokens.refresh_if_due(send)
        with tokens.locked():
            state = tokens.load()
        self.assertEqual('new-access', state['access_token'])
        self.assertEqual('new-refresh', state['refresh_token'])
        self.assertEqual(self.now+180*tokens.DAY, state['expires_at'])
        self.assertEqual('old-refresh', send.call_args.args[0]['refresh_token'])
        self.assertEqual(0o600, tokens.location().stat().st_mode & 0o777)
        # Simulate process restart: persistent credentials override stale environment.
        again = importlib.util.module_from_spec(spec); spec.loader.exec_module(again)
        with again.locked():
            self.assertEqual('new-access', again.load()['access_token'])
        tokens.refresh_if_due(send)
        self.assertEqual(1, send.call_count)

    def test_not_due_and_initial_fifteen_days_do_not_refresh(self):
        from unittest.mock import Mock
        send = Mock()
        with patch.dict(os.environ, {'JST_TOKEN_EXPIRES_AT':str(self.now+36*tokens.DAY)}):
            tokens.refresh_if_due(send)
        send.assert_not_called()
        with tokens.locked():
            state=tokens.load(); state.update(issued_at=self.now-2*tokens.DAY,expires_at=self.now+tokens.DAY);tokens.save(state)
        tokens.refresh_if_due(send); send.assert_not_called()

    def test_uncertain_timeout_or_malformed_success_stops_replay_and_retains_old(self):
        from unittest.mock import Mock
        for response in [None, {'code':0,'data':{'access_token':'partial'}}]:
            with self.subTest(response=response):
                if tokens.location().exists(): tokens.location().unlink()
                send=Mock(side_effect=TimeoutError) if response is None else Mock(return_value=response)
                tokens.refresh_if_due(send);tokens.refresh_if_due(send)
                self.assertEqual(1,send.call_count)
                self.assertEqual('UNCERTAIN',tokens.status()['status'])
                with tokens.locked(): self.assertEqual('old-access',tokens.load()['access_token'])

    def test_crash_before_commit_never_replays(self):
        from unittest.mock import Mock
        with tokens.locked():
            state=tokens.load();state['status']='REFRESHING';tokens.save(state)
        send=Mock();tokens.refresh_if_due(send);send.assert_not_called()
        self.assertEqual('UNCERTAIN',tokens.status()['status'])

    def test_rejected_or_expired_requires_operator(self):
        from unittest.mock import Mock
        send=Mock(return_value={'code':147,'msg':'secret echoes must not leak'})
        tokens.refresh_if_due(send);tokens.refresh_if_due(send)
        self.assertEqual(1,send.call_count)
        self.assertNotIn('secret echoes',json.dumps(tokens.status()))
        with tokens.locked():
            state=tokens.load();state.update(status='READY',expires_at=self.now-1);tokens.save(state)
        tokens.refresh_if_due(send)
        self.assertEqual('EXPIRED',tokens.status()['status'])
        self.assertEqual(1,send.call_count)

    def test_no_secret_in_public_status_and_missing_dates_are_reported(self):
        with patch.dict(os.environ,{'JST_TOKEN_EXPIRES_AT':''}):
            report=tokens.status()
        self.assertEqual('NEEDS_CONFIGURATION',report['status'])
        self.assertNotIn('old-',json.dumps(report))

    def test_app_environment_isolation_and_corruption_fails_closed(self):
        with tokens.locked(): tokens.load()
        first=tokens.location()
        with patch.dict(os.environ,{'JST_ENV':'production'}):
            with tokens.locked(): tokens.load()
            self.assertNotEqual(first,tokens.location())
        first.write_text('bad json')
        self.assertEqual('STORAGE_ERROR',tokens.status()['status'])
        with self.assertRaises(ValueError),tokens.locked(): tokens.load()

    def test_operator_import_requires_explicit_replace(self):
        with tokens.locked():tokens.load()
        with self.assertRaises(ValueError):tokens.import_environment()
        with patch.dict(os.environ,{'JST_ACCESS_TOKEN':'recovered'}):tokens.import_environment(replace=True)
        with tokens.locked():self.assertEqual('recovered',tokens.load()['access_token'])

    def test_storage_failure_during_rotation_preserves_uncertain_marker(self):
        from unittest.mock import Mock
        with tokens.locked(): tokens.load()
        original=tokens.save
        def failing_save(state):
            if state.get('last_success'): raise OSError('disk full')
            return original(state)
        with patch.object(tokens,'save',side_effect=failing_save):
            with self.assertRaises(OSError):tokens.refresh_if_due(Mock(return_value=self.reply))
        self.assertEqual('UNCERTAIN',tokens.status()['status'])

    def test_disabled_worker_never_sends_and_import_dates_require_timezone(self):
        from unittest.mock import Mock
        send=Mock()
        with patch.dict(os.environ,{'JST_AUTO_REFRESH':'0'}):tokens.refresh_if_due(send)
        send.assert_not_called()
        with self.assertRaises(ValueError):tokens.timestamp('2026-11-27T23:59:04')

    def test_parallel_workers_send_once(self):
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import Mock
        send=Mock(return_value=self.reply)
        with ThreadPoolExecutor(max_workers=3) as executor:
            list(executor.map(lambda _:tokens.refresh_if_due(send),range(3)))
        self.assertEqual(1,send.call_count)

if __name__=='__main__':unittest.main()

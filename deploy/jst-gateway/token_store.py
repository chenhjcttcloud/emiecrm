"""Self-owned-app token rotation. Files contain secrets; never log their contents."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time

LOCK = threading.RLock()
DAY = 86400
BEIJING = timezone(timedelta(hours=8))


def timestamp(value):
    if not value:
        return 0
    if str(value).isdigit():
        return int(value)
    date = datetime.fromisoformat(str(value).replace('Z', '+00:00'))
    if date.tzinfo is None:
        raise ValueError('Token dates require an explicit timezone')
    return int(date.timestamp())


def identity():
    return os.getenv('JST_ENV', 'sandbox') + '-' + hashlib.sha256(os.getenv('JST_APP_KEY', '').encode()).hexdigest()[:24]


def location():
    return Path(os.getenv('JST_TOKEN_DIRECTORY', '/data/tokens')) / (identity() + '.json')


@contextmanager
def locked():
    # Also serialize business calls with rotation, across threads and processes.
    with LOCK:
        path = location()
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(path.parent, 0o700)
        fd = os.open(str(path) + '.lock', os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)


def save(state):
    path = location()
    fd, temp = tempfile.mkstemp(prefix='.token-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(state, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def load():
    path = location()
    if path.exists():
        os.chmod(path, 0o600)
        state = json.loads(path.read_text())
        if state.get('identity') != identity() or not isinstance(state.get('access_token'), str) or not state['access_token']:
            raise ValueError('Invalid persisted token state')
        # A crash during refresh is ambiguous: do not replay a rotating request.
        if state.get('status') == 'REFRESHING':
            state.update(status='UNCERTAIN', error='refresh_outcome_unknown')
            save(state)
        return state
    state = dict(identity=identity(), access_token=os.getenv('JST_ACCESS_TOKEN', ''),
                 refresh_token=os.getenv('JST_REFRESH_TOKEN', ''),
                 issued_at=timestamp(os.getenv('JST_TOKEN_ISSUED_AT', '')),
                 expires_at=timestamp(os.getenv('JST_TOKEN_EXPIRES_AT', '')),
                 status='READY', last_attempt=0, last_success=0, next_attempt=0, error='')
    if state['access_token']:
        save(state)
    return state


def status():
    try:
        with locked():
            state = load()
        now = int(time.time())
        enabled = os.getenv('JST_AUTO_REFRESH') == '1'
        missing = not (state.get('refresh_token') and state.get('issued_at') and state.get('expires_at'))
        value = state['status']
        if value == 'READY':
            value = 'EXPIRED' if state.get('expires_at') and state['expires_at'] <= now else 'NEEDS_CONFIGURATION' if missing else 'READY' if enabled else 'DISABLED'
        return dict(status=value, enabled=enabled, expiresAt=state.get('expires_at', 0),
                    lastSuccessAt=state.get('last_success', 0), nextAttemptAt=state.get('next_attempt', 0),
                    error=state.get('error', ''))
    except Exception:
        return dict(status='STORAGE_ERROR', enabled=os.getenv('JST_AUTO_REFRESH') == '1', error='token_storage_unavailable')


def refresh_if_due(send):
    if os.getenv('JST_AUTO_REFRESH') != '1':
        return
    with locked():
        state = load()
        now = int(time.time())
        if state['status'] in ('UNCERTAIN', 'REJECTED', 'EXPIRED'):
            return
        if not all(state.get(k) for k in ('access_token', 'refresh_token', 'issued_at', 'expires_at')):
            return
        if state['expires_at'] <= now:
            state.update(status='EXPIRED', error='reauthorization_required')
            save(state)
            return
        # Official self-owned flow: refreshing within 15 days of issue is ineffective.
        due = max(state['issued_at'] + 15 * DAY, state['expires_at'] - 7 * DAY)
        if now < max(due, state.get('next_attempt', 0)):
            return
        state.update(status='REFRESHING', last_attempt=now, error='')
        save(state)  # Persist BEFORE request, so a crash cannot silently replay it.
        params = dict(app_key=os.environ['JST_APP_KEY'], timestamp=str(now),
                      grant_type='refresh_token', charset='utf-8',
                      refresh_token=state['refresh_token'], scope='all')
        try:
            result = send(params)
            if result.get('code') != 0:
                # Do not expose upstream text, which might echo credentials.
                state.update(status='REJECTED', error='refresh_rejected_check_authorization')
            else:
                data = result.get('data')
                if (not isinstance(data, dict) or not isinstance(data.get('access_token'), str)
                        or not data['access_token'] or not isinstance(data.get('refresh_token'), str)
                        or not data['refresh_token'] or type(data.get('expires_in')) is not int or data['expires_in'] <= 0):
                    raise ValueError('Invalid rotation response')
                state.update(access_token=data['access_token'], refresh_token=data['refresh_token'],
                             issued_at=now, expires_at=now + data['expires_in'], last_success=now,
                             status='READY', next_attempt=0, error='')
        except Exception:
            state.update(status='UNCERTAIN', error='refresh_outcome_unknown')
        save(state)


def next_daily_check(now):
    current = datetime.fromtimestamp(now, BEIJING)
    target = current.replace(hour=9, minute=0, second=0, microsecond=0)
    if target <= current:
        target += timedelta(days=1)
    return target.timestamp()


def worker(send, stop):
    # Catch up once at startup, then check at 09:00 Beijing time every day.
    while not stop.is_set():
        try:
            refresh_if_due(send)
        except Exception:
            pass  # /ready exposes storage errors; never print exception bodies.
        now = time.time()
        if stop.wait(next_daily_check(now) - now):
            return


def import_environment(replace=False):
    """Explicit operator recovery; startup never overwrites rotated state from old env."""
    with locked():
        if location().exists() and not replace:
            raise ValueError('Persisted token exists; explicit --replace required')
        for key in ('JST_APP_KEY', 'JST_APP_SECRET', 'JST_ACCESS_TOKEN', 'JST_REFRESH_TOKEN',
                    'JST_TOKEN_ISSUED_AT', 'JST_TOKEN_EXPIRES_AT'):
            if not os.getenv(key):
                raise ValueError('Missing credential configuration')
        issued, expires = timestamp(os.environ['JST_TOKEN_ISSUED_AT']), timestamp(os.environ['JST_TOKEN_EXPIRES_AT'])
        if not 0 < issued < expires or expires <= time.time():
            raise ValueError('Invalid credential dates')
        save(dict(identity=identity(), access_token=os.environ['JST_ACCESS_TOKEN'],
                  refresh_token=os.environ['JST_REFRESH_TOKEN'], issued_at=issued, expires_at=expires,
                  status='READY', last_attempt=0, last_success=0, next_attempt=0, error=''))


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Import private environment credentials without displaying them')
    parser.add_argument('action', choices=['import-env'])
    parser.add_argument('--replace', action='store_true')
    args = parser.parse_args()
    import_environment(args.replace)
    print('Private token state imported')

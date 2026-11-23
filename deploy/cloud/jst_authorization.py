"""Issue one production Jushuitan credential pair without exposing its values."""

import hashlib
import json
import os
import secrets
import stat
import string
import tempfile
import time
from http.client import IncompleteRead
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


ISSUE_URL = "https://openapi.jushuitan.com/openWeb/auth/getInitToken"
ATTEMPT_FILE = "get-init-token-attempt.json"
RESPONSE_FILE = "get-init-token-response.json"
MAX_RESPONSE_BYTES = 1024 * 1024


def _sync_directory(path):
    descriptor = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_file(path, data, mode=0o600):
    """Atomically replace a file and flush both its contents and directory entry."""
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as handle:
            os.fchmod(handle.fileno(), mode)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, str(path))
        _sync_directory(path.parent)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def _attempt_data(issued_at, status, vendor_code=None):
    data = {"issued_at": issued_at, "status": status}
    if vendor_code is not None:
        data["vendor_code"] = vendor_code
    return (json.dumps(data, sort_keys=True) + "\n").encode("utf-8")


def _reserve_attempt(path, issued_at):
    try:
        descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise RuntimeError("A Jushuitan authorization attempt already exists; review it before proceeding") from None
    except OSError:
        raise RuntimeError("Could not create the private Jushuitan authorization attempt marker") from None

    try:
        with os.fdopen(descriptor, "wb") as handle:
            os.fchmod(handle.fileno(), 0o600)
            handle.write(_attempt_data(issued_at, "ATTEMPT_STARTED"))
            handle.flush()
            os.fsync(handle.fileno())
        _sync_directory(path.parent)
    except OSError:
        raise RuntimeError("Could not persist the Jushuitan authorization attempt marker") from None


def _record_attempt(path, issued_at, status, vendor_code=None):
    try:
        write_file(path, _attempt_data(issued_at, status, vendor_code))
    except OSError:
        raise RuntimeError("Could not record the Jushuitan authorization outcome; review the private attempt files") from None


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def _read_response(response, output):
    while len(output) <= MAX_RESPONSE_BYTES:
        remaining = MAX_RESPONSE_BYTES + 1 - len(output)
        try:
            chunk = response.read(min(remaining, 65536))
        except IncompleteRead as error:
            output.extend(error.partial[:remaining])
            raise
        if not chunk:
            break
        output.extend(chunk)


def _send(request, output):
    opener = build_opener(ProxyHandler({}), _NoRedirect())
    try:
        response = opener.open(request, timeout=25)
    except HTTPError as error:
        response = error
    with response:
        _read_response(response, output)
        content_length = response.headers.get("Content-Length")
        if content_length is not None and int(content_length) != len(output):
            raise ValueError("Incomplete HTTP response")
        return response.getcode()


def _credential(value):
    return (
        isinstance(value, str)
        and bool(value)
        and value.isprintable()
        and not any(character.isspace() for character in value)
    )


def _json_object(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("Duplicate JSON key")
    return result


def issue(env, state_dir):
    """Issue one pair and retain the private response for recovery before returning it."""
    app_key = env.get("JST_APP_KEY")
    app_secret = env.get("JST_APP_SECRET")
    if not _credential(app_key) or not _credential(app_secret):
        raise RuntimeError("The private Jushuitan application credentials are missing or malformed")

    state_dir = Path(state_dir)
    try:
        private_directory = state_dir.is_dir() and not state_dir.is_symlink() and stat.S_IMODE(state_dir.stat().st_mode) == 0o700
    except OSError:
        private_directory = False
    if not private_directory:
        raise RuntimeError("The Jushuitan authorization state directory must have mode 0700")

    attempt_path = state_dir / ATTEMPT_FILE
    response_path = state_dir / RESPONSE_FILE
    if os.path.lexists(str(response_path)):
        raise RuntimeError("A prior Jushuitan authorization response exists; review it before proceeding")

    issued_at = int(time.time())
    params = {
        "app_key": app_key,
        "charset": "utf-8",
        "code": "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(6)),
        "grant_type": "authorization_code",
        "timestamp": str(issued_at),
    }
    signature_input = app_secret + "".join(key + params[key] for key in sorted(params))
    params["sign"] = hashlib.md5(signature_input.encode("utf-8")).hexdigest()
    request = Request(
        ISSUE_URL,
        data=urlencode(params).encode("utf-8"),
        headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        method="POST",
    )

    _reserve_attempt(attempt_path, issued_at)
    raw_response = bytearray()
    try:
        http_status = _send(request, raw_response)
    except Exception:
        http_status = None

    if raw_response:
        try:
            write_file(response_path, bytes(raw_response[:MAX_RESPONSE_BYTES]))
        except OSError:
            _record_attempt(attempt_path, issued_at, "UNCERTAIN")
            raise RuntimeError("Could not save the private Jushuitan authorization response; verify the outcome before proceeding") from None

    try:
        if http_status != 200 or len(raw_response) > MAX_RESPONSE_BYTES:
            raise ValueError
        response = json.loads(raw_response.decode("utf-8"), object_pairs_hook=_json_object)
        if not isinstance(response, dict) or type(response.get("code")) is not int:
            raise ValueError
    except (ValueError, RecursionError):
        _record_attempt(attempt_path, issued_at, "UNCERTAIN")
        raise RuntimeError("The Jushuitan authorization outcome is uncertain; review the private attempt files") from None

    if response["code"] != 0:
        _record_attempt(attempt_path, issued_at, "REJECTED", response["code"])
        raise RuntimeError("Jushuitan rejected authorization; review the private attempt files")

    data = response.get("data")
    if not isinstance(data, dict):
        data = {}
    access_token = data.get("access_token")
    refresh_token = data.get("refresh_token")
    expires_in = data.get("expires_in")
    if (
        not _credential(access_token)
        or not _credential(refresh_token)
        or type(expires_in) is not int
        or expires_in <= 0
        or data.get("scope") != "all"
    ):
        _record_attempt(attempt_path, issued_at, "UNCERTAIN")
        raise RuntimeError("Jushuitan returned an incomplete authorization response; review the private attempt files")

    _record_attempt(attempt_path, issued_at, "ISSUED")
    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "issued_at": issued_at,
        "expires_at": issued_at + expires_in,
    }

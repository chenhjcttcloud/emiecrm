#!/bin/bash
# 非破坏性生产烟测。完整业务回归见 backend/README.md，必须在独立测试库执行。
set -euo pipefail
cd /home/emie/emiecrm
curl -fsS http://127.0.0.1:8088/api/status | python3 -c 'import json,sys; s=json.load(sys.stdin); assert s["backend"]=="java" and not s.get("testMode"); print("Java production status passed")'
# Authenticated business checks require a local, private smoke-test credential.
python3 - <<'CHECK'
import urllib.request,urllib.error
try:
    urllib.request.urlopen('http://127.0.0.1:8088/api/state')
    raise SystemExit('Anonymous business access must be denied')
except urllib.error.HTTPError as e:
    assert e.code == 401
print('Anonymous business requests are protected')
CHECK

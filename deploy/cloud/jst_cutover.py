import fcntl
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, build_opener

from jst_authorization import issue, write_file
from jst_cutover_nginx import prepare


ROOT = Path("/opt/emiecrm")
WORK = ROOT / "jst-cutover"
BASE = ROOT / "compose.production.yaml"
ORIGINAL = ROOT / "compose.production.before-jst.yaml"
OVERLAY = ROOT / "compose.jst.yaml"
APP_ENV = ROOT / ".env.crm"
COMPOSE_ENV = ROOT / ".env"
GATEWAY_ENV = ROOT / "jst-gateway" / ".env"
GATEWAY_DATA = GATEWAY_ENV.parent / "data"
NGINX = Path("/etc/nginx/conf.d/emiecrm.conf")
ROUTE = ROOT / "nginx" / "jst" / "gateway.conf"
NETWORK = "emiecrm-prod-network"
IMAGE = "emie-jst-gateway:0.3.0"
PUBLIC = "https://crm.emie.cn:9002"
PRIVATE = PUBLIC + "/_internal/jst"
LOCAL = "http://127.0.0.1:8088"
START = int(datetime(2026, 11, 27, 4, 0, tzinfo=timezone.utc).timestamp())
OLD_EXPIRY = "2026-11-27T23:59:04+08:00"
END = int(datetime(2026, 11, 27, 15, 59, 4, tzinfo=timezone.utc).timestamp())
SAFE_SETTINGS = {"CRM_JST_READ_ONLY": "true", "JST_AUTO_REFRESH": "0",
                 "JST_ALLOW_ORDER_UPLOAD": "0", "JST_ALLOW_ORDER_CHANGE": "0"}
HTTP = build_opener(ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(label, *args, env=None, data=None, timeout=40):
    try:
        result = subprocess.run(args, cwd=str(ROOT), env=env, input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(label + " did not complete") from None
    require(result.returncode == 0, label + " failed")
    return result.stdout


def compose(env, files, label, *args, timeout=40):
    paths = [item for path in files for item in ("-f", str(path))]
    return run(label, "docker", "compose", "-p", "emiecrm-production",
               *paths, *args, env=env, timeout=timeout)


def dotenv(raw):
    values = {}
    for line in raw.decode().splitlines():
        key, sep, value = line.partition("=")
        if sep and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key.strip()):
            values[key.strip()] = value.strip().strip("'\"")
    return values


def update_env(raw, values):
    lines = raw.decode().splitlines()
    remaining = dict(values)
    for index, line in enumerate(lines):
        key, sep, _ = line.partition("=")
        if sep and key.strip() in values:
            name = key.strip()
            require(name in remaining, "Duplicate config setting: " + name)
            lines[index] = name + "=" + remaining.pop(name)
    lines.extend(key + "=" + value for key, value in remaining.items())
    return ("\n".join(lines) + "\n").encode()


def inspect(name):
    return json.loads(run("Docker inspection of " + name, "docker", "inspect", name))[0]


def container_env(container):
    return dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)


def wrapper(previous):
    return ("name: emiecrm-production\ninclude:\n  - path:\n"
            "      - " + previous.name + "\n      - compose.jst.yaml\n").encode()


def http(url):
    with HTTP.open(url, timeout=6) as response:
        require(response.status == 200, "CRM HTTP health check failed")
        return response.read(4 * 1024 * 1024)


def verify_crm():
    for base in (LOCAL, PUBLIC):
        status = json.loads(http(base + "/api/status"))
        require(all(status.get(key) == value for key, value in (
            ("backend", "java"), ("database", "mysql"), ("setup", True),
            ("loginRequired", True), ("testMode", False))), "Unexpected CRM status")
    feishu = json.loads(http(PUBLIC + "/api/auth/feishu/config"))
    require(feishu.get("enabled") is True and
            feishu.get("redirectUri") == PUBLIC + "/api/auth/feishu/callback",
            "Production Feishu login configuration did not match")
    page = http(PUBLIC + "/").decode()
    assets = set(re.findall(r'(?:src|href)="(/assets/[^" ]+\.(?:js|css))"', page))
    require({path.rsplit(".", 1)[-1] for path in assets} == {"js", "css"},
            "Missing production JS or CSS references")
    for path in assets:
        require(len(http(PUBLIC + path)) > 100, "Production static asset was empty")
    try:
        http(PUBLIC + "/api/state")
    except HTTPError as error:
        require(error.code == 401, "Unexpected anonymous CRM response")
    else:
        raise RuntimeError("Anonymous CRM request was accepted")


def wait_ready():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            verify_crm()
            return
        except (OSError, ValueError, RuntimeError):
            time.sleep(3)
    raise RuntimeError("CRM status, login configuration or static asset check failed")


def preflight():
    require(not ORIGINAL.exists() and not ROUTE.exists(), "Jushuitan route or cutover base already exists")
    require(not any(GATEWAY_DATA.iterdir()), "Gateway data already exists; inspect it before issuing")
    gateway_raw = GATEWAY_ENV.read_bytes()
    staged = dotenv(gateway_raw)
    require(staged.get("JST_ENV") == "production"
            and staged.get("JST_TOKEN_EXPIRES_AT") == OLD_EXPIRY
            and all(re.fullmatch(r"[0-9a-fA-F]{32}", staged.get(field, "")) for field in
                    ("JST_APP_KEY", "JST_APP_SECRET"))
            and re.fullmatch(r"[0-9a-f]{64}", staged.get("GATEWAY_TOKEN", ""))
            and not staged.get("JST_ACCESS_TOKEN") and not staged.get("JST_REFRESH_TOKEN"),
            "Staged authorization differs from the planned recovery")
    for file in (APP_ENV, COMPOSE_ENV, GATEWAY_ENV):
        require(stat.S_IMODE(file.stat().st_mode) == 0o600, "Unexpected private configuration permissions")
    for file in (BASE, APP_ENV, COMPOSE_ENV, NGINX, GATEWAY_ENV):
        require(file.is_file() and not file.is_symlink(), "Unexpected configuration file type")

    running, mysql = inspect("emiecrm-prod"), inspect("emiecrm-mysql-prod")
    require(all(item["State"]["Running"] and
                item["Config"]["Labels"].get("com.docker.compose.project") == "emiecrm-production"
                for item in (running, mysql)), "Production containers differ from the expected project")
    require(container_env(running).get("CRM_JST_GATEWAY_URL", "").rstrip("/")
            == "https://jstapi.emie.cn", "Running CRM has already moved away from the old address")
    require(inspect(running["Config"]["Image"])["Id"] == running["Image"],
            "Running CRM image tag no longer matches its image")
    require(inspect(IMAGE)["Architecture"] == "amd64", "Staged gateway image is missing or incompatible")
    run("Gateway data permission check", "docker", "run", "--rm", "--network", "none",
        "--read-only", "--mount", "type=bind,source=" + str(GATEWAY_DATA) + ",target=/data",
        "--entrypoint", "python", IMAGE, "-c",
        "import os; assert os.access('/data', os.W_OK | os.X_OK)")
    listeners = run("Gateway port check", "ss", "-ltnH").decode().splitlines()
    require(not any(len(line.split()) >= 4 and line.split()[3].endswith(":18766")
                    for line in listeners), "Gateway loopback port is already in use")

    network = inspect(NETWORK)
    bridge = network["IPAM"]["Config"][0]["Gateway"]
    require(network["Driver"] == "bridge" and re.fullmatch(r"[0-9.]+", bridge)
            and running["Id"] in network["Containers"], "Unexpected production network")
    settings = dict(SAFE_SETTINGS, CRM_JST_BRIDGE_GATEWAY=bridge)
    environment = dict(os.environ, **settings)
    route = run("Private route renderer", str(ROOT / "render-jst-route.sh"))
    nginx_input = NGINX.read_bytes()
    nginx = prepare(NGINX, route, WORK)
    require(NGINX.read_bytes() == nginx_input, "Nginx configuration changed during preflight")
    active_nginx = run("Active Nginx configuration check", "nginx", "-T")
    require(not re.search(rb"(?m)^\s*(real_ip_header|set_real_ip_from)\s", active_nginx),
            "Nginx source-address rewriting needs manual review")
    with tempfile.NamedTemporaryFile(dir=str(ROOT), prefix=".jst-compose-") as draft:
        draft.write(wrapper(BASE))
        draft.flush()
        original = json.loads(compose(environment, [BASE], "Current Compose validation",
                                      "config", "--format", "json"))
        combined = json.loads(compose(environment, [Path(draft.name)], "Combined Compose validation",
                                      "config", "--format", "json"))
    current_app = original["services"]["crm"]
    next_app = combined["services"]["crm"]
    gateway = combined["services"]["jst-gateway"]
    require(set(original["services"]) == {"crm", "mysql"}
            and set(combined["services"]) == {"crm", "mysql", "jst-gateway"}
            and current_app["image"] == next_app["image"] == running["Config"]["Image"]
            and current_app["ports"] == next_app["ports"]
            and combined["services"]["mysql"] == original["services"]["mysql"]
            and combined["volumes"] == original["volumes"]
            and next_app["environment"]["CRM_JST_GATEWAY_URL"] == PRIVATE
            and next_app["environment"]["CRM_JST_ENV"] == "production"
            and next_app["environment"]["CRM_JST_READ_ONLY"] == "true"
            and gateway["ports"][0]["host_ip"] == "127.0.0.1"
            and gateway["environment"]["JST_ENV"] == "production"
            and all(gateway["environment"][key] == SAFE_SETTINGS[key] for key in
                    ("JST_AUTO_REFRESH", "JST_ALLOW_ORDER_UPLOAD", "JST_ALLOW_ORDER_CHANGE")),
            "Combined deployment changed protected settings")
    verify_crm()
    print("Preflight passed: existing CRM, MySQL, image, Compose, TLS route and write restrictions")
    return staged, gateway_raw, environment, settings, bridge, route, nginx, nginx_input, running, mysql


def verify_private_route(bridge, token, expiry):
    for args in (("--resolve", "crm.emie.cn:9002:127.0.0.1"), ()):
        code = run("Denied-source HTTPS check", "curl", "-sS", "--noproxy", "*", "--max-time", "8",
                   "-o", "/dev/null", "-w", "%{http_code}", *args, PRIVATE + "/health")
        require(code == b"403", "Private gateway route was not denied from outside the CRM network")
    payload = json.dumps({"token": token, "expires_at": expiry}).encode()
    run("Private HTTPS read-only SKU and inventory probe", "docker", "run", "--rm", "-i",
        "--network", NETWORK, "--add-host", "crm.emie.cn:" + bridge, "--read-only",
        "--entrypoint", "python", IMAGE, "-c", (WORK / "jst_cutover_probe.py").read_text(),
        data=payload, timeout=180)
    print("Production read-only query passed: active warehouse, TEST001 and numeric inventory")


def cutover(prepared):
    staged, gateway_raw, environment, settings, bridge, route, nginx, nginx_input, old_crm, mysql = prepared
    require(START <= time.time() < END, "Outside the authorized token issuance window")
    digest = hashlib.sha256(gateway_raw).hexdigest()
    require((WORK / "authorization.sha256").read_text().strip() == digest,
            "Staged authorization changed after the timer was armed")
    backup = WORK / "previous"
    backup.mkdir(mode=0o700)
    sources = (BASE, APP_ENV, COMPOSE_ENV, NGINX)
    originals = {file: (file.read_bytes(), stat.S_IMODE(file.stat().st_mode)) for file in sources}
    require(originals[NGINX][0] == nginx_input, "Nginx configuration changed after preflight")
    for file, (data, _) in originals.items():
        write_file(backup / file.name, data)
    print("Private rollback configuration saved; existing MySQL container and volume retained")

    credentials = issue(staged, WORK)
    require(all(re.fullmatch(r"[A-Za-z0-9._~+/=-]{16,512}", credentials[field]) for field in
                ("access_token", "refresh_token")), "Issued credentials need private format review")
    write_file(GATEWAY_ENV, update_env(gateway_raw, {
        "JST_ACCESS_TOKEN": credentials["access_token"], "JST_REFRESH_TOKEN": credentials["refresh_token"],
        "JST_TOKEN_ISSUED_AT": datetime.fromtimestamp(credentials["issued_at"], timezone.utc).isoformat(),
        "JST_TOKEN_EXPIRES_AT": datetime.fromtimestamp(credentials["expires_at"], timezone.utc).isoformat(),
    }))
    print("New authorization stored privately")
    gateway_started = nginx_changed = crm_changed = restart_attempted = False
    try:
        gateway_started = True
        compose(environment, [BASE, OVERLAY], "Gateway startup",
                "up", "-d", "--no-deps", "--no-build", "--pull", "never", "jst-gateway", timeout=90)
        for attempt in range(15):
            try:
                require(json.loads(http("http://127.0.0.1:18766/health")).get("service") == "emie-jst",
                        "Unexpected gateway service")
                break
            except (OSError, ValueError, RuntimeError):
                if attempt == 14:
                    raise RuntimeError("Gateway did not become healthy") from None
                time.sleep(2)

        require(NGINX.read_bytes() == nginx_input and not ROUTE.exists(),
                "Nginx configuration changed during the operation")
        nginx_changed = True
        ROUTE.parent.mkdir(parents=True, exist_ok=True)
        write_file(ROUTE, route, 0o644)
        write_file(NGINX, nginx, originals[NGINX][1])
        run("Nginx configuration validation", "nginx", "-t")
        run("Nginx reload", "systemctl", "reload", "nginx")
        verify_private_route(bridge, staged["GATEWAY_TOKEN"], credentials["expires_at"])

        require(all(file.read_bytes() == originals[file][0] for file in (BASE, APP_ENV, COMPOSE_ENV)),
                "Production configuration changed during the operation")
        require(inspect("emiecrm-prod")["Id"] == old_crm["Id"]
                and inspect("emiecrm-mysql-prod")["Id"] == mysql["Id"],
                "Production containers changed during the operation")
        crm_changed = True
        write_file(ORIGINAL, originals[BASE][0], originals[BASE][1])
        write_file(COMPOSE_ENV, update_env(originals[COMPOSE_ENV][0], settings))
        write_file(APP_ENV, update_env(originals[APP_ENV][0], {
            "CRM_JST_GATEWAY_URL": PRIVATE, "CRM_JST_GATEWAY_TOKEN": staged["GATEWAY_TOKEN"],
            "CRM_JST_ENV": "production", "CRM_JST_READ_ONLY": "true",
        }))
        write_file(BASE, wrapper(ORIGINAL), originals[BASE][1])
        compose(environment, [BASE], "Live Compose validation", "config", "-q")
        restart_attempted = True
        compose(environment, [BASE], "CRM cutover",
                "up", "-d", "--no-deps", "--no-build", "--pull", "never", "crm", timeout=120)
        wait_ready()
        current = inspect("emiecrm-prod")
        live = container_env(current)
        require(current["Image"] == old_crm["Image"]
                and live.get("CRM_JST_GATEWAY_URL") == PRIVATE
                and live.get("CRM_JST_GATEWAY_TOKEN") == staged["GATEWAY_TOKEN"]
                and live.get("CRM_JST_ENV") == "production"
                and live.get("CRM_JST_READ_ONLY") == "true"
                and inspect("emiecrm-mysql-prod")["Id"] == mysql["Id"],
                "CRM runtime configuration or MySQL identity did not match")
        write_file(WORK / "completed", (datetime.now(timezone.utc).isoformat() + "\n").encode())
    except BaseException:
        print("Cutover checks failed; restoring production configuration")
        errors = []

        def restore(label, action):
            try:
                action()
            except Exception:
                errors.append(label)

        if crm_changed:
            for file in (BASE, APP_ENV, COMPOSE_ENV):
                restore(file.name, lambda file=file: write_file(file, *originals[file]))
        crm_files_restored = not errors
        if nginx_changed:
            restore("Nginx vhost", lambda: write_file(NGINX, *originals[NGINX]))
            if ROUTE.exists():
                restore("JST route", ROUTE.unlink)
            restore("Nginx check", lambda: run("Nginx rollback check", "nginx", "-t"))
            restore("Nginx reload", lambda: run("Nginx rollback", "systemctl", "reload", "nginx"))
        if restart_attempted and crm_files_restored:
            restore("CRM", lambda: compose(os.environ, [BASE], "CRM rollback",
                                           "up", "-d", "--no-deps", "--no-build", "--pull", "never", "crm",
                                           timeout=120))
            restore("CRM verification", wait_ready)
        if gateway_started:
            restore("staged gateway stop", lambda: compose(environment, [BASE, OVERLAY],
                                                            "Gateway rollback", "stop", "jst-gateway"))
        if errors:
            raise RuntimeError("Rollback requires review: " + ", ".join(errors)) from None
        print("Previous CRM configuration restored; any new authorization remains saved privately")
        raise
    print("Production CRM cut over in read-only mode; MySQL and production URL verified")


def interrupted(signum, frame):
    raise RuntimeError("Cutover received a stop signal")


if __name__ == "__main__":
    check_only = sys.argv[1:] == ["--check"]
    if not check_only and sys.argv[1:]:
        sys.exit("Usage: jst_cutover.py [--check]")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        require(check_only or START <= time.time() < END, "Outside the authorized token issuance window")
        with (WORK / "run.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            prepared = preflight()
            if not check_only:
                cutover(prepared)
    except RuntimeError as error:
        sys.exit(str(error))
    except Exception:
        sys.exit("Cutover stopped on unexpected runtime data; inspect private state before retrying")

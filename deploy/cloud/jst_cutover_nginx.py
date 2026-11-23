"""Prepare and check a shadow copy of the production CRM Nginx site."""

import fnmatch
import os
import re
import subprocess
import tempfile
from pathlib import Path


GATEWAY_INCLUDE = b"/opt/emiecrm/nginx/jst/gateway.conf"
CERTIFICATE = b"/opt/emiecrm/fullchain.pem"
CERTIFICATE_KEY = b"/opt/emiecrm/privkey.pem"
TOKEN = re.compile(
    rb"\s+|\#[^\r\n]*|[;{}]|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'"
    rb"|(?:\\.|\$\{[^}]+\}|[^\s;{}\"'\\#])+"
)


def _parse(config):
    servers = []
    statements = []
    current = None
    depth = 0
    words = []
    offset = 0
    for match in TOKEN.finditer(config):
        if match.start() != offset:
            raise RuntimeError("Cannot parse the live CRM Nginx configuration")
        offset = match.end()
        word = match.group()
        if word.isspace() or word.startswith(b"#"):
            continue
        if word == b"{":
            if not words:
                raise RuntimeError("Cannot parse the live CRM Nginx configuration")
            if depth == 0 and [value for value, _ in words] == [b"server"]:
                current = []
                servers.append(current)
            depth += 1
            words = []
        elif word == b"}":
            if words or depth == 0:
                raise RuntimeError("Cannot parse the live CRM Nginx configuration")
            depth -= 1
            if depth == 0:
                current = None
        elif word == b";":
            if not words:
                raise RuntimeError("Cannot parse the live CRM Nginx configuration")
            statement = ([value for value, _ in words], words[0][1], match.end())
            statements.append(statement)
            if depth == 1 and current is not None:
                current.append(statement)
            words = []
        else:
            if word[:1] in (b"'", b'"'):
                word = word[1:-1]
            words.append((word, match.start()))
    if words or depth or offset != len(config):
        raise RuntimeError("Cannot parse the live CRM Nginx configuration")
    return servers, statements


def _replacement(config, edits):
    for start, end, value in sorted(edits, reverse=True):
        config = config[:start] + value + config[end:]
    return config


def _quote(path):
    return b'"' + os.fsencode(str(path)).replace(b"\\", b"\\\\").replace(b'"', b'\\"') + b'"'


def prepare(live_path: Path, route: bytes, work_dir: Path) -> bytes:
    """Return a validated CRM site draft without modifying the live site."""
    try:
        live = live_path.read_bytes()
    except OSError:
        raise RuntimeError("Cannot read the live CRM Nginx configuration") from None

    servers, statements = _parse(live)
    matches = []
    for server in servers:
        names = [words for words, _, _ in server if words[0] == b"server_name"]
        listeners = [words for words, _, _ in server if words[0] == b"listen"]
        if any(b"crm.emie.cn" in words[1:] for words in names) and any(
            len(words) > 1 and words[1].rsplit(b":", 1)[-1] == b"9002" for words in listeners
        ):
            matches.append(server)
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one CRM Nginx server for port 9002")

    target = matches[0]
    names = [directive for directive in target if directive[0][0] == b"server_name"]
    tls_listeners = [words for words, _, _ in target if words[0] == b"listen" and
                     len(words) > 1 and words[1].rsplit(b":", 1)[-1] == b"9002"]
    if len(names) != 1 or names[0][0] != [b"server_name", b"crm.emie.cn"] or not all(
        b"ssl" in words[2:] for words in tls_listeners
    ):
        raise RuntimeError("Cannot identify the CRM TLS server unambiguously")
    for directive, path in ((b"ssl_certificate", CERTIFICATE), (b"ssl_certificate_key", CERTIFICATE_KEY)):
        if [words for words, _, _ in target if words[0] == directive] != [[directive, path]]:
            raise RuntimeError("The CRM TLS server does not use the expected certificate and key")

    includes = [item for item in statements if len(item[0]) == 2 and item[0][0] == b"include"
                and fnmatch.fnmatchcase(GATEWAY_INCLUDE, item[0][1])]
    if includes and (len(includes) != 1 or includes[0] not in target or
                     includes[0][0][1] != GATEWAY_INCLUDE):
        raise RuntimeError("An existing Nginx include would also load the gateway route")
    if includes:
        draft = live
    else:
        _, start, _ = names[0]
        newline = b"\r\n" if b"\r\n" in live else b"\n"
        prefix = live[live.rfind(b"\n", 0, start) + 1:start]
        indent = prefix if not prefix.strip() else b"    "
        addition = b"include " + GATEWAY_INCLUDE + b";" + newline + indent
        draft = live[:start] + addition + live[start:]

    try:
        with tempfile.TemporaryDirectory(prefix="jst-nginx-", dir=str(work_dir)) as directory:
            root = Path(directory).resolve()
            route_copy = root / "gateway.conf"
            site_copy = root / "site.conf"
            main = root / "nginx.conf"
            route_copy.write_bytes(route)
            edits = []
            for words, start, end in _parse(draft)[1]:
                if words == [b"include", GATEWAY_INCLUDE]:
                    edits.append((start, end, b"include " + _quote(route_copy) + b";"))
                elif words[0] == b"access_log":
                    edits.append((start, end, b"access_log off;"))
                elif words[0] == b"error_log":
                    edits.append((start, end, b"error_log stderr;"))
            site_copy.write_bytes(_replacement(draft, edits))
            main.write_bytes(b"error_log stderr;\npid " + _quote(root / "nginx.pid") +
                             b";\nevents {}\nhttp {\n    access_log off;\n    include " +
                             _quote(site_copy) + b";\n}\n")
            subprocess.run(["nginx", "-t", "-q", "-p", str(root) + "/", "-c", str(main)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           check=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Nginx rejected the staged CRM configuration or could not be checked") from None

    return draft

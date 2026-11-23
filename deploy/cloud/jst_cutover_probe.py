import json
import math
import sys
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


BASE = "https://crm.emie.cn:9002/_internal/jst"
SKU = "TEST001"
WAREHOUSE = 15136229


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def probe(token, expires_at):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    headers = {"Authorization": "Bearer " + token,
               "X-CRM-JST-Environment": "production", "Content-Type": "application/json"}

    def read(path, data=None, auth=True):
        request = Request(BASE + path, data=data, headers=headers if auth else {})
        with opener.open(request, timeout=35) as response:
            reply = json.load(response)
        require(isinstance(reply, dict), "Unexpected gateway response")
        return reply

    require(read("/health", auth=False).get("service") == "emie-jst", "Gateway health did not match")
    try:
        read("/ready", auth=False)
    except HTTPError as error:
        require(error.code == 401, "Unexpected anonymous gateway status")
    else:
        raise RuntimeError("Gateway readiness accepted an anonymous request")
    ready = read("/ready")
    auth = ready.get("authorization", {})
    require(isinstance(auth, dict), "Unexpected gateway authorization response")
    require(ready.get("configured") is True and ready.get("environment") == "production"
            and ready.get("order_upload_enabled") is False
            and ready.get("order_change_enabled") is False
            and auth.get("enabled") is False and auth.get("status") == "DISABLED"
            and auth.get("expiresAt") == expires_at, "Gateway authorization or write flags did not match")

    def query(path, collection, **options):
        biz = dict(page_index=1, page_size=100, **options)
        reply = read("/v1/query", json.dumps({"path": path, "biz": biz}).encode())
        require(type(reply.get("code")) is int and reply["code"] == 0
                and isinstance(reply.get("data"), dict),
                "Vendor query failed for " + path)
        rows = reply["data"].get(collection)
        require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
                "Unexpected vendor response for " + path)
        return rows

    warehouses = query("/open/wms/partner/query", "datas")
    require(any(str(row.get("wms_co_id")) == str(WAREHOUSE)
                and row.get("status") in (None, "生效") for row in warehouses),
            "Expected active warehouse was not found")
    products = query("/open/sku/query", "datas", sku_ids=SKU)
    require(any(row.get("sku_id") == SKU and str(row.get("enabled")) in ("1", "True")
                and str(row.get("i_id") or "").strip() for row in products),
            "Expected active SKU was not found")
    stock = query("/open/inventory/query", "inventorys", sku_ids=SKU,
                  wms_co_id=WAREHOUSE, has_lock_qty=True)
    matches = [row for row in stock if row.get("sku_id") == SKU]
    require(matches, "Expected SKU inventory row was not found")
    try:
        require(all(not isinstance(row.get("qty"), bool) and math.isfinite(float(row["qty"]))
                    for row in matches), "Invalid inventory quantity")
    except (TypeError, ValueError, KeyError, OverflowError):
        raise RuntimeError("Expected a numeric inventory quantity") from None


if __name__ == "__main__":
    try:
        payload = json.load(sys.stdin)
        probe(payload["token"], payload["expires_at"])
    except RuntimeError as error:
        sys.exit(str(error))
    except (ValueError, KeyError, OSError):
        sys.exit("Private HTTPS gateway probe did not complete")
    print("Private HTTPS, authorization, TEST001 and warehouse inventory verified")

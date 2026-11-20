#!/usr/bin/env python3
"""Prepare a private, reviewable old Feishu CRM migration; never writes the CRM."""
import argparse
import hashlib
import json
import os
from decimal import Decimal
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
HEADERS = ["展示名称", "客户编号", "客户名称", "负责人", "客户状态", "销售渠道", "线上销售店铺&链接",
           "联系人名称", "联系人职位", "联系人手机", "联系人微信", "预收款金额", "多维表格记录ID", "ID",
           "创建人", "创建时间", "更新人", "更新时间"]
FIELDS = {"name": "客户名称", "owner": "负责人", "status": "客户状态", "channel": "销售渠道",
          "links": "线上销售店铺&链接", "contact": "联系人名称", "position": "联系人职位",
          "phone": "联系人手机", "wechat": "联系人微信"}


def read_source(path):
    data = Path(path).read_bytes()
    if len(data) > 5 * 1024 * 1024:
        raise ValueError("Source exceeds 5 MiB")
    with ZipFile(path) as archive:
        if len(archive.infolist()) > 512 or sum(i.file_size for i in archive.infolist()) > 32 * 1024 * 1024:
            raise ValueError("Source archive exceeds limits")
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared = ["".join(t.text or "" for t in item.findall(".//m:t", NS))
                      for item in ET.fromstring(archive.read("xl/sharedStrings.xml")).findall("m:si", NS)]
        sheets = [p for p in archive.namelist() if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", p)]
        if len(sheets) != 1:
            raise ValueError("Expected exactly one customer worksheet")
        book = ET.fromstring(archive.read("xl/workbook.xml"))
        sheet_name = book.find("m:sheets/m:sheet", NS).attrib["name"]
        rows = []
        # Iterate actual cells, rather than trusting the export's incorrect dimension=A1.
        for row in ET.fromstring(archive.read(sheets[0])).findall("m:sheetData/m:row", NS):
            values = [None] * len(HEADERS)
            for cell in row.findall("m:c", NS):
                if cell.find("m:f", NS) is not None:
                    raise ValueError("Formula cells are not supported")
                ref = re.fullmatch(r"([A-Z]+)[0-9]+", cell.attrib["r"])
                if ref is None:
                    raise ValueError("Invalid cell reference")
                column = 0
                for letter in ref[1]:
                    column = column * 26 + ord(letter) - ord("A") + 1
                if column > len(HEADERS):
                    raise ValueError("Unexpected source column")
                value = cell.find("m:v", NS)
                text = value.text if value is not None else None
                if cell.get("t") == "s":
                    text = shared[int(text)]
                elif cell.get("t") == "inlineStr":
                    text = "".join(t.text or "" for t in cell.findall(".//m:t", NS))
                values[column - 1] = text
            if any(v is not None and str(v).strip() for v in values):
                rows.append((int(row.attrib["r"]), values))
    if not rows or rows[0] != (1, HEADERS) or not 1 <= len(rows) - 1 <= 2000:
        raise ValueError("Unexpected headers or row count")
    return data, sheet_name, rows[1:]


def prepare(path, state, confirmed):
    data, sheet, rows = read_source(path)
    names, legacy_ids = {}, {}
    for customer in state["customers"]:
        names.setdefault(customer["name"], []).append(customer)
        source = customer.get("legacySource", {})
        if source.get("system") == "legacy-feishu-crm" and source.get("id"):
            if source["id"] in legacy_ids:
                raise ValueError("Duplicate target legacy ID")
            legacy_ids[source["id"]] = customer
    output = {"revision": state["revision"], "source": {
        "sha256": hashlib.sha256(data).hexdigest(), "fileName": Path(path).name, "sheet": sheet},
        "rows": [], "decisions": []}
    seen_names, seen_ids = set(), set()
    total = Decimal("0")
    for number, values in rows:
        raw = dict(zip(HEADERS, values))
        legacy_id = raw["ID"]
        name = (raw["客户名称"] or "").strip()
        if not isinstance(legacy_id, str) or not legacy_id.strip() or legacy_id in seen_ids or not name or name in seen_names:
            raise ValueError("Missing/duplicate source ID or customer name at row %s" % number)
        seen_ids.add(legacy_id)
        seen_names.add(name)
        customer = {key: (raw[label] or "").strip() for key, label in FIELDS.items()}
        customer["entity"] = ""  # Source has no legal-entity column; do not invent one.
        opening = raw["预收款金额"]
        amount = None
        if opening is not None and opening.strip():
            if not re.fullmatch(r"[0-9]+(?:\.[0-9]{1,2})?", opening):
                raise ValueError("Invalid source opening amount at row %s" % number)
            amount = Decimal(opening)
            if amount > Decimal("999999999999.99"):
                raise ValueError("Source opening amount is too large")
            if amount and not confirmed:
                raise ValueError("Positive opening balances require explicit financial confirmation")
            total += amount
        output["rows"].append({"rowNumber": number, "legacyId": legacy_id, "customer": customer,
                               "openingBalance": None if amount is None else float(amount), "raw": raw})
        previous = legacy_ids.get(legacy_id)
        if previous:
            output["decisions"].append({"rowNumber": number, "action": "SKIP", "customerId": previous["id"]})
            continue
        matches = names.get(name, [])
        if len(matches) > 1:
            raise ValueError("Ambiguous production name at row %s" % number)
        decision = {"rowNumber": number, "action": "CREATE"}
        if matches:
            current = matches[0]
            source = current.get("legacySource", {})
            decision.update(action="SKIP" if source.get("id") == legacy_id else "LINK", customerId=current["id"])
        output["decisions"].append(decision)
    return output, total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("--state", required=True, help="Private current CRM state JSON snapshot")
    parser.add_argument("--out", required=True, help="New private JSON file; refuse overwriting")
    parser.add_argument("--confirmed-opening-balances", action="store_true")
    args = parser.parse_args()
    result, total = prepare(args.source, json.loads(Path(args.state).read_text()), args.confirmed_opening_balances)
    descriptor = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as target:
        json.dump(result, target, ensure_ascii=False, separators=(",", ":"))
    counts = {action: sum(r["action"] == action for r in result["decisions"]) for action in ["CREATE", "LINK", "SKIP"]}
    print(json.dumps({"rows": len(result["rows"]), "decisions": counts, "openingBalanceTotal": str(total),
                      "revision": result["revision"], "sourceSHA256": result["source"]["sha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()

import importlib.util
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from xml.etree.ElementTree import Element, SubElement, tostring

SPEC = importlib.util.spec_from_file_location("prepare", Path(__file__).resolve().parents[1] / "scripts/prepare-legacy-customers.py")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
NAMESPACE = PREPARE.NS["m"]


class PreparationTests(unittest.TestCase):
    def workbook(self, directory, amounts):
        root = Element("worksheet", xmlns=NAMESPACE)
        SubElement(root, "dimension", ref="A1")
        data = SubElement(root, "sheetData")
        def add(number, values):
            row = SubElement(data, "row", r=str(number))
            for i, text in enumerate(values):
                if text is None:
                    continue
                cell = SubElement(row, "c", r=chr(65 + i) + str(number), t="inlineStr")
                SubElement(SubElement(cell, "is"), "t").text = text
        add(1, PREPARE.HEADERS)
        for i, amount in enumerate(amounts, 2):
            add(i, ["客户" + str(i), "000" + str(i), "客户" + str(i), None, "感兴趣", "其他", None,
                    None, None, "00123456789", None, amount, None, "98765432101234" + str(i), None, None, None, None])
        validations = SubElement(root, "dataValidations", count="1")
        SubElement(validations, "dataValidation", sqref="E:E", type="list")
        book = Element("workbook", xmlns=NAMESPACE)
        SubElement(SubElement(book, "sheets"), "sheet", name="Sheet1", sheetId="1")
        path = Path(directory) / "source.xlsx"
        with ZipFile(path, "w") as archive:
            archive.writestr("xl/worksheets/sheet1.xml", tostring(root))
            archive.writestr("xl/workbook.xml", tostring(book))
        return path

    def test_wrong_dimension_does_not_drop_rows_and_blank_differs_from_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.workbook(directory, [None, "0", "86.50"])
            result, total = PREPARE.prepare(source, {"revision": 4, "customers": [{"id": "c1", "name": "客户3", "contact": "现资料"}]}, True)
            self.assertEqual(3, len(result["rows"]))
            self.assertEqual([None, 0.0, 86.5], [r["openingBalance"] for r in result["rows"]])
            self.assertEqual("86.50", str(total))
            self.assertEqual("0002", result["rows"][0]["raw"]["客户编号"])
            self.assertEqual("00123456789", result["rows"][0]["customer"]["phone"])
            self.assertEqual("", result["rows"][0]["customer"]["entity"])
            self.assertEqual({"rowNumber": 3, "action": "LINK", "customerId": "c1"}, result["decisions"][1])

    def test_positive_amount_needs_financial_confirmation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.workbook(directory, ["86.50"])
            with self.assertRaisesRegex(ValueError, "financial confirmation"):
                PREPARE.prepare(source, {"revision": 1, "customers": []}, False)

    def test_existing_legacy_id_is_skipped_after_customer_rename(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.workbook(directory, ["86.50"])
            state = {"revision": 4, "customers": [{"id": "c1", "name": "已改名",
                     "legacySource": {"system": "legacy-feishu-crm", "id": "987654321012342"}}]}
            result, _ = PREPARE.prepare(source, state, True)
            self.assertEqual({"rowNumber": 2, "action": "SKIP", "customerId": "c1"}, result["decisions"][0])

    def test_ambiguous_target_and_fractional_cents_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.workbook(directory, ["0"])
            with self.assertRaisesRegex(ValueError, "Ambiguous"):
                PREPARE.prepare(source, {"revision": 1, "customers": [{"name": "客户2"}, {"name": "客户2"}]}, True)
            source = self.workbook(directory, ["0.001"])
            with self.assertRaisesRegex(ValueError, "Invalid source opening"):
                PREPARE.prepare(source, {"revision": 1, "customers": []}, True)


if __name__ == "__main__":
    unittest.main()

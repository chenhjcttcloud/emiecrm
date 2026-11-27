import ast
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
from io import StringIO
from pathlib import Path
import stat
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "jst_shared_order_identity", Path(__file__).with_name("jst_shared_order_identity.py"))
patcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patcher)

# Public, minimal source fixture only. Never import production gateway modules.
FIXTURE = r'''import re

ORDER_ID = re.compile(r'CRM-[0-9a-f]{32}\Z')
SENTINEL = "unchanged private-settings placeholder"


def reconcile_order_lookup(path, biz, result, client):
    so_ids = biz.get('so_ids')
    if not isinstance(so_ids, list) or len(so_ids) != 1 or not isinstance(so_ids[0], str) or not ORDER_ID.fullmatch(so_ids[0]):
        return
    return (path, biz, result, client)


def validate_order_upload(body):
    order = body[0]
    so = order.get('so_id')
    if not isinstance(so, str) or not ORDER_ID.fullmatch(so):
        raise ValueError('invalid order')
    return order
'''


def isolated_predicate(source):
    tree = ast.parse(source)
    nodes = [node for node in tree.body
             if isinstance(node, ast.Import)
             or isinstance(node, ast.Assign) and any(
                 isinstance(target, ast.Name) and target.id == "ORDER_ID" for target in node.targets)
             or isinstance(node, ast.FunctionDef) and node.name == patcher.HELPER_NAME]
    # Execute only the public fixture's re import, known regex and new predicate.
    namespace = {}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), "public-predicate-fixture", "exec"), namespace)
    return namespace[patcher.HELPER_NAME]


class SourcePatchTests(unittest.TestCase):
    def test_changes_only_two_checks_and_inserts_one_exact_helper(self):
        expected = FIXTURE.replace("ORDER_ID.fullmatch(so_ids[0])", "valid_crm_order_identity(so_ids[0])")
        expected = expected.replace("ORDER_ID.fullmatch(so)", "valid_crm_order_identity(so)")
        expected = expected.replace("def validate_order_upload(body):", patcher.HELPER + "def validate_order_upload(body):")
        patched = patcher.patch_source(FIXTURE)
        self.assertEqual(expected, patched)
        self.assertEqual(1, patched.count(patcher.HELPER))
        self.assertIn('SENTINEL = "unchanged private-settings placeholder"', patched)
        self.assertEqual(patched, patcher.patch_source(patched))

    def test_predicate_keeps_strict_legacy_ids_and_accepts_real_short_numbers(self):
        valid = isolated_predicate(patcher.patch_source(FIXTURE))
        accepted = ["CRM-" + "a1" * 16, "N2611270001", "N26112710000", "N0002290001",
                    "N9912319223372036854775807", "N261127" + "0" * 24 + "1"]
        rejected = [None, True, 2611270001, "", "CRM-", "CRM-short", "CRM-" + "A" * 32,
                    "CRM-" + "f" * 33, "CRM-" + "f" * 32 + "\n", "N2602290001", "N2613310001",
                    "N2611000001", "N2611270000", "N261127001", "N2611279223372036854775808",
                    "N261127" + "0" * 25 + "1", "n2611270001", "S2611270001", "N２６１１２７0001",
                    "N2611270001\n", "N26112700\x0001", "N261127-001", "N2611270001.0"]
        for value in accepted:
            with self.subTest(value=value):
                self.assertTrue(valid(value))
        for value in rejected:
            with self.subTest(value=value):
                self.assertFalse(valid(value))

    def test_unknown_partial_or_modified_source_is_refused(self):
        patched = patcher.patch_source(FIXTURE)
        invalid = [
            FIXTURE.replace(patcher.ORDER_ID_LINE, "ORDER_ID = re.compile(r'CRM-.*')"),
            FIXTURE.replace("ORDER_ID.fullmatch(so_ids[0])", "ORDER_ID.fullmatch(so_ids[1])"),
            FIXTURE.replace("ORDER_ID.fullmatch(so)", "ORDER_ID.match(so)"),
            FIXTURE.replace("ORDER_ID.fullmatch(so)", "valid_crm_order_identity(so)"),
            FIXTURE + "\nORDER_ID = re.compile('changed')\n",
            FIXTURE + "\ndef extra_check(value):\n    return ORDER_ID.fullmatch(value)\n",
            FIXTURE + "\ndef validate_order_upload(body):\n    return body\n",
            FIXTURE.replace("import re", "import re as different_re"),
            patched.replace("<= 9223372036854775807", "<= 9223372036854775808"),
            patched + "\nvalid_crm_order_identity = bool\n",
            patched.replace("valid_crm_order_identity(so)", "ORDER_ID.fullmatch(so)"),
            FIXTURE.replace("def validate_order_upload", "@decorator\ndef validate_order_upload"),
            FIXTURE.replace("def reconcile_order_lookup", "async def reconcile_order_lookup"),
            FIXTURE.replace("\n", "\r\n"),
            "not valid Python (",
        ]
        for source in invalid:
            with self.subTest(source_kind=invalid.index(source)):
                with self.assertRaises(patcher.PatchError):
                    patcher.patch_source(source)

    def test_non_ascii_text_before_calls_is_preserved_byte_for_byte(self):
        source = FIXTURE.replace("if not isinstance(so, str)", "if '原始配置' and not isinstance(so, str)")
        patched = patcher.patch_source(source)
        self.assertEqual(source, patched.replace(patcher.HELPER, "").replace(
            "valid_crm_order_identity(so_ids[0])", "ORDER_ID.fullmatch(so_ids[0])").replace(
            "valid_crm_order_identity(so)", "ORDER_ID.fullmatch(so)"))


class CliTests(unittest.TestCase):
    def invoke(self, source, output, expected):
        stdout, stderr = StringIO(), StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                result = patcher.main(["--source", str(source), "--output", str(output),
                                       "--expected-sha256", expected])
            except SystemExit as failure:
                result = failure.code
        self.assertNotIn("unchanged private-settings placeholder", stdout.getvalue() + stderr.getvalue())
        return result

    def test_verifies_digest_and_writes_new_private_file_without_changing_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, output = Path(temporary) / "original.py", Path(temporary) / "patched.py"
            source.write_bytes(FIXTURE.encode())
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            self.assertEqual(0, self.invoke(source, output, digest))
            self.assertEqual(FIXTURE.encode(), source.read_bytes())
            self.assertEqual(patcher.patch_source(FIXTURE), output.read_text())
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))

    def test_bad_hash_same_path_existing_output_and_alias_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source, output = directory / "original.py", directory / "patched.py"
            source.write_bytes(FIXTURE.encode())
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            self.assertEqual(2, self.invoke(source, output, "0" * 64))
            self.assertFalse(output.exists())
            self.assertEqual(2, self.invoke(source, source, digest))
            output.write_text("existing output")
            self.assertEqual(2, self.invoke(source, output, digest))
            self.assertEqual("existing output", output.read_text())
            alias = directory / "alias.py"
            alias.symlink_to(source)
            self.assertEqual(2, self.invoke(source, alias, digest))
            hardlink = directory / "hardlink.py"
            hardlink.hardlink_to(source)
            self.assertEqual(2, self.invoke(source, hardlink, digest))
            self.assertEqual(FIXTURE.encode(), source.read_bytes())


if __name__ == "__main__":
    unittest.main()

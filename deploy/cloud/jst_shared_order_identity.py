"""Patch the known shared gateway's order identity checks without importing it.

The input may contain private connection settings. Never print its contents or
execute/import the resulting gateway module. This tool only writes a new file.
"""
import argparse
import ast
import hashlib
import hmac
import os
from pathlib import Path
import re


ORDER_ID_LINE = r"ORDER_ID = re.compile(r'CRM-[0-9a-f]{32}\Z')"
LEGACY_PATTERN = r"CRM-[0-9a-f]{32}\Z"
HELPER_NAME = "valid_crm_order_identity"
HELPER = '''# CRM_EXTERNAL_ORDER_IDENTITY_V1
def valid_crm_order_identity(value):
    if not isinstance(value, str):
        return False
    if ORDER_ID.fullmatch(value):
        return True
    if len(value) > 32 or re.fullmatch(r'N[0-9]{6}[0-9]{4,}', value) is None:
        return False
    try:
        from datetime import date
        date(2000 + int(value[1:3]), int(value[3:5]), int(value[5:7]))
        return 0 < int(value[7:]) <= 9223372036854775807
    except ValueError:
        return False


'''
TARGET_ARGUMENTS = {
    "reconcile_order_lookup": "so_ids[0]",
    "validate_order_upload": "so",
}


class PatchError(ValueError):
    """The source does not match the known original or exact patched version."""


def _legacy_call(node):
    return (isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "ORDER_ID"
            and node.func.attr == "fullmatch")


def _patched_call(node):
    return (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
            and node.func.id == HELPER_NAME)


def _shape(text):
    if not isinstance(text, str) or "\r" in text:
        raise PatchError("Unsupported source text format")
    try:
        tree = ast.parse(text)
    except (SyntaxError, ValueError):
        raise PatchError("Source is not a supported Python module") from None
    if text.splitlines().count(ORDER_ID_LINE) != 1:
        raise PatchError("Known ORDER_ID declaration not found exactly once")
    declarations = [node for node in ast.walk(tree)
                    if isinstance(node, ast.Name) and node.id == "ORDER_ID"
                    and isinstance(node.ctx, ast.Store)]
    if len(declarations) != 1:
        raise PatchError("Unexpected ORDER_ID assignment")
    declaration = next((node for node in tree.body
                        if isinstance(node, ast.Assign) and len(node.targets) == 1
                        and isinstance(node.targets[0], ast.Name)
                        and node.targets[0].id == "ORDER_ID"), None)
    if (declaration is None or not isinstance(declaration.value, ast.Call)
            or not isinstance(declaration.value.func, ast.Attribute)
            or not isinstance(declaration.value.func.value, ast.Name)
            or declaration.value.func.value.id != "re"
            or declaration.value.func.attr != "compile"
            or len(declaration.value.args) != 1 or declaration.value.keywords
            or not isinstance(declaration.value.args[0], ast.Constant)
            or declaration.value.args[0].value != LEGACY_PATTERN):
        raise PatchError("Unexpected ORDER_ID expression")
    if not any(isinstance(node, ast.Import)
               and any(alias.name == "re" and alias.asname is None for alias in node.names)
               for node in tree.body):
        raise PatchError("Expected regular expression import not found")
    functions = {}
    for name in TARGET_ARGUMENTS:
        matches = [node for node in ast.walk(tree)
                   if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                   and node.name == name]
        if len(matches) != 1 or matches[0] not in tree.body:
            raise PatchError("Expected identity check functions not found uniquely")
        if not isinstance(matches[0], ast.FunctionDef) or matches[0].decorator_list:
            raise PatchError("Modified identity check functions are not supported")
        functions[name] = matches[0]
    helpers = [node for node in ast.walk(tree)
               if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
               and node.name == HELPER_NAME]
    patched = bool(helpers)
    if patched:
        if len(helpers) != 1 or helpers[0] not in tree.body or text.count(HELPER) != 1:
            raise PatchError("Existing identity helper is not the exact supported patch")
        expected_helper = ast.parse(HELPER).body[0]
        if ast.dump(helpers[0]) != ast.dump(expected_helper):
            raise PatchError("Existing identity helper was modified")
        if any(isinstance(node, ast.Name) and node.id == HELPER_NAME
               and isinstance(node.ctx, ast.Store) for node in ast.walk(tree)):
            raise PatchError("Existing identity helper is reassigned")
    elif HELPER_NAME in text or "CRM_EXTERNAL_ORDER_IDENTITY_V1" in text:
        raise PatchError("Unexpected partial identity patch")
    targets = []
    for name, expression in TARGET_ARGUMENTS.items():
        function = functions[name]
        old_calls = [node for node in ast.walk(function) if _legacy_call(node)]
        new_calls = [node for node in ast.walk(function) if _patched_call(node)]
        calls = new_calls if patched else old_calls
        if len(calls) != 1 or (old_calls if patched else new_calls):
            raise PatchError("Identity call count does not match the known version")
        call = calls[0]
        expected = ast.parse(expression, mode="eval").body
        if (len(call.args) != 1 or call.keywords
                or ast.dump(call.args[0]) != ast.dump(expected)):
            raise PatchError("Identity call argument does not match the known version")
        source = (HELPER_NAME if patched else "ORDER_ID.fullmatch") + "(" + expression + ")"
        if ast.get_source_segment(text, call) != source:
            raise PatchError("Identity call text does not match the known version")
        targets.append(call)
    legacy_calls = [node for node in ast.walk(tree) if _legacy_call(node)]
    patched_calls = [node for node in ast.walk(tree) if _patched_call(node)]
    if len(legacy_calls) != (1 if patched else 2) or len(patched_calls) != (2 if patched else 0):
        raise PatchError("Unexpected additional identity checks")
    return tree, functions, targets, patched


def _offset(text, line, column):
    # AST columns count UTF-8 bytes; preserve non-ASCII text elsewhere verbatim.
    lines = text.splitlines(keepends=True)
    return sum(len(part) for part in lines[:line - 1]) + len(
        lines[line - 1].encode("utf-8")[:column].decode("utf-8"))


def patch_source(text):
    """Return the known original with exactly one helper and two call changes."""
    _, functions, calls, patched = _shape(text)
    if patched:
        return text
    edits = []
    for call in calls:
        start = _offset(text, call.lineno, call.col_offset)
        end = _offset(text, call.end_lineno, call.end_col_offset)
        original = ast.get_source_segment(text, call)
        edits.append((start, end, original.replace("ORDER_ID.fullmatch", HELPER_NAME, 1)))
    anchor = functions["validate_order_upload"]
    position = _offset(text, anchor.lineno, 0)
    edits.append((position, position, HELPER))
    result = text
    for start, end, replacement in sorted(edits, reverse=True):
        result = result[:start] + replacement + result[end:]
    _shape(result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Generate a new, verified shared gateway source file")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256):
            raise PatchError("Expected SHA-256 must be 64 lowercase hexadecimal characters")
        if args.source.resolve() == args.output.resolve():
            raise PatchError("Output must be a new path distinct from source")
        raw = args.source.read_bytes()
        if not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), args.expected_sha256):
            raise PatchError("Source SHA-256 does not match the expected version")
        result = patch_source(raw.decode("utf-8")).encode("utf-8")
        # O_EXCL also refuses existing files, symlinks and hard links. The generated
        # file may contain private connection settings, so it must remain private.
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(result)
            stream.flush()
            os.fsync(stream.fileno())
    except (OSError, UnicodeError, PatchError) as failure:
        # Never include source snippets, upstream configuration or exception bodies.
        parser.exit(2, "Shared gateway patch refused: " + (
            str(failure) if isinstance(failure, PatchError) else "file operation failed") + "\n")
    print("Verified patch written to a new private file; source unchanged.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

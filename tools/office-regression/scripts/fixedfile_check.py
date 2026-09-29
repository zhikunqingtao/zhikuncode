#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R-12 fixed-file (SHA256SUMS) checker (stdlib only, Python 3.9 compatible).

A small, deterministic manifest tool used by the regression suite to pin the
fixture set: it can generate a SHA256SUMS manifest and verify a directory against
one, with explicit reason codes (FIXEDFILE_MISMATCH / FIXEDFILE_MISSING /
FIXEDFILE_EXTRA / FIXEDFILE_MANIFEST_INVALID) so tampering is always attributed.

Exit codes: 0 = healthy, 2 = violations, 1 = usage/IO error.
"""

import argparse
import hashlib
import json
import os
import sys

CHUNK = 1 << 20


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(CHUNK)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def iter_files(directory):
    for root, dirs, files in os.walk(directory):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(root, name)
            yield os.path.relpath(full, directory).replace("\\", "/")


def generate(directory, out_path, excludes):
    entries = []
    for rel in iter_files(directory):
        if rel in excludes:
            continue
        entries.append((rel, sha256_file(os.path.join(directory, rel))))
    entries.sort()
    with open(out_path, "w", encoding="utf-8") as handle:
        for rel, digest in entries:
            handle.write("%s  %s\n" % (digest, rel))
    return entries


def load_manifest(path):
    entries = {}
    problems = []
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.rstrip("\n")
            if not line.strip():
                continue
            parts = line.split(None, 1)
            if len(parts) != 2 or len(parts[0]) != 64:
                problems.append("line %d is not '<sha256>  <path>': %r" % (number, line))
                continue
            entries[parts[1].strip()] = parts[0].lower()
    return entries, problems


def verify(directory, manifest_path, excludes):
    result = {"ok": False, "directory": directory, "manifest": manifest_path,
              "reason_codes": [], "reasons": []}

    def reason(code, detail):
        result["reasons"].append({"code": code, "detail": detail})
        if code not in result["reason_codes"]:
            result["reason_codes"].append(code)

    if not os.path.isfile(manifest_path):
        reason("FIXEDFILE_MANIFEST_INVALID", "manifest file not found: %s" % manifest_path)
        result["reason_codes"] = sorted(result["reason_codes"])
        return result
    expected, problems = load_manifest(manifest_path)
    for problem in problems:
        reason("FIXEDFILE_MANIFEST_INVALID", problem)
    if problems:
        result["reason_codes"] = sorted(result["reason_codes"])
        return result

    actual = {}
    for rel in iter_files(directory):
        if rel in excludes:
            continue
        actual[rel] = sha256_file(os.path.join(directory, rel))

    for rel, digest in sorted(expected.items()):
        if rel not in actual:
            reason("FIXEDFILE_MISSING", "%s is listed in the manifest but missing on disk" % rel)
        elif actual[rel] != digest:
            reason("FIXEDFILE_MISMATCH",
                   "%s sha256 %s != manifest %s" % (rel, actual[rel], digest))
    for rel in sorted(actual):
        if rel not in expected:
            reason("FIXEDFILE_EXTRA", "%s exists on disk but is not listed in the manifest" % rel)
    result["ok"] = not result["reasons"]
    result["checked_files"] = len(expected)
    result["reason_codes"] = sorted(result["reason_codes"])
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="R-12 fixed-file SHA256 manifest tool")
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("gen")
    gen.add_argument("--dir", required=True)
    gen.add_argument("--out", required=True)
    gen.add_argument("--exclude", action="append", default=[])
    ver = sub.add_parser("verify")
    ver.add_argument("--dir", required=True)
    ver.add_argument("--manifest", required=True)
    ver.add_argument("--exclude", action="append", default=[])
    args = parser.parse_args(argv)

    if args.command == "gen":
        entries = generate(os.path.abspath(args.dir), os.path.abspath(args.out),
                           set(args.exclude))
        print(json.dumps({"ok": True, "manifest": os.path.abspath(args.out),
                          "entries": len(entries)}, ensure_ascii=False, indent=2))
        return 0
    result = verify(os.path.abspath(args.dir), os.path.abspath(args.manifest), set(args.exclude))
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    sys.exit(main())

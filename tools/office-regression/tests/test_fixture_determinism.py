# -*- coding: utf-8 -*-
"""Fixture generator determinism: two independent runs must be byte-identical.

Byte determinism is what lets the manifest / fixed-file checks anchor on
fixture bytes, so it is asserted for the whole generated tree (xlsx/docx/pptx
incl. their negatives, plus the specs).
"""

import os
import shutil
import sys

import r12lib as R12

REQUIRED_ARTIFACTS = (
    "xlsx/positive.xlsx",
    "docx/positive.docx",
    "pptx/positive.pptx",
)


def test_fixture_generation_is_byte_deterministic(r12, generated):
    generator = os.path.join(r12["scripts"], "office_fixture_gen.py")
    runs = []
    for name in ("determinism-a", "determinism-b"):
        target = os.path.join(r12["work"], name)
        if os.path.isdir(target):
            shutil.rmtree(target)
        proc = R12.run([sys.executable, generator, "--out", target, "--quiet"],
                       timeout=180, check=True)
        assert proc.returncode == 0
        runs.append(target)

    hashes_a = R12.tree_hashes(runs[0])
    hashes_b = R12.tree_hashes(runs[1])

    evidence = {
        "run_a": runs[0],
        "run_b": runs[1],
        "files_a": sorted(hashes_a),
        "identical": hashes_a == hashes_b,
        "mismatched": sorted(set(hashes_a) ^ set(hashes_b)) or
                      sorted(k for k in hashes_a if hashes_a.get(k) != hashes_b.get(k)),
        "hashes_a": hashes_a,
    }
    R12.write_evidence(r12["evidence"], "fixture-determinism", evidence)

    assert hashes_a, "fixture generator produced no files"
    assert hashes_a == hashes_b, (
        "fixture generator is not byte-deterministic; differing files: %s" % evidence["mismatched"])
    for artifact in REQUIRED_ARTIFACTS:
        assert artifact in hashes_a, "fixture generator did not produce %s" % artifact

    # The session fixture set must be byte-identical to the fresh run as well.
    session_hashes = R12.tree_hashes(generated)
    assert session_hashes == hashes_a, (
        "session fixture tree differs from a fresh generator run: %s"
        % sorted(set(session_hashes) ^ set(hashes_a)))
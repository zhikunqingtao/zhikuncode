# -*- coding: utf-8 -*-
"""Fixed-file checker: fixtures/HTML-SHA256SUMS anchors the served HTML set.

The pristine fixture directory must verify clean; tampering, deleting and
adding files must each be attributed with their exact reason code.
"""

import os
import shutil

import r12lib as R12


def _verify(r12, directory, label):
    return R12.invoke_fixedfile(
        r12["scripts"],
        ["verify", "--dir", directory, "--manifest", r12["html_manifest"]],
        r12["evidence"], label)


def test_fixedfile_manifest_ok(r12):
    rc, payload = _verify(r12, r12["fixtures_html"], "fixedfile-pristine")
    assert rc == 0, "pristine fixture directory must verify clean: %s" % payload
    assert payload["ok"] is True
    assert payload["reason_codes"] == []
    assert payload["checked_files"] == 6, payload


def test_fixedfile_tamper_matrix_reason_codes(r12):
    base = os.path.join(r12["work"], "fixedfile")
    fixtures = r12["fixtures_html"]

    def fresh_copy(name):
        target = os.path.join(base, name)
        if os.path.isdir(target):
            shutil.rmtree(target)
        shutil.copytree(fixtures, target)
        return target

    # 1) tampered content -> FIXEDFILE_MISMATCH (and nothing else)
    tampered = fresh_copy("tampered")
    with open(os.path.join(tampered, "assets", "app.js"), "ab") as handle:
        handle.write(b"\n// tampered\n")
    rc, payload = _verify(r12, tampered, "fixedfile-tampered")
    assert rc == 2, payload
    assert payload["reason_codes"] == ["FIXEDFILE_MISMATCH"], payload
    assert any("app.js" in reason["detail"] for reason in payload["reasons"]), payload

    # 2) manifest entry missing on disk -> FIXEDFILE_MISSING
    missing = fresh_copy("missing")
    os.remove(os.path.join(missing, "broken.html"))
    rc, payload = _verify(r12, missing, "fixedfile-missing")
    assert rc == 2, payload
    assert payload["reason_codes"] == ["FIXEDFILE_MISSING"], payload
    assert any("broken.html" in reason["detail"] for reason in payload["reasons"]), payload

    # 3) extra unlisted file -> FIXEDFILE_EXTRA
    extra = fresh_copy("extra")
    with open(os.path.join(extra, "extra.txt"), "w", encoding="utf-8") as handle:
        handle.write("not in the manifest\n")
    rc, payload = _verify(r12, extra, "fixedfile-extra")
    assert rc == 2, payload
    assert payload["reason_codes"] == ["FIXEDFILE_EXTRA"], payload
    assert any("extra.txt" in reason["detail"] for reason in payload["reasons"]), payload
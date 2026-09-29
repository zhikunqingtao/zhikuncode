# -*- coding: utf-8 -*-
"""DOCX regression: native heading hierarchy + tables, PDF text, negative controls.

Heading detection must be by native w:pStyle (outline level), never by font
size / boldness -- negative-appearance.docx only "looks" like headings and must
be attributed as such.
"""

import json
import os

import r12lib as R12


def _fixture(generated, *parts):
    return os.path.join(generated, *parts)


def _spec(generated, kind):
    path = _fixture(generated, "specs", kind + ".json")
    with open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def test_docx_positive_structure_native_headings_and_table(r12, generated):
    spec_path, spec = _spec(generated, "docx")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "docx",
        _fixture(generated, "docx", "positive.docx"), spec_path,
        r12["evidence"], "docx-positive-structure")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive docx must have no structural violations: %s" % payload.get("reasons")
    assert rc == 0
    # Guard against a vacuous pass: the native heading styles and table were
    # really present in the inspected paragraph inventory.
    styles = payload["info"]["paragraph_styles"]
    assert "Heading1" in styles and "Heading2" in styles, styles
    assert styles.index("Heading1") < styles.index("Heading2"), styles
    # The positive fixture must carry *effective* outline levels (fixture drift
    # to name-only headings would make this "positive" vacuous).
    assert payload["info"]["heading_levels"] == [1, 2], payload["info"]
    expected_heading_texts = [h["text"] for h in spec["headings"]]
    assert expected_heading_texts == ["收入概览", "国内业务"]


def test_docx_positive_pdf_text(r12, generated):
    spec_path, spec = _spec(generated, "docx")
    soffice = R12.soffice_bin()
    work = os.path.join(r12["work"], "docx-positive")
    pdf = R12.lo_convert(soffice, _fixture(generated, "docx", "positive.docx"), work, convert_to="pdf")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "pdf", "docx", pdf, spec_path,
        r12["evidence"], "docx-positive-pdf")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "pdf checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive docx PDF must satisfy spec (text + page count): %s" % payload.get("reasons")
    assert rc == 0

    compact = "".join(R12.pdftotext(pdf).split())
    for required in spec["pdf_text_required"]:
        assert "".join(required.split()) in compact, "DOCX PDF text lost %r" % required
    for required in spec["body_text_required"]:
        assert "".join(required.split()) in compact, "DOCX PDF body text lost %r" % required
    assert R12.pdf_page_count(pdf) == spec["pdf_page_count"]
    R12.render_pdf_png(pdf, os.path.join(work, "docx-positive"), resolution=60)
    R12.write_evidence(r12["evidence"], "docx-positive-pdf-summary", {
        "pdf_file": pdf, "pdf_chars": payload["info"].get("pdf_chars"),
        "pdf_pages": payload["info"].get("pdf_pages")})


def test_docx_negatives_hit_reason_codes(r12, generated):
    spec_path, _ = _spec(generated, "docx")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "docx",
        _fixture(generated, "docx", "negative-appearance.docx"), spec_path,
        r12["evidence"], "docx-negative-appearance")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"DOCX_HEADING_STYLE_MISSING"}, (
        "negative-appearance.docx (fake headings) must hit exactly DOCX_HEADING_STYLE_MISSING, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "docx",
        _fixture(generated, "docx", "negative-hierarchy.docx"), spec_path,
        r12["evidence"], "docx-negative-hierarchy")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"DOCX_HEADING_HIERARCHY_BROKEN"}, (
        "negative-hierarchy.docx (Heading2 before Heading1) must hit exactly "
        "DOCX_HEADING_HIERARCHY_BROKEN, got %s (reasons=%s)" % (sorted(codes), payload.get("reasons")))


def test_docx_headings_use_effective_outline_level(r12, generated):
    """Heading matching must follow the *effective* outline level, not the
    pStyle name string.

    negative-no-outline.docx keeps the Heading1/Heading2 style names (and their
    definitions) but grants no w:outlineLvl anywhere in the paragraph or its
    basedOn chain: LibreOffice renders plain body text, so the checker must not
    accept it. positive-outline-inherited.docx gets its levels only through the
    basedOn chain and must still pass (guards against a false positive from
    ignoring inheritance).
    """
    spec_path, _ = _spec(generated, "docx")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "docx",
        _fixture(generated, "docx", "negative-no-outline.docx"), spec_path,
        r12["evidence"], "docx-negative-no-outline")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"DOCX_HEADING_STYLE_MISSING"}, (
        "negative-no-outline.docx (style names kept, no effective outline level) must hit exactly "
        "DOCX_HEADING_STYLE_MISSING, got %s (reasons=%s)" % (sorted(codes), payload.get("reasons")))
    assert payload["info"]["heading_levels"] == [], payload["info"]
    assert any("effective" in reason["detail"] and "outline level" in reason["detail"]
               for reason in payload["reasons"]), payload["reasons"]

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "docx",
        _fixture(generated, "docx", "positive-outline-inherited.docx"), spec_path,
        r12["evidence"], "docx-positive-outline-inherited")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "inherited outline levels must not be misreported: %s" % payload.get("reasons")
    assert rc == 0
    assert payload["info"]["heading_levels"] == [1, 2], payload["info"]
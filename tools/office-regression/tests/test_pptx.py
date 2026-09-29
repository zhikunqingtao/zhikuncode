# -*- coding: utf-8 -*-
"""PPTX regression: native chart part + embedded workbook, PDF render.

The positive deck carries a real chart part (ppt/charts/chart1.xml) with an
embedded xlsx; the negative decks flatten the chart into a picture or drop the
embedded workbook, and must be attributed with the matching reason code.

Embedding defects are exercised under both the full spec and an `embed-only`
spec (no `require_chart`), so the embedded-workbook check cannot regress into
a branch that is unreachable without a declared chart.
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


def test_pptx_positive_structure_native_chart_and_embedding(r12, generated):
    spec_path, spec = _spec(generated, "pptx")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "positive.pptx"), spec_path,
        r12["evidence"], "pptx-positive-structure")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive pptx must have no structural violations: %s" % payload.get("reasons")
    assert rc == 0
    # Guard against a vacuous pass: chart part + embedded workbook were really found.
    assert payload["info"]["slide_count"] == len(spec["slides"]) == 2
    assert payload["info"]["chart_parts"] == ["ppt/charts/chart1.xml"], payload["info"]
    assert payload["info"]["embedded_workbooks"] == ["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"], payload["info"]


def test_pptx_positive_pdf_render(r12, generated):
    spec_path, spec = _spec(generated, "pptx")
    soffice = R12.soffice_bin()
    work = os.path.join(r12["work"], "pptx-positive")
    pdf = R12.lo_convert(soffice, _fixture(generated, "pptx", "positive.pptx"), work, convert_to="pdf")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "pdf", "pptx", pdf, spec_path,
        r12["evidence"], "pptx-positive-pdf")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "pdf checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive pptx PDF must satisfy spec (text + page count): %s" % payload.get("reasons")
    assert rc == 0

    compact = "".join(R12.pdftotext(pdf).split())
    for required in spec["pdf_text_required"]:
        assert "".join(required.split()) in compact, "PPTX PDF text lost %r" % required
    assert R12.pdf_page_count(pdf) == spec["pdf_page_count"] == 2
    R12.render_pdf_png(pdf, os.path.join(work, "pptx-positive"), resolution=60)
    R12.write_evidence(r12["evidence"], "pptx-positive-pdf-summary", {
        "pdf_file": pdf, "pdf_chars": payload["info"].get("pdf_chars"),
        "pdf_pages": payload["info"].get("pdf_pages")})


def test_pptx_negatives_hit_reason_codes(r12, generated):
    spec_path, _ = _spec(generated, "pptx")

    # Chart replaced by a flattened image AND no embedded workbook.
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "negative-flattened.pptx"), spec_path,
        r12["evidence"], "pptx-negative-flattened")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"PPTX_CHART_FLATTENED_IMAGE", "PPTX_EMBEDDED_WORKBOOK_MISSING"}, (
        "negative-flattened.pptx must hit exactly the flattened-image and missing-embedding codes, "
        "got %s (reasons=%s)" % (sorted(codes), payload.get("reasons")))

    # Native chart kept, embedded workbook dropped.
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "negative-noembed.pptx"), spec_path,
        r12["evidence"], "pptx-negative-noembed")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"PPTX_EMBEDDED_WORKBOOK_MISSING"}, (
        "negative-noembed.pptx must hit exactly PPTX_EMBEDDED_WORKBOOK_MISSING, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))


def test_pptx_embed_only_spec_reaches_embedded_workbook_check(r12, generated):
    """A spec that requires the embedded workbook *without* `require_chart`
    must still detect a deck whose workbook was dropped.

    Regression: the embedding check used to be nested inside the require_chart
    branch, so this field combination silently passed while the same deck under
    the full spec was attributed correctly.
    """
    spec_path, spec = _spec(generated, "pptx-embed-only")
    assert "require_chart" not in spec["slides"][1], spec["slides"][1]
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "negative-noembed.pptx"), spec_path,
        r12["evidence"], "pptx-embed-only-negative-noembed")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2, "the dropped workbook must be a violation even without require_chart"
    assert codes == {"PPTX_EMBEDDED_WORKBOOK_MISSING"}, (
        "negative-noembed.pptx under the embed-only spec must hit exactly "
        "PPTX_EMBEDDED_WORKBOOK_MISSING, got %s (reasons=%s)" % (sorted(codes), payload.get("reasons")))


def test_pptx_embed_only_spec_invalid_embedded_workbooks(r12, generated):
    """The embed-only spec must also attribute an unusable embedded workbook
    (non-zip bytes / inner package without xl/workbook.xml) as INVALID."""
    spec_path, _ = _spec(generated, "pptx-embed-only")
    for name in ("negative-embed-notzip.pptx", "negative-embed-noworkbook.pptx"):
        rc, payload = R12.invoke_checker(
            r12["scripts"], "structure", "pptx",
            _fixture(generated, "pptx", name), spec_path,
            r12["evidence"], "pptx-embed-only-" + name[:-len(".pptx")])
        codes = R12.checker_codes(rc, payload)
        assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
        assert rc == 2, "%s must be a violation, got exit %s" % (name, rc)
        assert codes == {"PPTX_EMBEDDED_WORKBOOK_INVALID"}, (
            "%s must hit exactly PPTX_EMBEDDED_WORKBOOK_INVALID, got %s (reasons=%s)"
            % (name, sorted(codes), payload.get("reasons")))


def test_pptx_embedding_link_and_content_type_negatives(r12, generated):
    """Even with require_chart declared, a workbook the chart can no longer
    reach, and a linked workbook whose [Content_Types].xml declaration was
    dropped, must both be attributed (they used to be silently accepted)."""
    spec_path, _ = _spec(generated, "pptx")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "negative-embed-unlinked.pptx"), spec_path,
        r12["evidence"], "pptx-negative-embed-unlinked")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"PPTX_EMBEDDED_WORKBOOK_MISSING"}, (
        "negative-embed-unlinked.pptx (chart->workbook relationship removed, part kept) must hit "
        "exactly PPTX_EMBEDDED_WORKBOOK_MISSING, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "negative-embed-noctoverride.pptx"), spec_path,
        r12["evidence"], "pptx-negative-embed-noctoverride")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"PPTX_EMBEDDED_WORKBOOK_CONTENT_TYPE_MISSING"}, (
        "negative-embed-noctoverride.pptx (content-types Override dropped) must hit exactly "
        "PPTX_EMBEDDED_WORKBOOK_CONTENT_TYPE_MISSING, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))


def test_pptx_chart_without_external_data_link_is_missing_workbook(r12, generated):
    """A readable chart chain whose <c:externalData> link was deleted must be a
    determined "no workbook" verdict, not a fallback to the package namelist.

    Regression: without the link the check used to fall back to scanning
    ppt/embeddings/*.xlsx, so the orphaned workbook part left in the same
    package masked the missing link (exit 0 under both specs).
    """
    fixture = _fixture(generated, "pptx", "negative-embed-noexternaldata.pptx")
    for kind, label in (("pptx", "pptx-negative-embed-noexternaldata"),
                        ("pptx-embed-only", "pptx-embed-only-negative-embed-noexternaldata")):
        spec_path, _ = _spec(generated, kind)
        rc, payload = R12.invoke_checker(
            r12["scripts"], "structure", "pptx", fixture, spec_path,
            r12["evidence"], label)
        codes = R12.checker_codes(rc, payload)
        assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
        assert rc == 2, "the deleted workbook link must be a violation under %s, got exit %s" % (kind, rc)
        assert codes == {"PPTX_EMBEDDED_WORKBOOK_MISSING"}, (
            "negative-embed-noexternaldata.pptx under %s must hit exactly "
            "PPTX_EMBEDDED_WORKBOOK_MISSING, got %s (reasons=%s)"
            % (kind, sorted(codes), payload.get("reasons")))
        # The fixture really is "chart kept, workbook part kept but orphaned":
        # the detection must not come from the namelist scan finding nothing.
        assert payload["info"]["chart_parts"] == ["ppt/charts/chart1.xml"], payload["info"]
        assert payload["info"]["embedded_workbooks"] == ["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"], payload["info"]


def test_pptx_embed_only_spec_positive_no_false_positive(r12, generated):
    """The embed-only spec must not flag a healthy deck (guard against an
    over-eager hoisted check) and the evidence must stay concrete."""
    spec_path, _ = _spec(generated, "pptx-embed-only")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "pptx",
        _fixture(generated, "pptx", "positive.pptx"), spec_path,
        r12["evidence"], "pptx-embed-only-positive")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive pptx under the embed-only spec must have no violations: %s" % payload.get("reasons")
    assert rc == 0
    assert payload["info"]["embedded_workbooks"] == ["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"], payload["info"]
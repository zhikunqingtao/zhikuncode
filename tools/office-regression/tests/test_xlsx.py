# -*- coding: utf-8 -*-
"""XLSX regression: native structure, LibreOffice recalculation, display formats.

Positive fixtures must pass with zero reason codes; negative fixtures must hit
the *specific* reason code for their defect (never "some command failed").
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


def test_xlsx_positive_structure(r12, generated):
    spec_path, spec = _spec(generated, "xlsx")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "xlsx",
        _fixture(generated, "xlsx", "positive.xlsx"), spec_path,
        r12["evidence"], "xlsx-positive-structure")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive xlsx must have no structural violations: %s" % payload.get("reasons")
    assert rc == 0
    # The structure evidence itself must be real: both sheets and the cross-sheet
    # formula cells were inspected (guards against a vacuous checker).
    assert payload["info"]["sheets"] == spec["sheets_in_order"]
    formulas = [cell.get("cross_sheet_ref") for cell in spec["cells"] if cell.get("cross_sheet_ref")]
    assert formulas == ["Data!", "Data!", "Data!"]


def test_xlsx_positive_recalculated_values_and_pdf_display(r12, generated):
    spec_path, spec = _spec(generated, "xlsx")
    positive = _fixture(generated, "xlsx", "positive.xlsx")
    soffice = R12.soffice_bin()
    work = os.path.join(r12["work"], "xlsx-positive")
    evidence = {"spec_expected": [c.get("expect_value") for c in spec["cells"]],
                "spec_pdf_display": [c.get("pdf_display") for c in spec["cells"]]}

    # 1) LibreOffice must *recalculate* the formulas (the fixture carries no
    #    cached values) and the result must match the independently authored spec.
    recalculated = R12.lo_convert(soffice, positive, work, convert_to="xlsx")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "values", "xlsx", recalculated, spec_path,
        r12["evidence"], "xlsx-positive-values")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "values checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), (
        "positive xlsx recalculated by LibreOffice must match the independent spec: %s"
        % payload.get("reasons"))
    assert rc == 0
    evidence["recalculated_file"] = recalculated

    # 2) The rendered PDF must show the percent / units number formats from the spec.
    pdf = R12.lo_convert(soffice, positive, work, convert_to="pdf")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "pdf", "xlsx", pdf, spec_path,
        r12["evidence"], "xlsx-positive-pdf")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "pdf checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert codes == set(), "positive xlsx PDF display values must match the spec: %s" % payload.get("reasons")
    assert rc == 0
    compact = "".join(R12.pdftotext(pdf).split())
    for display in spec["pdf_text_required"]:
        assert "".join(display.split()) in compact, "PDF text lost %r" % display
    evidence["pdf_file"] = pdf
    evidence["pdf_chars"] = payload["info"].get("pdf_chars")
    R12.render_pdf_png(pdf, os.path.join(work, "xlsx-positive"), resolution=60)
    R12.write_evidence(r12["evidence"], "xlsx-positive-values-pdf-summary", evidence)


def test_xlsx_negative_values_hit_reason_codes(r12, generated):
    """Wrong cross-sheet target + division by zero must be attributed, not fatal."""
    spec_path, _spec_data = _spec(generated, "xlsx")
    negative = _fixture(generated, "xlsx", "negative-values.xlsx")
    work = os.path.join(r12["work"], "xlsx-negative-values")
    soffice = R12.soffice_bin()
    recalculated = R12.lo_convert(soffice, negative, work, convert_to="xlsx")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "values", "xlsx", recalculated, spec_path,
        r12["evidence"], "xlsx-negative-values")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2, "violations must exit with code 2, got %s" % rc
    assert codes == {"XLSX_VALUE_MISMATCH", "XLSX_FORMULA_ERROR"}, (
        "negative-values.xlsx must hit exactly the wrong-value and formula-error codes, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))


def test_xlsx_negative_structure_reason_codes(r12, generated):
    """Wrong display format / missing <f> formula must be attributed exactly."""
    spec_path, _spec_data = _spec(generated, "xlsx")

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "xlsx",
        _fixture(generated, "xlsx", "negative-numfmt.xlsx"), spec_path,
        r12["evidence"], "xlsx-negative-numfmt")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"XLSX_MISSING_NUMFMT"}, (
        "negative-numfmt.xlsx must hit exactly XLSX_MISSING_NUMFMT, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))

    rc, payload = R12.invoke_checker(
        r12["scripts"], "structure", "xlsx",
        _fixture(generated, "xlsx", "negative-missing-formula.xlsx"), spec_path,
        r12["evidence"], "xlsx-negative-missing-formula")
    codes = R12.checker_codes(rc, payload)
    assert codes is not None, "checker did not complete normally: rc=%s payload=%s" % (rc, payload)
    assert rc == 2
    assert codes == {"XLSX_MISSING_FORMULA"}, (
        "negative-missing-formula.xlsx must hit exactly XLSX_MISSING_FORMULA, got %s (reasons=%s)"
        % (sorted(codes), payload.get("reasons")))
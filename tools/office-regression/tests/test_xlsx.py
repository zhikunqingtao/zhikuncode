# -*- coding: utf-8 -*-
"""XLSX regression: native structure, LibreOffice recalculation, display formats.

Positive fixtures must pass with zero reason codes; negative fixtures must hit
the *specific* reason code for their defect (never "some command failed").
"""

import json
import os
import xml.etree.ElementTree as ET
import zipfile

import pytest

import r12lib as R12

SSML = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def _fixture(generated, *parts):
    return os.path.join(generated, *parts)


def _spec(generated, kind):
    path = _fixture(generated, "specs", kind + ".json")
    with open(path, encoding="utf-8") as handle:
        return path, json.load(handle)


def _xlsx_with_b2(source, destination, formula=None, cell_type=None, value=None):
    """Copy a fixture and change only Summary!B2, never the independent spec."""
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(destination, "w") as output:
        for part in archive.infolist():
            data = archive.read(part.filename)
            if part.filename == "xl/worksheets/sheet1.xml":
                sheet = ET.fromstring(data)
                cell = sheet.find(".//" + SSML + "c[@r='B2']")
                assert cell is not None, "fixture must contain Summary!B2"
                cached = cell.find(SSML + "v")
                if formula is not None:
                    cell.find(SSML + "f").text = formula
                    cell.attrib.pop("t", None)
                    if cached is not None:
                        cell.remove(cached)
                else:
                    cell.set("t", cell_type)
                    if cached is None:
                        cached = ET.SubElement(cell, SSML + "v")
                    cached.text = value
                data = ET.tostring(sheet, encoding="utf-8", xml_declaration=True)
            output.writestr(part, data)
    return str(destination)


def _b2_type_and_value(path):
    with zipfile.ZipFile(path) as archive:
        sheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
    cell = sheet.find(".//" + SSML + "c[@r='B2']")
    return cell.get("t", "n"), cell.find(SSML + "v").text


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


@pytest.mark.parametrize("label,formula,cell_type,value,expected_codes", [
    ("string-200", 'IF(Data!B2>0,"200",Data!B2*2)', "str", "200", set()),
    ("string-nan", 'IF(Data!B2>0,"NaN",Data!B2*2)', "str", "NaN", {"XLSX_VALUE_MISMATCH"}),
    ("wrong-300", "Data!B2*3", "n", "300", {"XLSX_VALUE_MISMATCH"}),
    ("division-by-zero", "Data!B2/0", "e", "#DIV/0!", {"XLSX_FORMULA_ERROR"}),
], ids=["string-200", "string-nan", "wrong-300", "division-by-zero"])
def test_xlsx_recalculated_value_boundaries(
        r12, generated, tmp_path, label, formula, cell_type, value, expected_codes):
    """Real LibreOffice results: numeric strings remain compatible, NaN does not."""
    spec_path, _ = _spec(generated, "xlsx")
    source = _xlsx_with_b2(
        _fixture(generated, "xlsx", "positive.xlsx"), tmp_path / (label + ".xlsx"),
        formula=formula)
    work = os.path.join(r12["work"], "xlsx-recalculated-" + label)
    soffice = R12.soffice_bin()
    recalculated = R12.lo_convert(soffice, source, work, convert_to="xlsx")
    assert _b2_type_and_value(recalculated) == (cell_type, value), (
        "LibreOffice must produce the intended cell type and value")
    rc, payload = R12.invoke_checker(
        r12["scripts"], "values", "xlsx", recalculated, spec_path,
        r12["evidence"], "xlsx-recalculated-" + label)
    assert R12.checker_codes(rc, payload) == expected_codes, payload
    assert rc == (2 if expected_codes else 0), payload

    if label == "string-nan":
        # A valid string-valued formula satisfies the structural contract, but
        # cannot satisfy either the independent numeric or PDF display contract.
        rc, payload = R12.invoke_checker(
            r12["scripts"], "structure", "xlsx", recalculated, spec_path,
            r12["evidence"], "xlsx-string-nan-structure")
        assert R12.checker_codes(rc, payload) == set(), payload
        assert rc == 0, payload
        pdf = R12.lo_convert(soffice, source, work, convert_to="pdf")
        rc, payload = R12.invoke_checker(
            r12["scripts"], "pdf", "xlsx", pdf, spec_path,
            r12["evidence"], "xlsx-string-nan-pdf")
        assert R12.checker_codes(rc, payload) == {"PDF_TEXT_MISSING"}, payload
        assert rc == 2, payload


@pytest.fixture(scope="module")
def recalculated_xlsx_for_cache_controls(r12, generated):
    return R12.lo_convert(
        R12.soffice_bin(), _fixture(generated, "xlsx", "positive.xlsx"),
        os.path.join(r12["work"], "xlsx-cache-controls"), convert_to="xlsx")


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-Infinity"])
def test_xlsx_artificial_nonfinite_numeric_cache(
        r12, generated, tmp_path, recalculated_xlsx_for_cache_controls, value):
    """Synthetic cache controls; these bytes are deliberately not recalculated."""
    spec_path, _ = _spec(generated, "xlsx")
    modified = _xlsx_with_b2(
        recalculated_xlsx_for_cache_controls, tmp_path / "numeric-cache.xlsx",
        cell_type="n", value=value)
    assert _b2_type_and_value(modified) == ("n", value)
    rc, payload = R12.invoke_checker(
        r12["scripts"], "values", "xlsx", modified, spec_path,
        r12["evidence"], "xlsx-artificial-cache-" + value)
    assert R12.checker_codes(rc, payload) == {"XLSX_VALUE_MISMATCH"}, payload
    assert rc == 2, payload


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

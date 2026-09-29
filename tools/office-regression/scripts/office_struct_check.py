#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R-12 OOXML structure / recalculated-value / PDF-text checker (stdlib only).

This is a *test asset*, not a product tool. It inspects OOXML packages and
LibreOffice outputs with explicit reason codes so that negative-control tests can
assert the exact detection cause instead of "something failed".

Modes:
    structure   inspect the package itself (native constructs only)
    values      inspect a LibreOffice-recalculated XLSX export
    pdf         inspect a LibreOffice PDF export (text extraction via poppler)

Exit codes: 0 = healthy, 2 = violations found (reason codes printed), 1 = usage/IO error.
Python 3.9 compatible.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

SSML = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
WML = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
PML = "{http://schemas.openxmlformats.org/presentationml/2006/main}"
DML = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
CHART_NS = "{http://schemas.openxmlformats.org/drawingml/2006/chart}"
REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
OD_REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
CT_NS = "{http://schemas.openxmlformats.org/package/2006/content-types}"

BUILTIN_NUMFMT = {
    0: "General", 1: "0", 2: "0.00", 3: "#,##0", 4: "#,##0.00",
    9: "0%", 10: "0.00%", 11: "0.00E+00", 12: "# ?/?", 13: "# ??/??",
    14: "mm-dd-yy", 15: "d-mmm-yy", 16: "d-mmm", 17: "mmm-yy",
    18: "h:mm AM/PM", 19: "h:mm:ss AM/PM", 20: "h:mm", 21: "h:mm:ss",
    22: "m/d/yy h:mm", 37: "#,##0 ;(#,##0)", 38: "#,##0 ;[Red](#,##0)",
    39: "#,##0.00;(#,##0.00)", 40: "#,##0.00;[Red](#,##0.00)",
    49: "@",
}

CHART_SERIES_TAGS = ("barChart", "lineChart", "pieChart", "areaChart",
                     "scatterChart", "doughnutChart", "radarChart")


def compact(text):
    return re.sub(r"\s+", "", text or "")


def as_text(value):
    return "" if value is None else str(value)


class Result(object):
    def __init__(self, mode, kind, path):
        self.mode = mode
        self.kind = kind
        self.path = path
        self.reasons = []
        self.info = {}

    def reason(self, code, detail):
        self.reasons.append({"code": code, "detail": detail})

    def add_info(self, key, value):
        self.info[key] = value

    @property
    def ok(self):
        return not self.reasons

    def to_dict(self):
        return {
            "ok": self.ok,
            "mode": self.mode,
            "kind": self.kind,
            "file": self.path,
            "reason_codes": sorted({r["code"] for r in self.reasons}),
            "reasons": self.reasons,
            "info": self.info,
        }

    def emit(self, stream=None):
        payload = json.dumps(self.to_dict(), ensure_ascii=False, indent=2, sort_keys=True)
        print(payload, file=stream or sys.stdout)
        return 0 if self.ok else 2


# ---------------------------------------------------------------------------
# generic helpers
# ---------------------------------------------------------------------------

def load_zip(path):
    if not os.path.isfile(path):
        raise IOError("file not found: %s" % path)
    try:
        return zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise IOError("not a valid OOXML zip package: %s (%s)" % (path, exc))


def read_xml(archive, part):
    try:
        return ET.fromstring(archive.read(part))
    except KeyError:
        return None
    except ET.ParseError as exc:
        raise IOError("part %s is not well-formed XML: %s" % (part, exc))


def rels_map(archive, part):
    """Return {rel_id: target_part_path} for the rels part of the given part."""
    directory, name = os.path.split(part)
    rels_part = os.path.join(directory, "_rels", name + ".rels").replace("\\", "/")
    root = read_xml(archive, rels_part)
    result = {}
    if root is None:
        return result
    for rel in root.findall(REL_NS + "Relationship"):
        target = rel.get("Target", "")
        if target.startswith("../") or target.startswith("./"):
            target = os.path.normpath(os.path.join(directory, target)).replace("\\", "/")
        elif not target.startswith("/"):
            target = os.path.normpath(os.path.join(directory, target)).replace("\\", "/")
        else:
            target = target.lstrip("/")
        result[rel.get("Id")] = target
    return result


def norm_part(name):
    return os.path.normpath(name).replace("\\", "/").lstrip("/")


# ---------------------------------------------------------------------------
# XLSX
# ---------------------------------------------------------------------------

def xlsx_numfmt_codes(archive):
    root = read_xml(archive, "xl/styles.xml")
    codes = {}
    if root is None:
        return codes
    numfmts = root.find(SSML + "numFmts")
    if numfmts is not None:
        for node in numfmts.findall(SSML + "numFmt"):
            codes[int(node.get("numFmtId"))] = node.get("formatCode", "")
    cell_xfs = root.find(SSML + "cellXfs")
    style_codes = {}
    if cell_xfs is not None:
        for index, node in enumerate(cell_xfs.findall(SSML + "xf")):
            numfmt_id = int(node.get("numFmtId", "0"))
            style_codes[index] = codes.get(numfmt_id, BUILTIN_NUMFMT.get(numfmt_id, ""))
    return style_codes


def classify_numfmt(code):
    lowered = (code or "").lower()
    if "%" in lowered:
        return "percent"
    if "unit" in lowered:
        return "units"
    if reduced_general(code):
        return "general"
    return "number"


def reduced_general(code):
    return (code or "").strip().lower() in ("", "general", "@")


def xlsx_sheet_parts(archive):
    workbook = read_xml(archive, "xl/workbook.xml")
    if workbook is None:
        raise IOError("xl/workbook.xml missing")
    rels = rels_map(archive, "xl/workbook.xml")
    sheets = []
    sheets_node = workbook.find(SSML + "sheets")
    if sheets_node is not None:
        for sheet in sheets_node.findall(SSML + "sheet"):
            rel_id = sheet.get(OD_REL_NS + "id") or sheet.get("id")
            target = rels.get(rel_id, "")
            sheets.append({"name": sheet.get("name"), "part": norm_part(target)})
    return sheets


def xlsx_cells(archive, part):
    root = read_xml(archive, part)
    cells = {}
    if root is None:
        return cells
    sheet_data = root.find(SSML + "sheetData")
    if sheet_data is None:
        return cells
    for row in sheet_data.findall(SSML + "row"):
        for cell in row.findall(SSML + "c"):
            cells[cell.get("r")] = cell
    return cells


def xlsx_shared_strings(archive):
    root = read_xml(archive, "xl/sharedStrings.xml")
    values = []
    if root is None:
        return values
    for item in root.findall(SSML + "si"):
        values.append("".join(node.text or "" for node in item.iter(SSML + "t")))
    return values


def cell_text_value(archive, cell, shared):
    cell_type = cell.get("t", "n")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(SSML + "t"))
    node = cell.find(SSML + "v")
    raw = node.text if node is not None else None
    if cell_type == "s" and raw is not None:
        index = int(raw)
        return shared[index] if 0 <= index < len(shared) else ""
    return raw


def check_xlsx_structure(result, archive, spec):
    sheets = xlsx_sheet_parts(archive)
    names = [s["name"] for s in sheets]
    result.add_info("sheets", names)
    expected_order = spec.get("sheets_in_order") or []
    if expected_order and names != expected_order:
        result.reason("XLSX_SHEET_ORDER_MISMATCH",
                      "sheet order %s != expected %s" % (names, expected_order))
    sheet_parts = {s["name"]: s["part"] for s in sheets}
    style_codes = xlsx_numfmt_codes(archive)
    cell_cache = {}
    for expected in spec.get("cells", []):
        sheet_name = expected["sheet"]
        part = sheet_parts.get(sheet_name)
        if part is None:
            result.reason("XLSX_SHEET_MISSING", "sheet %s missing (have %s)" % (sheet_name, names))
            continue
        if sheet_name not in cell_cache:
            cell_cache[sheet_name] = xlsx_cells(archive, part)
        cell = cell_cache[sheet_name].get(expected["ref"])
        if cell is None:
            result.reason("XLSX_CELL_MISSING", "cell %s!%s not found" % (sheet_name, expected["ref"]))
            continue
        formula_node = cell.find(SSML + "f")
        if expected.get("expect_formula"):
            if formula_node is None or not as_text(formula_node.text).strip():
                result.reason("XLSX_MISSING_FORMULA",
                              "cell %s!%s has no <f> formula element" % (sheet_name, expected["ref"]))
            elif expected.get("cross_sheet_ref") and expected["cross_sheet_ref"] not in as_text(formula_node.text):
                result.reason(
                    "XLSX_FORMULA_MISSING_CROSS_SHEET_REF",
                    "cell %s!%s formula %r does not reference %r"
                    % (sheet_name, expected["ref"], as_text(formula_node.text), expected["cross_sheet_ref"]))
        if expected.get("numfmt"):
            style_index = int(cell.get("s", "0"))
            code = style_codes.get(style_index, "")
            actual = classify_numfmt(code)
            if actual != expected["numfmt"]:
                result.reason(
                    "XLSX_MISSING_NUMFMT",
                    "cell %s!%s number format %r classified as %r, expected %r"
                    % (sheet_name, expected["ref"], code, actual, expected["numfmt"]))
    return result


def check_xlsx_values(result, archive, spec):
    sheets = xlsx_sheet_parts(archive)
    sheet_parts = {s["name"]: s["part"] for s in sheets}
    shared = xlsx_shared_strings(archive)
    cell_cache = {}
    for expected in spec.get("cells", []):
        sheet_name = expected["sheet"]
        part = sheet_parts.get(sheet_name)
        if part is None:
            result.reason("XLSX_SHEET_MISSING", "sheet %s missing (have %s)" % (sheet_name, list(sheet_parts)))
            continue
        if sheet_name not in cell_cache:
            cell_cache[sheet_name] = xlsx_cells(archive, part)
        cell = cell_cache[sheet_name].get(expected["ref"])
        if cell is None:
            result.reason("XLSX_CELL_MISSING", "cell %s!%s not found in recalculated export"
                          % (sheet_name, expected["ref"]))
            continue
        if cell.get("t") == "e":
            raw = cell_text_value(archive, cell, shared)
            result.reason("XLSX_FORMULA_ERROR",
                          "cell %s!%s recalculated to spreadsheet error %r"
                          % (sheet_name, expected["ref"], raw))
            continue
        if "expect_value" not in expected:
            continue
        raw = cell_text_value(archive, cell, shared)
        if raw is None or raw == "":
            result.reason("XLSX_VALUE_MISMATCH",
                          "cell %s!%s has no recalculated <v> value" % (sheet_name, expected["ref"]))
            continue
        try:
            actual = float(raw)
        except ValueError:
            result.reason("XLSX_VALUE_MISMATCH",
                          "cell %s!%s recalculated value %r is not numeric" % (sheet_name, expected["ref"], raw))
            continue
        expected_value = float(expected["expect_value"])
        tolerance = float(expected.get("tolerance", 1e-9))
        if abs(actual - expected_value) > tolerance:
            result.reason("XLSX_VALUE_MISMATCH",
                          "cell %s!%s recalculated to %s, independently expected %s"
                          % (sheet_name, expected["ref"], actual, expected_value))
    return result


# ---------------------------------------------------------------------------
# DOCX
# ---------------------------------------------------------------------------

def docx_paragraphs(root):
    body = root.find(WML + "body")
    if body is None:
        return [], []
    headings = []
    tables = []
    for child in body:
        if child.tag == WML + "p":
            style = None
            props = child.find(WML + "pPr")
            if props is not None:
                style_node = props.find(WML + "pStyle")
                if style_node is not None:
                    style = style_node.get(WML + "val")
            text = "".join(node.text or "" for node in child.iter(WML + "t"))
            headings.append({"style": style, "text": text, "props": props})
        elif child.tag == WML + "tbl":
            rows = []
            for row in child.findall(WML + "tr"):
                cells = []
                for cell in row.findall(WML + "tc"):
                    cells.append("".join(node.text or "" for node in cell.iter(WML + "t")))
                rows.append(cells)
            tables.append(rows)
    return headings, tables


def docx_style_table(archive):
    """{styleId: {"based_on": ..., "outline_lvl": ...}} from word/styles.xml.

    `outline_lvl` is the raw ``w:outlineLvl`` value of the style definition (or
    None). Paragraph heading detection must follow the basedOn chain, exactly
    like LibreOffice: a style name alone (without any effective outline level)
    is not a heading.
    """
    table = {}
    try:
        root = read_xml(archive, "word/styles.xml")
    except IOError:
        # An unreadable styles part contributes no levels; the pStyle links
        # then count as dangling (non-headings).
        return table
    if root is None:
        return table
    for node in root.findall(WML + "style"):
        style_id = node.get(WML + "styleId")
        if not style_id:
            continue
        based_node = node.find(WML + "basedOn")
        props = node.find(WML + "pPr")
        level_node = props.find(WML + "outlineLvl") if props is not None else None
        table[style_id] = {
            "based_on": based_node.get(WML + "val") if based_node is not None else None,
            "outline_lvl": level_node.get(WML + "val") if level_node is not None else None,
        }
    return table


def _outline_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def docx_effective_outline_level(paragraph, style_table):
    """Effective ``w:outlineLvl`` for a paragraph, or None when it has none.

    A paragraph-level ``<w:pPr><w:outlineLvl>`` wins; otherwise the first
    outlineLvl found while walking the pStyle's basedOn chain applies. A
    dangling style (or a dangling basedOn link) contributes no level, matching
    LibreOffice, which only treats paragraphs with an effective outline level
    of 0-8 as headings.
    """
    props = paragraph.get("props")
    if props is not None:
        direct = props.find(WML + "outlineLvl")
        if direct is not None:
            return _outline_int(direct.get(WML + "val"))
    style_id = paragraph.get("style")
    seen = set()
    while style_id and style_id not in seen:
        seen.add(style_id)
        style = style_table.get(style_id)
        if style is None:
            return None
        level = style["outline_lvl"]
        if level is not None:
            return _outline_int(level)
        style_id = style["based_on"]
    return None


def check_docx_structure(result, archive, spec):
    root = read_xml(archive, "word/document.xml")
    if root is None:
        result.reason("DOCX_DOCUMENT_MISSING", "word/document.xml missing")
        return result
    paragraphs, tables = docx_paragraphs(root)
    style_table = docx_style_table(archive)
    for paragraph in paragraphs:
        outline = docx_effective_outline_level(paragraph, style_table)
        paragraph["outline_level"] = outline
        # Only an effective outline level of 0-8 makes a heading (9 means
        # "body text"); a style *name* without one is not a heading.
        paragraph["heading_level"] = (
            outline + 1 if outline is not None and 0 <= outline <= 8 else None)
    result.add_info("paragraph_styles", [p["style"] for p in paragraphs if p["style"]])
    result.add_info("heading_levels",
                    [p["heading_level"] for p in paragraphs if p["heading_level"] is not None])
    # Expected style name -> the heading level a healthy document must produce
    # for it (through the style's effective outline level).
    expected_levels = {"Heading1": 1, "Heading2": 2, "Heading3": 3, "Heading4": 4,
                       "Heading5": 5, "Heading6": 6}
    for expected in spec.get("headings", []):
        style = expected["style"]
        text = expected["text"]
        expected_level = expected_levels.get(style)
        match = [p for p in paragraphs
                 if p["style"] == style and compact(p["text"]) == compact(text)
                 and p["heading_level"] is not None
                 and (expected_level is None or p["heading_level"] == expected_level)]
        if not match:
            same_text = [p for p in paragraphs if compact(p["text"]) == compact(text)]
            detail = "no paragraph with native style %s and text %r" % (style, text)
            styled = [p for p in same_text if p["style"] == style]
            if styled:
                actual = styled[0]["heading_level"]
                if actual is None:
                    detail += (" (style name %s is present but the paragraph has no effective "
                               "outline level: w:outlineLvl missing from the paragraph and its "
                               "basedOn chain)" % style)
                else:
                    detail += (" (style name %s is present but its effective outline level yields "
                               "heading %s, not %s)" % (style, actual, expected_level))
            elif same_text:
                detail += " (text exists but carries style=%r - no native OOXML heading)" % same_text[0]["style"]
            result.reason("DOCX_HEADING_STYLE_MISSING", detail)
    seen_levels = set()
    for paragraph in paragraphs:
        level = paragraph["heading_level"]
        if level is None:
            continue
        if level > 1 and (level - 1) not in seen_levels:
            result.reason("DOCX_HEADING_HIERARCHY_BROKEN",
                          "heading %r has level %d before any level %d heading" % (paragraph["text"], level, level - 1))
            break
        seen_levels.add(level)
    table_spec = spec.get("table")
    if table_spec:
        expected_cells = [compact(c) for c in table_spec.get("cells", [])]
        found = False
        for rows in tables:
            flat = [compact(c) for row in rows for c in row]
            if (len(rows) == table_spec.get("rows") and rows and all(len(r) == table_spec.get("cols") for r in rows)
                    and all(c in flat for c in expected_cells)):
                found = True
                break
        if not found:
            shape = "x".join([str(len(tables)), str([len(r) for r in tables[0]] if tables else [])])
            result.reason("DOCX_TABLE_MISSING",
                          "no %sx%s table carrying cells %s (found %s tables, shape %s)"
                          % (table_spec.get("rows"), table_spec.get("cols"), expected_cells, len(tables), shape))
    return result


# ---------------------------------------------------------------------------
# PPTX
# ---------------------------------------------------------------------------

def pptx_slide_parts(archive):
    presentation = read_xml(archive, "ppt/presentation.xml")
    if presentation is None:
        raise IOError("ppt/presentation.xml missing")
    rels = rels_map(archive, "ppt/presentation.xml")
    slides = []
    slide_list = presentation.find(PML + "sldIdLst")
    if slide_list is not None:
        for node in slide_list.findall(PML + "sldId"):
            rel_id = node.get(OD_REL_NS + "id")
            slides.append(norm_part(rels.get(rel_id, "")))
    return slides


def pptx_chart_external_data_targets(archive, parts, chart_refs):
    """Resolve a slide's chart parts to their embedded-workbook targets.

    A native chart points at its workbook through `<c:externalData r:id="...">`
    plus the chart's own relationships part. Returns (targets, determined):

    * determined=True: a readable chart part produced a verdict for this slide.
      `targets` holds the part names reached, with ``None`` marking a
      relationship that did not resolve (the caller reports it as missing). A
      readable chart without any `<c:externalData>` yields determined=True with
      no target: "no workbook link" is the verdict, not a reason to fall back
      to the package namelist (an orphaned xlsx must not mask the missing link).
    * determined=False: no inspectable chart chain on the slide (no chart
      reference, chart part absent or unreadable); the caller falls back to
      scanning ``ppt/embeddings/*.xlsx`` via the package namelist.
    """
    targets = []
    determined = False
    for chart_part in chart_refs:
        if chart_part not in parts:
            continue
        try:
            chart_root = read_xml(archive, chart_part)
        except IOError:
            continue
        if chart_root is None:
            continue
        determined = True
        external = chart_root.find(CHART_NS + "externalData")
        if external is None:
            continue
        rel_id = external.get(OD_REL_NS + "id")
        try:
            chart_rels = rels_map(archive, chart_part)
        except IOError:
            chart_rels = {}
        target = chart_rels.get(rel_id)
        targets.append(norm_part(target) if target else None)
    return targets, determined


def content_type_declared(archive, part):
    """True when [Content_Types].xml declares `part` via Override or Default.

    A part that no declaration covers cannot be consumed from the package (the
    missing Override/Default is itself the defect), so an absent or empty
    content-types part counts as "not declared".
    """
    root = read_xml(archive, "[Content_Types].xml")
    if root is None:
        return False
    overrides = set()
    default_extensions = set()
    for node in root.findall(CT_NS + "Override"):
        overrides.add((node.get("PartName") or "").lstrip("/"))
    for node in root.findall(CT_NS + "Default"):
        default_extensions.add((node.get("Extension") or "").lower())
    if part in overrides:
        return True
    base = os.path.basename(part)
    extension = base.rsplit(".", 1)[-1].lower() if "." in base else ""
    return bool(extension) and extension in default_extensions


def check_pptx_structure(result, archive, spec):
    slides = pptx_slide_parts(archive)
    result.add_info("slide_count", len(slides))
    parts = set(archive.namelist())
    chart_parts = sorted(p for p in parts
                         if p.startswith("ppt/charts/") and p.endswith(".xml") and "/_rels/" not in p)
    embedding_parts = sorted(p for p in parts
                             if p.startswith("ppt/embeddings/") and p.endswith(".xlsx"))
    result.add_info("chart_parts", chart_parts)
    result.add_info("embedded_workbooks", embedding_parts)
    if spec.get("pdf_page_count") is not None:
        result.add_info("expected_pdf_pages", spec["pdf_page_count"])

    for expected in spec.get("slides", []):
        index = expected["index"]
        if index > len(slides):
            result.reason("PPTX_SLIDE_MISSING", "slide %d missing (have %d slides)" % (index, len(slides)))
            continue
        part = slides[index - 1]
        root = read_xml(archive, part)
        if root is None:
            result.reason("PPTX_SLIDE_MISSING", "slide part %s missing" % part)
            continue
        texts = [(node.text or "") for node in root.iter(DML + "t")]
        for required in expected.get("texts", []):
            if not any(compact(t) == compact(required) for t in texts):
                result.reason("PPTX_TEXT_MISSING",
                              "slide %d does not carry native text %r (found %s)" % (index, required, texts))
        shape_count = len(list(root.iter(PML + "sp")))
        minimum = expected.get("min_shapes")
        if minimum is not None and shape_count < minimum:
            result.reason("PPTX_SHAPE_MISSING",
                          "slide %d has %d shapes, expected at least %d" % (index, shape_count, minimum))
        chart_refs = []
        if expected.get("require_chart") or expected.get("require_embedded_workbook"):
            # Both checks walk the slide's chart references: collect them once.
            slide_rels = rels_map(archive, part)
            for node in root.iter(CHART_NS + "chart"):
                rel_id = node.get(OD_REL_NS + "id")
                target = slide_rels.get(rel_id)
                if target:
                    chart_refs.append(norm_part(target))
        if expected.get("require_chart"):
            pictures = list(root.iter(PML + "pic"))
            if not chart_refs:
                if pictures:
                    result.reason("PPTX_CHART_FLATTENED_IMAGE",
                                  "slide %d expects a native chart but only a flattened image (p:pic) is present"
                                  % index)
                else:
                    result.reason("PPTX_CHART_PART_MISSING",
                                  "slide %d expects a native chart but no chart reference exists" % index)
            else:
                for target in chart_refs:
                    if target not in parts:
                        result.reason("PPTX_CHART_PART_MISSING",
                                      "slide %d references chart part %s which is not in the package" % (index, target))
                        continue
                    chart_root = read_xml(archive, target)
                    series_tags = [tag for tag in CHART_SERIES_TAGS if chart_root.find(".//" + CHART_NS + tag) is not None]
                    series = chart_root.findall(".//" + CHART_NS + "ser")
                    if not series_tags or not series:
                        result.reason("PPTX_CHART_PART_INVALID",
                                      "chart part %s has no supported plot type or series" % target)
        if expected.get("require_embedded_workbook"):
            # Hoisted out of the require_chart branch: this check must be
            # reachable for specs that require the workbook without a chart.
            targets, determined = pptx_chart_external_data_targets(archive, parts, chart_refs)
            if not determined:
                # No inspectable chart chain names a workbook on this slide
                # (e.g. the chart was flattened): fall back to the package-wide
                # namelist scan.
                targets = list(embedding_parts)
                if not targets:
                    result.reason("PPTX_EMBEDDED_WORKBOOK_MISSING",
                                  "slide %d requires an embedded workbook under ppt/embeddings/*.xlsx" % index)
            elif not targets:
                # A readable chart chain exists but none of its charts carries
                # a <c:externalData> workbook link: the workbook is missing by
                # determination, so namelist scanning must not mask it.
                result.reason("PPTX_EMBEDDED_WORKBOOK_MISSING",
                              "slide %d chart has no <c:externalData> link to an embedded workbook" % index)
            for target in dict.fromkeys(targets):
                if not target or target not in parts:
                    if target:
                        detail = ("slide %d chart <c:externalData> points at %s which is not in the package"
                                  % (index, target))
                    else:
                        detail = ("slide %d chart <c:externalData> does not resolve to an embedded workbook part"
                                  % index)
                    result.reason("PPTX_EMBEDDED_WORKBOOK_MISSING", detail)
                    continue
                try:
                    inner = zipfile.ZipFile(_bytes_reader(archive, target))
                    if "xl/workbook.xml" not in inner.namelist():
                        result.reason("PPTX_EMBEDDED_WORKBOOK_INVALID",
                                      "embedded workbook %s has no xl/workbook.xml" % target)
                        continue
                except zipfile.BadZipFile:
                    result.reason("PPTX_EMBEDDED_WORKBOOK_INVALID",
                                  "embedded workbook %s is not a valid xlsx zip" % target)
                    continue
                if not content_type_declared(archive, target):
                    result.reason("PPTX_EMBEDDED_WORKBOOK_CONTENT_TYPE_MISSING",
                                  "embedded workbook %s has no [Content_Types].xml Override/Default declaration"
                                  % target)
    return result


class _BytesReader(object):
    def __init__(self, data):
        import io
        self._buffer = io.BytesIO(data)

    def read(self, size=-1):
        return self._buffer.read(size)

    def seek(self, offset, whence=0):
        return self._buffer.seek(offset, whence)

    def tell(self):
        return self._buffer.tell()

    def seekable(self):
        return True

    def close(self):
        self._buffer.close()


def _bytes_reader(archive, part):
    return _BytesReader(archive.read(part))


# ---------------------------------------------------------------------------
# PDF text
# ---------------------------------------------------------------------------

def pdf_text(path):
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return None
    tool = shutil.which("pdftotext")
    if tool is None:
        raise RuntimeError("pdftotext (poppler-utils) not available")
    proc = subprocess.run([tool, "-layout", path, "-"], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError("pdftotext failed for %s: %s" % (path, proc.stderr.decode("utf-8", "replace").strip()))
    return proc.stdout.decode("utf-8", "replace")


def pdf_page_count(path):
    tool = shutil.which("pdfinfo")
    if tool is None:
        return None
    proc = subprocess.run([tool, path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if proc.returncode != 0:
        return None
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        if line.lower().startswith("pages:"):
            try:
                return int(line.split(":", 1)[1].strip())
            except ValueError:
                return None
    return None


def check_pdf(result, spec, kind):
    text = pdf_text(result.path)
    if text is None:
        result.reason("PDF_MISSING", "PDF export %s missing or empty" % result.path)
        return result
    normalized = compact(text)
    required = list(spec.get("pdf_text_required", []))
    if kind == "xlsx":
        required += [cell.get("pdf_display") for cell in spec.get("cells", []) if cell.get("pdf_display")]
    missing = [item for item in required if compact(item) not in normalized]
    for item in missing:
        result.reason("PDF_TEXT_MISSING", "PDF text does not contain expected display value %r" % item)
    expected_pages = spec.get("pdf_page_count")
    if expected_pages is not None:
        actual = pdf_page_count(result.path)
        result.add_info("pdf_pages", actual)
        if actual != expected_pages:
            result.reason("PDF_PAGE_COUNT_MISMATCH",
                          "PDF has %s pages, expected %s" % (actual, expected_pages))
    result.add_info("pdf_chars", len(text))
    return result


# ---------------------------------------------------------------------------
# entry point
# ---------------------------------------------------------------------------

def run(mode, kind, path, spec_path):
    with open(spec_path, encoding="utf-8") as handle:
        spec = json.load(handle)
    kind = kind or spec.get("kind")
    result = Result(mode, kind, path)
    if mode == "pdf":
        return check_pdf(result, spec, kind)
    archive = load_zip(path)
    try:
        if mode == "structure":
            if kind == "xlsx":
                return check_xlsx_structure(result, archive, spec)
            if kind == "docx":
                return check_docx_structure(result, archive, spec)
            if kind == "pptx":
                return check_pptx_structure(result, archive, spec)
            raise ValueError("unsupported kind for structure mode: %s" % kind)
        if mode == "values":
            if kind != "xlsx":
                raise ValueError("values mode only supports xlsx")
            return check_xlsx_values(result, archive, spec)
        raise ValueError("unsupported mode: %s" % mode)
    finally:
        archive.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="R-12 OOXML regression checker")
    parser.add_argument("mode", choices=["structure", "values", "pdf"])
    parser.add_argument("--kind", choices=["xlsx", "docx", "pptx"], default=None)
    parser.add_argument("--file", required=True)
    parser.add_argument("--spec", required=True)
    args = parser.parse_args(argv)
    try:
        result = run(args.mode, args.kind, os.path.abspath(args.file), os.path.abspath(args.spec))
    except (IOError, OSError, ValueError, RuntimeError) as exc:
        print(json.dumps({"ok": False, "mode": args.mode, "kind": args.kind,
                          "file": args.file, "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1
    return result.emit()


if __name__ == "__main__":
    sys.exit(main())

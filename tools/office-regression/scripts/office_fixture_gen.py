#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R-12 offline regression fixture generator (offline, stdlib only).

Builds minimal-but-valid OOXML fixtures by hand (zipfile + xml + zlib), so the
fixture set has no third-party runtime dependency and is byte-deterministic.

Generated artefacts (under --out):
    xlsx/positive.xlsx, xlsx/negative-values.xlsx, xlsx/negative-numfmt.xlsx,
    xlsx/negative-missing-formula.xlsx
    docx/positive.docx, docx/negative-appearance.docx, docx/negative-hierarchy.docx,
    docx/negative-no-outline.docx, docx/positive-outline-inherited.docx
    pptx/positive.pptx, pptx/negative-flattened.pptx, pptx/negative-noembed.pptx,
    pptx/negative-embed-unlinked.pptx, pptx/negative-embed-notzip.pptx,
    pptx/negative-embed-noworkbook.pptx, pptx/negative-embed-noctoverride.pptx,
    pptx/negative-embed-noexternaldata.pptx
    specs/xlsx.json, specs/docx.json, specs/pptx.json, specs/pptx-embed-only.json
    specs/README.txt (human note about expectations)

Every archive is written with fixed timestamps / ordering / permissions so that
two runs in the same interpreter produce identical bytes (verified by tests).

Python 3.9 compatible.
"""

import argparse
import io
import json
import os
import struct
import sys
import zlib
import zipfile

FIXED_DATE = (1980, 1, 1, 0, 0, 0)
FIXED_EXTERNAL_ATTR = 0o644 << 16

CT = "http://schemas.openxmlformats.org/package/2006/content-types"
PR = "http://schemas.openxmlformats.org/package/2006/relationships"
OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
SSML = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
WML = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
DML = "http://schemas.openxmlformats.org/drawingml/2006/main"
CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart"


def xml_header():
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'


def write_package(path, parts):
    """Write parts dict {name: bytes} as a deterministic zip archive."""
    names = sorted(parts.keys())
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = FIXED_EXTERNAL_ATTR
            info.create_system = 0
            archive.writestr(info, parts[name])
    return path


# --------------------------------------------------------------------------
# XLSX
# --------------------------------------------------------------------------

XLSX_CT = (
    xml_header()
    + ('<Types xmlns="%s">' % CT)
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    + '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    + "</Types>"
).encode("utf-8")

XLSX_ROOT_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/officeDocument" Target="xl/workbook.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")

XLSX_WORKBOOK = (
    xml_header()
    + ('<workbook xmlns="%s" xmlns:r="%s">' % (SSML, OD))
    + "<sheets>"
    + '<sheet name="Summary" sheetId="1" r:id="rId1"/>'
    + '<sheet name="Data" sheetId="2" r:id="rId2"/>'
    + "</sheets>"
    + '<calcPr calcId="191029" fullCalcOnLoad="1"/>'
    + "</workbook>"
).encode("utf-8")

XLSX_WORKBOOK_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/worksheet" Target="worksheets/sheet1.xml"/>' % OD
    + '<Relationship Id="rId2" Type="%s/worksheet" Target="worksheets/sheet2.xml"/>' % OD
    + '<Relationship Id="rId3" Type="%s/styles" Target="styles.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")

# numFmtId 4 (builtin) = #,##0.00 ; 164 = "#,##0.00 \" units\"" ; 165 = "0.00%"
XLSX_STYLES = (
    xml_header()
    + ('<styleSheet xmlns="%s">' % SSML)
    + '<numFmts count="2">'
    + '<numFmt numFmtId="164" formatCode="#,##0.00&quot; units&quot;"/>'
    + '<numFmt numFmtId="165" formatCode="0.00%"/>'
    + "</numFmts>"
    + '<fonts count="1"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts>'
    + '<fills count="2"><fill><patternFill patternType="none"/></fill>'
    + '<fill><patternFill patternType="gray125"/></fill></fills>'
    + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    + '<cellXfs count="4">'
    + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
    + '<xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
    + '<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
    + '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
    + "</cellXfs>"
    + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
    + "</styleSheet>"
).encode("utf-8")


def _xlsx_sheet(rows, cols_definition):
    out = [xml_header(), '<worksheet xmlns="%s">' % SSML]
    if cols_definition:
        out.append("<cols>%s</cols>" % cols_definition)
    out.append("<sheetData>")
    for row in rows:
        out.append('<row r="%d">' % row["r"])
        for cell in row["cells"]:
            style = ' s="%d"' % cell["s"] if "s" in cell else ""
            if "formula" in cell:
                out.append('<c r="%s"%s><f>%s</f></c>' % (cell["ref"], style, cell["formula"]))
            elif "inline" in cell:
                out.append('<c r="%s"%s t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'
                           % (cell["ref"], style, cell["inline"]))
            else:
                out.append('<c r="%s"%s><v>%s</v></c>' % (cell["ref"], style, cell["valueText"]))
        out.append("</row>")
    out.append("</sheetData></worksheet>")
    return "".join(out).encode("utf-8")


COLS_WIDE = '<col min="1" max="1" width="18" customWidth="1"/><col min="2" max="2" width="20" customWidth="1"/>'

SUMMARY_ROWS = {
    "header": {"r": 1, "cells": [
        {"ref": "A1", "inline": "Metric"}, {"ref": "B1", "inline": "Value"}]},
    "total": {"r": 2, "cells": [
        {"ref": "A2", "inline": "Total"}, {"ref": "B2", "s": 1}]},
    "growth": {"r": 3, "cells": [
        {"ref": "A3", "inline": "Growth"}, {"ref": "B3", "s": 2}]},
    "units": {"r": 4, "cells": [
        {"ref": "A4", "inline": "Units"}, {"ref": "B4", "s": 3}]},
}

DATA_ROWS = [
    {"r": 1, "cells": [{"ref": "A1", "inline": "Item"}, {"ref": "B1", "inline": "Amount"}]},
    {"r": 2, "cells": [{"ref": "A2", "inline": "Alpha"}, {"ref": "B2", "valueText": "100"}]},
    {"r": 3, "cells": [{"ref": "A3", "inline": "Beta"}, {"ref": "B3", "valueText": "125.5"}]},
    {"r": 4, "cells": [{"ref": "A4", "inline": "Gamma"}, {"ref": "B4", "valueText": "49.5"}]},
]


def _summary_sheet(formula_total, formula_growth, formula_units, growth_style=2, units_style=3,
                   total_style=1, total_value=None):
    rows = []
    for key in ("header", "total", "growth", "units"):
        row = {"r": SUMMARY_ROWS[key]["r"], "cells": [dict(c) for c in SUMMARY_ROWS[key]["cells"]]}
        if key == "total":
            row["cells"][1]["s"] = total_style
            if formula_total is not None:
                row["cells"][1]["formula"] = formula_total
            else:
                row["cells"][1]["valueText"] = total_value
        if key == "growth":
            row["cells"][1]["s"] = growth_style
            row["cells"][1]["formula"] = formula_growth
        if key == "units":
            row["cells"][1]["s"] = units_style
            row["cells"][1]["formula"] = formula_units
        rows.append(row)
    return _xlsx_sheet(rows, COLS_WIDE)


def build_xlsx_positive(parts):
    parts["xl/worksheets/sheet1.xml"] = _summary_sheet(
        "Data!B2*2", "(Data!B3-Data!B2)/Data!B2", "SUM(Data!B2:B4)")
    parts["xl/worksheets/sheet2.xml"] = _xlsx_sheet([dict(r) for r in DATA_ROWS], COLS_WIDE)


def build_xlsx_negative_values(parts):
    # Wrong cross-sheet target (empty cell -> 0) and a division by zero.
    parts["xl/worksheets/sheet1.xml"] = _summary_sheet(
        "Data!B99*2", "1/0", "SUM(Data!B2:B4)")
    parts["xl/worksheets/sheet2.xml"] = _xlsx_sheet([dict(r) for r in DATA_ROWS], COLS_WIDE)


def build_xlsx_negative_numfmt(parts):
    # Correct formulas, but the growth cell carries a plain decimal format.
    parts["xl/worksheets/sheet1.xml"] = _summary_sheet(
        "Data!B2*2", "(Data!B3-Data!B2)/Data!B2", "SUM(Data!B2:B4)", growth_style=1)
    parts["xl/worksheets/sheet2.xml"] = _xlsx_sheet([dict(r) for r in DATA_ROWS], COLS_WIDE)


def build_xlsx_negative_missing_formula(parts):
    # B2 is a hard-coded literal instead of a formula.
    parts["xl/worksheets/sheet1.xml"] = _summary_sheet(
        None, "(Data!B3-Data!B2)/Data!B2", "SUM(Data!B2:B4)", total_value="200")
    parts["xl/worksheets/sheet2.xml"] = _xlsx_sheet([dict(r) for r in DATA_ROWS], COLS_WIDE)


def make_xlsx(builder):
    parts = {
        "[Content_Types].xml": XLSX_CT,
        "_rels/.rels": XLSX_ROOT_RELS,
        "xl/workbook.xml": XLSX_WORKBOOK,
        "xl/_rels/workbook.xml.rels": XLSX_WORKBOOK_RELS,
        "xl/styles.xml": XLSX_STYLES,
    }
    builder(parts)
    return parts


# --------------------------------------------------------------------------
# DOCX
# --------------------------------------------------------------------------

DOCX_CT = (
    xml_header()
    + ('<Types xmlns="%s">' % CT)
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    + '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
    + "</Types>"
).encode("utf-8")

DOCX_ROOT_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/officeDocument" Target="word/document.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")

DOCX_DOC_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/styles" Target="styles.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")


def _docx_style(style_id, name, base, size_half_points, bold, heading_level=None):
    out = ['<w:style w:type="paragraph" w:styleId="%s">' % style_id]
    out.append('<w:name w:val="%s"/>' % name)
    if base:
        out.append('<w:basedOn w:val="%s"/>' % base)
    if heading_level is not None:
        out.append('<w:pPr><w:outlineLvl w:val="%d"/></w:pPr>' % heading_level)
    out.append('<w:rPr>%s<w:sz w:val="%d"/><w:szCs w:val="%d"/>'
               '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Noto Sans CJK SC"/></w:rPr>'
               % ("<w:b/>" if bold else "", size_half_points, size_half_points))
    out.append("</w:style>")
    return "".join(out)


def _docx_styles_xml(heading1_level=0, heading1_base="Normal",
                     heading2_level=1, heading2_base="Normal", extra_styles=""):
    """word/styles.xml; heading levels can be dropped/inherited per fixture."""
    return (
        xml_header()
        + ('<w:styles xmlns:w="%s">' % WML)
        + '<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/><w:szCs w:val="22"/>'
        + '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Noto Sans CJK SC"/></w:rPr></w:rPrDefault></w:docDefaults>'
        + _docx_style("Normal", "Normal", None, 22, False)
        + extra_styles
        + _docx_style("Title", "Title", "Normal", 56, True)
        + _docx_style("Heading1", "heading 1", heading1_base, 32, True, heading_level=heading1_level)
        + _docx_style("Heading2", "heading 2", heading2_base, 26, True, heading_level=heading2_level)
        + '<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/>'
        + '<w:tblPr><w:tblBorders>'
        + '<w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        + '<w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        + '<w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        + '<w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/>'
        + "</w:tblBorders></w:tblPr></w:style>"
        + "</w:styles>"
    )


DOCX_STYLES = _docx_styles_xml().encode("utf-8")


def _esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def _docx_para(text, style=None, bold=False, size=None):
    props = ""
    if style:
        props += '<w:pStyle w:val="%s"/>' % style
    run_props = ""
    if bold:
        run_props += "<w:b/>"
    if size is not None:
        run_props += '<w:sz w:val="%d"/>' % size
    return ('<w:p><w:pPr>%s</w:pPr><w:r>%s<w:t xml:space="preserve">%s</w:t></w:r></w:p>'
            % (props, ("<w:rPr>%s</w:rPr>" % run_props) if run_props else "", _esc(text)))


def _docx_table(rows):
    out = ['<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>'
           '<w:tblW w:w="0" w:type="auto"/></w:tblPr>']
    out.append("<w:tblGrid><w:gridCol w:w=\"2600\"/><w:gridCol w:w=\"2600\"/></w:tblGrid>")
    for row in rows:
        out.append("<w:tr>")
        for cell in row:
            out.append('<w:tc><w:tcPr><w:tcW w:w="2600" w:type="dxa"/></w:tcPr>%s</w:tc>'
                       % _docx_para(cell))
        out.append("</w:tr>")
    out.append("</w:tbl>")
    return "".join(out)


SECT_PR = ('<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
           '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>'
           "</w:sectPr>")


def _docx_document(body):
    return (
        xml_header()
        + ('<w:document xmlns:w="%s" xmlns:r="%s">' % (WML, OD))
        + "<w:body>" + body + SECT_PR + "</w:body></w:document>"
    ).encode("utf-8")


DOCX_TABLE_ROWS = [["项目", "数值"], ["收入", "1234"]]


def _positive_docx_body():
    return (_docx_para("季度报告", style="Title")
            + _docx_para("收入概览", style="Heading1")
            + _docx_para("国内业务", style="Heading2")
            + _docx_para("这是正文段落，用于验证 PDF 文本提取。")
            + _docx_table(DOCX_TABLE_ROWS)
            + _docx_para(""))


def build_docx_positive(parts):
    parts["word/document.xml"] = _docx_document(_positive_docx_body())


def build_docx_negative_no_outline(parts):
    """Heading style *names* kept, but no effective outline level anywhere.

    The Heading1/Heading2 style definitions still exist (so the w:pStyle
    references resolve) and are still named "heading 1"/"heading 2", but
    neither they nor their basedOn chain (Normal) grant a w:outlineLvl:
    LibreOffice renders those paragraphs as plain body text, so a checker that
    only compares style names must not accept them.
    """
    parts["word/document.xml"] = _docx_document(_positive_docx_body())
    parts["word/styles.xml"] = _docx_styles_xml(
        heading1_level=None, heading2_level=None).encode("utf-8")


def build_docx_positive_outline_inherited(parts):
    """Headings whose effective outline level is inherited through basedOn.

    Heading1/Heading2 carry no w:outlineLvl of their own; the level comes from
    the basedOn chain (HeadingBase -> 0, Heading2Base -> 1). LibreOffice
    honours that inheritance, so the document must still be accepted as native
    headings with levels 1 and 2 (an implementation that only inspects the
    style definition itself would misreport this as missing headings).
    """
    extra = (_docx_style("HeadingBase", "heading base", "Normal", 30, True, heading_level=0)
             + _docx_style("Heading2Base", "heading 2 base", "HeadingBase", 26, True, heading_level=1))
    parts["word/document.xml"] = _docx_document(_positive_docx_body())
    parts["word/styles.xml"] = _docx_styles_xml(
        heading1_level=None, heading1_base="HeadingBase",
        heading2_level=None, heading2_base="Heading2Base",
        extra_styles=extra).encode("utf-8")


def build_docx_negative_appearance(parts):
    # Looks like headings (large + bold) but carries no native heading style.
    body = (_docx_para("季度报告", bold=True, size=56)
            + _docx_para("收入概览", bold=True, size=36)
            + _docx_para("国内业务", bold=True, size=30)
            + _docx_para("这是正文段落，用于验证 PDF 文本提取。")
            + _docx_table(DOCX_TABLE_ROWS)
            + _docx_para(""))
    parts["word/document.xml"] = _docx_document(body)


def build_docx_negative_hierarchy(parts):
    # Heading 2 appears before any Heading 1.
    body = (_docx_para("季度报告", style="Title")
            + _docx_para("国内业务", style="Heading2")
            + _docx_para("收入概览", style="Heading1")
            + _docx_para("这是正文段落，用于验证 PDF 文本提取。")
            + _docx_table(DOCX_TABLE_ROWS)
            + _docx_para(""))
    parts["word/document.xml"] = _docx_document(body)


def make_docx(builder):
    parts = {
        "[Content_Types].xml": DOCX_CT,
        "_rels/.rels": DOCX_ROOT_RELS,
        "word/_rels/document.xml.rels": DOCX_DOC_RELS,
        "word/styles.xml": DOCX_STYLES,
    }
    builder(parts)
    return parts


# --------------------------------------------------------------------------
# PNG (tiny solid-colour image, stdlib only)
# --------------------------------------------------------------------------

def make_png(width, height, rgb):
    raw = b""
    row = bytes(rgb) * width
    for _ in range(height):
        raw += b"\x00" + row

    def chunk(kind, data):
        payload = kind + data
        return (struct.pack(">I", len(data)) + payload
                + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# --------------------------------------------------------------------------
# PPTX
# --------------------------------------------------------------------------

PPTX_CT = (
    xml_header()
    + ('<Types xmlns="%s">' % CT)
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Default Extension="png" ContentType="image/png"/>'
    + '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
    + '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
    + '<Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
    + '<Override PartName="/ppt/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>'
    + '<Override PartName="/ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/>'
    + "</Types>"
).encode("utf-8")

PPTX_ROOT_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/officeDocument" Target="ppt/presentation.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")

PPTX_PRESENTATION = (
    xml_header()
    + ('<p:presentation xmlns:p="%s" xmlns:a="%s" xmlns:r="%s">' % (PML, DML, OD))
    + '<p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst>'
    + '<p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/>'
    + "</p:presentation>"
).encode("utf-8")

PPTX_PRESENTATION_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/slide" Target="slides/slide1.xml"/>' % OD
    + '<Relationship Id="rId2" Type="%s/slide" Target="slides/slide2.xml"/>' % OD
    + "</Relationships>"
).encode("utf-8")


def _pptx_text_shape(shape_id, name, x, y, cx, cy, text, size=2400, bold=False):
    return (
        '<p:sp><p:nvSpPr><p:cNvPr id="%d" name="%s"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
        '<p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p>'
        '<a:r><a:rPr lang="zh-CN" sz="%d"%s><a:latin typeface="Noto Sans CJK SC"/>'
        '<a:ea typeface="Noto Sans CJK SC"/></a:rPr><a:t>%s</a:t></a:r>'
        "</a:p></p:txBody></p:sp>"
        % (shape_id, name, x, y, cx, cy, size, ' b="1"' if bold else "", _esc(text))
    )


def _pptx_slide_xml(shapes_xml, extra_ns=""):
    return (
        xml_header()
        + ('<p:sld xmlns:p="%s" xmlns:a="%s" xmlns:r="%s"%s>' % (PML, DML, OD, extra_ns))
        + "<p:cSld><p:spTree>"
        + '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        + '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>'
        + '<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        + shapes_xml
        + "</p:spTree></p:cSld>"
        + '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>'
        + "</p:sld>"
    ).encode("utf-8")


SLIDE1_SHAPES = (
    _pptx_text_shape(2, "Title 1", 914400, 457200, 10058400, 1143000,
                     "R-12 演示文稿", size=3600, bold=True)
    + _pptx_text_shape(3, "Body 1", 914400, 2000000, 10058400, 1600200, "要点一：图表可编辑")
)

SLIDE2_SHAPES = (
    _pptx_text_shape(2, "Title 2", 914400, 457200, 10058400, 914400,
                     "图表页", size=3000, bold=True)
)

CHART_GRAPHIC_FRAME = (
    '<p:graphicFrame><p:nvGraphicFramePr>'
    '<p:cNvPr id="4" name="Chart 1"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>'
    '<p:xfrm><a:off x="914400" y="1524000"/><a:ext cx="10058400" cy="4572000"/></p:xfrm>'
    '<a:graphic><a:graphicData uri="%s/chart">'
    '<c:chart xmlns:c="%s" xmlns:r="%s" r:id="rId1"/>'
    "</a:graphicData></a:graphic></p:graphicFrame>" % (DML, CHART, OD)
)

PPTX_CHART = (
    xml_header()
    + ('<c:chartSpace xmlns:c="%s" xmlns:a="%s" xmlns:r="%s">' % (CHART, DML, OD))
    + "<c:chart><c:plotArea><c:layout/>"
    + '<c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:varyColors val="0"/>'
    + "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>"
    + '<c:tx><c:strRef><c:f>Sheet1!$B$1</c:f><c:strCache><c:ptCount val="1"/>'
    + "<c:pt idx=\"0\"><c:v>销售额</c:v></c:pt></c:strCache></c:strRef></c:tx>"
    + "<c:cat><c:strRef><c:f>Sheet1!$A$2:$A$4</c:f><c:strCache><c:ptCount val=\"3\"/>"
    + "<c:pt idx=\"0\"><c:v>Q1</c:v></c:pt><c:pt idx=\"1\"><c:v>Q2</c:v></c:pt>"
    + "<c:pt idx=\"2\"><c:v>Q3</c:v></c:pt></c:strCache></c:strRef></c:cat>"
    + "<c:val><c:numRef><c:f>Sheet1!$B$2:$B$4</c:f><c:numCache><c:formatCode>General</c:formatCode>"
    + "<c:ptCount val=\"3\"/><c:pt idx=\"0\"><c:v>10</c:v></c:pt>"
    + "<c:pt idx=\"1\"><c:v>20</c:v></c:pt><c:pt idx=\"2\"><c:v>30</c:v></c:pt>"
    + "</c:numCache></c:numRef></c:val></c:ser>"
    + '<c:axId val="111111111"/><c:axId val="222222222"/></c:barChart>'
    + '<c:catAx><c:axId val="111111111"/><c:scaling><c:orientation val="minMax"/></c:scaling>'
    + '<c:delete val="0"/><c:axPos val="b"/><c:crossAx val="222222222"/></c:catAx>'
    + '<c:valAx><c:axId val="222222222"/><c:scaling><c:orientation val="minMax"/></c:scaling>'
    + '<c:delete val="0"/><c:axPos val="l"/><c:crossAx val="111111111"/></c:valAx>'
    + "</c:plotArea><c:plotVisOnly val=\"1\"/></c:chart>"
    + '<c:externalData r:id="rId1"><c:autoUpdate val="0"/></c:externalData>'
    + "</c:chartSpace>"
).encode("utf-8")

# The chart's workbook link, removed verbatim by the "externalData deleted" fixture.
PPTX_CHART_EXTERNAL_DATA_LINK = b'<c:externalData r:id="rId1"><c:autoUpdate val="0"/></c:externalData>'

PPTX_CHART_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/package" Target="../embeddings/Microsoft_Excel_Worksheet1.xlsx"/>' % OD
    + "</Relationships>"
).encode("utf-8")

PPTX_SLIDE1_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + "</Relationships>"
).encode("utf-8")

PPTX_SLIDE2_RELS = (
    xml_header()
    + ('<Relationships xmlns="%s">' % PR)
    + '<Relationship Id="rId1" Type="%s/chart" Target="../charts/chart1.xml"/>' % OD
    + '<Relationship Id="rId2" Type="%s/package" Target="../embeddings/Microsoft_Excel_Worksheet1.xlsx"/>' % OD
    + "</Relationships>"
).encode("utf-8")


def _embedded_workbook():
    sheet_rows = [
        {"r": 1, "cells": [{"ref": "A1", "inline": "季度"}, {"ref": "B1", "inline": "销售额"}]},
        {"r": 2, "cells": [{"ref": "A2", "inline": "Q1"}, {"ref": "B2", "valueText": "10"}]},
        {"r": 3, "cells": [{"ref": "A3", "inline": "Q2"}, {"ref": "B3", "valueText": "20"}]},
        {"r": 4, "cells": [{"ref": "A4", "inline": "Q3"}, {"ref": "B4", "valueText": "30"}]},
    ]
    return {
        "[Content_Types].xml": (
            xml_header()
            + ('<Types xmlns="%s">' % CT)
            + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            + '<Default Extension="xml" ContentType="application/xml"/>'
            + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            + "</Types>"
        ).encode("utf-8"),
        "_rels/.rels": XLSX_ROOT_RELS,
        "xl/workbook.xml": (
            xml_header()
            + ('<workbook xmlns="%s" xmlns:r="%s">' % (SSML, OD))
            + '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'
        ).encode("utf-8"),
        "xl/_rels/workbook.xml.rels": (
            xml_header()
            + ('<Relationships xmlns="%s">' % PR)
            + '<Relationship Id="rId1" Type="%s/worksheet" Target="worksheets/sheet1.xml"/>' % OD
            + "</Relationships>"
        ).encode("utf-8"),
        "xl/worksheets/sheet1.xml": _xlsx_sheet(sheet_rows, COLS_WIDE),
    }


def _pptx_content_types(include_chart, include_embedding):
    overrides = [
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>',
        '<Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>',
        '<Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>',
    ]
    if include_chart:
        overrides.append('<Override PartName="/ppt/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>')
    if include_embedding:
        overrides.append('<Override PartName="/ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/>')
    return (
        xml_header()
        + ('<Types xmlns="%s">' % CT)
        + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        + '<Default Extension="xml" ContentType="application/xml"/>'
        + '<Default Extension="png" ContentType="image/png"/>'
        + "".join(overrides)
        + "</Types>"
    ).encode("utf-8")


def _pptx_common_parts(slide2_shapes, slide2_rels, include_chart, include_embedding):
    parts = {
        "[Content_Types].xml": _pptx_content_types(include_chart, include_embedding),
        "_rels/.rels": PPTX_ROOT_RELS,
        "ppt/presentation.xml": PPTX_PRESENTATION,
        "ppt/_rels/presentation.xml.rels": PPTX_PRESENTATION_RELS,
        "ppt/slides/slide1.xml": _pptx_slide_xml(SLIDE1_SHAPES),
        "ppt/slides/_rels/slide1.xml.rels": PPTX_SLIDE1_RELS,
        "ppt/slides/slide2.xml": _pptx_slide_xml(slide2_shapes),
        "ppt/slides/_rels/slide2.xml.rels": slide2_rels,
    }
    if include_chart:
        parts["ppt/charts/chart1.xml"] = PPTX_CHART
        parts["ppt/charts/_rels/chart1.xml.rels"] = PPTX_CHART_RELS
    return parts


def _zip_bytes(parts):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(parts.keys()):
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = FIXED_EXTERNAL_ATTR
            info.create_system = 0
            archive.writestr(info, parts[name])
    return buffer.getvalue()


def _pptx_parts(slide2_shapes, slide2_rels, include_chart, include_embedding):
    parts = _pptx_common_parts(slide2_shapes, slide2_rels, include_chart, include_embedding)
    if include_embedding:
        parts["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"] = _zip_bytes(_embedded_workbook())
    return parts


def build_pptx_positive_package():
    return _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                       include_chart=True, include_embedding=True)


def build_pptx_negative_flattened():
    picture = (
        '<p:pic><p:nvPicPr><p:cNvPr id="9" name="ChartImage 1"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>'
        '<p:blipFill><a:blip r:embed="rId3"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>'
        '<p:spPr><a:xfrm><a:off x="914400" y="1524000"/><a:ext cx="10058400" cy="4572000"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>'
    )
    rels = (
        xml_header()
        + ('<Relationships xmlns="%s">' % PR)
        + '<Relationship Id="rId3" Type="%s/image" Target="../media/image1.png"/>' % OD
        + "</Relationships>"
    ).encode("utf-8")
    parts = _pptx_parts(SLIDE2_SHAPES + picture, rels, include_chart=False, include_embedding=False)
    parts["ppt/media/image1.png"] = make_png(16, 16, (32, 96, 160))
    return parts


def build_pptx_negative_noembed():
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=False)
    parts["ppt/charts/_rels/chart1.xml.rels"] = (
        xml_header()
        + ('<Relationships xmlns="%s">' % PR)
        + "</Relationships>"
    ).encode("utf-8")
    return parts


def build_pptx_negative_embed_unlinked():
    """Workbook part kept, but the chart -> workbook relationship is removed.

    The chart still declares <c:externalData r:id="rId1">; with the chart's
    rels emptied the workbook is no longer reachable from the chart.
    """
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=True)
    parts["ppt/charts/_rels/chart1.xml.rels"] = (
        xml_header()
        + ('<Relationships xmlns="%s">' % PR)
        + "</Relationships>"
    ).encode("utf-8")
    return parts


def build_pptx_negative_embed_notzip():
    """Workbook part present and linked, but its bytes are not a zip at all."""
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=True)
    parts["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"] = b"this is not a zip archive\n"
    return parts


def build_pptx_negative_embed_noworkbook():
    """Workbook part present and linked, but the inner package has no workbook.xml."""
    inner = _embedded_workbook()
    del inner["xl/workbook.xml"]
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=True)
    parts["ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"] = _zip_bytes(inner)
    return parts


def build_pptx_negative_embed_noctoverride():
    """Workbook part present, linked and valid, but its [Content_Types].xml
    Override was dropped: the package no longer declares the part's content type."""
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=True)
    parts["[Content_Types].xml"] = _pptx_content_types(include_chart=True, include_embedding=False)
    return parts


def build_pptx_negative_embed_noexternaldata():
    """Chart part present and readable, workbook part still in the package, but
    the chart's <c:externalData> workbook link was deleted.

    Nothing reaches the (now orphaned) xlsx anymore, so the deck must be
    attributed with the missing embedded workbook instead of passing through a
    package-namelist fallback that only sees "an xlsx exists somewhere".
    """
    if PPTX_CHART_EXTERNAL_DATA_LINK not in PPTX_CHART:
        raise AssertionError("chart template no longer carries the externalData link marker")
    parts = _pptx_parts(SLIDE2_SHAPES + CHART_GRAPHIC_FRAME, PPTX_SLIDE2_RELS,
                        include_chart=True, include_embedding=True)
    parts["ppt/charts/chart1.xml"] = PPTX_CHART.replace(PPTX_CHART_EXTERNAL_DATA_LINK, b"")
    return parts


# --------------------------------------------------------------------------
# Specs
# --------------------------------------------------------------------------

def xlsx_spec():
    return {
        "kind": "xlsx",
        "sheets_in_order": ["Summary", "Data"],
        "cells": [
            {"sheet": "Summary", "ref": "B2", "expect_formula": True,
             "cross_sheet_ref": "Data!", "expect_value": 200.0, "numfmt": "number",
             "pdf_display": "200.00"},
            {"sheet": "Summary", "ref": "B3", "expect_formula": True,
             "cross_sheet_ref": "Data!", "expect_value": 0.255, "numfmt": "percent",
             "pdf_display": "25.50%"},
            {"sheet": "Summary", "ref": "B4", "expect_formula": True,
             "cross_sheet_ref": "Data!", "expect_value": 275.0, "numfmt": "units",
             "pdf_display": "275.00 units"},
        ],
        "pdf_text_required": ["Total", "200.00", "25.50%", "275.00 units"],
        "notes": "expect_value is authored independently (100*2, (125.5-100)/100, 100+125.5+49.5); "
                 "LibreOffice must recompute it from the formulas, not read a cached value.",
    }


def docx_spec():
    return {
        "kind": "docx",
        "headings": [
            {"style": "Heading1", "text": "收入概览"},
            {"style": "Heading2", "text": "国内业务"},
        ],
        "table": {"rows": 2, "cols": 2, "cells": ["项目", "数值", "收入", "1234"]},
        "body_text_required": ["这是正文段落，用于验证 PDF 文本提取。"],
        "pdf_text_required": ["收入概览", "国内业务", "项目", "1234"],
        "pdf_page_count": 1,
        "notes": "Heading detection must be by native w:pStyle, never by font size/boldness. "
                 "The small positive document must fit exactly one page.",
    }


def pptx_spec():
    return {
        "kind": "pptx",
        "slides": [
            {"index": 1, "texts": ["R-12 演示文稿", "要点一：图表可编辑"], "min_shapes": 2},
            {"index": 2, "texts": ["图表页"], "require_chart": True,
             "require_embedded_workbook": True},
        ],
        "pdf_text_required": ["R-12 演示文稿", "图表页"],
        "pdf_page_count": 2,
        "notes": "The chart on slide 2 must be a native chart part with an embedded workbook, "
                 "not a flattened image. Two slides must render as exactly two PDF pages.",
    }


def pptx_embed_only_spec():
    """Slide 2 requires the embedded workbook only (no `require_chart`).

    This is the field combination under which the checker's embedded-workbook
    logic used to be unreachable: a deck that dropped its workbook must still
    be attributed with PPTX_EMBEDDED_WORKBOOK_MISSING.
    """
    spec = pptx_spec()
    spec["slides"][1].pop("require_chart", None)
    spec["notes"] = ("Slide 2 requires an embedded workbook without requiring a native chart. "
                     "A deck with the workbook dropped must be attributed with "
                     "PPTX_EMBEDDED_WORKBOOK_MISSING.")
    return spec


# --------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description="Generate R-12 OOXML regression fixtures")
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    out = os.path.abspath(args.out)
    specs_dir = os.path.join(out, "specs")

    xlsx_dir = os.path.join(out, "xlsx")
    write_package(os.path.join(xlsx_dir, "positive.xlsx"), make_xlsx(build_xlsx_positive))
    write_package(os.path.join(xlsx_dir, "negative-values.xlsx"), make_xlsx(build_xlsx_negative_values))
    write_package(os.path.join(xlsx_dir, "negative-numfmt.xlsx"), make_xlsx(build_xlsx_negative_numfmt))
    write_package(os.path.join(xlsx_dir, "negative-missing-formula.xlsx"),
                  make_xlsx(build_xlsx_negative_missing_formula))

    docx_dir = os.path.join(out, "docx")
    write_package(os.path.join(docx_dir, "positive.docx"), make_docx(build_docx_positive))
    write_package(os.path.join(docx_dir, "negative-appearance.docx"), make_docx(build_docx_negative_appearance))
    write_package(os.path.join(docx_dir, "negative-hierarchy.docx"), make_docx(build_docx_negative_hierarchy))
    write_package(os.path.join(docx_dir, "negative-no-outline.docx"),
                  make_docx(build_docx_negative_no_outline))
    write_package(os.path.join(docx_dir, "positive-outline-inherited.docx"),
                  make_docx(build_docx_positive_outline_inherited))

    pptx_dir = os.path.join(out, "pptx")
    write_package(os.path.join(pptx_dir, "positive.pptx"), build_pptx_positive_package())
    write_package(os.path.join(pptx_dir, "negative-flattened.pptx"), build_pptx_negative_flattened())
    write_package(os.path.join(pptx_dir, "negative-noembed.pptx"), build_pptx_negative_noembed())
    write_package(os.path.join(pptx_dir, "negative-embed-unlinked.pptx"),
                  build_pptx_negative_embed_unlinked())
    write_package(os.path.join(pptx_dir, "negative-embed-notzip.pptx"),
                  build_pptx_negative_embed_notzip())
    write_package(os.path.join(pptx_dir, "negative-embed-noworkbook.pptx"),
                  build_pptx_negative_embed_noworkbook())
    write_package(os.path.join(pptx_dir, "negative-embed-noctoverride.pptx"),
                  build_pptx_negative_embed_noctoverride())
    write_package(os.path.join(pptx_dir, "negative-embed-noexternaldata.pptx"),
                  build_pptx_negative_embed_noexternaldata())

    os.makedirs(specs_dir, exist_ok=True)
    with open(os.path.join(specs_dir, "xlsx.json"), "w", encoding="utf-8") as handle:
        json.dump(xlsx_spec(), handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    with open(os.path.join(specs_dir, "docx.json"), "w", encoding="utf-8") as handle:
        json.dump(docx_spec(), handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    with open(os.path.join(specs_dir, "pptx.json"), "w", encoding="utf-8") as handle:
        json.dump(pptx_spec(), handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    with open(os.path.join(specs_dir, "pptx-embed-only.json"), "w", encoding="utf-8") as handle:
        json.dump(pptx_embed_only_spec(), handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    with open(os.path.join(specs_dir, "README.txt"), "w", encoding="utf-8") as handle:
        handle.write(
            "Expectations in this directory are authored independently of the generated files.\n"
            "LibreOffice must recompute XLSX values from formulas; the specs state the expected\n"
            "values, formulas and display formats that a healthy product must satisfy.\n")

    if not args.quiet:
        print("fixtures written to %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

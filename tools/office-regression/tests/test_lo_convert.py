# -*- coding: utf-8 -*-
"""lo_convert freshness regression: a stale artifact must never masquerade as
this conversion's output.

Headless LibreOffice exits 0 even when the source cannot be loaded, so the old
"exit code + target exists in the caller's outdir" check accepted a leftover
file from an earlier run as proof that this conversion succeeded. lo_convert
must convert into a fresh per-call output directory and only accept a product
found there.
"""

import os

import pytest

import r12lib as R12


STALE_BYTES = b"%PDF-1.4\n% stale artifact from a previous run\n"


def _write_stale(path):
    with open(path, "wb") as handle:
        handle.write(STALE_BYTES)


def test_lo_convert_missing_source_with_stale_artifact_fails(r12):
    """The reproduced defect: missing input + a stale same-named artifact in
    the reused output directory used to return "successfully". It must raise.
    """
    soffice = R12.soffice_bin()
    work = os.path.join(r12["work"], "lo-convert-stale")
    os.makedirs(work, exist_ok=True)
    stale = os.path.join(work, "gone.pdf")  # exactly where the old code looked
    _write_stale(stale)
    missing_source = os.path.join(r12["work"], "lo-convert-missing", "gone.docx")
    os.makedirs(os.path.dirname(missing_source), exist_ok=True)

    with pytest.raises(AssertionError) as excinfo:
        R12.lo_convert(soffice, missing_source, work, convert_to="pdf")
    assert "LibreOffice conversion to 'pdf' failed" in str(excinfo.value), (
        "missing input must fail with the conversion AssertionError: %s" % excinfo.value)
    # The stale artifact was never returned nor overwritten by this call.
    with open(stale, "rb") as handle:
        assert handle.read() == STALE_BYTES, "the stale artifact must be left untouched"


def test_lo_convert_returns_a_fresh_product_per_call(r12, generated):
    """A healthy conversion must return the file produced by *this* call.

    A stale artifact with the same name must neither satisfy the check nor be
    returned; two calls must never share a product path.
    """
    soffice = R12.soffice_bin()
    work = os.path.join(r12["work"], "lo-convert-fresh")
    os.makedirs(work, exist_ok=True)
    source = os.path.join(generated, "docx", "positive.docx")
    stale = os.path.join(work, "positive.pdf")
    _write_stale(stale)

    produced = R12.lo_convert(soffice, source, work, convert_to="pdf")
    assert produced != stale, "lo_convert returned the pre-existing (stale) path"
    assert os.path.isfile(produced), produced
    with open(produced, "rb") as handle:
        assert handle.read(5) == b"%PDF-", "the returned artifact must be a real PDF"
    assert os.path.getsize(produced) > 100, produced
    assert R12.pdf_page_count(produced) == 1

    again = R12.lo_convert(soffice, source, work, convert_to="pdf")
    assert again != produced, "two conversions must not share one product path"
    assert os.path.isfile(again), again

    with open(stale, "rb") as handle:
        assert handle.read() == STALE_BYTES, "the stale artifact must be left untouched"
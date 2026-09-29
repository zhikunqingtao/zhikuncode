# -*- coding: utf-8 -*-
"""Preflight: prove the container really carries the pinned toolchain.

The regression evidence is only meaningful inside the pinned image, so a
missing or mismatched component makes this suite FAIL loudly ("BLOCKED") --
it is never skipped or silently accepted.
"""

import importlib.metadata as importlib_metadata
import re
import shutil
import sys

import r12lib as R12

PINNED_PY_PACKAGES = [
    ("playwright", "PLAYWRIGHT_VERSION"),
    ("pytest", "PYTEST_VERSION"),
    ("pytest-timeout", "PYTEST_TIMEOUT_VERSION"),
    ("fastapi", "FASTAPI_VERSION"),
    ("starlette", "STARLETTE_VERSION"),
    ("uvicorn", "UVICORN_VERSION"),
    ("pydantic", "PYDANTIC_VERSION"),
]

PINNED_DEB_PACKAGES = [
    ("libreoffice-core", "LIBREOFFICE_VERSION"),
    ("fonts-noto-cjk", "FONTS_NOTO_CJK_VERSION"),
    ("poppler-utils", "POPPLER_UTILS_VERSION"),
]


def test_preflight_toolchain_and_fonts(r12, pins):
    failures = []
    evidence = {"deb_packages": {}, "tools": {}}

    soffice = shutil.which("soffice")
    if not soffice:
        failures.append("BLOCKED: soffice not found on PATH")
    else:
        proc = R12.run([soffice, "--version"], timeout=120)
        out = (proc.stdout + proc.stderr).decode("utf-8", "replace").strip()
        first_line = out.splitlines()[0] if out else ""
        evidence["tools"]["soffice"] = {"path": soffice, "version_line": first_line,
                                        "exit_code": proc.returncode}
        prefix = pins["SOFFICE_VERSION_PREFIX"]
        if proc.returncode != 0 or not first_line:
            failures.append("BLOCKED: `soffice --version` failed (exit %s): %r"
                            % (proc.returncode, out[:500]))
        elif not first_line.startswith(prefix):
            failures.append("BLOCKED: soffice version %r does not start with pinned prefix %r"
                            % (first_line, prefix))

    dpkg = shutil.which("dpkg-query")
    if not dpkg:
        failures.append("BLOCKED: dpkg-query not found (base image is not Debian-based?)")
    else:
        for package, pin_key in PINNED_DEB_PACKAGES:
            proc = R12.run([dpkg, "-W", "-f=${Version}", package], timeout=60)
            actual = proc.stdout.decode("utf-8", "replace").strip()
            expected = pins[pin_key]
            evidence["deb_packages"][package] = {"expected": expected, "actual": actual,
                                                 "exit_code": proc.returncode}
            if proc.returncode != 0 or actual != expected:
                failures.append("BLOCKED: %s=%r does not match pin %r"
                                % (package, actual or proc.stderr.decode("utf-8", "replace").strip(),
                                   expected))

    pdftotext = shutil.which("pdftotext")
    if not pdftotext:
        failures.append("BLOCKED: pdftotext not found on PATH")
    else:
        proc = R12.run([pdftotext, "-v"], timeout=60)
        text = (proc.stdout + proc.stderr).decode("utf-8", "replace")
        match = re.search(r"pdftotext version ([0-9]+(?:\.[0-9]+)+)", text)
        evidence["tools"]["pdftotext"] = {"path": pdftotext, "version_text": text.strip(),
                                          "exit_code": proc.returncode}
        expected_version = pins["POPPLER_UTILS_VERSION"].split("-", 1)[0]
        if not match:
            failures.append("BLOCKED: unexpected `pdftotext -v` output: %r" % text[:300])
        elif match.group(1) != expected_version:
            failures.append("BLOCKED: pdftotext version %r does not match pinned poppler %r"
                            % (match.group(1), expected_version))

    for tool in ("pdfinfo", "pdftoppm"):
        if not shutil.which(tool):
            failures.append("BLOCKED: %s not found on PATH (poppler-utils incomplete)" % tool)

    fc_list = shutil.which("fc-list")
    if not fc_list:
        failures.append("BLOCKED: fc-list not found on PATH (fontconfig missing)")
    else:
        proc = R12.run([fc_list], timeout=180)
        fonts = proc.stdout.decode("utf-8", "replace")
        evidence["cjk_fonts"] = [line for line in fonts.splitlines() if "Noto Sans CJK" in line][:20]
        if "Noto Sans CJK" not in fonts:
            failures.append("BLOCKED: no `Noto Sans CJK` font installed (CJK PDF rendering unreliable)")

    R12.write_evidence(r12["evidence"], "preflight-toolchain", evidence)
    assert not failures, "preflight toolchain check failed:\n" + "\n".join(failures)


def test_preflight_python_packages(r12, pins):
    failures = []
    evidence = {"executable": sys.executable, "packages": {}, "imports": {}}

    for dist, pin_key in PINNED_PY_PACKAGES:
        expected = pins[pin_key]
        try:
            actual = importlib_metadata.version(dist)
        except importlib_metadata.PackageNotFoundError:
            actual = None
        evidence["packages"][dist] = {"expected": expected, "actual": actual}
        if actual != expected:
            failures.append("BLOCKED: python package %s==%s does not match pin %s==%s"
                            % (dist, actual, dist, expected))

    for module in ("playwright", "playwright.async_api", "fastapi", "starlette", "uvicorn", "pydantic"):
        try:
            __import__(module)
            evidence["imports"][module] = "ok"
        except Exception as exc:  # noqa: BLE001 - report the real import error
            evidence["imports"][module] = "FAILED: %r" % (exc,)
            failures.append("BLOCKED: cannot import %s: %r" % (module, exc))

    R12.write_evidence(r12["evidence"], "preflight-python-packages", evidence)
    assert not failures, "preflight python package check failed:\n" + "\n".join(failures)
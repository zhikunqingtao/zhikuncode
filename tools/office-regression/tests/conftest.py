# -*- coding: utf-8 -*-
"""Session fixtures for the R-12 offline regression suite.

Everything runs inside the pinned regression image with `--network none`:
LibreOffice/poppler come from the image, the HTML fixtures are served by an
in-container loopback HTTP server, and the HTML interaction goes through the
real `services.browser_service.BrowserService` (imported from
/work/python-service/src, mounted read-only at /work).
"""

import asyncio
import os
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import r12lib as R12  # noqa: E402


@pytest.fixture(scope="session")
def r12():
    repo = R12.default_repo_root()
    tools = R12.default_tools_dir()
    out = R12.out_dir()
    paths = {
        "repo": repo,
        "tools": tools,
        "scripts": os.path.join(tools, "scripts"),
        "pins_env": os.environ.get("OFFICE_REGRESSION_PINS", os.path.join(tools, "pins.env")),
        "fixtures_html": os.path.join(tools, "fixtures", "html"),
        "html_manifest": os.path.join(tools, "fixtures", "HTML-SHA256SUMS"),
        "python_service_src": os.environ.get(
            "R12_PYTHON_SERVICE_SRC", os.path.join(repo, "python-service", "src")),
        "out": out,
        "evidence": os.path.join(out, "evidence"),
        "work": os.path.join(out, "work"),
        "generated": os.path.join(out, "generated", "fixtures"),
    }
    for key in ("evidence", "work", "generated"):
        os.makedirs(paths[key], exist_ok=True)
    return paths


@pytest.fixture(scope="session")
def pins(r12):
    return R12.read_pins(r12["pins_env"])


@pytest.fixture(scope="session")
def generated(r12):
    """The fixture set produced by office_fixture_gen.py (deterministic)."""
    out = r12["generated"]
    marker = os.path.join(out, "specs", "xlsx.json")
    if not os.path.isfile(marker):
        proc = R12.run(
            [sys.executable, os.path.join(r12["scripts"], "office_fixture_gen.py"),
             "--out", out, "--quiet"],
            timeout=180)
        assert proc.returncode == 0, (
            "fixture generation failed: %s\n%s"
            % (proc.stdout.decode("utf-8", "replace"), proc.stderr.decode("utf-8", "replace")))
    return out


@pytest.fixture(scope="session")
def html_server(r12):
    server = R12.LoopbackServer(r12["fixtures_html"]).start()
    yield server
    server.stop()


@pytest.fixture(scope="session")
def asyncio_loop():
    loop = asyncio.new_event_loop()
    yield loop
    if not loop.is_closed():
        loop.close()


@pytest.fixture(scope="session")
def browser_service(r12, asyncio_loop):
    """The real BrowserService, started once and shared by all HTML tests."""
    src = r12["python_service_src"]
    assert os.path.isdir(src), "BLOCKED: python-service sources not found at %s" % src
    if src not in sys.path:
        sys.path.insert(0, src)
    from services.browser_service import BrowserService  # noqa: E402

    service = BrowserService()
    try:
        asyncio_loop.run_until_complete(service.startup())
    except Exception as exc:  # pragma: no cover - surfaces as a clear test error
        raise AssertionError(
            "BLOCKED: real BrowserService failed to start inside the container: %r" % (exc,))
    yield service
    try:
        asyncio_loop.run_until_complete(service.shutdown())
    finally:
        try:
            asyncio_loop.run_until_complete(asyncio_loop.shutdown_asyncgens())
        except Exception:
            pass


@pytest.fixture(scope="session")
def browser(asyncio_loop, browser_service):
    harness = R12.BrowserHarness(asyncio_loop, browser_service)
    yield harness
    harness.close_all()
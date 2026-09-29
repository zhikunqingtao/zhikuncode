# -*- coding: utf-8 -*-
"""Shared helpers for the R-12 offline regression suite (stdlib + Playwright).

This module is a *test asset*. It contains only test-side plumbing: path and
pins discovery, byte-level hashing, LibreOffice/pdftotext invocation, checker
CLI invocation with reason-code extraction, the loopback HTTP fixture server
and the HTML audits (resources / keyboard / print / offline) that supplement
the real BrowserService under test.

Reason-code helpers deliberately ignore any checker invocation that did not
complete normally (usage/IO error): "the checker crashed" is never treated as
"a violation was detected". Python 3.9 compatible.
"""

import hashlib
import http.server
import functools
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import uuid

# ---------------------------------------------------------------------------
# paths / environment
# ---------------------------------------------------------------------------


def default_repo_root():
    return os.environ.get("R12_REPO_ROOT", "/work")


def default_tools_dir():
    repo = default_repo_root()
    return os.environ.get("R12_TOOLS_DIR", os.path.join(repo, "tools", "office-regression"))


def out_dir():
    return os.environ.get("OFFICE_REGRESSION_OUT", "/tmp/office-regression-out")


def read_pins(path):
    """Parse the `KEY=VALUE` pins file (comments, blanks, optional quotes)."""
    pins = {}
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            pins[key.strip()] = value
    return pins


# ---------------------------------------------------------------------------
# processes / hashing / evidence
# ---------------------------------------------------------------------------


def run(args, timeout=300, check=False, env=None):
    proc = subprocess.run(
        args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, env=env)
    if check and proc.returncode != 0:
        raise AssertionError(
            "command failed with exit code %s: %s\n--- stdout ---\n%s\n--- stderr ---\n%s"
            % (proc.returncode, " ".join(args),
               proc.stdout.decode("utf-8", "replace")[-4000:],
               proc.stderr.decode("utf-8", "replace")[-4000:]))
    return proc


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def tree_hashes(directory):
    """{relative_path: sha256} for every file under directory (sorted walk)."""
    hashes = {}
    for root, dirs, files in os.walk(directory):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, directory).replace("\\", "/")
            hashes[rel] = sha256_file(full)
    return hashes


def write_evidence(evidence_dir, label, payload):
    os.makedirs(evidence_dir, exist_ok=True)
    path = os.path.join(evidence_dir, label + ".json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    return path


# ---------------------------------------------------------------------------
# LibreOffice / poppler
# ---------------------------------------------------------------------------


def soffice_bin():
    tool = shutil.which("soffice")
    if not tool:
        raise AssertionError("BLOCKED: soffice not found on PATH inside the regression container")
    return tool


def lo_convert(soffice, source, outdir, convert_to="pdf", timeout=300):
    """Convert `source` with headless LibreOffice; return the produced file path.

    Uses a fresh per-call user profile (in the container's own /tmp) so
    concurrent/sequential conversions cannot collide through LibreOffice's
    single-instance behaviour, and a fresh per-call output directory inside
    `outdir` so a leftover artifact from an earlier run can never satisfy the
    success check (headless LibreOffice exits 0 even when the source cannot be
    loaded, so a reused output directory would let a stale file masquerade as
    this conversion's result).
    """
    os.makedirs(outdir, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="r12-lo-profile-")
    fresh_outdir = tempfile.mkdtemp(prefix="r12-lo-out-", dir=outdir)
    ext = convert_to.split(":", 1)[0]
    stem = os.path.splitext(os.path.basename(source))[0]
    env = dict(os.environ)
    env.setdefault("HOME", "/tmp")
    cmd = [
        soffice, "--headless", "--nologo", "--norestore", "--nolockcheck",
        "-env:UserInstallation=file://%s" % profile,
        "--convert-to", convert_to, "--outdir", fresh_outdir, source,
    ]
    proc = run(cmd, timeout=timeout, env=env)
    produced = os.path.join(fresh_outdir, stem + "." + ext)
    if proc.returncode != 0 or not os.path.isfile(produced):
        raise AssertionError(
            "LibreOffice conversion to %r failed (exit %s)\ncmd: %s\nstdout:\n%s\nstderr:\n%s\noutdir contains: %s"
            % (convert_to, proc.returncode, " ".join(cmd),
               proc.stdout.decode("utf-8", "replace")[-2000:],
               proc.stderr.decode("utf-8", "replace")[-2000:],
               sorted(os.listdir(fresh_outdir))[:50]))
    return produced


def pdftotext(pdf_path, layout=True, timeout=180):
    tool = shutil.which("pdftotext")
    if not tool:
        raise AssertionError("BLOCKED: pdftotext not found on PATH")
    args = [tool] + (["-layout"] if layout else []) + [pdf_path, "-"]
    proc = run(args, timeout=timeout)
    if proc.returncode != 0:
        raise AssertionError("pdftotext failed for %s: %s"
                             % (pdf_path, proc.stderr.decode("utf-8", "replace")[-1000:]))
    return proc.stdout.decode("utf-8", "replace")


def pdf_page_count(pdf_path, timeout=120):
    tool = shutil.which("pdfinfo")
    if not tool:
        raise AssertionError("BLOCKED: pdfinfo not found on PATH")
    proc = run([tool, pdf_path], timeout=timeout)
    if proc.returncode != 0:
        raise AssertionError("pdfinfo failed for %s: %s"
                             % (pdf_path, proc.stderr.decode("utf-8", "replace")[-1000:]))
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        if line.lower().startswith("pages:"):
            return int(line.split(":", 1)[1].strip())
    return None


def render_pdf_png(pdf_path, prefix, resolution=60, timeout=180):
    """Render PDF pages to PNG (evidence only; never a substitute for the
    structural checks). Returns the list of produced files."""
    tool = shutil.which("pdftoppm")
    if not tool:
        raise AssertionError("BLOCKED: pdftoppm not found on PATH")
    run([tool, "-png", "-r", str(resolution), pdf_path, prefix], timeout=timeout, check=True)
    base = os.path.dirname(prefix)
    stem = os.path.basename(prefix)
    return sorted(os.path.join(base, name) for name in os.listdir(base)
                  if name.startswith(stem) and name.endswith(".png"))


# ---------------------------------------------------------------------------
# checker CLIs (reason codes are the contract)
# ---------------------------------------------------------------------------


def invoke_checker(scripts_dir, mode, kind, file_path, spec_path, evidence_dir, label, timeout=300):
    """Run office_struct_check.py; return (exit_code, payload).

    The payload is only trusted when the script emitted parseable JSON. Any
    other outcome (IO error, crash, unparsable output) surfaces as a payload
    with no `reason_codes`, which `checker_codes` maps to None.
    """
    script = os.path.join(scripts_dir, "office_struct_check.py")
    cmd = [sys.executable, script, mode]
    if kind:
        cmd += ["--kind", kind]
    cmd += ["--file", file_path, "--spec", spec_path]
    proc = run(cmd, timeout=timeout)
    stdout = proc.stdout.decode("utf-8", "replace")
    stderr = proc.stderr.decode("utf-8", "replace")
    payload = None
    try:
        payload = json.loads(stdout)
    except ValueError:
        payload = None
    write_evidence(evidence_dir, label, {
        "command": cmd,
        "exit_code": proc.returncode,
        "stdout": stdout[-8000:],
        "stderr": stderr[-4000:],
    })
    if payload is None:
        payload = {"ok": None, "unparsable_output": stdout[-2000:], "stderr": stderr[-2000:]}
    return proc.returncode, payload


def checker_codes(exit_code, payload):
    """Reason codes, or None when the checker did not run to completion.

    Exit 0 = healthy, 2 = violations found. Anything else (1 = usage/IO error,
    crash, signal) must never be counted as a detected violation.
    """
    if exit_code not in (0, 2):
        return None
    codes = payload.get("reason_codes")
    if not isinstance(codes, list):
        return None
    return set(codes)


def invoke_fixedfile(scripts_dir, args, evidence_dir, label, timeout=300):
    script = os.path.join(scripts_dir, "fixedfile_check.py")
    cmd = [sys.executable, script] + list(args)
    proc = run(cmd, timeout=timeout)
    stdout = proc.stdout.decode("utf-8", "replace")
    payload = None
    try:
        payload = json.loads(stdout)
    except ValueError:
        payload = None
    write_evidence(evidence_dir, label, {
        "command": cmd,
        "exit_code": proc.returncode,
        "stdout": stdout[-8000:],
        "stderr": proc.stderr.decode("utf-8", "replace")[-4000:],
    })
    return proc.returncode, payload


# ---------------------------------------------------------------------------
# loopback HTTP fixture server
# ---------------------------------------------------------------------------


class LoopbackServer(object):
    """Threaded HTTP server for fixtures/html with an access log [(path, status)].

    Runs on 127.0.0.1 with an ephemeral port; `--network none` containers keep
    a working loopback, so the whole HTML suite stays offline.
    """

    def __init__(self, directory):
        self.directory = directory
        self.requests = []
        self._lock = threading.Lock()
        requests_log = self.requests
        lock = self._lock

        class Handler(http.server.SimpleHTTPRequestHandler):
            def log_request(self, code="-", size="-"):
                with lock:
                    requests_log.append((self.path, code if isinstance(code, int) else -1))

            def log_message(self, fmt, *args):  # silence stderr noise
                pass

        self.httpd = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0), functools.partial(Handler, directory=directory))
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="r12-http", daemon=True)

    @property
    def port(self):
        return self.httpd.server_address[1]

    def base_url(self):
        return "http://127.0.0.1:%d" % self.port

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def snapshot(self):
        with self._lock:
            return list(self.requests)

    def statuses_for(self, path):
        with self._lock:
            return [status for req_path, status in self.requests
                    if req_path.split("?", 1)[0] == path]


SUBRESOURCE_JS = """
() => {
  const out = [];
  const add = (u) => { if (u) { const x = new URL(u); out.push({url: u, path: x.pathname}); } };
  document.querySelectorAll('link[rel="stylesheet"]').forEach(el => add(el.href));
  document.querySelectorAll('script[src]').forEach(el => add(el.src));
  document.querySelectorAll('img[src]').forEach(el => add(el.src));
  return out;
}
"""


def audit_resources(loop, page, server):
    """Attribute every referenced subresource: loaded vs failed.

    Returns (reasons, info). Reasons:
      HTML_RESOURCE_LOAD_FAILED    – browser asked for it, server answered >= 400
      HTML_RESOURCE_NOT_REQUESTED  – the browser never requested the reference
    """
    refs = loop.run_until_complete(page.evaluate(SUBRESOURCE_JS))
    reasons = []
    failed = []
    for ref in refs:
        statuses = server.statuses_for(ref["path"])
        if not statuses:
            reasons.append({"code": "HTML_RESOURCE_NOT_REQUESTED",
                            "detail": "%s was never requested by the browser" % ref["url"]})
            continue
        status = statuses[-1]
        if status >= 400:
            failed.append([ref["url"], status])
            reasons.append({"code": "HTML_RESOURCE_LOAD_FAILED",
                            "detail": "%s responded with HTTP %s" % (ref["url"], status)})
    info = {"references": refs, "failed": failed, "server_requests": server.snapshot()}
    return reasons, info


KEYBOARD_STATE_JS = """
() => {
  const a = document.activeElement;
  return {
    active: a ? (a.id || a.tagName.toLowerCase()) : null,
    activation: (typeof window.__lastActivation === 'string') ? window.__lastActivation : null,
  };
}
"""


def keyboard_audit(loop, page, target_id, expect_activation, tab_presses=8):
    """Keyboard-only operability of #target_id: reachable via Tab, then Enter.

    Returns (reasons, info). Reason code HTML_KEYBOARD_INOPERABLE is produced
    (with a detail distinguishing "never focusable" from "focusable but not
    keyboard-activatable") when the element cannot be operated by keyboard
    alone. The negative fixtures must hit exactly this code.
    """
    loop.run_until_complete(page.evaluate(
        "() => { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); }"))
    sequence = []
    reached = False
    for _ in range(tab_presses):
        loop.run_until_complete(page.keyboard.press("Tab"))
        state = loop.run_until_complete(page.evaluate(KEYBOARD_STATE_JS))
        sequence.append(state["active"])
        if state["active"] == target_id:
            reached = True
            break

    reasons = []
    activation = None
    if reached:
        loop.run_until_complete(page.keyboard.press("Enter"))
        state = loop.run_until_complete(page.evaluate(KEYBOARD_STATE_JS))
        activation = state["activation"]
        if activation != expect_activation:
            reasons.append({
                "code": "HTML_KEYBOARD_INOPERABLE",
                "detail": "#%s was focused via Tab but Enter did not activate it "
                          "(activation=%r, expected %r)" % (target_id, activation, expect_activation)})
    else:
        loop.run_until_complete(page.keyboard.press("Enter"))
        state = loop.run_until_complete(page.evaluate(KEYBOARD_STATE_JS))
        activation = state["activation"]
        reasons.append({
            "code": "HTML_KEYBOARD_INOPERABLE",
            "detail": "#%s never became document.activeElement within %d Tab presses "
                      "(focus sequence=%s)" % (target_id, tab_presses, sequence)})
    info = {"focus_sequence": sequence, "reached": reached, "activation": activation}
    return reasons, info


PRINT_STATE_JS = """
() => {
  const visible = (el) => !!el && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const p = document.getElementById('print-only');
  const s = document.getElementById('screen-only');
  return {
    print_only_display: p ? getComputedStyle(p).display : null,
    screen_only_display: s ? getComputedStyle(s).display : null,
    print_only_visible: visible(p),
    screen_only_visible: visible(s),
  };
}
"""


def print_state(loop, page, media):
    loop.run_until_complete(page.emulate_media(media=media))
    try:
        return loop.run_until_complete(page.evaluate(PRINT_STATE_JS))
    finally:
        loop.run_until_complete(page.emulate_media(media="screen"))


OFFLINE_FETCH_JS = """
async () => {
  try {
    await fetch('/__r12_offline_probe__?t=' + Date.now(), {cache: 'no-store'});
    return 'reachable';
  } catch (e) {
    return 'blocked';
  }
}
"""


def offline_fetch_probe(loop, page):
    """'blocked' proves the context really has no network access."""
    return loop.run_until_complete(page.evaluate(OFFLINE_FETCH_JS))


# ---------------------------------------------------------------------------
# BrowserService harness (real service, driven on one asyncio loop)
# ---------------------------------------------------------------------------


class BrowserHarness(object):
    """Drives the real `services.browser_service.BrowserService` object.

    The service keeps a persistent asyncio loop (Playwright's async transport
    is loop-bound); each test opens/closes its own named session.
    """

    def __init__(self, loop, service):
        self.loop = loop
        self.service = service
        self._sessions = []

    def run(self, coro, timeout=120):
        import asyncio
        return self.loop.run_until_complete(asyncio.wait_for(coro, timeout))

    def open(self, prefix):
        sid = "%s-%s" % (prefix, uuid.uuid4().hex[:8])
        session = self.run(self.service.get_or_create_session(sid))
        self._sessions.append(sid)
        return sid, session

    def close(self, sid):
        self.run(self.service.close_session(sid))
        if sid in self._sessions:
            self._sessions.remove(sid)

    def close_all(self):
        for sid in list(reversed(self._sessions)):
            try:
                self.close(sid)
            except Exception:
                pass

    def navigate(self, sid, url, wait_until="load"):
        return self.run(self.service.navigate(sid, url, wait_until=wait_until))
#!/usr/bin/env python3
"""Re-measure the frozen King case browser evidence against the current case HTML.

Runs a real Chromium (Playwright) session against a locally served copy of
docs/case-studies/zhikuncode开发王者荣耀.html and emits the direct-browser
measurement JSON consumed by scripts/update-king-browser-v14.mjs on stdin.

Every emitted number comes from the live DOM / real network session; nothing is
copied from previous evidence. The HTML file itself is never modified.

Prerequisite (serve the case directory, not the repo root):
    cd docs/case-studies && ../../python-service/.venv/bin/python -m http.server 8123

Usage:
    ../../python-service/.venv/bin/python scripts/measure-king-browser.py > /tmp/king-measurement.json
    node scripts/update-king-browser-v14.mjs < /tmp/king-measurement.json

Measurement semantics (kept consistent with the frozen evidence schema):
- primaryLayout (1280x720): per figure, the SVG element's rendered viewport box
  is taken as the canvas frame and every rendered element's client rect is
  converted with the same uniform client->viewBox scale used by the frozen
  evidence (scaleX = viewBox.width / svgWidth, scaleY = viewBox.height /
  svgHeight, relative to the SVG's top-left client corner). contentBounds =
  union of that frame and all rendered content, rounded to integer viewBox
  units. overflow = content extending beyond the frame (i.e. beyond the
  rendered SVG viewport), rounded to integer viewBox units. blankRatios = gaps
  between contentBounds and the frame edges.
- text collisions: pairwise intersections of rendered <text> client rects,
  counted when the intersection area exceeds 8% of the smaller text box.
- stageOverflowIds: figure stages .v12-stage/.pv-stage only (the metric used by
  the frozen evidence; log-viz stages are not part of this field).
- interactions are exercised at 1600x900 because #auditToggle only renders at
  >=1600px while #motionToggle (topbar) only renders below ~1880px.
- onlineDeployment is measured by loading https://king.zhikun.xin/ and
  https://king.zhikun.xin/?demo=1 in Chromium and observing the real pages.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen

from playwright.sync_api import sync_playwright

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_HTML = REPO_ROOT / "docs/case-studies/zhikuncode开发王者荣耀.html"
DEFAULT_BASE_URL = "http://127.0.0.1:8123"
VIEWPORTS = [(1280, 720), (1440, 900), (1920, 1080), (390, 844)]
PRIMARY_VIEWPORT = (1280, 720)
INTERACTION_VIEWPORT = (1600, 900)
EXPECTED_FIGURE_COUNT = 91
STANDARD_ONLINE_URL = "https://king.zhikun.xin/"
DEMO_ONLINE_URL = "https://king.zhikun.xin/?demo=1"
ONLINE_CLASSIFICATION = "OUTSIDE_DEVELOPMENT_WINDOW_ALIYUN_HTTPS_DEPLOYMENT"
ONLINE_BOOT_TITLE = "KING_OK"


def log(message: str) -> None:
    print(message, file=sys.stderr)


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# In-page measurement programs
# ---------------------------------------------------------------------------

VIEWPORT_JS = r"""
() => {
  const figures = [...document.querySelectorAll('figure[data-viz-code]')];
  let svgCount = 0;
  const stageOverflowIds = [];
  let maximumStageOverflowPx = 0;
  for (const figure of figures) {
    svgCount += figure.querySelectorAll('svg').length;
    const stage = figure.querySelector('.v12-stage, .pv-stage');
    if (!stage) continue;
    const overflow = Math.max(0, stage.scrollWidth - stage.clientWidth);
    if (overflow > 0) {
      stageOverflowIds.push(figure.dataset.vizCode);
      maximumStageOverflowPx = Math.max(maximumStageOverflowPx, overflow);
    }
  }
  const images = [...document.querySelectorAll('img')].filter((img) => img.getAttribute('src'));
  const refs = [...new Set(images.map((img) => img.getAttribute('src')))];
  const loadedHtmlImages = refs.filter((ref) => images.some((img) => img.getAttribute('src') === ref && img.complete && img.naturalWidth > 0)).length;
  const videos = [...document.querySelectorAll('video')];
  return {
    figureCount: figures.length,
    svgCount,
    pageOverflowPx: Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth),
    stageOverflowIds,
    stageOverflowCount: stageOverflowIds.length,
    maximumStageOverflowPx,
    documentHeightPx: document.documentElement.scrollHeight,
    media: {
      htmlImageRefs: refs.length,
      loadedHtmlImages,
      previewVideosReady: videos.filter((video) => video.readyState >= 2 && !video.error).length,
    },
  };
}
"""

LAYOUT_JS = r"""
() => {
  const SKIP = new Set(['defs', 'title', 'desc', 'style', 'metadata', 'clippath', 'mask', 'filter',
    'lineargradient', 'radialgradient', 'pattern', 'marker', 'stop', 'feflood', 'fegaussianblur',
    'femerge', 'femergenode', 'fedropshadow', 'fecolormatrix', 'feoffset', 'feblend']);
  const round6 = (value) => Math.round(value * 1e6) / 1e6;
  const figures = [...document.querySelectorAll('figure[data-viz-code]')];
  const diagnostics = { fullCanvasFigures: 0, figuresWithOverflow: 0, collisionFigures: 0, maxViewportEscapePx: 0 };
  const records = figures.map((figure) => {
    const svg = figure.querySelector('svg');
    const viewBox = svg.viewBox.baseVal;
    const width = Math.round(viewBox.width);
    const height = Math.round(viewBox.height);
    const svgRect = svg.getBoundingClientRect();
    const scaleX = viewBox.width / svgRect.width;
    const scaleY = viewBox.height / svgRect.height;
    const toUser = (rect) => ({
      x1: (rect.left - svgRect.left) * scaleX,
      y1: (rect.top - svgRect.top) * scaleY,
      x2: (rect.right - svgRect.left) * scaleX,
      y2: (rect.bottom - svgRect.top) * scaleY,
    });
    // The canvas frame is the rendered SVG viewport box; rendered content refines it.
    const union = { x1: 0, y1: 0, x2: viewBox.width, y2: viewBox.height };
    for (const el of svg.querySelectorAll('*')) {
      if (SKIP.has(el.tagName.toLowerCase())) continue;
      let ancestor = el.parentElement;
      let skipped = false;
      while (ancestor && ancestor !== svg) {
        if (SKIP.has(ancestor.tagName.toLowerCase())) { skipped = true; break; }
        ancestor = ancestor.parentElement;
      }
      if (skipped) continue;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) continue;
      const escapePx = Math.max(
        0, svgRect.left - rect.left, rect.right - svgRect.right,
        svgRect.top - rect.top, rect.bottom - svgRect.bottom,
      );
      if (escapePx > diagnostics.maxViewportEscapePx) diagnostics.maxViewportEscapePx = escapePx;
      const box = toUser(rect);
      union.x1 = Math.min(union.x1, box.x1); union.y1 = Math.min(union.y1, box.y1);
      union.x2 = Math.max(union.x2, box.x2); union.y2 = Math.max(union.y2, box.y2);
    }
    const contentBounds = {
      x1: Math.round(union.x1), y1: Math.round(union.y1),
      x2: Math.round(union.x2), y2: Math.round(union.y2),
    };
    const overflow = {
      top: Math.max(0, -contentBounds.y1),
      right: Math.max(0, contentBounds.x2 - width),
      bottom: Math.max(0, contentBounds.y2 - height),
      left: Math.max(0, -contentBounds.x1),
    };
    const blankRatios = {
      top: round6(Math.max(0, contentBounds.y1) / height),
      right: round6(Math.max(0, width - contentBounds.x2) / width),
      bottom: round6(Math.max(0, height - contentBounds.y2) / height),
      left: round6(Math.max(0, contentBounds.x1) / width),
    };
    if (contentBounds.x1 === 0 && contentBounds.y1 === 0 && contentBounds.x2 === width && contentBounds.y2 === height) {
      diagnostics.fullCanvasFigures += 1;
    }
    if (Object.values(overflow).some((value) => value > 0)) diagnostics.figuresWithOverflow += 1;
    const textRects = [...svg.querySelectorAll('text')]
      .map((text) => text.getBoundingClientRect())
      .filter((rect) => rect.width > 0 && rect.height > 0);
    const collisionSamples = [];
    let textCollisionCount = 0;
    for (let i = 0; i < textRects.length; i += 1) {
      for (let j = i + 1; j < textRects.length; j += 1) {
        const a = textRects[i];
        const b = textRects[j];
        const intersectionX = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        const intersectionY = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        const intersection = intersectionX * intersectionY;
        if (intersection <= 0) continue;
        const smaller = Math.min(a.width * a.height, b.width * b.height);
        if (intersection > 0.08 * smaller) {
          textCollisionCount += 1;
          if (collisionSamples.length < 5) {
            collisionSamples.push({
              intersectionRatio: Math.round((intersection / smaller) * 1e4) / 1e4,
              first: [Math.round(a.left), Math.round(a.top), Math.round(a.width), Math.round(a.height)],
              second: [Math.round(b.left), Math.round(b.top), Math.round(b.width), Math.round(b.height)],
            });
          }
        }
      }
    }
    if (textCollisionCount > 0) diagnostics.collisionFigures += 1;
    const sizes = [...svg.querySelectorAll('text')]
      .map((text) => Number.parseFloat(getComputedStyle(text).fontSize))
      .filter((value) => value > 0);
    return {
      id: figure.dataset.vizCode,
      viewBox: { width, height },
      renderedWidthPx: Math.round(svgRect.width),
      minimumTextSizePx: sizes.length > 0 ? Math.round(Math.min(...sizes)) : 0,
      contentBounds,
      overflow,
      blankRatios,
      textCollisionCount,
      collisionSamples,
    };
  });
  return { figures: records, diagnostics };
}
"""

CONTENT_JS = r"""
() => {
  const figures = [...document.querySelectorAll('figure[data-viz-code]')];
  const images = [...document.querySelectorAll('img')];
  const sourcedImages = images.filter((img) => img.getAttribute('src'));
  const refs = [...new Set(sourcedImages.map((img) => img.getAttribute('src')))];
  const loadedRefs = refs.filter((ref) => sourcedImages.some((img) => img.getAttribute('src') === ref && img.complete && img.naturalWidth > 0));
  const failedHtmlRefs = refs.filter((ref) => sourcedImages.every((img) => img.getAttribute('src') !== ref || (img.complete && img.naturalWidth === 0)));
  const svgImages = [...document.querySelectorAll('svg image')];
  const svgRefs = [...new Set(svgImages
    .map((image) => image.getAttribute('href') || image.getAttribute('xlink:href'))
    .filter(Boolean))];
  const resourceEntries = new Map(performance.getEntriesByType('resource').map((entry) => [entry.name, entry]));
  const failedSvgRefs = svgRefs.filter((ref) => {
    const entry = resourceEntries.get(new URL(ref, location.href).href);
    if (!entry) return true;
    return typeof entry.responseStatus === 'number' && entry.responseStatus >= 400;
  });
  const videos = [...document.querySelectorAll('video')];
  const claimHeading = document.getElementById('claim-evidence-boundary');
  let claimBoundaryRows = 0;
  if (claimHeading) {
    let sibling = claimHeading.nextElementSibling;
    for (let index = 0; sibling && index < 6; index += 1, sibling = sibling.nextElementSibling) {
      const table = sibling.tagName === 'TABLE' ? sibling : sibling.querySelector?.('table');
      if (table) { claimBoundaryRows = table.querySelectorAll('tbody tr').length; break; }
    }
  }
  const platV09 = document.querySelector('figure[data-viz-code="PLAT-V09"]');
  const platV09Entities = platV09
    ? [...platV09.querySelectorAll('.pv-node .v11-card-title')].map((node) => node.textContent.trim())
    : [];
  return {
    content: {
      figures: figures.length,
      svgs: figures.reduce((count, figure) => count + figure.querySelectorAll('svg').length, 0),
      htmlImageElements: images.length,
      htmlImageRefs: refs.length,
      svgImageElements: svgImages.length,
      svgImageRefs: svgRefs.length,
      evidenceLedgerRows: document.querySelectorAll('#evTable tbody tr').length,
      claimBoundaryRows,
      tables: document.querySelectorAll('table').length,
      preformattedBlocks: document.querySelectorAll('pre').length,
      details: document.querySelectorAll('details').length,
      openDetails: document.querySelectorAll('details[open]').length,
    },
    media: {
      failedHtmlImages: failedHtmlRefs.length,
      failedSvgImages: failedSvgRefs.length,
      htmlImageElements: images.length,
      htmlImageRefs: refs.length,
      loadedHtmlImages: loadedRefs.length,
      svgImageElements: svgImages.length,
      svgImageRefs: svgRefs.length,
      previewVideos: videos.length,
      previewVideosReady: videos.filter((video) => video.readyState >= 2 && !video.error).length,
      previewVideoErrors: videos.filter((video) => Boolean(video.error)).length,
    },
    semantics: {
      platV09PublicationNode: platV09 ? /publication/i.test(platV09.outerHTML) : false,
      platV09Entities,
    },
  };
}
"""

FIND_HOTSPOT_FIGURE_JS = r"""
() => {
  for (const figure of document.querySelectorAll('figure[data-viz-code]')) {
    const nodes = figure.querySelectorAll('.pv-node[data-detail]');
    if (figure.querySelector('.pv-inspector') && nodes.length >= 3) {
      return { code: figure.dataset.vizCode, nodeCount: nodes.length };
    }
  }
  return null;
}
"""

ACTIVATION_STATE_JS = r"""
(code) => {
  const figure = document.querySelector('figure[data-viz-code="' + code + '"]');
  const active = [...figure.querySelectorAll('.pv-node.is-active')];
  const inspector = figure.querySelector('.pv-inspector');
  return {
    activeCount: active.length,
    pressed: active.length === 1 ? active[0].getAttribute('aria-pressed') : null,
    activeDetail: active.length === 1 ? active[0].getAttribute('data-detail') : null,
    inspector: inspector ? inspector.textContent : null,
  };
}
"""

VISIBLE_EVIDENCE_ROWS_JS = r"""
() => [...document.querySelectorAll('#evTable tbody tr')].filter((row) => row.style.display !== 'none').length
"""

AUDIT_STATE_JS = r"""
() => ({
  auditMode: document.body.classList.contains('audit-mode'),
  openDetails: document.querySelectorAll('details[open]').length,
  label: document.getElementById('auditToggle').textContent.trim(),
})
"""

LIGHTBOX_STATE_JS = r"""
() => {
  const lightbox = document.getElementById('lb');
  const clicked = document.querySelector('figure.shot img');
  const lightboxImage = lightbox.querySelector('img');
  return {
    open: lightbox.open,
    srcMatch: Boolean(clicked) && lightboxImage.src === clicked.src,
    altMatch: Boolean(clicked) && lightbox.querySelector('p').textContent === clicked.alt,
  };
}
"""

MOTION_STATE_JS = r"""
() => ({
  paused: document.body.classList.contains('motion-paused'),
  aria: document.getElementById('motionToggle').getAttribute('aria-pressed'),
  label: document.getElementById('motionToggle').textContent.trim(),
  heroVideoPaused: document.querySelector('#v15HeroMediaSlot video')?.paused ?? null,
})
"""

HERO_MEDIA_JS = r"""
() => {
  const video = document.querySelector('#v15HeroMediaSlot video');
  if (!video) return { present: false };
  return {
    present: true,
    src: video.getAttribute('src'),
    muted: video.muted,
    paused: video.paused,
    readyState: video.readyState,
    loop: video.loop,
    autoplay: video.autoplay,
    relocatedMarker: Boolean(document.querySelector('.v15-video-relocated')),
  };
}
"""

INITIAL_STATE_JS = r"""
() => ({
  details: document.querySelectorAll('details').length,
  openDetails: document.querySelectorAll('details[open]').length,
})
"""


# ---------------------------------------------------------------------------
# Measurement helpers
# ---------------------------------------------------------------------------

def load_page(page, url: str, settle_ms: int = 2200, attempts: int = 3):
    last_error = None
    for _ in range(attempts):
        try:
            page.goto(url, wait_until="load", timeout=90000)
            page.wait_for_timeout(settle_ms)
            return
        except Exception as exc:  # noqa: BLE001 - transient network resets are retried
            last_error = exc
            page.wait_for_timeout(1500)
    raise last_error


def collect_console_errors(page) -> list:
    errors: list = []
    page.on("console", lambda msg: errors.append(msg.text) if msg.type == "error" else None)
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    return errors


def measure_viewport(browser, url: str, width: int, height: int) -> dict:
    page = browser.new_page(viewport={"width": width, "height": height})
    errors = collect_console_errors(page)
    try:
        load_page(page, url)
        data = page.evaluate(VIEWPORT_JS)
    finally:
        page.close()
    return {
        "width": width,
        "height": height,
        "figureCount": data["figureCount"],
        "svgCount": data["svgCount"],
        "pageOverflowPx": data["pageOverflowPx"],
        "consoleErrors": len(errors),
        "stageOverflowIds": data["stageOverflowIds"],
        "stageOverflowCount": data["stageOverflowCount"],
        "maximumStageOverflowPx": data["maximumStageOverflowPx"],
        "documentHeightPx": data["documentHeightPx"],
        "media": data["media"],
    }


def wait_for_title(page, expected: str, timeout_seconds: float = 45.0) -> str:
    deadline = time.monotonic() + timeout_seconds
    title = page.title()
    while title != expected and time.monotonic() < deadline:
        page.wait_for_timeout(400)
        title = page.title()
    return title


def measure_online_standard(browser) -> dict:
    page = browser.new_page(viewport={"width": 1280, "height": 720})
    console_errors: list = []
    resource_errors: list = []
    page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
    page.on("pageerror", lambda exc: console_errors.append(str(exc)))
    page.on("requestfailed", lambda req: resource_errors.append(f"{req.url} {req.failure}"))
    page.on("response", lambda res: resource_errors.append(f"HTTP {res.status} {res.url}") if res.status >= 400 else None)
    try:
        load_page(page, STANDARD_ONLINE_URL)
        title = wait_for_title(page, ONLINE_BOOT_TITLE)
        state = page.evaluate(
            "() => ({"
            " heroSelectContainer: Boolean(document.getElementById('hero-select')),"
            " heroCards: document.querySelectorAll('#hero-select .hs-card').length,"
            " canvasCount: document.querySelectorAll('canvas').length,"
            "})"
        )
        selection = {"hero": "亚瑟", "selected": None, "lockSelectionSucceeded": False,
                     "postLockHeroSelectContainer": None, "postLockTimer": None, "postLockCanvasCount": None}
        card = page.locator('#hero-select .hs-card[data-hero="arthur"]')
        if card.count() > 0:
            card.first.click()
            page.wait_for_timeout(350)
            selected = page.evaluate("() => document.querySelector('#hero-select .hs-card.sel')?.dataset.hero ?? null")
            lock_enabled = page.evaluate("() => !document.querySelector('#hero-select .hs-lock')?.classList.contains('disabled')")
            selection["selected"] = selected
            if selected == "arthur" and lock_enabled:
                page.locator('#hero-select .hs-lock').click()
                page.wait_for_timeout(3200)
                post_lock = page.evaluate(
                    "() => ({"
                    " heroSelectContainer: Boolean(document.getElementById('hero-select')),"
                    " timer: document.querySelector('#tb-time')?.textContent ?? null,"
                    " canvasCount: document.querySelectorAll('canvas').length,"
                    "})"
                )
                selection["lockSelectionSucceeded"] = post_lock["heroSelectContainer"] is False
                selection["postLockHeroSelectContainer"] = post_lock["heroSelectContainer"]
                selection["postLockTimer"] = post_lock["timer"]
                selection["postLockCanvasCount"] = post_lock["canvasCount"]
        result = {
            "url": STANDARD_ONLINE_URL,
            "title": title,
            "heroSelectContainer": state["heroSelectContainer"],
            "heroCards": state["heroCards"],
            "canvasCount": state["canvasCount"],
            "selectionTest": selection,
            "consoleErrors": len(console_errors),
            "resourceErrors": len(resource_errors),
        }
    finally:
        page.close()
    return result


def measure_online_demo(browser) -> dict:
    page = browser.new_page(viewport={"width": 1280, "height": 720})
    console_errors: list = []
    resource_errors: list = []
    page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
    page.on("pageerror", lambda exc: console_errors.append(str(exc)))
    page.on("requestfailed", lambda req: resource_errors.append(f"{req.url} {req.failure}"))
    page.on("response", lambda res: resource_errors.append(f"HTTP {res.status} {res.url}") if res.status >= 400 else None)
    try:
        load_page(page, DEMO_ONLINE_URL)
        title = wait_for_title(page, ONLINE_BOOT_TITLE)
        timer_before = page.evaluate("() => document.querySelector('#tb-time')?.textContent ?? null")
        page.wait_for_timeout(2500)
        state = page.evaluate(
            "() => ({"
            " heroSelectContainer: Boolean(document.getElementById('hero-select')),"
            " timer: document.querySelector('#tb-time')?.textContent ?? null,"
            " canvasCount: document.querySelectorAll('canvas').length,"
            "})"
        )
        def to_seconds(value):
            if not value or ":" not in value:
                return None
            minutes, seconds = value.split(":", 1)
            try:
                return int(minutes) * 60 + int(seconds)
            except ValueError:
                return None
        before = to_seconds(timer_before)
        after = to_seconds(state["timer"])
        timer_advanced = before is not None and after is not None and after > before
        result = {
            "url": DEMO_ONLINE_URL,
            "title": title,
            "heroSelectContainer": state["heroSelectContainer"],
            "timerBefore": timer_before,
            "timerAfter": state["timer"],
            "timerAdvanced": timer_advanced,
            "canvasCount": state["canvasCount"],
            "consoleErrors": len(console_errors),
            "resourceErrors": len(resource_errors),
        }
    finally:
        page.close()
    return result


def run_interaction_suite(browser, url: str) -> dict:
    width, height = INTERACTION_VIEWPORT
    page = browser.new_page(viewport={"width": width, "height": height})
    errors = collect_console_errors(page)
    try:
        load_page(page, url, settle_ms=2500)
        initial = page.evaluate(INITIAL_STATE_JS)
        interactions = {
            "hotspotClick": False,
            "keyboardEnter": False,
            "keyboardSpace": False,
            "inspectorUpdated": False,
            "searchFilter": False,
            "auditToggle": False,
            "lightbox": False,
            "motionToggle": False,
            "auditDefaultOpen": initial["details"] == initial["openDetails"] and initial["details"] > 0,
            "heroMedia": False,
        }

        # --- hotspot click / keyboard activation on real .pv-node controls ---
        hotspot = page.evaluate(FIND_HOTSPOT_FIGURE_JS)
        if hotspot:
            code = hotspot["code"]
            nodes = page.locator(f'[data-viz-code="{code}"] .pv-node[data-detail]')
            inspector_matches = []
            details = [nodes.nth(index).get_attribute("data-detail") for index in range(3)]
            nodes.nth(0).click()
            page.wait_for_timeout(250)
            state = page.evaluate(ACTIVATION_STATE_JS, code)
            interactions["hotspotClick"] = (
                state["activeCount"] == 1 and state["pressed"] == "true" and state["inspector"] == details[0]
            )
            inspector_matches.append(state["activeCount"] == 1 and state["inspector"] == details[0])

            nodes.nth(1).focus()
            page.keyboard.press("Enter")
            page.wait_for_timeout(250)
            state = page.evaluate(ACTIVATION_STATE_JS, code)
            interactions["keyboardEnter"] = (
                state["activeCount"] == 1 and state["pressed"] == "true" and state["inspector"] == details[1]
            )
            inspector_matches.append(state["activeCount"] == 1 and state["inspector"] == details[1])

            nodes.nth(2).focus()
            page.keyboard.press(" ")
            page.wait_for_timeout(250)
            state = page.evaluate(ACTIVATION_STATE_JS, code)
            interactions["keyboardSpace"] = (
                state["activeCount"] == 1 and state["pressed"] == "true" and state["inspector"] == details[2]
            )
            inspector_matches.append(state["activeCount"] == 1 and state["inspector"] == details[2])
            interactions["inspectorUpdated"] = all(inspector_matches)

        # --- evidence search filter ---
        search = page.locator("#evSearch")
        search.scroll_into_view_if_needed()
        search.fill("zzz_no_such_evidence_xyz")
        page.wait_for_timeout(300)
        bogus_visible = page.evaluate(VISIBLE_EVIDENCE_ROWS_JS)
        search.fill("E01")
        page.wait_for_timeout(300)
        match_visible = page.evaluate(VISIBLE_EVIDENCE_ROWS_JS)
        search.fill("")
        page.wait_for_timeout(300)
        cleared_visible = page.evaluate(VISIBLE_EVIDENCE_ROWS_JS)
        interactions["searchFilter"] = bogus_visible == 0 and 0 < match_visible < 42 and cleared_visible == 42

        # --- lightbox on a real figure.shot image ---
        shot = page.locator("figure.shot img").first
        shot.scroll_into_view_if_needed()
        shot.click()
        page.wait_for_timeout(300)
        lightbox_state = page.evaluate(LIGHTBOX_STATE_JS)
        page.locator("#lb").click(position={"x": 6, "y": 6})
        page.wait_for_timeout(300)
        lightbox_closed = page.evaluate("() => document.getElementById('lb').open === false")
        interactions["lightbox"] = (
            lightbox_state["open"] and lightbox_state["srcMatch"] and lightbox_state["altMatch"] and lightbox_closed
        )

        # --- motion toggle (topbar) ---
        page.evaluate("() => window.scrollTo(0, 0)")
        page.wait_for_timeout(900)
        page.locator("#motionToggle").click()
        page.wait_for_timeout(400)
        paused_state = page.evaluate(MOTION_STATE_JS)
        page.locator("#motionToggle").click()
        page.wait_for_timeout(1200)
        resumed_state = page.evaluate(MOTION_STATE_JS)
        interactions["motionToggle"] = (
            paused_state["paused"] is True
            and paused_state["aria"] == "true"
            and "继续" in paused_state["label"]
            and paused_state["heroVideoPaused"] is True
            and resumed_state["paused"] is False
            and resumed_state["aria"] == "false"
            and "暂停" in resumed_state["label"]
            and resumed_state["heroVideoPaused"] is False
        )

        # --- audit toggle (rail, only visible at >=1600px wide) ---
        page.locator("#auditToggle").click()
        page.wait_for_timeout(350)
        closed_state = page.evaluate(AUDIT_STATE_JS)
        page.locator("#auditToggle").click()
        page.wait_for_timeout(350)
        reopened_state = page.evaluate(AUDIT_STATE_JS)
        interactions["auditToggle"] = (
            closed_state["auditMode"] is False
            and closed_state["openDetails"] == 0
            and "展开" in closed_state["label"]
            and reopened_state["auditMode"] is True
            and reopened_state["openDetails"] == initial["details"]
            and "收起" in reopened_state["label"]
        )

        # --- hero media (cloud-demo video relocated into the hero monitor slot) ---
        page.evaluate("() => window.scrollTo(0, 0)")
        page.wait_for_timeout(1500)
        hero = page.evaluate(HERO_MEDIA_JS)
        interactions["heroMedia"] = (
            hero["present"] is True
            and hero["muted"] is True
            and hero["paused"] is False
            and hero["readyState"] >= 2
            and hero["relocatedMarker"] is True
            and "05-阿里云在线试玩-2x.mp4" in (hero["src"] or "")
        )
    finally:
        page.close()
    if errors:
        log(f"WARNING: interaction page console errors: {len(errors)}")
        for message in errors[:5]:
            log(f"  - {message[:200]}")
    return interactions


# ---------------------------------------------------------------------------
# Self-check mirroring scripts/update-king-browser-v14.mjs constraints
# ---------------------------------------------------------------------------

def self_check(measurement: dict, report_html: str) -> list:
    problems: list = []
    figure_ids = re.findall(r'data-viz-code="([A-Z]+-V\d{2})"', report_html)
    if len(figure_ids) != EXPECTED_FIGURE_COUNT or len(set(figure_ids)) != EXPECTED_FIGURE_COUNT:
        problems.append(f"report does not contain {EXPECTED_FIGURE_COUNT} unique figure ids")

    viewports = measurement["viewports"]
    if [(entry["width"], entry["height"]) for entry in viewports] != VIEWPORTS:
        problems.append("viewport set/order differs")
    for entry in viewports:
        label = f"{entry['width']}x{entry['height']}"
        if entry["figureCount"] != EXPECTED_FIGURE_COUNT or entry["svgCount"] != EXPECTED_FIGURE_COUNT:
            problems.append(f"{label} figure/svg count differs: {entry['figureCount']}/{entry['svgCount']}")
        if entry["pageOverflowPx"] != 0:
            problems.append(f"{label} page overflow {entry['pageOverflowPx']}px")
        if entry["consoleErrors"] != 0:
            problems.append(f"{label} console errors {entry['consoleErrors']}")
        if entry["width"] >= 1280 and entry["stageOverflowIds"]:
            problems.append(f"{label} stage overflow {entry['stageOverflowIds'][:3]}")

    primary = measurement["primaryLayout"]
    if (primary["width"], primary["height"]) != PRIMARY_VIEWPORT:
        problems.append("primary layout viewport differs")
    figures = primary["figures"]
    if [entry["id"] for entry in figures] != figure_ids:
        problems.append("primary layout figure order differs from report order")
    for entry in figures:
        bottom_limit = 0.15 if entry["viewBox"]["width"] >= 1400 else 0.12
        if any(value > 1 for value in entry["overflow"].values()):
            problems.append(f"{entry['id']} overflow >1 viewBox unit: {entry['overflow']}")
        if entry["textCollisionCount"] != 0:
            problems.append(f"{entry['id']} text collisions {entry['textCollisionCount']}")
        if entry["blankRatios"]["bottom"] > bottom_limit:
            problems.append(f"{entry['id']} bottom blank {entry['blankRatios']['bottom']} > {bottom_limit}")

    for field, value in measurement["interactions"].items():
        if value is not True:
            problems.append(f"interaction failed: {field}")

    media = measurement["media"]
    if media["failedHtmlImages"] != 0 or media["failedSvgImages"] != 0:
        problems.append(f"failed images: html={media['failedHtmlImages']} svg={media['failedSvgImages']}")
    if media["previewVideos"] != 5 or media["previewVideosReady"] != 5 or media["previewVideoErrors"] != 0:
        problems.append("preview video readiness differs")

    content = measurement["content"]
    if content["evidenceLedgerRows"] != 42 or content["claimBoundaryRows"] != 19:
        problems.append(f"ledger rows {content['evidenceLedgerRows']}/claim rows {content['claimBoundaryRows']}")
    if content["tables"] != 39 or content["preformattedBlocks"] != 25:
        problems.append(f"tables {content['tables']}/pre {content['preformattedBlocks']}")
    if content["details"] != content["openDetails"] or content["details"] <= 0:
        problems.append("audit corpus not fully expanded by default")

    semantics = measurement["semantics"]
    if semantics["platV09PublicationNode"] is not False:
        problems.append("PLAT-V09 still contains a publication node")
    if semantics["platV09Entities"] != ["sessions", "messages", "activities", "interaction_requests"]:
        problems.append(f"PLAT-V09 entities differ: {semantics['platV09Entities']}")

    online = measurement["onlineDeployment"]
    if online["classification"] != ONLINE_CLASSIFICATION:
        problems.append("online classification differs")
    standard = online["standard"]
    if standard["url"] != STANDARD_ONLINE_URL or standard["title"] != ONLINE_BOOT_TITLE:
        problems.append("standard online url/title differs")
    if standard["heroSelectContainer"] is not True or standard["heroCards"] != 5:
        problems.append("standard online hero selection differs")
    if standard["consoleErrors"] != 0 or standard["resourceErrors"] != 0:
        problems.append("standard online page has console/resource errors")
    demo = online["demo"]
    if demo["url"] != DEMO_ONLINE_URL or demo["title"] != ONLINE_BOOT_TITLE:
        problems.append("demo online url/title differs")
    if demo["heroSelectContainer"] is not False or demo["timerAdvanced"] is not True:
        problems.append("demo auto-start check failed")
    if demo["canvasCount"] < 2:
        problems.append("demo canvas count < 2")
    if demo["consoleErrors"] != 0 or demo["resourceErrors"] != 0:
        problems.append("demo online page has console/resource errors")
    return problems


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--html", type=Path, default=DEFAULT_HTML, help="case HTML path (default: repo case file)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="local HTTP base URL serving docs/case-studies")
    parser.add_argument("--output", type=Path, default=None, help="also write the measurement JSON to this path")
    args = parser.parse_args()

    html_path: Path = args.html
    if not html_path.is_file():
        log(f"ERROR: HTML file not found: {html_path}")
        return 2
    html_bytes = html_path.read_bytes()
    report_sha256 = sha256_bytes(html_bytes)
    report_size = len(html_bytes)

    url = f"{args.base_url.rstrip('/')}/{quote(html_path.name)}"
    try:
        request = Request(url, headers={"Cache-Control": "no-store"})
        with urlopen(request, timeout=15) as response:  # noqa: S310 - local measurement server
            served_bytes = response.read()
    except Exception as exc:  # noqa: BLE001
        log(f"ERROR: cannot fetch {url}: {exc}")
        log("Start the local server first, e.g.:")
        log("  cd docs/case-studies && ../../python-service/.venv/bin/python -m http.server 8123")
        return 2
    if served_bytes != html_bytes:
        log(f"ERROR: served copy of {html_path.name} differs from the repo file (stale server?). "
            f"served={len(served_bytes)}B sha256={sha256_bytes(served_bytes)[:16]}..., "
            f"file={report_size}B sha256={report_sha256[:16]}...")
        return 2
    log(f"HTML: {html_path.name} bytes={report_size} sha256={report_sha256}")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        browser_version = browser.version
        log(f"Browser: Chromium {browser_version} (headless, Playwright)")

        viewports = []
        for width, height in VIEWPORTS:
            entry = measure_viewport(browser, url, width, height)
            viewports.append(entry)
            log(f"Viewport {width}x{height}: figures={entry['figureCount']} svgs={entry['svgCount']} "
                f"pageOverflow={entry['pageOverflowPx']} consoleErrors={entry['consoleErrors']} "
                f"stageOverflow={entry['stageOverflowCount']} maxStageOverflowPx={entry['maximumStageOverflowPx']} "
                f"loadedHtmlImages={entry['media']['loadedHtmlImages']} videosReady={entry['media']['previewVideosReady']}")

        primary_width, primary_height = PRIMARY_VIEWPORT
        page = browser.new_page(viewport={"width": primary_width, "height": primary_height})
        primary_errors = collect_console_errors(page)
        load_page(page, url)
        layout = page.evaluate(LAYOUT_JS)
        content_and_media = page.evaluate(CONTENT_JS)
        page.close()
        figures = layout["figures"]
        diagnostics = layout["diagnostics"]
        log(f"Primary layout 1280x720: figures={len(figures)} fullCanvasBounds={diagnostics['fullCanvasFigures']} "
            f"figuresWithOverflow={diagnostics['figuresWithOverflow']} collisionFigures={diagnostics['collisionFigures']} "
            f"maxViewportEscapePx={diagnostics['maxViewportEscapePx']:.3f}, consoleErrors={len(primary_errors)}")
        rendered_widths = sorted({entry["renderedWidthPx"] for entry in figures})
        bottom_blanks = sorted({entry["blankRatios"]["bottom"] for entry in figures})
        minimum_text_sizes = sorted({entry["minimumTextSizePx"] for entry in figures})
        log(f"  renderedWidthPx values={rendered_widths} bottomBlankRatios={bottom_blanks} minimumTextSizes={minimum_text_sizes}")

        interactions = run_interaction_suite(browser, url)
        log(f"Interactions: {json.dumps(interactions, ensure_ascii=False)}")

        online_standard = measure_online_standard(browser)
        log(f"Online standard: title={online_standard['title']} heroSelect={online_standard['heroSelectContainer']} "
            f"heroCards={online_standard['heroCards']} consoleErrors={online_standard['consoleErrors']} "
            f"resourceErrors={online_standard['resourceErrors']} selectionTest={json.dumps(online_standard['selectionTest'], ensure_ascii=False)}")
        online_demo = measure_online_demo(browser)
        log(f"Online demo: title={online_demo['title']} heroSelect={online_demo['heroSelectContainer']} "
            f"timer={online_demo['timerBefore']}->{online_demo['timerAfter']} advanced={online_demo['timerAdvanced']} "
            f"canvasCount={online_demo['canvasCount']} consoleErrors={online_demo['consoleErrors']} "
            f"resourceErrors={online_demo['resourceErrors']}")
        browser.close()

    if html_path.read_bytes() != html_bytes:
        log("ERROR: case HTML changed while measuring; aborting without emitting evidence.")
        return 2

    measurement = {
        "measurementVersion": 1,
        "reportSha256": report_sha256,
        "observedAt": iso_now(),
        "browser": {
            "name": "Chromium",
            "version": browser_version,
            "surface": "Playwright local harness (python-service venv)",
            "measurement": "direct DOM geometry and interactive controls",
        },
        "content": content_and_media["content"],
        "viewports": viewports,
        "primaryLayout": {"width": primary_width, "height": primary_height, "figures": figures},
        "interactions": interactions,
        "media": content_and_media["media"],
        "semantics": content_and_media["semantics"],
        "onlineDeployment": {
            "classification": ONLINE_CLASSIFICATION,
            "observedAt": iso_now(),
            "standard": online_standard,
            "demo": online_demo,
        },
    }

    problems = self_check(measurement, html_bytes.decode("utf-8"))
    if problems:
        log("MEASUREMENT FAILED SELF-CHECK (evidence not emitted):")
        for problem in problems:
            log(f"  - {problem}")
        return 1

    payload = json.dumps(measurement, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
        log(f"Measurement written to {args.output}")
    print(payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())

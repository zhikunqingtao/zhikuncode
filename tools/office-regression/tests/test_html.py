# -*- coding: utf-8 -*-
"""HTML regression over a loopback server, driven through the real BrowserService.

The pages are served by an in-container loopback HTTP server (so the suite
never needs the outside network). Page loading and interaction go through the
real `services.browser_service.BrowserService`; keyboard operation, accessible
names and print emulation are supplemented test-side with Playwright on the
same page, and the offline scope (page loaded, then `set_offline(True)`) is
verified through the service as well.

Negative controls must hit the specific judgement codes:
  broken.html          -> HTML_RESOURCE_LOAD_FAILED
  keyboard-broken.html -> HTML_KEYBOARD_INOPERABLE
Never the other way around: the positive page must produce neither.
"""

import os

import r12lib as R12


def _evidence(r12, label, payload):
    R12.write_evidence(r12["evidence"], label, payload)


def test_html_app_positive_resources_and_accessible_names(r12, html_server, browser):
    sid, session = browser.open("html-app")
    try:
        nav = browser.navigate(sid, html_server.base_url() + "/app.html", wait_until="load")
        assert nav["status"] == 200, nav
        browser.run(session.page.wait_for_load_state("networkidle"))

        reasons, info = R12.audit_resources(browser.loop, session.page, html_server)
        assert reasons == [], "positive app.html must load every subresource: %s" % reasons

        # The resources really took effect in the rendered page.
        assert browser.run(session.page.evaluate(
            "() => document.getElementById('pixel').naturalWidth")) == 16
        assert browser.run(session.page.evaluate(
            "() => getComputedStyle(document.body).marginTop")) == "24px"
        assert browser.run(session.page.evaluate(
            "() => typeof window.__lastActivation")) == "string"

        # Accessible names, via the browser accessibility tree (test-side Playwright).
        assert browser.run(session.page.get_by_role("button", name="激活按钮").count()) == 1
        assert browser.run(session.page.get_by_label("姓名输入").count()) == 1
        aria = browser.run(session.page.locator("body").aria_snapshot())
        assert "激活按钮" in aria and "姓名输入" in aria

        # And the service itself can read the page back.
        extracted = browser.run(browser.service.extract_text(sid))
        assert "R-12 HTML 回归" in extracted["text"], extracted
        assert extracted["truncated"] is False

        browser.run(session.page.screenshot(
            path=os.path.join(r12["evidence"], "html-app-positive.png")))
        _evidence(r12, "html-app-resources-a11y", {
            "navigation": nav, "resource_audit": info, "aria_snapshot": aria,
            "extract_text_length": extracted["length"]})
    finally:
        browser.close(sid)


def test_html_app_positive_keyboard_operable(r12, html_server, browser):
    sid, session = browser.open("html-app-keyboard")
    try:
        nav = browser.navigate(sid, html_server.base_url() + "/app.html", wait_until="load")
        assert nav["status"] == 200, nav

        reasons, info = R12.keyboard_audit(browser.loop, session.page, "activate", "button")
        assert reasons == [], "positive app.html must be keyboard operable: %s" % info
        assert info["reached"] is True, info
        assert info["activation"] == "button", info

        result_text = browser.run(session.page.evaluate(
            "() => document.getElementById('result').textContent"))
        assert result_text == "activated:", result_text
        _evidence(r12, "html-app-keyboard", {"navigation": nav, "keyboard": info})
    finally:
        browser.close(sid)


def test_html_app_positive_print_and_offline_interactive(r12, html_server, browser):
    sid, session = browser.open("html-app-print-offline")
    try:
        nav = browser.navigate(sid, html_server.base_url() + "/app.html", wait_until="load")
        assert nav["status"] == 200, nav
        browser.run(session.page.wait_for_load_state("networkidle"))

        # ---- print emulation: the CSS print rules must swap the two blocks.
        screen_state = R12.print_state(browser.loop, session.page, "screen")
        assert screen_state["screen_only_visible"] is True, screen_state
        assert screen_state["print_only_visible"] is False, screen_state

        browser.run(session.page.emulate_media(media="print"))
        try:
            print_state = browser.run(session.page.evaluate(R12.PRINT_STATE_JS))
            browser.run(session.page.screenshot(
                path=os.path.join(r12["evidence"], "html-app-print.png"), full_page=True))
        finally:
            browser.run(session.page.emulate_media(media="screen"))
        assert print_state["print_only_visible"] is True, print_state
        assert print_state["screen_only_visible"] is False, print_state
        assert print_state["print_only_display"] == "block"

        # ---- offline scope: page loaded first, then the network is cut and the
        #      interactive flow must still work through the real service.
        requests_before = len(html_server.snapshot())
        browser.run(session.context.set_offline(True))
        try:
            assert R12.offline_fetch_probe(browser.loop, session.page) == "blocked", \
                "set_offline(True) did not take effect"
            typed = browser.run(browser.service.type_text(sid, "#name", "张三"))
            assert typed.get("success") is True, typed
            clicked = browser.run(browser.service.click(sid, "#activate"))
            assert clicked.get("clicked") == "#activate", clicked
            result_text = browser.run(session.page.evaluate(
                "() => document.getElementById('result').textContent"))
            assert result_text == "activated:张三", result_text
        finally:
            browser.run(session.context.set_offline(False))
        assert len(html_server.snapshot()) == requests_before, \
            "offline interaction must not reach the network"

        _evidence(r12, "html-app-print-offline", {
            "navigation": nav, "screen_state": screen_state, "print_state": print_state,
            "offline_result": result_text, "requests_before": requests_before})
    finally:
        browser.close(sid)


def test_html_negative_broken_resources_hit_reason_code(r12, html_server, browser):
    sid, session = browser.open("html-broken")
    try:
        nav = browser.navigate(sid, html_server.base_url() + "/broken.html", wait_until="load")
        assert nav["status"] == 200, nav  # the document itself loads; only its resources fail
        browser.run(session.page.wait_for_load_state("networkidle"))
        assert browser.run(session.page.title()) == "R-12 HTML 回归夹具（资源失效反例）"

        reasons, info = R12.audit_resources(browser.loop, session.page, html_server)
        codes = {reason["code"] for reason in reasons}
        assert codes == {"HTML_RESOURCE_LOAD_FAILED"}, (
            "broken.html must hit exactly HTML_RESOURCE_LOAD_FAILED, got %s (reasons=%s)"
            % (sorted(codes), reasons))
        failed_names = sorted(os.path.basename(url) for url, _status in info["failed"])
        assert failed_names == ["missing.css", "missing.js", "missing.png"], info["failed"]
        assert {status for _url, status in info["failed"]} == {404}, info["failed"]

        # user-visible: the missing image really failed to render
        assert browser.run(session.page.evaluate(
            "() => document.getElementById('missing-image').naturalWidth")) == 0
        _evidence(r12, "html-broken-resources", {
            "navigation": nav, "resource_audit": info,
            "reason_codes": sorted(codes)})
    finally:
        browser.close(sid)


def test_html_negative_keyboard_inoperable_hit_reason_code(r12, html_server, browser):
    sid, session = browser.open("html-keyboard-broken")
    try:
        nav = browser.navigate(sid, html_server.base_url() + "/keyboard-broken.html", wait_until="load")
        assert nav["status"] == 200, nav

        assert browser.run(session.page.get_by_role("button", name="仅鼠标按钮").count()) == 1
        reasons, info = R12.keyboard_audit(browser.loop, session.page, "mouse-only", "mouse")
        codes = {reason["code"] for reason in reasons}
        assert codes == {"HTML_KEYBOARD_INOPERABLE"}, (
            "keyboard-broken.html must hit exactly HTML_KEYBOARD_INOPERABLE, got %s (reasons=%s)"
            % (sorted(codes), reasons))
        assert info["reached"] is False, info

        # The page and its JS are alive: a mouse click activates the same element,
        # so the defect is keyboard-only and correctly attributed.
        browser.run(session.page.click("#mouse-only"))
        assert browser.run(session.page.evaluate("() => window.__lastActivation")) == "mouse"
        _evidence(r12, "html-keyboard-broken", {
            "navigation": nav, "keyboard": info,
            "reason_codes": sorted(codes)})
    finally:
        browser.close(sid)
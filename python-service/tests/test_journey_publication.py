"""Regression tests for the browser path used by publication verification."""
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from routers import browser, journey
from services.browser_service import BrowserService
from services.journey_models import JourneyRunRequest


@pytest.mark.asyncio
async def test_relative_navigation_uses_requested_server(monkeypatch):
    page = SimpleNamespace(url="http://127.0.0.1:8765/", screenshot=AsyncMock(return_value=b"jpeg"))
    session = SimpleNamespace(page=page, _js_errors=[], _record_opts={})
    service = SimpleNamespace(
        default_timeout=30000,
        _create_context_for_journey=AsyncMock(return_value=session),
        navigate=AsyncMock(return_value={"status": 200}),
    )
    monkeypatch.setattr(browser, "browser_service", service)
    result = await journey.journey_run(JourneyRunRequest(
        session_id="relative", base_url="http://127.0.0.1:8765",
        steps=[{"action": "navigate", "url": "/"}],
    ))
    assert result.passed
    service.navigate.assert_awaited_once_with("relative", "http://127.0.0.1:8765/", timeout=30000)


@pytest.mark.asyncio
async def test_wait_for_can_match_hidden_loading_element():
    service = SimpleNamespace(wait_for=AsyncMock(return_value={"success": True}))
    result = await journey._execute_step(service, "s", None,
        {"action": "wait_for", "selector": "#loading.done", "state": "attached"}, 60000)
    assert result["success"]
    service.wait_for.assert_awaited_once_with("s", wait_until=None,
        selector="#loading.done", state="attached", timeout=60000)


@pytest.mark.asyncio
async def test_console_errors_fail_journey_assertion():
    service = BrowserService()
    page = MagicMock()
    context = SimpleNamespace(new_page=AsyncMock(return_value=page), add_init_script=AsyncMock())
    service._browser = SimpleNamespace(new_context=AsyncMock(return_value=context))
    session = await service._create_context_for_journey("console", {}, {"width": 800, "height": 600})
    listeners = {call.args[0]: call.args[1] for call in page.on.call_args_list}
    listeners["console"](SimpleNamespace(type="error", text="model failed to load"))
    result = await journey._execute_step(service, "console", session, {"action": "assert_no_console_error"}, 30000)
    assert not result["success"]
    assert "model failed to load" in result["error"]


@pytest.mark.asyncio
async def test_timed_out_click_is_not_retried():
    # The handler already ran before Playwright timed out waiting for completion.
    state = {"on": False}
    async def click(*args, **kwargs):
        state["on"] = not state["on"]
        raise TimeoutError("click completion timed out")
    page = SimpleNamespace(click=AsyncMock(side_effect=click))
    service = SimpleNamespace(click=AsyncMock())
    with pytest.raises(TimeoutError):
        await journey._execute_step(service, "s", SimpleNamespace(page=page),
                                    {"action": "click", "selector": "#toggle"}, 30000)
    assert state["on"]
    assert page.click.await_count == 1
    service.click.assert_not_awaited()

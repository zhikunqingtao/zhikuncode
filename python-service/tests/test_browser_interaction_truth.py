"""Interaction failures must not become successful HTTP tool responses."""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from routers import browser


@pytest.mark.asyncio
@pytest.mark.parametrize("action,data,expected_code,expected_message", [
    ("click", {"clicked": False, "method": "both_failed", "error": "both clicks failed"},
     "BROWSER_CLICK_FAILED", "both clicks failed"),
    ("click", {"success": False, "method": "playwright", "error": "click failed"},
     "BROWSER_CLICK_FAILED", "click failed"),
    ("click", {"success": True, "clicked": False, "error": "no click"},
     "BROWSER_CLICK_FAILED", "no click"),
    ("type", {"success": False, "method": "both_failed", "error": "both fills failed"},
     "BROWSER_TYPE_FAILED", "both fills failed"),
    ("click", {"success": False, "error_code": "SESSION_NOT_FOUND", "error_message": "gone"},
     "SESSION_NOT_FOUND", "gone"),
    ("type", {"success": False, "error_code": "SESSION_NOT_FOUND", "error_message": "gone"},
     "SESSION_NOT_FOUND", "gone"),
    ("type", {"success": False, "error_code": "CUSTOM", "error": "original error"},
     "CUSTOM", "original error"),
])
async def test_explicit_failure_retains_details_and_never_retries(
    monkeypatch, action, data, expected_code, expected_message,
):
    operation = AsyncMock(return_value=data)
    service = SimpleNamespace(**{"click" if action == "click" else "type_text": operation})
    monkeypatch.setattr(browser, "browser_service", service)
    app = FastAPI()
    app.include_router(browser.router, prefix="/api/browser")
    payload = {"session_id": "test", "selector": "#target", "strict_session": True}
    if action == "type":
        payload["text"] = "value"
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(f"/api/browser/{action}", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is False
    assert body["error_code"] == expected_code
    assert body["error_message"] == expected_message
    assert body["data"] == data
    operation.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("action,method", [
    ("click", "playwright"), ("click", "js_fallback"),
    ("type", "playwright"), ("type", "js_fallback"),
])
async def test_successful_native_and_fallback_results_remain_unchanged(monkeypatch, action, method):
    data = {"method": method}
    if action == "click":
        data["clicked"] = "#target"  # Existing successful click has no success field.
    else:
        data.update(success=True, filled="#target")
    if method == "js_fallback":
        data["warning"] = "Native action failed; JavaScript was used"
    operation = AsyncMock(return_value=data)
    monkeypatch.setattr(browser, "browser_service", SimpleNamespace(
        **{"click" if action == "click" else "type_text": operation},
    ))
    app = FastAPI()
    app.include_router(browser.router, prefix="/api/browser")
    payload = {"selector": "#target", "text": "value"}
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(f"/api/browser/{action}", json=payload)
    body = response.json()
    assert body["success"] is True
    assert body["data"] == data
    assert body["error_code"] is None
    operation.assert_awaited_once()

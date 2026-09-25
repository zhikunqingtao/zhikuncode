"""Real ASGI/middleware regressions; only the browser driver is replaced."""
import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from httpx import ASGITransport, AsyncClient

import main
from routers import browser, journey
from services.browser_service import BrowserService


@pytest.fixture
def production(monkeypatch):
    service = BrowserService()
    service.cleanup_timeout = 0.05
    page = Mock(url="http://local/", screenshot=AsyncMock(return_value=b"jpeg"),
                add_init_script=AsyncMock())
    context = Mock(new_page=AsyncMock(return_value=page), add_init_script=AsyncMock(), close=AsyncMock())
    service._browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
    service._playwright = SimpleNamespace(stop=AsyncMock())
    monkeypatch.setattr(browser, "browser_service", service)
    # Lifespan normally registers the capability router and launches Playwright.
    monkeypatch.setattr(main.app.router, "routes", list(main.app.router.routes))
    main.app.include_router(journey.router, prefix="/api/browser")
    main.app.include_router(browser.router, prefix="/api/browser")
    return main.app, service, page


BODY = {"session_id": "http-journey", "base_url": "http://local/", "steps": [{"action": "screenshot"}]}


@pytest.mark.asyncio
@pytest.mark.parametrize("state,status,code", [
    ("normal", 200, None),
    ("capacity", 503, "BROWSER_CAPACITY_REACHED"),
    ("duplicate", 409, "BROWSER_SESSION_CONFLICT"),
    ("stopped", 503, "BROWSER_NOT_RUNNING"),
])
async def test_requests_finish_with_production_middleware(production, state, status, code):
    app, service, page = production
    if state == "capacity":
        service.max_sessions = 1
        await service.get_or_create_session("existing")
    elif state == "duplicate":
        await service.get_or_create_session(BODY["session_id"])
    elif state == "stopped":
        service._stopping = True
    try:
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            # A full-capacity response used to hang in the real Request's watcher.
            response = await asyncio.wait_for(client.post(
                "/api/browser/journey/run", json=BODY, headers={"X-Request-Id": "journey-regression"}), 1)
        assert response.status_code == status
        assert response.headers["X-Request-Id"] == "journey-regression"
        if code:
            assert response.json()["detail"] == code
            page.screenshot.assert_not_awaited()
        else:
            assert response.json()["passed"]
        assert not [task for task in asyncio.all_tasks()
                    if task.get_coro().__qualname__ == "_wait_for_disconnect"]
    finally:
        await service.shutdown()


@pytest.mark.asyncio
async def test_disconnect_reaches_journey_through_production_middleware(production, monkeypatch):
    app, service, page = production
    started = asyncio.Event()

    async def screenshot(**kwargs):
        started.set()
        await asyncio.Event().wait()

    page.screenshot.side_effect = screenshot
    monkeypatch.setattr(journey, "JOURNEY_EXECUTION_TIMEOUT_SECONDS", 0.5)
    monkeypatch.setattr(journey, "DISCONNECT_POLL_SECONDS", 0.01)
    body = json.dumps(BODY).encode()
    delivered = False
    sent = []

    async def receive():
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}
        await started.wait()
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
             "method": "POST", "scheme": "http", "path": "/api/browser/journey/run",
             "raw_path": b"/api/browser/journey/run", "query_string": b"", "root_path": "",
             "headers": [(b"content-type", b"application/json")],
             "server": ("test", 80), "client": ("test", 1)}
    try:
        await asyncio.wait_for(app(scope, receive, send), 1)
        assert next(m["status"] for m in sent if m["type"] == "http.response.start") == 499
        assert b"JOURNEY_CLIENT_DISCONNECTED" in b"".join(m.get("body", b"") for m in sent)
        assert not service._sessions
        assert not service._creating
    finally:
        await service.shutdown()


@pytest.mark.asyncio
async def test_maintenance_endpoint_refuses_to_interrupt_healthy_session(production):
    app, service, _ = production
    await service.get_or_create_session("healthy")
    try:
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            response = await client.post("/api/browser/recover_failed_cleanup")
        assert response.json()["error_code"] == "BROWSER_RECOVERY_NOT_READY"
        assert "healthy" in service._sessions
        service._browser.close.assert_not_awaited()
    finally:
        await service.shutdown()

"""Journey ownership, deadline and cancellation contracts (no real browser)."""
import asyncio
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import HTTPException

from routers import browser, journey
from services.journey_models import JourneyRunRequest
from services.browser_service import BrowserService


@pytest.fixture
def owned_service(monkeypatch):
    session = SimpleNamespace(
        page=SimpleNamespace(url="http://local/", screenshot=AsyncMock(return_value=b"jpeg")),
        _js_errors=[], _record_opts={},
    )
    service = SimpleNamespace(
        default_timeout=30000,
        _create_context_for_journey=AsyncMock(return_value=session),
        close_session=AsyncMock(return_value=True),
        release_session=AsyncMock(),
    )
    monkeypatch.setattr(browser, "browser_service", service)
    return service, session


def request(**kwargs):
    return JourneyRunRequest(session_id="rv-owned", base_url="http://local/",
                             steps=[{"action": "screenshot"}], **kwargs)


@pytest.fixture
def registered_service(monkeypatch):
    """Real lifecycle implementation with only the Playwright I/O mocked."""
    service = BrowserService()
    page = MagicMock()
    page.url = "http://local/"
    page.screenshot = AsyncMock(return_value=b"jpeg")
    page.title = AsyncMock(return_value="Failure page")
    page.evaluate = AsyncMock(return_value={"nodeCount": 1, "interactive": []})
    page.locator.return_value.first.aria_snapshot = AsyncMock(return_value='- button "Retry"')
    context = SimpleNamespace(new_page=AsyncMock(return_value=page),
                              add_init_script=AsyncMock(), close=AsyncMock())
    service._browser = SimpleNamespace(new_context=AsyncMock(return_value=context))
    monkeypatch.setattr(browser, "browser_service", service)
    return service, context, page


@pytest.mark.asyncio
@pytest.mark.parametrize("fails", [False, True])
async def test_completed_result_keeps_context_for_java_snapshot(owned_service, fails):
    service, session = owned_service
    req = request()
    if fails:
        req.steps = [{"action": "assert_url", "contains": "missing"}]
    result = await journey.journey_run(req)
    assert result.passed is not fails
    assert result.step_results[0].screenshot_base64 is not None
    service.close_session.assert_not_awaited()
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_expired_request_never_creates_a_context(owned_service):
    service, _ = owned_service
    with pytest.raises(HTTPException) as error:
        await journey.journey_run(request(deadline_epoch_ms=int(time.time() * 1000) - 1))
    assert error.value.status_code == 504
    assert error.value.detail == "JOURNEY_DEADLINE_EXCEEDED"
    service._create_context_for_journey.assert_not_awaited()
    service.close_session.assert_not_awaited()


@pytest.mark.asyncio
async def test_total_deadline_includes_screenshot_and_cleans_owned_context(owned_service, monkeypatch):
    service, session = owned_service
    cancelled = asyncio.Event()
    async def screenshot(**kwargs):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()
    session.page.screenshot.side_effect = screenshot
    monkeypatch.setattr(journey, "JOURNEY_EXECUTION_TIMEOUT_SECONDS", 0.02)
    with pytest.raises(HTTPException) as error:
        await journey.journey_run(request())
    assert error.value.status_code == 504
    assert cancelled.is_set()
    service.close_session.assert_awaited_once_with("rv-owned", expected_session=session)
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_creation_is_cancelled_and_joined_before_timeout_returns(owned_service, monkeypatch):
    service, _ = owned_service
    cleaned = asyncio.Event()
    async def create(*args):
        try:
            await asyncio.Event().wait()
        finally:
            cleaned.set()  # BrowserService owns partial creation cleanup.
    service._create_context_for_journey.side_effect = create
    monkeypatch.setattr(journey, "JOURNEY_EXECUTION_TIMEOUT_SECONDS", 0.02)
    with pytest.raises(HTTPException):
        await journey.journey_run(request())
    assert cleaned.is_set()
    service.close_session.assert_not_awaited()
    service.release_session.assert_not_awaited()


@pytest.mark.asyncio
async def test_route_cancellation_is_preserved_after_resource_cleanup(owned_service):
    service, session = owned_service
    started = asyncio.Event()
    async def screenshot(**kwargs):
        started.set()
        await asyncio.Event().wait()
    session.page.screenshot.side_effect = screenshot
    task = asyncio.create_task(journey.journey_run(request()))
    await asyncio.wait_for(started.wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    service.close_session.assert_awaited_once_with("rv-owned", expected_session=session)
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_disconnect_cancels_work_and_closes_context(owned_service):
    service, session = owned_service
    started = asyncio.Event()
    async def screenshot(**kwargs):
        started.set()
        await asyncio.Event().wait()
    async def disconnected():
        await started.wait()
        return True
    session.page.screenshot.side_effect = screenshot
    http_request = SimpleNamespace(is_disconnected=disconnected)
    with pytest.raises(HTTPException) as error:
        await journey.journey_run(request(), http_request)
    assert error.value.status_code == 499
    service.close_session.assert_awaited_once_with("rv-owned", expected_session=session)
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_duplicate_creation_failure_does_not_close_other_owner(owned_service):
    service, _ = owned_service
    service._create_context_for_journey.side_effect = RuntimeError("resource ID already exists")
    with pytest.raises(RuntimeError, match="already exists"):
        await journey.journey_run(request())
    service.close_session.assert_not_awaited()
    service.release_session.assert_not_awaited()


@pytest.mark.asyncio
async def test_unexpected_post_step_failure_closes_context(owned_service, monkeypatch):
    service, session = owned_service
    monkeypatch.setattr(journey, "_execute_journey", AsyncMock(side_effect=RuntimeError("bad response")))
    with pytest.raises(RuntimeError, match="bad response"):
        await journey.journey_run(request())
    service.close_session.assert_awaited_once_with("rv-owned", expected_session=session)
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_cleanup_failure_does_not_replace_cancellation(owned_service, caplog):
    service, session = owned_service
    started = asyncio.Event()
    async def screenshot(**kwargs):
        started.set()
        await asyncio.Event().wait()
    session.page.screenshot.side_effect = screenshot
    service.close_session.side_effect = RuntimeError("context close failed")
    task = asyncio.create_task(journey.journey_run(request()))
    await asyncio.wait_for(started.wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert "Journey cleanup unconfirmed" in caplog.text
    service.release_session.assert_awaited_once_with("rv-owned", session)


@pytest.mark.asyncio
async def test_real_registration_preserves_failure_snapshot_until_explicit_close(registered_service):
    service, context, _ = registered_service
    req = request()
    req.steps = [{"action": "assert_url", "contains": "missing"}]
    result = await journey.journey_run(req)
    assert not result.passed
    assert service._sessions["rv-owned"].owner_task is None
    snapshot = await service.snapshot_semantic("rv-owned", strict_session=True)
    assert snapshot["title"] == "Failure page"
    assert "Retry" in snapshot["tree"]["aria"]
    context.close.assert_not_awaited()
    assert await service.close_session("rv-owned")
    missing = await service.snapshot_semantic("rv-owned", strict_session=True)
    assert missing["error_code"] == "SESSION_NOT_FOUND"
    service._browser.new_context.assert_awaited_once()


@pytest.mark.asyncio
async def test_external_close_cancels_active_journey_without_recreating_context(registered_service):
    service, context, page = registered_service
    started = asyncio.Event()
    async def click(*args, **kwargs):
        started.set()
        await asyncio.Event().wait()
    page.click = AsyncMock(side_effect=click)
    req = request()
    req.steps = [{"action": "click", "selector": "#button"}, {"action": "navigate", "url": "/late"}]
    task = asyncio.create_task(journey.journey_run(req))
    await asyncio.wait_for(started.wait(), 1)
    assert await service.close_session("rv-owned")
    with pytest.raises(HTTPException) as error:
        await asyncio.wait_for(task, 1)
    assert error.value.status_code == 409
    assert error.value.detail == "JOURNEY_CANCELLED"
    assert not service._sessions
    assert not service._creating
    context.close.assert_awaited_once()
    service._browser.new_context.assert_awaited_once()


@pytest.mark.asyncio
async def test_http_cleanup_join_is_bounded_and_retains_late_owner(owned_service, monkeypatch):
    service, session = owned_service
    service.cleanup_timeout = 0.01
    release = asyncio.Event()
    cleaned = asyncio.Event()

    async def screenshot(**kwargs):
        await asyncio.Event().wait()

    async def close(*args, **kwargs):
        await release.wait()
        cleaned.set()

    session.page.screenshot.side_effect = screenshot
    service.close_session.side_effect = close
    monkeypatch.setattr(journey, "JOURNEY_EXECUTION_TIMEOUT_SECONDS", 0.01)
    owner = asyncio.create_task(journey.journey_run(request()))
    try:
        done, _ = await asyncio.wait({owner}, timeout=2)
        assert done, "A stuck cleanup must not hold the HTTP handler indefinitely"
        with pytest.raises(HTTPException) as error:
            owner.result()
        assert error.value.status_code == 504
        retained = [task for task in journey._pending_cleanup_tasks if not task.done()]
        assert len(retained) == 1
        assert not cleaned.is_set()
        service.release_session.assert_not_awaited()
    finally:
        release.set()
        await asyncio.wait_for(asyncio.gather(owner, return_exceptions=True), 1)
        await asyncio.gather(*list(journey._pending_cleanup_tasks), return_exceptions=True)
    assert cleaned.is_set()
    assert not journey._pending_cleanup_tasks
    service.release_session.assert_awaited_once_with("rv-owned", session)

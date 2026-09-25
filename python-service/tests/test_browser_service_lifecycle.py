"""Browser ownership tests: mocked driver only, no browser or network access."""

import asyncio
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from playwright._impl._browser_context import BrowserContext as DriverContext
from playwright.async_api._context_manager import PlaywrightContextManager

from services.browser_service import BrowserService, BrowserSession


@pytest.fixture
def service():
    service = BrowserService()
    service.cleanup_timeout = 0.05
    page = SimpleNamespace(
        set_default_timeout=Mock(), add_init_script=AsyncMock(), on=Mock(),
    )
    context = SimpleNamespace(
        new_page=AsyncMock(return_value=page), close=AsyncMock(),
        add_init_script=AsyncMock(), tracing=SimpleNamespace(start=AsyncMock()),
    )
    # Driver objects are hashable; rollback tracking relies on their identity.
    context = Mock(**vars(context))
    service._browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
    service._playwright = SimpleNamespace(stop=AsyncMock())
    return service


async def create(service, kind, session_id="resource"):
    if kind == "journey":
        return await service._create_context_for_journey(session_id, {"trace": True}, {"width": 800, "height": 600})
    return await service.get_or_create_session(session_id)


@pytest.mark.asyncio
@pytest.mark.parametrize("kind,stage", [
    ("ordinary", "page"), ("ordinary", "script"),
    ("journey", "page"), ("journey", "script"), ("journey", "trace"),
])
async def test_creation_failure_closes_obtained_context(service, kind, stage):
    context = service._browser.new_context.return_value
    operation = {"page": context.new_page,
                 "script": context.add_init_script if kind == "journey" else context.new_page.return_value.add_init_script,
                 "trace": context.tracing.start}[stage]
    operation.side_effect = RuntimeError("driver failure")
    with pytest.raises(RuntimeError, match="driver failure"):
        await create(service, kind)
    context.close.assert_awaited_once()
    assert service._creating == service._sessions == service._unclosed_contexts == {}


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["ordinary", "journey"])
async def test_creation_cancel_closes_context_and_releases_capacity(service, kind):
    context = service._browser.new_context.return_value
    entered = asyncio.Event()

    async def stalled_page():
        entered.set()
        await asyncio.Event().wait()

    context.new_page.side_effect = stalled_page
    task = asyncio.create_task(create(service, kind))
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    context.close.assert_awaited_once()
    assert not service._sessions and not service._creating


@pytest.mark.asyncio
@pytest.mark.parametrize("first,second", [(a, b) for a in ("ordinary", "journey") for b in ("ordinary", "journey")])
async def test_pending_creations_share_one_atomic_capacity(service, first, second):
    service.max_sessions = 1
    context = service._browser.new_context.return_value
    page = context.new_page.return_value
    entered, release = asyncio.Event(), asyncio.Event()

    async def stalled_page():
        entered.set()
        await release.wait()
        return page

    context.new_page.side_effect = stalled_page
    task = asyncio.create_task(create(service, first, "first"))
    await entered.wait()
    with pytest.raises(RuntimeError, match="capacity"):
        await create(service, second, "second")
    assert service._browser.new_context.await_count == 1
    release.set()
    session = await task
    assert len(service._sessions) == 1 and not service._creating
    await service.release_session("first", session)
    await service.close_session("first")


@pytest.mark.asyncio
async def test_duplicate_journey_never_overwrites_existing_session(service):
    original = await create(service, "journey")
    with pytest.raises(RuntimeError, match="already exists"):
        await create(service, "journey")
    assert service._sessions["resource"] is original
    assert service._browser.new_context.await_count == 1
    original.context.close.assert_not_awaited()


@pytest.mark.asyncio
async def test_duplicate_pending_id_is_rejected(service):
    context = service._browser.new_context.return_value
    entered = asyncio.Event()

    async def pending():
        entered.set()
        await asyncio.Event().wait()

    context.new_page.side_effect = pending
    task = asyncio.create_task(create(service, "ordinary"))
    await entered.wait()
    with pytest.raises(RuntimeError, match="being created"):
        await create(service, "journey")
    await service.close_session("resource")
    with pytest.raises(asyncio.CancelledError):
        await task


@pytest.mark.asyncio
async def test_concurrent_ordinary_callers_share_one_creation(service):
    service.max_sessions = 1
    context = service._browser.new_context.return_value
    page = context.new_page.return_value
    entered, release = asyncio.Event(), asyncio.Event()

    async def pending():
        entered.set()
        await release.wait()
        return page

    context.new_page.side_effect = pending
    owner = asyncio.create_task(create(service, "ordinary"))
    await entered.wait()
    waiter = asyncio.create_task(create(service, "ordinary"))
    cancelled_waiter = asyncio.create_task(create(service, "ordinary"))
    await asyncio.sleep(0)
    cancelled_waiter.cancel()
    with pytest.raises(asyncio.CancelledError):
        await cancelled_waiter
    assert not owner.done() and not waiter.done()
    release.set()
    first, second = await asyncio.gather(owner, waiter)
    assert first is second is service._sessions["resource"]
    assert service._browser.new_context.await_count == 1
    assert not service._creating


@pytest.mark.asyncio
@pytest.mark.parametrize("close", [False, True])
async def test_waiting_ordinary_caller_shares_failure_without_recreating(service, close):
    context = service._browser.new_context.return_value
    entered, release = asyncio.Event(), asyncio.Event()
    failure = RuntimeError("creation failed")

    async def pending():
        entered.set()
        await release.wait()
        raise failure

    context.new_page.side_effect = pending
    owner = asyncio.create_task(create(service, "ordinary"))
    await entered.wait()
    waiter = asyncio.create_task(create(service, "ordinary"))
    await asyncio.sleep(0)
    if close:
        assert await service.close_session("resource")
    else:
        release.set()
    results = await asyncio.gather(owner, waiter, return_exceptions=True)
    if close:
        assert all(isinstance(result, asyncio.CancelledError) for result in results)
    else:
        assert results == [failure, failure]
    context.close.assert_awaited_once()
    assert service._browser.new_context.await_count == 1
    assert not service._sessions and not service._creating


@pytest.mark.asyncio
async def test_close_during_creation_prevents_late_registration_even_if_driver_swallows_cancel(service):
    context = service._browser.new_context.return_value
    page = context.new_page.return_value
    entered = asyncio.Event()

    async def swallow_cancel():
        entered.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            return page

    context.new_page.side_effect = swallow_cancel
    task = asyncio.create_task(create(service, "journey"))
    await entered.wait()
    assert await service.close_session("resource")
    with pytest.raises(asyncio.CancelledError):
        await task
    context.close.assert_awaited_once()
    assert not service._sessions and not service._creating


@pytest.mark.asyncio
async def test_owner_checked_close_cannot_close_another_session(service):
    session = await create(service, "ordinary")
    assert not await service.close_session("resource", expected_session=object())
    assert service._sessions["resource"] is session
    session.context.close.assert_not_awaited()


@pytest.mark.asyncio
async def test_expired_journey_is_protected_until_lease_release(service):
    session = await create(service, "journey")
    session.last_activity = datetime.now() - timedelta(days=1)
    await service._cleanup_expired_sessions()
    session.context.close.assert_not_awaited()
    await service.release_session("resource", session)
    assert not session.is_expired(service.idle_timeout)
    session.last_activity = datetime.now() - timedelta(days=1)
    await service._cleanup_expired_sessions()
    session.context.close.assert_awaited_once()
    assert not service._sessions


@pytest.mark.asyncio
async def test_expired_cleanup_failure_does_not_block_later_sessions_or_orphan_contexts(service):
    first = await create(service, "ordinary", "first")
    first.context.close.side_effect = RuntimeError("first close failed")
    second_context = Mock(close=AsyncMock(), new_page=AsyncMock(return_value=first.page))
    service._browser.new_context.return_value = second_context
    second = await create(service, "ordinary", "second")
    first.last_activity = second.last_activity = datetime.now() - timedelta(days=1)
    orphan = Mock(close=AsyncMock())
    service._unclosed_contexts[orphan] = "unfinished"

    await service._cleanup_expired_sessions()

    assert service._sessions == {"first": first}
    first.context.close.assert_awaited_once()
    second_context.close.assert_awaited_once()
    orphan.close.assert_awaited_once()
    assert not service._unclosed_contexts


@pytest.mark.asyncio
async def test_external_close_cancels_active_journey_owner(service):
    created, stopped = asyncio.Event(), asyncio.Event()

    async def journey():
        session = await create(service, "journey")
        created.set()
        try:
            await asyncio.Event().wait()
        finally:
            await service.release_session("resource", session)
            stopped.set()

    task = asyncio.create_task(journey())
    await created.wait()
    assert await service.close_session("resource")
    with pytest.raises(asyncio.CancelledError):
        await task
    assert stopped.is_set() and not service._sessions


@pytest.mark.asyncio
async def test_closed_journey_cannot_recreate_while_owner_still_holds_lease(service):
    created, resume = asyncio.Event(), asyncio.Event()

    async def journey():
        session = await create(service, "journey")
        created.set()
        try:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                await resume.wait()  # emulate a driver that swallowed cancellation
            with pytest.raises(RuntimeError, match="closing"):
                await service.get_or_create_session("resource")
        finally:
            await service.release_session("resource", session)

    task = asyncio.create_task(journey())
    await created.wait()
    assert await service.close_session("resource")
    assert service._sessions["resource"].closed
    resume.set()
    await task
    assert not service._sessions
    assert service._browser.new_context.await_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel", [False, True])
async def test_launch_failure_or_cancel_stops_started_playwright(monkeypatch, cancel):
    service = BrowserService()
    driver = SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock()), stop=AsyncMock())
    driver.chromium.launch.side_effect = asyncio.CancelledError() if cancel else RuntimeError("launch failed")
    monkeypatch.setattr("services.browser_service.async_playwright", lambda: SimpleNamespace(
        start=AsyncMock(return_value=driver), __aexit__=AsyncMock()))
    with pytest.raises(asyncio.CancelledError if cancel else RuntimeError):
        await service.startup()
    driver.stop.assert_awaited_once()
    assert service._playwright is None and service._browser is None


@pytest.mark.asyncio
async def test_shutdown_continues_after_context_and_browser_failures(service):
    session = await create(service, "ordinary")
    session.context.close.side_effect = RuntimeError("context failed")
    browser, driver = service._browser, service._playwright
    browser.close.side_effect = RuntimeError("browser failed")
    await service.shutdown()
    session.context.close.assert_awaited_once()
    browser.close.assert_awaited_once()
    driver.stop.assert_awaited_once()
    # Driver shutdown is a reliable ancestor release even if browser.close failed.
    assert service._browser is None and service._playwright is None
    assert not service._sessions and not service._resource_close_tasks


@pytest.mark.asyncio
async def test_cancelled_shutdown_still_releases_driver(service):
    entered, release = asyncio.Event(), asyncio.Event()
    driver = service._playwright

    async def close_browser():
        entered.set()
        await release.wait()

    service._browser.close.side_effect = close_browser
    task = asyncio.create_task(service.shutdown())
    await entered.wait()
    task.cancel()
    release.set()
    with pytest.raises(asyncio.CancelledError):
        await task
    driver.stop.assert_awaited_once()


@pytest.mark.asyncio
async def test_close_timeout_is_bounded_and_retains_session_for_retry(service):
    session = await create(service, "ordinary")
    release = asyncio.Event()

    async def never_close():
        await release.wait()

    session.context.close.side_effect = never_close
    try:
        with pytest.raises(RuntimeError, match="not confirmed"):
            await asyncio.wait_for(service.close_session("resource"), timeout=0.5)
        assert service._sessions["resource"] is session and session.closing
        with pytest.raises(RuntimeError, match="closing"):
            await create(service, "ordinary")
    finally:
        release.set()
        pending = service._resource_close_tasks.get(session.context.close)
        if pending:
            await asyncio.wait_for(pending, timeout=0.5)
    assert await service.close_session("resource")
    assert not service._sessions


@pytest.mark.asyncio
async def test_failed_creation_cleanup_retains_capacity_until_browser_shutdown(service):
    service.max_sessions = 1
    context = service._browser.new_context.return_value
    context.new_page.side_effect = RuntimeError("create failed")
    context.close.side_effect = RuntimeError("close failed")
    with pytest.raises(RuntimeError, match="create failed"):
        await create(service, "ordinary")
    assert not service._creating and context in service._unclosed_contexts
    with pytest.raises(RuntimeError, match="capacity"):
        await create(service, "ordinary", "second")
    with pytest.raises(RuntimeError, match="not confirmed"):
        await service.close_session("resource")
    failed_close = service._resource_close_tasks[context.close]
    context.close.side_effect = None
    with pytest.raises(RuntimeError, match="not confirmed"):
        await service.close_session("resource")
    assert service._resource_close_tasks[context.close] is failed_close
    context.close.assert_awaited_once()
    assert context in service._unclosed_contexts
    await service.shutdown()
    assert not service._unclosed_contexts and not service._resource_close_tasks


@pytest.mark.asyncio
@pytest.mark.parametrize("browser_close_fails", [False, True])
async def test_real_playwright_failed_close_cannot_release_capacity_by_noop_retry(service, browser_close_fails):
    # Run the installed SDK's close method, not an AsyncMock of its latch semantics.
    context = object.__new__(DriverContext)
    context._closing_or_closed = False
    context._request = SimpleNamespace(dispose=AsyncMock(side_effect=RuntimeError("dispose failed")))
    context._channel = SimpleNamespace(send=AsyncMock())
    session = BrowserSession(context, None, datetime.now())
    service._sessions["failed"] = session
    service.max_sessions = 2
    other = await create(service, "ordinary", "other")
    browser, driver = service._browser, service._playwright
    if browser_close_fails:
        browser.close.side_effect = RuntimeError("browser close failed")

    for _ in range(2):
        with pytest.raises(RuntimeError, match="not confirmed"):
            await service.close_session("failed")
        assert service._sessions["failed"] is session and not session.closed
        assert service._sessions["other"] is other
        assert await service.validate_session("other")
        browser.close.assert_not_awaited()
        driver.stop.assert_not_awaited()
        with pytest.raises(RuntimeError, match="capacity"):
            await create(service, "ordinary", "third")

    assert context._closing_or_closed
    context._request.dispose.assert_awaited_once()
    context._channel.send.assert_not_awaited()
    failed_close = service._resource_close_tasks[context.close]
    assert failed_close.done() and isinstance(failed_close.exception(), RuntimeError)
    await service.shutdown()
    context._request.dispose.assert_awaited_once()
    assert service._browser is None and service._playwright is None
    assert not service._sessions and not service._unclosed_contexts
    assert not service._resource_close_tasks


@pytest.mark.asyncio
async def test_failed_driver_stop_is_not_retried_as_latched_success(service):
    manager = object.__new__(PlaywrightContextManager)
    manager._exit_was_called = False
    manager._connection = SimpleNamespace(stop_async=AsyncMock(side_effect=RuntimeError("stop failed")))
    driver = SimpleNamespace(stop=manager.__aexit__)
    service._playwright = driver
    for _ in range(2):
        await service.shutdown()
        assert service._playwright is driver
        with pytest.raises(RuntimeError, match="unreleased resources"):
            await service.startup()
    manager._connection.stop_async.assert_awaited_once()
    assert manager._exit_was_called
    assert service._resource_close_tasks[driver.stop].done()


@pytest.mark.asyncio
@pytest.mark.parametrize("failed_close", [False, True])
async def test_normal_lifecycle_can_restart_after_confirmed_shutdown(monkeypatch, failed_close):
    service = BrowserService()
    service.cleanup_timeout = 0.01
    page = SimpleNamespace(set_default_timeout=Mock(), add_init_script=AsyncMock(), on=Mock())
    for generation in range(2):
        context = Mock(new_page=AsyncMock(return_value=page), close=AsyncMock())
        browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
        driver = SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock(return_value=browser)),
                                 stop=AsyncMock())
        monkeypatch.setattr("services.browser_service.async_playwright", lambda: SimpleNamespace(
            start=AsyncMock(return_value=driver), __aexit__=AsyncMock()))
        try:
            await service.startup()
            await create(service, "ordinary")
            if failed_close and generation == 0:
                context.close.side_effect = RuntimeError("context close failed")
                with pytest.raises(RuntimeError, match="not confirmed"):
                    await service.close_session("resource")
            else:
                assert await service.close_session("resource")
        finally:
            await service.shutdown()
        context.close.assert_awaited_once()
        assert service._browser is None and service._playwright is None
        assert service._cleanup_task is None
        assert not service._sessions and not service._resource_close_tasks


@pytest.mark.asyncio
async def test_late_strict_snapshot_does_not_recreate_closed_session(service):
    await create(service, "ordinary")
    await service.close_session("resource")
    result = await service.snapshot_semantic("resource", strict_session=True)
    assert result["error_code"] == "SESSION_NOT_FOUND"
    assert service._browser.new_context.await_count == 1


@pytest.mark.asyncio
async def test_failed_create_rollback_is_not_reported_as_successful_close(service):
    context = service._browser.new_context.return_value
    entered = asyncio.Event()

    async def pending():
        entered.set()
        await asyncio.Event().wait()

    context.new_page.side_effect = pending
    context.close.side_effect = RuntimeError("cleanup failed")
    task = asyncio.create_task(create(service, "ordinary"))
    await entered.wait()
    with pytest.raises(RuntimeError, match="not confirmed"):
        await service.close_session("resource")
    with pytest.raises(asyncio.CancelledError):
        await task
    assert context in service._unclosed_contexts
    with pytest.raises(RuntimeError, match="unfinished cleanup"):
        await create(service, "ordinary")


@pytest.mark.asyncio
async def test_same_id_cannot_create_while_close_is_in_progress(service):
    session = await create(service, "ordinary")
    entered, release = asyncio.Event(), asyncio.Event()

    async def slow_close():
        entered.set()
        await release.wait()

    session.context.close.side_effect = slow_close
    task = asyncio.create_task(service.close_session("resource"))
    await entered.wait()
    with pytest.raises(RuntimeError, match="closing"):
        await create(service, "ordinary")
    release.set()
    assert await task


@pytest.mark.asyncio
@pytest.mark.parametrize("late_failure", [False, True])
async def test_repeated_close_and_shutdown_reuse_driver_task_without_cancelling(service, late_failure):
    session = await create(service, "ordinary")
    release = asyncio.Event()
    cancellations = 0

    async def stubborn_close():
        nonlocal cancellations
        try:
            await release.wait()
        except asyncio.CancelledError:
            cancellations += 1
            await release.wait()
        if late_failure:
            raise RuntimeError("late close failure after driver release")

    session.context.close.side_effect = stubborn_close
    pending = None
    try:
        for _ in range(3):
            with pytest.raises(RuntimeError, match="not confirmed"):
                await service.close_session("resource")
            current = service._resource_close_tasks[session.context.close]
            assert pending is None or current is pending
            pending = current
        await service.shutdown()
        assert session.context.close.await_count == 1
        assert cancellations == 0
        assert service._resource_close_tasks[session.context.close] is pending
        assert not pending.done() and pending.cancelling() == 0
        assert service._browser is None and service._playwright is None
        with pytest.raises(RuntimeError, match="unreleased resources"):
            await service.startup()
    finally:
        release.set()
        if pending is not None:
            results = await asyncio.wait_for(asyncio.gather(pending, return_exceptions=True), timeout=0.5)
            assert isinstance(results[0], RuntimeError) if late_failure else results == [None]
    assert not service._resource_close_tasks


@pytest.mark.asyncio
@pytest.mark.parametrize("late_failure", [False, True])
async def test_concurrent_orphan_close_cannot_restore_ownership_after_shutdown(service, monkeypatch, late_failure):
    context = service._browser.new_context.return_value
    context_release, browser_entered, browser_release = asyncio.Event(), asyncio.Event(), asyncio.Event()
    external_entered = asyncio.Event()
    context_attempts = 0
    close_resource = service._close_resource

    async def pending_context_close():
        await context_release.wait()
        if late_failure:
            raise RuntimeError("late context close failed")

    async def pending_browser_close():
        browser_entered.set()
        await browser_release.wait()

    async def observed_close(close, label):
        nonlocal context_attempts
        if close == context.close:
            context_attempts += 1
            if context_attempts == 2:
                external_entered.set()
        return await close_resource(close, label)

    context.close.side_effect = pending_context_close
    service._browser.close.side_effect = pending_browser_close
    service._unclosed_contexts[context] = "resource"
    monkeypatch.setattr(service, "_close_resource", observed_close)
    shutdown = asyncio.create_task(service.shutdown())
    external = pending = None
    try:
        await asyncio.wait_for(browser_entered.wait(), timeout=0.5)
        pending = service._resource_close_tasks[context.close]
        external = asyncio.create_task(service.close_session("resource"))
        await asyncio.wait_for(external_entered.wait(), timeout=0.5)
        browser_release.set()
        await asyncio.wait_for(shutdown, timeout=0.5)
        assert await asyncio.wait_for(external, timeout=0.5)
        assert not service._unclosed_contexts
        assert service._resource_close_tasks[context.close] is pending
        assert not pending.done() and pending.cancelling() == 0
        with pytest.raises(RuntimeError, match="unreleased resources"):
            await service.startup()

        context_release.set()
        results = await asyncio.wait_for(asyncio.gather(pending, return_exceptions=True), timeout=0.5)
        assert isinstance(results[0], RuntimeError) if late_failure else results == [None]
        assert not service._unclosed_contexts and not service._resource_close_tasks
        # A second shutdown must not be needed to erase a resurrected orphan.
        browser = SimpleNamespace(close=AsyncMock())
        driver = SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock(return_value=browser)),
                                 stop=AsyncMock())
        monkeypatch.setattr("services.browser_service.async_playwright", lambda: SimpleNamespace(
            start=AsyncMock(return_value=driver), __aexit__=AsyncMock()))
        await service.startup()
        assert service._browser is browser
        context.close.assert_awaited_once()
    finally:
        browser_release.set()
        context_release.set()
        await asyncio.gather(*(task for task in (shutdown, external, pending) if task is not None),
                             return_exceptions=True)
        await service.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel_owner", [False, True])
async def test_creation_waiter_gets_failure_before_late_cleanup_finishes(service, cancel_owner):
    service.max_sessions = 1
    service.cleanup_timeout = 0.2
    context = service._browser.new_context.return_value
    entered, allocation_release, close_release = asyncio.Event(), asyncio.Event(), asyncio.Event()

    async def pending_context(**kwargs):
        entered.set()
        await allocation_release.wait()
        return context

    async def pending_close():
        await close_release.wait()

    service._browser.new_context.side_effect = pending_context
    context.close.side_effect = pending_close
    if not cancel_owner:
        context.new_page.side_effect = RuntimeError("page creation failed")
    owner = asyncio.create_task(create(service, "ordinary"))
    waiter = None
    reservation = None
    try:
        await entered.wait()
        reservation = service._creating["resource"]
        waiter = asyncio.create_task(create(service, "ordinary"))
        await asyncio.sleep(0)
        if cancel_owner:
            owner.cancel()
        else:
            allocation_release.set()
        done, _ = await asyncio.wait({waiter}, timeout=0.1)
        assert waiter in done, "known creation failure must not wait for resource cleanup"
        with pytest.raises(asyncio.CancelledError if cancel_owner else RuntimeError):
            await waiter
        assert reservation.result_ready.is_set() and not reservation.done.is_set()
        assert reservation.rollback is not None and not reservation.rollback.done()
        assert service._creating["resource"] is reservation
        with pytest.raises(RuntimeError, match="capacity"):
            await create(service, "ordinary", "second")
        assert service._browser.new_context.await_count == 1
    finally:
        allocation_release.set()
        close_release.set()
        await asyncio.gather(*(task for task in (owner, waiter) if task is not None), return_exceptions=True)
        if reservation is not None and reservation.rollback is not None:
            await asyncio.wait_for(reservation.rollback, timeout=0.5)
    context.close.assert_awaited_once()
    assert not service._creating and not service._sessions and not service._resource_close_tasks


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["ordinary", "journey"])
@pytest.mark.parametrize("late_close_failure", [False, True])
async def test_cancelled_context_rpc_retains_capacity_until_late_context_is_closed(service, kind, late_close_failure):
    service.max_sessions = 1
    context = service._browser.new_context.return_value
    if late_close_failure:
        context.close.side_effect = RuntimeError("context close after browser release failed")
    entered, release = asyncio.Event(), asyncio.Event()

    async def pending_context(**kwargs):
        entered.set()
        await release.wait()
        return context

    service._browser.new_context.side_effect = pending_context
    owner = asyncio.create_task(create(service, kind))
    await entered.wait()
    reservation = service._creating["resource"]
    try:
        owner.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(owner, timeout=0.5)
        assert service._creating["resource"] is reservation
        assert not reservation.allocation.done() and not reservation.rollback.done()
        assert not reservation.done.is_set()
        with pytest.raises(RuntimeError, match="capacity"):
            await create(service, "ordinary", "second")
        rollback = reservation.rollback
        with pytest.raises(RuntimeError, match="not confirmed"):
            await service.close_session("resource")
        assert reservation.rollback is rollback  # one late-result owner, not a retry
        await service.shutdown()
        assert service._creating["resource"] is reservation
        with pytest.raises(RuntimeError, match="unreleased resources"):
            await service.startup()
    finally:
        release.set()
        if not owner.done():
            owner.cancel()
            await asyncio.gather(owner, return_exceptions=True)
        if reservation.rollback:
            await asyncio.wait_for(reservation.rollback, timeout=0.5)
    context.close.assert_awaited_once()
    assert service._browser is None
    assert not service._sessions and not service._creating
    assert not service._unclosed_contexts and not service._resource_close_tasks


@pytest.mark.asyncio
async def test_shutdown_resolves_cancelled_context_rpc_without_returned_context(service):
    entered, driver_closed = asyncio.Event(), asyncio.Event()

    async def pending_context(**kwargs):
        entered.set()
        await driver_closed.wait()
        raise RuntimeError("browser closed")

    service._browser.new_context.side_effect = pending_context
    service._browser.close.side_effect = driver_closed.set
    owner = asyncio.create_task(create(service, "ordinary"))
    await entered.wait()
    reservation = service._creating["resource"]
    try:
        owner.cancel()
        with pytest.raises(asyncio.CancelledError):
            await owner
        await service.shutdown()
        assert reservation.done.is_set()
        assert not service._creating and not service._resource_close_tasks
    finally:
        driver_closed.set()
        await asyncio.gather(owner, return_exceptions=True)
        if reservation.rollback:
            await asyncio.wait_for(reservation.rollback, timeout=0.5)


@pytest.mark.asyncio
@pytest.mark.parametrize("start_fails", [False, True])
async def test_cancelled_start_retains_one_cleanup_until_driver_is_obtainable(monkeypatch, start_fails):
    service = BrowserService()
    service.cleanup_timeout = 0.01
    entered, release = asyncio.Event(), asyncio.Event()
    driver = SimpleNamespace(stop=AsyncMock())

    async def delayed_start():
        entered.set()
        await release.wait()
        if start_fails:
            raise RuntimeError("partial driver startup failed")
        return driver

    manager = SimpleNamespace(start=AsyncMock(side_effect=delayed_start), __aexit__=AsyncMock())
    monkeypatch.setattr("services.browser_service.async_playwright", lambda: manager)
    owner = asyncio.create_task(service.startup())
    await entered.wait()
    cleanup = None
    try:
        owner.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(owner, timeout=0.5)
        cleanup = service._startup_cleanup
        assert cleanup is not None and not cleanup.done()
        assert service._starting is not None and not service._starting.done()
        with pytest.raises(RuntimeError, match="unreleased resources"):
            await service.startup()
        await service.shutdown()
        assert service._startup_cleanup is cleanup
        manager.start.assert_awaited_once()
    finally:
        release.set()
        await asyncio.gather(owner, return_exceptions=True)
        if cleanup:
            await asyncio.wait_for(cleanup, timeout=0.5)
    if start_fails:
        manager.__aexit__.assert_awaited_once_with(None, None, None)
    else:
        driver.stop.assert_awaited_once()
    assert service._starting is None and service._startup_cleanup is None
    assert service._playwright is None and service._playwright_exit is None
    assert not service._resource_close_tasks


@pytest.mark.asyncio
@pytest.mark.parametrize("ancestor_closes", [True, False])
async def test_explicit_recovery_requires_confirmed_ancestor_release(service, monkeypatch, ancestor_closes):
    service.max_sessions = 1
    session = await create(service, "ordinary")
    session.context.close.side_effect = RuntimeError("latched context failure")
    with pytest.raises(RuntimeError, match="not confirmed"):
        await service.close_session("resource")
    old_browser, old_driver = service._browser, service._playwright
    if not ancestor_closes:
        old_browser.close.side_effect = RuntimeError("browser close failed")
        old_driver.stop.side_effect = RuntimeError("driver stop failed")
    fresh_context = Mock(new_page=AsyncMock(return_value=SimpleNamespace(
        set_default_timeout=Mock(), add_init_script=AsyncMock(), on=Mock())), close=AsyncMock())
    fresh_browser = SimpleNamespace(new_context=AsyncMock(return_value=fresh_context), close=AsyncMock())
    fresh_driver = SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock(return_value=fresh_browser)),
                                   stop=AsyncMock())
    start = AsyncMock(return_value=fresh_driver)
    monkeypatch.setattr("services.browser_service.async_playwright", lambda: SimpleNamespace(
        start=start, __aexit__=AsyncMock()))
    try:
        if ancestor_closes:
            assert await service.recover_failed_cleanup()
            assert await create(service, "ordinary")  # Same ID and capacity become available.
            assert service._browser is fresh_browser
            start.assert_awaited_once()
        else:
            with pytest.raises(RuntimeError, match="unreleased resources"):
                await service.recover_failed_cleanup()
            start.assert_not_awaited()
            assert service._sessions["resource"] is session
            assert service._browser is old_browser
            with pytest.raises(RuntimeError, match="not running"):
                await create(service, "ordinary", "other")
        # Retrying the latched close must never manufacture a successful no-op.
        session.context.close.assert_awaited_once()
    finally:
        await service.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("busy", ["healthy", "owner", "creating"])
async def test_recovery_never_interrupts_existing_work(service, busy):
    session = await create(service, "ordinary")
    if busy != "healthy":
        session.closing = True
    if busy == "owner":
        session.owner_task = asyncio.current_task()
    elif busy == "creating":
        service._creating["pending"] = object()
    service._unclosed_contexts[session.context] = "resource"
    assert not await service.recover_failed_cleanup()
    service._browser.close.assert_not_awaited()
    service._playwright.stop.assert_not_awaited()
    assert not service._stopping
    service._creating.clear()
    session.owner_task = None
    service._unclosed_contexts.clear()
    await service.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("interruption,completion", [
    ("launch_failure", "retry"), ("cleanup_cancel", "retry"), ("launch_cancel", "retry"),
    ("launch_failure", "startup"), ("launch_failure", "shutdown"),
])
async def test_recovery_can_resume_after_interruption(service, monkeypatch, interruption, completion):
    session = await create(service, "ordinary")
    session.context.close.side_effect = RuntimeError("latched context failure")
    with pytest.raises(RuntimeError, match="not confirmed"):
        await service.close_session("resource")

    fresh_context = Mock(new_page=AsyncMock(return_value=SimpleNamespace(
        set_default_timeout=Mock(), add_init_script=AsyncMock(), on=Mock())), close=AsyncMock())
    fresh_browser = SimpleNamespace(new_context=AsyncMock(return_value=fresh_context), close=AsyncMock())
    launch = AsyncMock(return_value=fresh_browser)
    driver = SimpleNamespace(chromium=SimpleNamespace(launch=launch), stop=AsyncMock())
    monkeypatch.setattr("services.browser_service.async_playwright", lambda: SimpleNamespace(
        start=AsyncMock(return_value=driver), __aexit__=AsyncMock()))
    entered, release = asyncio.Event(), asyncio.Event()
    task = None

    async def paused_cleanup():
        entered.set()
        await release.wait()

    async def paused_launch(**kwargs):
        entered.set()
        await asyncio.Event().wait()

    try:
        if interruption == "launch_failure":
            launch.side_effect = RuntimeError("transient launch failure")
            with pytest.raises(RuntimeError, match="transient launch failure"):
                await service.recover_failed_cleanup()
        else:
            if interruption == "cleanup_cancel":
                service._browser.close.side_effect = paused_cleanup
            else:
                launch.side_effect = paused_launch
            task = asyncio.create_task(service.recover_failed_cleanup())
            await asyncio.wait_for(entered.wait(), 1)
            task.cancel()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)

        assert service._browser is None and service._playwright is None
        assert not service._resource_close_tasks
        assert service._stopping
        launch.side_effect = None
        if completion == "shutdown":
            await service.shutdown()
            assert not await service.recover_failed_cleanup()
            launch.assert_awaited_once()  # Only the failed attempt, no implicit restart.
            return
        if completion == "startup":
            await service.startup()
        else:
            assert await service.recover_failed_cleanup()
        assert service._browser is fresh_browser
        assert not service._stopping
        # Successful recovery clears its intent; another request must not reset it.
        assert not await service.recover_failed_cleanup()
        fresh_browser.close.assert_not_awaited()
        restored = await create(service, "ordinary")
        assert restored.context is fresh_context
        session.context.close.assert_awaited_once()
    finally:
        release.set()
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        await service.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("previously_running", [False, True])
async def test_recovery_does_not_start_a_service_without_failed_recovery(service, monkeypatch, previously_running):
    if previously_running:
        await service.shutdown()
    else:
        service = BrowserService()
    factory = Mock()
    monkeypatch.setattr("services.browser_service.async_playwright", factory)
    assert not await service.recover_failed_cleanup()
    factory.assert_not_called()

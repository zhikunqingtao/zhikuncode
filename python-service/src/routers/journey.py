"""
用户旅程验证路由 — POST /journey/run

接收 steps DSL 并驱动 Playwright 逐步执行，返回每步结果 + 截图。
依赖 BROWSER_AUTOMATION 能力域（与 browser 路由同域注册）。
"""

import asyncio
import base64
import time
import uuid
import logging
from urllib.parse import urljoin
from typing import Dict, Any

from fastapi import APIRouter, HTTPException, Request

from services.browser_service import BrowserAdmissionError
from services.journey_models import JourneyRunRequest, JourneyRunResponse, StepResultModel

logger = logging.getLogger(__name__)
router = APIRouter()
# Match BrowserVerifier: <=120s work, <=6s cleanup join, 4s transport headroom
# inside its 130s HTTP timeout. Java's later snapshot/close have separate budgets.
JOURNEY_EXECUTION_TIMEOUT_SECONDS = 120.0
DISCONNECT_POLL_SECONDS = 0.25
# Retain late cleanup tasks after the HTTP cleanup budget expires.
_pending_cleanup_tasks: set[asyncio.Task] = set()


def _cleanup_finished(task):
    _pending_cleanup_tasks.discard(task)
    if not task.cancelled():
        task.exception()


@router.post("/journey/run")
async def journey_run(request: JourneyRunRequest, http_request: Request = None) -> JourneyRunResponse:
    """执行用户旅程验证 — 逐步执行 steps DSL，首个失败即停止"""
    from routers.browser import browser_service

    remaining = JOURNEY_EXECUTION_TIMEOUT_SECONDS
    if request.deadline_epoch_ms is not None:
        remaining = min(remaining, request.deadline_epoch_ms / 1000 - time.time())
    if remaining <= 0:
        raise HTTPException(status_code=504, detail="JOURNEY_DEADLINE_EXCEEDED")

    stop_watcher = asyncio.Event()
    execution = asyncio.create_task(_run_owned_journey(browser_service, request))
    disconnected = (asyncio.create_task(_wait_for_disconnect(http_request, stop_watcher))
                    if http_request is not None else None)
    try:
        watched = {execution, disconnected} if disconnected is not None else {execution}
        done, _ = await asyncio.wait(watched, timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
        if execution in done:
            try:
                return await execution
            except BrowserAdmissionError as error:
                raise HTTPException(status_code=error.status_code, detail=error.code) from error
            except asyncio.CancelledError:
                # A close_session cancelled the child; do not swallow cancellation
                # of the HTTP handler itself.
                if asyncio.current_task().cancelling():
                    raise
                raise HTTPException(status_code=409, detail="JOURNEY_CANCELLED")
        if disconnected is not None and disconnected in done:
            disconnected.result()  # Receive failures are not client disconnects.
            raise HTTPException(status_code=499, detail="JOURNEY_CLIENT_DISCONNECTED")
        raise HTTPException(status_code=504, detail="JOURNEY_DEADLINE_EXCEEDED")
    finally:
        stop_watcher.set()
        for task in watched:
            _pending_cleanup_tasks.add(task)
            task.add_done_callback(_cleanup_finished)
            if not task.done():
                task.cancel()
        # wait_for(gather(...)) can itself wait forever for suppressed cancellation.
        # asyncio.wait bounds the join without cancelling Playwright cleanup again.
        _, pending = await asyncio.wait(
            watched, timeout=getattr(browser_service, "cleanup_timeout", 5.0) + 1.0
        )
        if pending:
            logger.warning("Journey cleanup still pending: %d task(s)", len(pending))


async def _wait_for_disconnect(request: Request, stopped: asyncio.Event):
    while not stopped.is_set():
        if await request.is_disconnected():
            return
        # Request.is_disconnected may absorb Task.cancel inside AnyIO's scope.
        # The explicit stop flag still ends this watcher after that call returns.
        if stopped.is_set():
            return
        try:
            await asyncio.wait_for(stopped.wait(), timeout=DISCONNECT_POLL_SECONDS)
        except asyncio.TimeoutError:
            pass


async def _run_owned_journey(browser_service, request: JourneyRunRequest) -> JourneyRunResponse:
    session_id = request.session_id or f"rv-{uuid.uuid4().hex[:8]}"
    # Creation itself is cancellation-safe; do not close by ID if creation fails
    # (a duplicate request must never close the existing owner's context).
    session = await browser_service._create_context_for_journey(
        session_id, request.record, request.viewport
    )
    try:
        return await _execute_journey(browser_service, request, session_id, session)
    except BaseException:
        # Normal step failures are results and retain the context for Java's
        # failure semantic snapshot. Only aborted execution is released here.
        try:
            await browser_service.close_session(session_id, expected_session=session)
        except Exception:
            # Keep the original failure/cancellation. BrowserService retains
            # failed cleanup for retry; do not silently claim it was released.
            logger.exception("Journey cleanup unconfirmed for %s", session_id)
        raise
    finally:
        await browser_service.release_session(session_id, session)


async def _execute_journey(browser_service, request, session_id, session) -> JourneyRunResponse:

    step_results = []

    for i, step in enumerate(request.steps):
        timeout = step.get("timeout", browser_service.default_timeout)
        start_time = time.time()

        try:
            if step.get("action") == "navigate":
                step = {**step, "url": urljoin(request.base_url.rstrip("/") + "/", step.get("url", ""))}
            result = await _execute_step(browser_service, session_id, session, step, timeout)
            duration_ms = int((time.time() - start_time) * 1000)

            # 每步截图 (JPEG quality=80)
            screenshot_b64 = None
            try:
                screenshot_bytes = await session.page.screenshot(type="jpeg", quality=80)
                screenshot_b64 = base64.b64encode(screenshot_bytes).decode()
            except Exception as e:
                logger.warning(f"Step {i} screenshot failed: {e}")

            # 获取 console errors
            console_errors = list(getattr(session, '_js_errors', []))

            step_ok = result.get("success", True) and "error" not in result

            step_result = StepResultModel(
                index=i,
                action=step["action"],
                ok=step_ok,
                duration_ms=duration_ms,
                screenshot_base64=screenshot_b64,
                error=result.get("error"),
                console_errors=console_errors,
            )
            step_results.append(step_result)

            # D2: 首个失败即停止
            if not step_ok:
                break

        except Exception as e:
            duration_ms = int((time.time() - start_time) * 1000)
            step_results.append(StepResultModel(
                index=i,
                action=step.get("action", "unknown"),
                ok=False,
                duration_ms=duration_ms,
                error=str(e),
                screenshot_base64=None,
                console_errors=[],
            ))
            break

    passed = all(s.ok for s in step_results) and len(step_results) == len(request.steps)

    # 收集 artifacts
    artifacts: Dict[str, str] = {}
    record_opts = getattr(session, '_record_opts', {})
    if record_opts.get("trace"):
        import tempfile
        import os
        trace_path = os.path.join(tempfile.mkdtemp(prefix="rv-trace-"), f"{session_id}.zip")
        try:
            await session.context.tracing.stop(path=trace_path)
            artifacts["trace_path"] = trace_path
        except Exception as e:
            logger.warning(f"Trace stop failed: {e}")

    final_url = session.page.url

    return JourneyRunResponse(
        passed=passed,
        step_results=step_results,
        session_id=session_id,
        final_url=final_url,
        artifacts=artifacts,
    )


async def _execute_step(
    service, session_id: str, session, step: Dict[str, Any], timeout: int
) -> Dict[str, Any]:
    """将 step DSL 映射到 browser_service 现有方法"""
    action = step["action"]

    if action == "navigate":
        url = step.get("url", "")
        result = await service.navigate(session_id, url, timeout=timeout)
        return {"success": True, **(result if isinstance(result, dict) else {})}

    elif action == "click":
        # A timed-out click may already have taken effect. Do not retry it via JS
        # (the general browser helper does), which can undo a toggle or submit twice.
        await session.page.click(step["selector"], timeout=timeout)
        return {"success": True}

    elif action == "type":
        result = await service.type_text(session_id, step["selector"], step["text"], timeout=timeout)
        if isinstance(result, dict) and result.get("success") is False:
            return {"success": False, "error": result.get("error", "type_text failed")}
        return {"success": True}

    elif action == "wait_for":
        result = await service.wait_for(
            session_id,
            wait_until=step.get("wait_until"),
            selector=step.get("selector"),
            state=step.get("state", "visible"),
            timeout=timeout,
        )
        if isinstance(result, dict) and "error" in result:
            return {"success": False, "error": result["error"]}
        return {"success": True}

    elif action == "assert_text":
        # D3: contains 匹配
        text_result = await service.extract_text(session_id, step["selector"])
        actual_text = text_result.get("text", "") if isinstance(text_result, dict) else ""
        if step["expected"] not in actual_text:
            return {
                "success": False,
                "error": f"assert_text failed: expected '{step['expected']}' not found in '{actual_text[:200]}'",
            }
        return {"success": True}

    elif action == "assert_url":
        current_url = session.page.url
        if step["contains"] not in current_url:
            return {
                "success": False,
                "error": f"assert_url failed: '{step['contains']}' not in '{current_url}'",
            }
        return {"success": True}

    elif action == "assert_no_console_error":
        errors = getattr(session, '_js_errors', [])
        if errors:
            return {
                "success": False,
                "error": f"Console errors found: {errors[:3]}",
            }
        return {"success": True}

    elif action == "screenshot":
        return {"success": True}  # 截图在外层统一处理

    else:
        return {"success": False, "error": f"Unknown action: {action}"}

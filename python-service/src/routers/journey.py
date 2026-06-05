"""
用户旅程验证路由 — POST /journey/run

接收 steps DSL 并驱动 Playwright 逐步执行，返回每步结果 + 截图。
依赖 BROWSER_AUTOMATION 能力域（与 browser 路由同域注册）。
"""

import base64
import time
import uuid
import logging
from typing import Dict, Any

from fastapi import APIRouter

from services.journey_models import JourneyRunRequest, JourneyRunResponse, StepResultModel

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/journey/run")
async def journey_run(request: JourneyRunRequest) -> JourneyRunResponse:
    """执行用户旅程验证 — 逐步执行 steps DSL，首个失败即停止"""
    from routers.browser import browser_service

    # 生成 session_id
    session_id = request.session_id or f"rv-{uuid.uuid4().hex[:8]}"

    # 创建带录制能力的新 session
    session = await browser_service._create_context_for_journey(
        session_id, request.record, request.viewport
    )

    step_results = []

    for i, step in enumerate(request.steps):
        timeout = step.get("timeout", browser_service.default_timeout)
        start_time = time.time()

        try:
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
        result = await service.click(session_id, step["selector"], timeout=timeout)
        if isinstance(result, dict) and "error" in result:
            return {"success": False, "error": result["error"]}
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

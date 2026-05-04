"""
浏览器自动化 FastAPI 路由 — §10.4 B3

前缀 /api/browser，13 个端点对应 WebBrowserTool.java 的 13 个 action。
每个端点：接收 Pydantic 模型 → 委托 BrowserService → 统一 BrowserResponse。

生命周期：
  browser_service 的 startup/shutdown 通过 main.py lifespan 管理，
  或通过 startup_browser/shutdown_browser 回调注入。
"""

import logging

from fastapi import APIRouter

from services.browser_service import BrowserService
from services.browser_models import (
    NavigateRequest,
    ClickRequest,
    TypeRequest,
    EvaluateRequest,
    ScreenshotRequest,
    ExtractRequest,
    WaitForRequest,
    SelectOptionRequest,
    DialogRequest,
    CookieRequest,
    SetCookieRequest,
    CloseSessionRequest,
    JsErrorsRequest,
    BrowserResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter()
browser_service = BrowserService()


# ═══ 生命周期回调（由 main.py lifespan 调用）═══

async def startup_browser():
    await browser_service.startup()


async def shutdown_browser():
    await browser_service.shutdown()


# ═══ 端点 ═══

@router.post("/navigate")
async def navigate(req: NavigateRequest) -> BrowserResponse:
    try:
        data = await browser_service.navigate(
            req.session_id, req.url, req.wait_until, req.timeout
        )
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/screenshot")
async def screenshot(req: ScreenshotRequest) -> BrowserResponse:
    try:
        data = await browser_service.screenshot(
            req.session_id, req.full_page, req.selector,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/click")
async def click(req: ClickRequest) -> BrowserResponse:
    try:
        data = await browser_service.click(
            req.session_id, req.selector, req.timeout,
            strict_session=req.strict_session,
            no_wait_after=req.no_wait_after,
            force=req.force,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/type")
async def type_text(req: TypeRequest) -> BrowserResponse:
    try:
        data = await browser_service.type_text(
            req.session_id, req.selector, req.text, req.timeout,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/evaluate")
async def evaluate(req: EvaluateRequest) -> BrowserResponse:
    try:
        data = await browser_service.evaluate(
            req.session_id, req.script,
            strict_session=req.strict_session,
        )
        # evaluate 内部已做了错误捕获，检查返回结构
        if data.get("success") is False and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        if data.get("success") is False:
            return BrowserResponse(success=False, data=data, error_code="JS_EVALUATION_ERROR", error_message=data.get("error_message", "JavaScript evaluation failed"))
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/extract_text")
async def extract_text(req: ExtractRequest) -> BrowserResponse:
    try:
        data = await browser_service.extract_text(
            req.session_id, req.selector,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/extract_html")
async def extract_html(req: ExtractRequest) -> BrowserResponse:
    try:
        data = await browser_service.extract_html(
            req.session_id, req.selector,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/wait_for")
async def wait_for(req: WaitForRequest) -> BrowserResponse:
    try:
        data = await browser_service.wait_for(
            req.session_id,
            selector=req.selector,
            state=req.state,
            timeout=req.timeout,
            wait_until=req.wait_until,
            text_contains=req.text_contains,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        if data.get("error"):
            return BrowserResponse(success=False, error_code="INVALID_PARAMS", error_message=data["error"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/select_option")
async def select_option(req: SelectOptionRequest) -> BrowserResponse:
    try:
        data = await browser_service.select_option(
            req.session_id, req.selector, req.values,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/handle_dialog")
async def handle_dialog(req: DialogRequest) -> BrowserResponse:
    try:
        data = await browser_service.handle_dialog(
            req.session_id, req.accept, req.text,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/get_cookies")
async def get_cookies(req: CookieRequest) -> BrowserResponse:
    try:
        data = await browser_service.get_cookies(
            req.session_id,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/set_cookie")
async def set_cookie(req: SetCookieRequest) -> BrowserResponse:
    try:
        data = await browser_service.set_cookie(
            req.session_id, req.cookie,
            strict_session=req.strict_session,
        )
        if not data.get("success", True) is True and "error_code" in data:
            return BrowserResponse(success=False, error_code=data["error_code"], error_message=data["error_message"])
        return BrowserResponse(success=True, data=data)
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/close_session")
async def close_session(req: CloseSessionRequest) -> BrowserResponse:
    try:
        closed = await browser_service.close_session(req.session_id)
        return BrowserResponse(success=True, data={"closed": closed})
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.delete("/session/{session_id}")
async def delete_session(session_id: str) -> BrowserResponse:
    """RESTful DELETE endpoint to explicitly close and cleanup a browser session."""
    try:
        closed = await browser_service.close_session(session_id)
        return BrowserResponse(success=True, data={"closed": closed})
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )


@router.post("/get_js_errors")
async def get_js_errors(req: JsErrorsRequest) -> BrowserResponse:
    """Return collected JS errors for a given session."""
    try:
        errors = await browser_service.get_js_errors(req.session_id)
        return BrowserResponse(success=True, data={"js_errors": errors, "count": len(errors)})
    except Exception as e:
        return BrowserResponse(
            success=False, error_code=type(e).__name__, error_message=str(e)
        )

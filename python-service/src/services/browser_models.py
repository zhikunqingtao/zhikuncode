"""
浏览器自动化 Pydantic 请求/响应模型 — §10.4 B3

所有请求模型对应 WebBrowserTool.java 的 13 个 action。
统一响应格式：BrowserResponse(success, data, error_code, error_message)。
"""

from typing import Any, Optional
from pydantic import BaseModel, Field


# ═══ 通用基类 ═══

class BrowserRequestBase(BaseModel):
    """所有浏览器请求的基类 — 必含 session_id"""
    session_id: str = Field(default="default", description="Browser session ID")
    timeout: Optional[int] = Field(default=None, description="Timeout in milliseconds")
    strict_session: bool = Field(default=False, description="If true, fail when session does not exist instead of auto-creating")


# ═══ 请求模型 ═══

class NavigateRequest(BrowserRequestBase):
    url: str = Field(..., description="URL to navigate to")
    wait_until: str = Field(default="load", description="Wait condition: load, domcontentloaded, networkidle")


class ClickRequest(BrowserRequestBase):
    selector: str = Field(..., description="CSS selector for target element")
    no_wait_after: bool = Field(default=False, description="If true, skip waiting for navigation after click (useful for AJAX pages)")
    force: bool = Field(default=False, description="Force click, skip visibility/actionability checks")


class TypeRequest(BrowserRequestBase):
    selector: str = Field(..., description="CSS selector for input field")
    text: str = Field(..., description="Text to type")


class EvaluateRequest(BrowserRequestBase):
    script: str = Field(..., description="JavaScript expression to evaluate")


class ScreenshotRequest(BrowserRequestBase):
    full_page: bool = Field(default=False, description="Whether to take a full page screenshot")
    selector: Optional[str] = Field(default=None, description="CSS selector for element screenshot")


class ExtractRequest(BrowserRequestBase):
    selector: Optional[str] = Field(default=None, description="CSS selector (optional, defaults to body)")


class WaitForRequest(BrowserRequestBase):
    selector: Optional[str] = Field(default=None, description="CSS selector to wait for")
    state: Optional[str] = Field(default="visible", description="Element state: visible/hidden/attached/detached")
    wait_until: Optional[str] = Field(
        default=None,
        description="Wait condition type: networkidle, load, domcontentloaded, text_change"
    )
    text_contains: Optional[str] = Field(
        default=None,
        description="Wait until the element text contains this value (requires selector)"
    )


class SelectOptionRequest(BrowserRequestBase):
    selector: str = Field(..., description="CSS selector for select element")
    values: list[str] = Field(..., description="Values to select")


class DialogRequest(BrowserRequestBase):
    accept: bool = Field(default=True, description="Whether to accept the dialog")
    text: Optional[str] = Field(default=None, description="Text for prompt dialog")


class CookieRequest(BrowserRequestBase):
    """get_cookies 不需要额外字段"""
    pass


class SetCookieRequest(BrowserRequestBase):
    cookie: dict[str, Any] = Field(..., description="Cookie object with name, value, domain, path")


class JsErrorsRequest(BrowserRequestBase):
    """获取指定会话收集的 JS 错误"""
    pass


class CloseSessionRequest(BaseModel):
    session_id: str = Field(..., description="Session ID to close")


# ═══ 响应模型 ═══

class BrowserResponse(BaseModel):
    """统一浏览器响应 — 与 Java 端 BrowserResponse record 对应"""
    success: bool = True
    data: Optional[dict[str, Any]] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None

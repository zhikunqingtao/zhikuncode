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


# ═══ 请求模型 ═══

class NavigateRequest(BrowserRequestBase):
    url: str = Field(..., description="URL to navigate to")
    wait_until: str = Field(default="load", description="Wait condition: load, domcontentloaded, networkidle")


class ClickRequest(BrowserRequestBase):
    selector: str = Field(..., description="CSS selector for target element")


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
    selector: str = Field(..., description="CSS selector to wait for")


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


class CloseSessionRequest(BaseModel):
    session_id: str = Field(..., description="Session ID to close")


# ═══ 响应模型 ═══

class BrowserResponse(BaseModel):
    """统一浏览器响应 — 与 Java 端 BrowserResponse record 对应"""
    success: bool = True
    data: Optional[dict[str, Any]] = None
    error_code: Optional[str] = None
    error_message: Optional[str] = None

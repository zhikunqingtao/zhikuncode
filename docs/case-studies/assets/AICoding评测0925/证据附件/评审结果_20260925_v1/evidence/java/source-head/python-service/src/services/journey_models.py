"""
Journey Run 端点 Pydantic 模型 — 用户旅程验证 DSL 请求/响应定义
"""

from pydantic import BaseModel
from typing import List, Dict, Any, Optional


class JourneyRunRequest(BaseModel):
    session_id: Optional[str] = None
    base_url: str
    steps: List[Dict[str, Any]]
    record: Dict[str, bool] = {}
    viewport: Dict[str, int] = {"width": 1280, "height": 800}
    mode: str = "browser"  # "browser" 或 "http_api"
    # Optional for existing callers. Java/Python normally share the host clock;
    # an absolute deadline also rejects requests delayed beyond the caller budget.
    deadline_epoch_ms: Optional[int] = None


class StepResultModel(BaseModel):
    index: int
    action: str
    ok: bool
    duration_ms: int = 0
    screenshot_base64: Optional[str] = None
    error: Optional[str] = None
    console_errors: List[str] = []


class JourneyRunResponse(BaseModel):
    passed: bool
    step_results: List[StepResultModel]
    session_id: str
    final_url: str
    artifacts: Dict[str, str] = {}

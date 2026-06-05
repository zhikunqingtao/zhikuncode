"""
HTTP API 验证路由 — POST /journey/run

接收 HTTP DSL steps 并逐步执行 httpx 调用，返回每步结果。
依赖 HTTP_API 能力域（httpx + jsonpath-ng）。
"""

import copy
import json
import logging
import re
import sys
import time
import uuid
from typing import Dict, Any

import httpx
from fastapi import APIRouter
from jsonpath_ng import parse as jsonpath_parse

from services.journey_models import JourneyRunRequest, JourneyRunResponse, StepResultModel

logger = logging.getLogger(__name__)
router = APIRouter()

# ─── 边界防护常量 ───
MAX_STEPS = 500
MAX_CONTEXT_SIZE_BYTES = 50 * 1024 * 1024  # 50MB


@router.post("/journey/run")
async def http_journey_run(request: JourneyRunRequest) -> JourneyRunResponse:
    """
    执行 HTTP API 验证 — 逐步执行 HTTP DSL steps，首个失败即停止
    
    支持的 actions:
    - http_get: 发送 GET 请求
    - http_post: 发送 POST 请求
    - http_put: 发送 PUT 请求
    - http_delete: 发送 DELETE 请求
    - assert_status: 断言响应状态码
    - assert_json: JSONPath 断言 (jsonpath-ng)
    - assert_header: 断言响应头
    - set_variable: 从响应中提取变量（跨步骤使用）
    """
    
    session_id = request.session_id or f"http-{uuid.uuid4().hex[:8]}"
    base_url = request.base_url
    
    # 步骤数硬上限防护
    if len(request.steps) > MAX_STEPS:
        return JourneyRunResponse(
            passed=False,
            step_results=[StepResultModel(
                index=0, action="validation", ok=False, duration_ms=0,
                error=f"Journey exceeds max steps: {len(request.steps)} > {MAX_STEPS}",
                screenshot_base64=None, console_errors=[]
            )],
            session_id=session_id,
            final_url=base_url,
            artifacts={}
        )
    
    step_results = []
    context_vars = {}  # 跨步骤上下文变量
    last_response = None
    
    # Journey 级 HTTP client — 复用 TCP 连接池
    async with httpx.AsyncClient(follow_redirects=True) as client:
        for i, step in enumerate(request.steps):
            # 内存防护：监控 context_vars 大小
            if len(json.dumps(context_vars).encode('utf-8')) > MAX_CONTEXT_SIZE_BYTES:
                step_results.append(StepResultModel(
                    index=i, action=step.get("action", "unknown"), ok=False, duration_ms=0,
                    error=f"Context vars exceed {MAX_CONTEXT_SIZE_BYTES} bytes — possible runaway accumulation",
                    screenshot_base64=None, console_errors=[]
                ))
                break
            
            action = step.get("action")
            timeout = step.get("timeout", 30)
            start_time = time.time()
            
            try:
                # 变量替换
                step_resolved = _resolve_variables(step, context_vars)
                
                result = await _execute_http_step(
                    session_id, base_url, step_resolved, timeout,
                    last_response, context_vars, client
                )
                
                duration_ms = int((time.time() - start_time) * 1000)
                
                if "response_obj" in result:
                    last_response = result.pop("response_obj")
                
                step_ok = result.get("success", True) and "error" not in result
                
                step_results.append(StepResultModel(
                    index=i,
                    action=action,
                    ok=step_ok,
                    duration_ms=duration_ms,
                    error=result.get("error"),
                    screenshot_base64=None,
                    console_errors=[]
                ))
                
                if not step_ok:
                    break
                    
            except Exception as e:
                duration_ms = int((time.time() - start_time) * 1000)
                step_results.append(StepResultModel(
                    index=i,
                    action=action,
                    ok=False,
                    duration_ms=duration_ms,
                    error=str(e),
                    screenshot_base64=None,
                    console_errors=[]
                ))
                break
    
    passed = all(s.ok for s in step_results) and len(step_results) == len(request.steps)
    
    return JourneyRunResponse(
        passed=passed,
        step_results=step_results,
        session_id=session_id,
        final_url=str(last_response.url) if last_response else base_url,
        artifacts={}
    )


# ─── HTTP Action Handlers ───

async def _handle_http_get(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    url = _build_url(base_url, step.get("url", ""))
    headers = step.get("headers", {})
    params = step.get("query_params", {})
    
    try:
        response = await client.get(url, headers=headers, params=params, timeout=timeout)
        return {
            "success": True,
            "response_obj": response,
            "status_code": response.status_code,
            "body": response.text,
            "headers": dict(response.headers)
        }
    except httpx.ConnectError as e:
        return {"success": False, "error": f"Connection failed: {str(e)[:200]}"}
    except httpx.TimeoutException as e:
        return {"success": False, "error": f"Request timeout after {timeout}s"}
    except httpx.HTTPError as e:
        return {"success": False, "error": f"HTTP error: {str(e)[:200]}"}


async def _handle_http_post(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    url = _build_url(base_url, step.get("url", ""))
    body = step.get("body", {})
    headers = step.get("headers", {})
    content_type = step.get("content_type", "application/json")
    
    try:
        if content_type == "application/json":
            response = await client.post(url, json=body, headers=headers, timeout=timeout)
        else:
            response = await client.post(url, content=body, headers={
                **headers, "Content-Type": content_type
            }, timeout=timeout)
        
        return {
            "success": True,
            "response_obj": response,
            "status_code": response.status_code,
            "body": response.text,
            "headers": dict(response.headers)
        }
    except httpx.ConnectError as e:
        return {"success": False, "error": f"Connection failed: {str(e)[:200]}"}
    except httpx.TimeoutException as e:
        return {"success": False, "error": f"Request timeout after {timeout}s"}
    except httpx.HTTPError as e:
        return {"success": False, "error": f"HTTP error: {str(e)[:200]}"}


async def _handle_http_put(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    url = _build_url(base_url, step.get("url", ""))
    body = step.get("body", {})
    headers = step.get("headers", {})
    
    try:
        response = await client.put(url, json=body, headers=headers, timeout=timeout)
        return {
            "success": True,
            "response_obj": response,
            "status_code": response.status_code,
            "body": response.text,
            "headers": dict(response.headers)
        }
    except httpx.ConnectError as e:
        return {"success": False, "error": f"Connection failed: {str(e)[:200]}"}
    except httpx.TimeoutException as e:
        return {"success": False, "error": f"Request timeout after {timeout}s"}
    except httpx.HTTPError as e:
        return {"success": False, "error": f"HTTP error: {str(e)[:200]}"}


async def _handle_http_delete(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    url = _build_url(base_url, step.get("url", ""))
    headers = step.get("headers", {})
    
    try:
        response = await client.delete(url, headers=headers, timeout=timeout)
        return {
            "success": True,
            "response_obj": response,
            "status_code": response.status_code,
            "body": response.text,
            "headers": dict(response.headers)
        }
    except httpx.ConnectError as e:
        return {"success": False, "error": f"Connection failed: {str(e)[:200]}"}
    except httpx.TimeoutException as e:
        return {"success": False, "error": f"Request timeout after {timeout}s"}
    except httpx.HTTPError as e:
        return {"success": False, "error": f"HTTP error: {str(e)[:200]}"}


async def _handle_assert_status(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    expected = step.get("expected_code")
    actual = last_response.status_code if last_response else None
    
    if actual != expected:
        return {"success": False, "error": f"assert_status failed: expected {expected}, got {actual}"}
    return {"success": True}


async def _handle_assert_json(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    json_path = step.get("path")
    expected = step.get("expected")
    
    if not last_response:
        return {"success": False, "error": "No previous response for assert_json"}
    
    try:
        body_obj = last_response.json()
        jsonpath_expr = jsonpath_parse(json_path)
        matches = [m.value for m in jsonpath_expr.find(body_obj)]
        
        if not matches:
            return {"success": False, "error": f"assert_json failed: JSONPath '{json_path}' not found in response"}
        
        actual = matches[0]
        if actual != expected:
            return {"success": False, "error": f"assert_json failed: expected {expected}, got {actual}"}
        return {"success": True}
    except Exception as e:
        return {"success": False, "error": f"assert_json error: {str(e)}"}


async def _handle_assert_header(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    header_name = step.get("name")
    expected_substring = step.get("contains")
    
    if not last_response:
        return {"success": False, "error": "No previous response for assert_header"}
    
    actual = last_response.headers.get(header_name, "")
    if expected_substring not in actual:
        return {"success": False, "error": f"assert_header failed: '{expected_substring}' not in {header_name}: {actual}"}
    return {"success": True}


async def _handle_set_variable(step: dict, base_url: str, last_response, context_vars: dict, client: httpx.AsyncClient, timeout: int) -> dict:
    var_name = step.get("name")
    json_path = step.get("from_response_path")
    
    if not last_response:
        return {"success": False, "error": "No previous response for set_variable"}
    
    try:
        body_obj = last_response.json()
        jsonpath_expr = jsonpath_parse(json_path)
        matches = [m.value for m in jsonpath_expr.find(body_obj)]
        
        if not matches:
            return {"success": False, "error": f"set_variable failed: JSONPath '{json_path}' not found"}
        
        context_vars[var_name] = matches[0]
        return {"success": True, "variable": {var_name: matches[0]}}
    except Exception as e:
        return {"success": False, "error": f"set_variable error: {str(e)}"}


# ─── 策略字典 ───
HTTP_ACTION_HANDLERS = {
    "http_get": _handle_http_get,
    "http_post": _handle_http_post,
    "http_put": _handle_http_put,
    "http_delete": _handle_http_delete,
    "assert_status": _handle_assert_status,
    "assert_json": _handle_assert_json,
    "assert_header": _handle_assert_header,
    "set_variable": _handle_set_variable,
}


async def _execute_http_step(
    session_id: str, base_url: str, step: dict, timeout: int,
    last_response, context_vars: dict, client: httpx.AsyncClient
) -> dict:
    """策略字典分发"""
    action = step.get("action")
    handler = HTTP_ACTION_HANDLERS.get(action)
    if not handler:
        return {"success": False, "error": f"Unknown HTTP action: {action}"}
    return await handler(step, base_url, last_response, context_vars, client, timeout)


# ─── 辅助函数 ───

def _build_url(base_url: str, path: str) -> str:
    """构建完整 URL"""
    if path.startswith("http://") or path.startswith("https://"):
        return path
    base = base_url.rstrip("/")
    path = path.lstrip("/") if path else ""
    return f"{base}/{path}" if path else base


def _resolve_variables(step: dict, context_vars: dict) -> dict:
    """将步骤中的 ${var_name} 替换为上下文变量值"""
    resolved = copy.deepcopy(step)
    
    def replace_var(obj):
        if isinstance(obj, str):
            def replacer(m):
                var_name = m.group(1)
                value = context_vars.get(var_name, m.group(0))
                logger.debug(f"Variable substitution: ${var_name} → {value}")
                return str(value)
            return re.sub(r'\$\{(\w+)\}', replacer, obj)
        elif isinstance(obj, dict):
            return {k: replace_var(v) for k, v in obj.items()}
        elif isinstance(obj, list):
            return [replace_var(item) for item in obj]
        else:
            return obj
    
    return replace_var(resolved)

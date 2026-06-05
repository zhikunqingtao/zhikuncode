"""
test_http_api.py — HTTP API 验证路由 (/api/http/journey/run) 单元测试

被测模块: src/routers/http_api.py
关注点：
  - HTTP 动词 action handlers (http_get/post/put/delete) 的成功/异常路径
  - 断言 action handlers (assert_status/assert_json/assert_header) 的真/假分支
  - 变量系统：set_variable 抽取与 _resolve_variables 替换
  - 边界防护：MAX_STEPS=500、未知 action、首步失败即停

Mock 策略：patch routers.http_api 模块内引用的 httpx.AsyncClient 构造器，
注入一个在 async with 中返回受控 MagicMock 的伪客户端，避免真实网络。
"""
import os
import sys
import httpx
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from fastapi import FastAPI
from httpx import AsyncClient, ASGITransport

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from routers import http_api as http_api_module  # noqa: E402

# 被测路由通过 lifespan 动态注册到主 app；单测直接构建独立 app 并
# 以与生产相同的 /api/http 前缀挂载 router，避免触发 lifespan。
app = FastAPI()
app.include_router(http_api_module.router, prefix="/api/http", tags=["HTTP API Verification"])

ENDPOINT = "/api/http/journey/run"


# ─────────────────────────── Helpers ───────────────────────────

def make_response(status_code=200, json_data=None, text="", headers=None, url="http://test/api"):
    """构造伪 httpx.Response — 仅暴露被测路由用到的属性"""
    resp = MagicMock()
    resp.status_code = status_code
    resp.json = MagicMock(return_value=json_data if json_data is not None else {})
    resp.text = text or (str(json_data) if json_data is not None else "")
    resp.headers = headers if headers is not None else {}
    resp.url = url
    return resp


class _FakeAsyncClientCM:
    """异步上下文管理器：包装预置 mock client，使 `async with httpx.AsyncClient(...)` 生效"""
    def __init__(self, mock_client):
        self._client = mock_client

    async def __aenter__(self):
        return self._client

    async def __aexit__(self, exc_type, exc, tb):
        return None


def patched_async_client(mock_client):
    """返回一个可作为 httpx.AsyncClient 替身的 lambda"""
    return lambda *args, **kwargs: _FakeAsyncClientCM(mock_client)


def make_mock_client(*, get=None, post=None, put=None, delete=None):
    """构造 mock httpx 客户端，按需挂载 AsyncMock 方法"""
    client = MagicMock()
    client.get = AsyncMock(return_value=get) if not isinstance(get, Exception) else AsyncMock(side_effect=get)
    client.post = AsyncMock(return_value=post) if not isinstance(post, Exception) else AsyncMock(side_effect=post)
    client.put = AsyncMock(return_value=put) if not isinstance(put, Exception) else AsyncMock(side_effect=put)
    client.delete = AsyncMock(return_value=delete) if not isinstance(delete, Exception) else AsyncMock(side_effect=delete)
    return client


async def post_journey(payload):
    """发起一次 /journey/run 请求"""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        return await ac.post(ENDPOINT, json=payload)


# ─────────────────── 组 1: HTTP 动词 Action Handler ───────────────────

@pytest.mark.asyncio
async def test_http_get_success_200():
    """TC-HA-01: http_get 正常请求返回 200，step ok=true"""
    resp = make_response(status_code=200, json_data={"hello": "world"}, url="http://api.test/items")
    client = make_mock_client(get=resp)

    payload = {
        "session_id": "s1",
        "base_url": "http://api.test",
        "steps": [{"action": "http_get", "url": "/items"}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    assert r.status_code == 200
    body = r.json()
    assert body["passed"] is True
    assert len(body["step_results"]) == 1
    assert body["step_results"][0]["ok"] is True
    assert body["step_results"][0]["action"] == "http_get"
    client.get.assert_awaited_once()
    called_url = client.get.call_args.args[0]
    assert called_url == "http://api.test/items"


@pytest.mark.asyncio
async def test_http_get_connect_error():
    """TC-HA-02: http_get 连接失败 → ok=false，error 含 'Connection failed'"""
    client = make_mock_client(get=httpx.ConnectError("DNS lookup failed"))

    payload = {
        "base_url": "http://unreachable.test",
        "steps": [{"action": "http_get", "url": "/x"}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][0]["ok"] is False
    assert "Connection failed" in body["step_results"][0]["error"]


@pytest.mark.asyncio
async def test_http_get_timeout():
    """TC-HA-03: http_get 超时 → ok=false，error 含 'timeout'"""
    client = make_mock_client(get=httpx.TimeoutException("read timeout"))

    payload = {
        "base_url": "http://slow.test",
        "steps": [{"action": "http_get", "url": "/x", "timeout": 5}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][0]["ok"] is False
    assert "timeout" in body["step_results"][0]["error"].lower()


@pytest.mark.asyncio
async def test_http_post_with_json_body():
    """TC-HA-04: http_post 携带 JSON body 正常发送"""
    resp = make_response(status_code=201, json_data={"id": 7})
    client = make_mock_client(post=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [{
            "action": "http_post", "url": "/users",
            "body": {"name": "alice", "age": 30},
            "headers": {"X-Trace": "t1"},
        }],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    assert r.status_code == 200
    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][0]["ok"] is True
    client.post.assert_awaited_once()
    kwargs = client.post.call_args.kwargs
    assert kwargs["json"] == {"name": "alice", "age": 30}
    assert kwargs["headers"] == {"X-Trace": "t1"}


@pytest.mark.asyncio
async def test_http_put_success():
    """TC-HA-05: http_put 正常请求"""
    resp = make_response(status_code=200, json_data={"updated": True})
    client = make_mock_client(put=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [{"action": "http_put", "url": "/users/1", "body": {"name": "bob"}}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][0]["ok"] is True
    client.put.assert_awaited_once()


@pytest.mark.asyncio
async def test_http_delete_success():
    """TC-HA-06: http_delete 正常请求"""
    resp = make_response(status_code=204, json_data=None)
    client = make_mock_client(delete=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [{"action": "http_delete", "url": "/users/1"}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][0]["ok"] is True
    client.delete.assert_awaited_once()


# ─────────────────── 组 2: 断言 Action Handler ───────────────────

@pytest.mark.asyncio
async def test_assert_status_match():
    """TC-HA-07: assert_status 实际 200 == 预期 200 → ok=true"""
    resp = make_response(status_code=200, json_data={})
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/x"},
            {"action": "assert_status", "expected_code": 200},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][1]["ok"] is True


@pytest.mark.asyncio
async def test_assert_status_mismatch():
    """TC-HA-08: assert_status 实际 200 != 预期 201 → ok=false"""
    resp = make_response(status_code=200, json_data={})
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/x"},
            {"action": "assert_status", "expected_code": 201},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][1]["ok"] is False
    assert "expected 201" in body["step_results"][1]["error"]


@pytest.mark.asyncio
async def test_assert_json_path_match():
    """TC-HA-09: assert_json JSONPath 匹配且值相等 → ok=true"""
    resp = make_response(status_code=200, json_data={"data": {"name": "alice"}})
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/me"},
            {"action": "assert_json", "path": "$.data.name", "expected": "alice"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][1]["ok"] is True


@pytest.mark.asyncio
async def test_assert_json_path_not_found():
    """TC-HA-10: assert_json JSONPath 不存在 → ok=false，error 含 'not found'"""
    resp = make_response(status_code=200, json_data={"data": {}})
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/me"},
            {"action": "assert_json", "path": "$.data.missing", "expected": "x"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][1]["ok"] is False
    assert "not found" in body["step_results"][1]["error"]


@pytest.mark.asyncio
async def test_assert_header_contains():
    """TC-HA-11: assert_header 子串匹配 → ok=true"""
    resp = make_response(
        status_code=200, json_data={},
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/x"},
            {"action": "assert_header", "name": "Content-Type", "contains": "application/json"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][1]["ok"] is True


@pytest.mark.asyncio
async def test_assert_header_not_contains():
    """TC-HA-12: assert_header 子串不匹配 → ok=false"""
    resp = make_response(
        status_code=200, json_data={},
        headers={"Content-Type": "text/html"},
    )
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/x"},
            {"action": "assert_header", "name": "Content-Type", "contains": "application/json"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][1]["ok"] is False
    assert "not in" in body["step_results"][1]["error"]


# ─────────────────── 组 3: 变量系统 ───────────────────

@pytest.mark.asyncio
async def test_set_variable_extracts_from_json():
    """TC-HA-13: set_variable 通过 JSONPath 从响应抽取变量并标记 ok"""
    resp = make_response(status_code=200, json_data={"data": {"token": "abc123"}})
    client = make_mock_client(get=resp)

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/login"},
            {"action": "set_variable", "name": "token", "from_response_path": "$.data.token"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert body["step_results"][1]["ok"] is True
    assert body["step_results"][1]["action"] == "set_variable"


def test_resolve_variables_substitutes_in_url():
    """TC-HA-14: _resolve_variables 将 ${var} 替换为 context_vars 中的值（URL 字段）"""
    step = {"action": "http_get", "url": "/users/${uid}/orders/${oid}"}
    context = {"uid": "42", "oid": "7"}

    resolved = http_api_module._resolve_variables(step, context)

    assert resolved["url"] == "/users/42/orders/7"
    # 原 step 不被修改（深拷贝）
    assert step["url"] == "/users/${uid}/orders/${oid}"


@pytest.mark.asyncio
async def test_cross_step_variable_propagation():
    """TC-HA-15: set_variable 后续步骤可使用 ${var} — 第二个 http_get URL 被替换"""
    login_resp = make_response(status_code=200, json_data={"data": {"token": "T0KEN"}})
    fetch_resp = make_response(status_code=200, json_data={"ok": True})
    # get 被调用 2 次：第一次返回 login_resp，第二次返回 fetch_resp
    client = MagicMock()
    client.get = AsyncMock(side_effect=[login_resp, fetch_resp])

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/login"},
            {"action": "set_variable", "name": "token", "from_response_path": "$.data.token"},
            {"action": "http_get", "url": "/items/${token}"},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is True
    assert len(body["step_results"]) == 3
    assert all(s["ok"] for s in body["step_results"])
    # 第二次 get 调用的 URL 应该已经替换变量
    second_call_url = client.get.call_args_list[1].args[0]
    assert second_call_url == "http://api.test/items/T0KEN"


# ─────────────── 组 4: 边界防护与流控 ───────────────

@pytest.mark.asyncio
async def test_max_steps_boundary_rejects_overflow():
    """TC-HA-16: steps 数量 > MAX_STEPS(500) 时拒绝执行，返回 validation 错误"""
    assert http_api_module.MAX_STEPS == 500
    over_limit_steps = [{"action": "http_get", "url": "/x"}] * (http_api_module.MAX_STEPS + 1)
    client = make_mock_client(get=make_response())

    payload = {
        "base_url": "http://api.test",
        "steps": over_limit_steps,
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert len(body["step_results"]) == 1
    assert body["step_results"][0]["action"] == "validation"
    assert "exceeds max steps" in body["step_results"][0]["error"]
    # 因为 validation 在执行循环之前，client.get 不应被调用
    client.get.assert_not_called()


@pytest.mark.asyncio
async def test_first_step_failure_stops_subsequent_execution():
    """TC-HA-17: step 0 失败后，step 1 不被执行（首步失败即停）"""
    client = MagicMock()
    client.get = AsyncMock(side_effect=httpx.ConnectError("boom"))
    client.post = AsyncMock(return_value=make_response())

    payload = {
        "base_url": "http://api.test",
        "steps": [
            {"action": "http_get", "url": "/dead"},
            {"action": "http_post", "url": "/should-not-run", "body": {}},
        ],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert len(body["step_results"]) == 1
    assert body["step_results"][0]["ok"] is False
    client.post.assert_not_called()


@pytest.mark.asyncio
async def test_unsupported_action_returns_error():
    """TC-HA-18: 不支持的 action → ok=false，error 含 'Unknown HTTP action'"""
    client = make_mock_client()

    payload = {
        "base_url": "http://api.test",
        "steps": [{"action": "do_magic", "url": "/x"}],
        "mode": "http_api",
    }
    with patch.object(http_api_module.httpx, "AsyncClient", patched_async_client(client)):
        r = await post_journey(payload)

    body = r.json()
    assert body["passed"] is False
    assert body["step_results"][0]["ok"] is False
    assert "Unknown HTTP action" in body["step_results"][0]["error"]

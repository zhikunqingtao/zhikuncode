"""CLI 权限模式与后端 PermissionMode 协议的契约测试。"""

import inspect
import pytest
import httpx

from typer.testing import CliRunner

import cli.main as cli_main
from cli.main import PermissionMode, main


runner = CliRunner()


def test_cli_permission_modes_match_backend_contract():
    assert {mode.value for mode in PermissionMode} == {
        "default",
        "plan",
        "accept_edits",
        "dont_ask",
        "auto_approve",
    }


def test_cli_does_not_expose_permission_bypass_flag():
    """AUTO_APPROVE 只是一种权限模式，不引入跳过全部安全检查的入口。"""
    assert "no_permissions" not in inspect.signature(main).parameters
    assert "skip_all_prompts" not in {mode.value for mode in PermissionMode}


@pytest.mark.parametrize("resume", [False, True])
@pytest.mark.parametrize("output", ["json", "stream-json"])
@pytest.mark.parametrize("mode", [None, "auto_approve", "dont_ask", "plan"])
def test_cli_only_sends_explicit_permission_mode(monkeypatch, resume, output, mode):
    bodies = []

    class FakeClient:
        def __init__(self, **_kwargs):
            pass

        def sync_query(self, body):
            bodies.append(body)
            return {"sessionId": "session-1", "result": "ok"}

        def stream_query(self, body):
            bodies.append(body)
            return iter([])

    monkeypatch.setattr(cli_main, "AicaClient", FakeClient)
    monkeypatch.setattr(cli_main.SessionCache, "save_last_session", lambda *_a, **_kw: None)
    args = ["hello", "--output-format", output]
    if resume:
        args += ["--resume", "session-1"]
    if mode:
        args += ["--permission-mode", mode]
    result = runner.invoke(cli_main.app, args)
    assert result.exit_code == 0, result.output
    assert len(bodies) == 1
    if mode is None:
        assert "permissionMode" not in bodies[0]
    else:
        assert bodies[0]["permissionMode"] == mode.upper()
    if resume:
        assert bodies[0]["sessionId"] == "session-1"


@pytest.mark.parametrize("output", ["json", "stream-json"])
def test_cli_explains_mode_conflict_without_retrying(monkeypatch, output):
    calls = []

    class FakeClient:
        def __init__(self, **_kwargs):
            pass

        def sync_query(self, body):
            calls.append(body)
            response = httpx.Response(409, request=httpx.Request("POST", "http://localhost/api/query"),
                json={"error": {"code": "PERMISSION_MODE_MISMATCH"}})
            response.raise_for_status()

        stream_query = sync_query

    monkeypatch.setattr(cli_main, "AicaClient", FakeClient)
    result = runner.invoke(cli_main.app, ["hello", "--resume", "session-1", "--output-format", output,
        "--permission-mode", "plan"])
    assert result.exit_code == 1
    assert "指定权限与会话权限不一致" in result.output
    assert len(calls) == 1


def test_streaming_client_preserves_conflict_body_after_closing_stream(monkeypatch):
    from cli.client import AicaClient

    class Body(httpx.SyncByteStream):
        def __iter__(self):
            yield b'{"error":{"code":"PERMISSION_MODE_MISMATCH"}}'

    def conflict(request):
        assert request.headers['Accept'] == 'text/event-stream, application/json'
        return httpx.Response(409, stream=Body())

    transport = httpx.MockTransport(conflict)
    client_type = httpx.Client
    monkeypatch.setattr(httpx, "Client", lambda **kwargs: client_type(transport=transport, **kwargs))
    with pytest.raises(httpx.HTTPStatusError) as caught:
        list(AicaClient(token="test-token").stream_query({"prompt": "hello"}))
    assert caught.value.response.is_closed
    assert caught.value.response.json()["error"]["code"] == "PERMISSION_MODE_MISMATCH"

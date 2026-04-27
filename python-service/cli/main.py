"""
aica CLI 入口模块 — §4.21

Typer CLI 框架 + httpx HTTP 客户端 + Rich 终端美化。
命令名称: aica (AI Code Assistant 缩写，4 字符，易输入)

退出码:
  0 — 成功完成
  1 — 通用错误
  2 — 参数错误
  3 — 连接错误
  4 — 认证错误
  130 — SIGINT 中断
"""

import os
import sys
import json
import signal
from pathlib import Path
from typing import Optional
from enum import Enum
from importlib.metadata import version as pkg_version, PackageNotFoundError

import typer
import httpx
from rich.console import Console
from rich.markdown import Markdown

from .client import AicaClient, StreamEvent
from .session import SessionCache

app = typer.Typer(
    name="aica",
    help="AI Code Assistant CLI — 通过管道和脚本调用 AI 编程助手",
    no_args_is_help=True,
    add_completion=True,
)
console = Console(stderr=True)   # 元信息输出到 stderr
stdout_console = Console()       # LLM 内容输出到 stdout


def _version_callback(value: bool):
    if value:
        try:
            v = pkg_version("zhikuncode-python-service")
        except PackageNotFoundError:
            v = "1.0.0"
        print(f"aica {v}")
        raise typer.Exit()


class OutputFormat(str, Enum):
    text = "text"
    json = "json"
    stream_json = "stream-json"


class PermissionMode(str, Enum):
    dont_ask = "dont_ask"
    bypass = "bypass"
    default = "default"


class EffortLevel(str, Enum):
    low = "low"
    medium = "medium"
    high = "high"
    max = "max"


def _handle_sigint(signum, frame):
    """Ctrl+C → exit 130"""
    console.print("\n[dim]Interrupted[/dim]")
    sys.exit(130)


@app.command()
def main(
    prompt: Optional[str] = typer.Argument(None, help="查询内容"),
    version: bool = typer.Option(
        False, "--version", "-V", callback=_version_callback,
        is_eager=True, help="显示版本号"),
    # 输出控制
    output_format: OutputFormat = typer.Option(
        OutputFormat.text, "--output-format", "-f", help="输出格式"),
    input_format: str = typer.Option(
        "text", "--input-format", help="输入格式: text | stream-json"),
    include_partial_messages: bool = typer.Option(
        False, "--include-partial-messages", help="包含部分消息块(仅stream-json)"),
    verbose: bool = typer.Option(False, "--verbose", help="详细输出"),
    quiet: bool = typer.Option(False, "--quiet", "-q", help="静默模式"),
    # 模型与行为
    model: Optional[str] = typer.Option(None, "--model", "-m", help="指定模型"),
    effort: Optional[EffortLevel] = typer.Option(
        None, "--effort", help="推理努力等级"),
    fallback_model: Optional[str] = typer.Option(
        None, "--fallback-model", help="主模型过载时降级模型"),
    system_prompt: Optional[str] = typer.Option(
        None, "--system-prompt", help="替换系统提示"),
    system_prompt_file: Optional[Path] = typer.Option(
        None, "--system-prompt-file", help="从文件读取系统提示"),
    append_system_prompt: Optional[str] = typer.Option(
        None, "--append-system-prompt", help="追加系统提示"),
    max_turns: Optional[int] = typer.Option(
        None, "--max-turns", help="最大轮次"),
    max_budget: Optional[float] = typer.Option(
        None, "--max-budget", help="预算上限 USD"),
    json_schema: Optional[str] = typer.Option(
        None, "--json-schema", help="JSON Schema 约束输出结构"),
    # 权限
    permission_mode: PermissionMode = typer.Option(
        PermissionMode.dont_ask, "--permission-mode", help="权限模式"),
    no_permissions: bool = typer.Option(
        False, "--no-permissions", help="跳过所有权限"),
    # 工具
    allowed_tools: Optional[str] = typer.Option(
        None, "--allowed-tools", help="工具白名单(逗号分隔)"),
    disallowed_tools: Optional[str] = typer.Option(
        None, "--disallowed-tools", help="工具黑名单(逗号分隔)"),
    tools: Optional[str] = typer.Option(
        None, "--tools", help="指定可用工具集(逗号分隔)"),
    # 会话
    continue_session: bool = typer.Option(
        False, "--continue", "-c", help="继续上次会话"),
    resume: Optional[str] = typer.Option(
        None, "--resume", "-r", help="恢复指定会话"),
    session_id: Optional[str] = typer.Option(
        None, "--session-id", help="使用指定会话 ID"),
    fork_session: bool = typer.Option(
        False, "--fork-session", help="恢复时创建新会话 ID"),
    name: Optional[str] = typer.Option(
        None, "--name", "-n", help="会话显示名称"),
    no_session: bool = typer.Option(
        False, "--no-session", help="不持久化会话"),
    # 连接
    server: str = typer.Option(
        "http://localhost:8080", "--server", "-s", help="后端地址"),
    token: Optional[str] = typer.Option(
        None, "--token", help="认证 Token"),
    timeout: int = typer.Option(300, "--timeout", help="超时秒数"),
    # MCP
    mcp_config: Optional[str] = typer.Option(
        None, "--mcp-config", help="MCP 配置文件"),
    # 工作目录
    working_dir: Optional[str] = typer.Option(
        None, "--working-dir", "-w", help="工作目录"),
):
    """
    AI Code Assistant — 命令行查询接口。

    支持管道输入:  cat file.py | aica "review this"
    支持结构化输出: aica -f json "query" | jq '.result'
    """
    signal.signal(signal.SIGINT, _handle_sigint)

    # 1. 读取 stdin（如果是管道）
    stdin_content = None
    if not sys.stdin.isatty():
        stdin_content = sys.stdin.read(1024 * 1024)  # 最大 1MB
        if len(stdin_content) >= 1024 * 1024:
            console.print("[yellow]Warning: stdin truncated at 1MB[/yellow]")

    # 2. 从文件读取系统提示
    effective_system_prompt = system_prompt
    if system_prompt_file and not system_prompt:
        effective_system_prompt = system_prompt_file.read_text(encoding="utf-8")

    # 3. 验证输入
    if not prompt and not stdin_content:
        console.print("[red]Error: No prompt or stdin input[/red]")
        raise typer.Exit(code=2)

    # 4. 解析权限模式
    perm = "BYPASS" if no_permissions else permission_mode.value.upper()
    if no_permissions and not quiet:
        console.print("[yellow]WARNING: All permissions bypassed[/yellow]")

    # 5. 解析会话
    cache = SessionCache()
    wd = working_dir or os.getcwd()
    resolved_sid = session_id
    if continue_session and not resolved_sid:
        resolved_sid = cache.get_last_session(wd)
    elif resume and not resolved_sid:
        resolved_sid = resume

    # 6. 构建请求
    request_body: dict = {
        "prompt": prompt,
        "model": model,
        "effort": effort.value if effort else None,
        "fallbackModel": fallback_model,
        "systemPrompt": effective_system_prompt,
        "appendSystemPrompt": append_system_prompt,
        "permissionMode": perm,
        "maxTurns": max_turns or 10,
        "maxBudgetUsd": max_budget,
        "allowedTools": allowed_tools.split(",") if allowed_tools else None,
        "disallowedTools": disallowed_tools.split(",") if disallowed_tools else None,
        "tools": tools.split(",") if tools else None,
        "sessionId": resolved_sid,
        "forkSession": fork_session or None,
        "name": name,
        "workingDirectory": wd,
        "timeoutSeconds": timeout,
        "jsonSchema": json_schema,
        "includePartialMessages": include_partial_messages or None,
        "context": {"stdin": stdin_content} if stdin_content else None,
    }
    request_body = {k: v for k, v in request_body.items() if v is not None}

    # 7. 创建客户端并执行查询
    client = AicaClient(server=server, token=token, timeout=timeout)

    response_sid = None
    try:
        if output_format == OutputFormat.stream_json:
            response_sid = _stream_query(client, request_body, verbose)
        elif output_format == OutputFormat.json:
            response_sid = _sync_query_json(client, request_body)
        else:
            response_sid = _sync_query_text(client, request_body, verbose, quiet)
    except httpx.ConnectError:
        console.print(f"[red]Error: Backend not reachable at {server}[/red]")
        raise typer.Exit(code=3)
    except httpx.HTTPStatusError as e:
        if e.response.status_code in (401, 403):
            console.print("[red]Error: Authentication failed[/red]")
            raise typer.Exit(code=4)
        console.print(f"[red]Error: HTTP {e.response.status_code}[/red]")
        raise typer.Exit(code=1)

    # 8. 更新本地会话缓存（优先使用后端响应中的 sessionId）
    final_sid = response_sid or resolved_sid or ""
    if not no_session:
        cache.save_last_session(wd, final_sid, model or "")


def _stream_query(client: AicaClient, body: dict, verbose: bool) -> Optional[str]:
    """SSE 流式查询 — POST /api/query/stream"""
    response_session_id = None
    for event in client.stream_query(body):
        print(json.dumps(event, ensure_ascii=False), flush=True)
        # 从 message_complete 事件中提取 sessionId
        if isinstance(event, dict) and event.get("sessionId"):
            response_session_id = event["sessionId"]
    return response_session_id


def _sync_query_json(client: AicaClient, body: dict) -> Optional[str]:
    """同步查询 JSON 输出"""
    data = client.sync_query(body)
    print(json.dumps(data, ensure_ascii=False, indent=2))
    return data.get("sessionId")


def _sync_query_text(client: AicaClient, body: dict,
                     verbose: bool, quiet: bool) -> Optional[str]:
    """同步查询文本输出"""
    if not quiet:
        console.print("[dim]Thinking...[/dim]")
    data = client.sync_query(body)
    result = data.get("result", "")

    if sys.stdout.isatty():
        stdout_console.print(Markdown(result))
    else:
        print(result)

    if verbose and not quiet:
        usage = data.get("usage", {})
        cost = data.get("costUsd", 0)
        console.print(
            f"[dim]Tokens: {usage.get('inputTokens', 0)}in "
            f"+ {usage.get('outputTokens', 0)}out | "
            f"Cost: ${cost:.4f}[/dim]"
        )

    return data.get("sessionId")


if __name__ == "__main__":
    app()

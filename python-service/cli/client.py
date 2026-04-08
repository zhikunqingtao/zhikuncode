"""
aica HTTP 客户端 — §4.21.8

httpx 同步/异步 HTTP + SSE 客户端封装。
所有请求路由到 Spring Boot REST + SSE 端点。
"""

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional

import httpx

logger = logging.getLogger(__name__)

# Token 默认路径
DEFAULT_TOKEN_PATH = Path.home() / ".config" / "ai-code-assistant" / "access-token"

# 环境变量映射
ENV_SERVER = "AICA_SERVER"
ENV_TOKEN = "AICA_TOKEN"
ENV_MODEL = "AICA_MODEL"


@dataclass
class StreamEvent:
    """SSE 流式事件"""
    type: str       # thinking | text | tool_use | tool_result | error | message_complete
    data: dict


class AicaClient:
    """aica CLI HTTP 客户端 — httpx 封装"""

    def __init__(
        self,
        server: str = "http://localhost:8080",
        token: Optional[str] = None,
        timeout: int = 300,
    ) -> None:
        self.server = server.rstrip("/")
        self.token = token or self._load_token()
        self.timeout = timeout

    def _load_token(self) -> Optional[str]:
        """加载认证 Token: 环境变量 > 文件"""
        import os
        env_token = os.environ.get(ENV_TOKEN)
        if env_token:
            return env_token
        if DEFAULT_TOKEN_PATH.exists():
            return DEFAULT_TOKEN_PATH.read_text(encoding="utf-8").strip()
        return None

    def _headers(self) -> dict[str, str]:
        headers: dict[str, str] = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def sync_query(self, body: dict) -> dict:
        """同步查询 — POST /api/query"""
        url = f"{self.server}/api/query"
        response = httpx.post(
            url,
            json=body,
            headers=self._headers(),
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.json()

    def stream_query(self, body: dict) -> Iterator[dict]:
        """SSE 流式查询 — POST /api/query/stream

        使用 httpx 的 stream 功能读取 SSE 事件流，
        每个事件解析为 JSON dict 并 yield。
        """
        url = f"{self.server}/api/query/stream"
        headers = self._headers()
        headers["Accept"] = "text/event-stream"

        with httpx.Client(timeout=self.timeout) as client:
            with client.stream("POST", url, json=body, headers=headers) as response:
                response.raise_for_status()
                buffer = ""
                for chunk in response.iter_text():
                    buffer += chunk
                    while "\n\n" in buffer:
                        event_str, buffer = buffer.split("\n\n", 1)
                        parsed = self._parse_sse_event(event_str)
                        if parsed:
                            yield parsed

    def conversation_query(self, body: dict) -> dict:
        """多轮会话查询 — POST /api/query/conversation"""
        url = f"{self.server}/api/query/conversation"
        response = httpx.post(
            url,
            json=body,
            headers=self._headers(),
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.json()

    def health_check(self) -> dict:
        """健康检查 — GET /api/health"""
        url = f"{self.server}/api/health"
        response = httpx.get(url, headers=self._headers(), timeout=5)
        response.raise_for_status()
        return response.json()

    @staticmethod
    def _parse_sse_event(event_str: str) -> Optional[dict]:
        """解析 SSE 事件字符串为 dict"""
        data_lines = []
        for line in event_str.strip().split("\n"):
            if line.startswith("data:"):
                data_lines.append(line[5:].strip())
            elif line.startswith("data: "):
                data_lines.append(line[6:])
        if data_lines:
            raw = "\n".join(data_lines)
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                logger.warning(f"Failed to parse SSE data: {raw[:200]}")
        return None

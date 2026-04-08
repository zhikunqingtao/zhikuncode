"""
CLI 会话管理 — §4.21.5

本地缓存文件: ~/.config/ai-code-assistant/cli-sessions.json
格式: { "/path/to/project": { "lastSessionId": "uuid", "model": "...", "updatedAt": "ISO-8601" } }
"""

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

CLI_SESSIONS_PATH = (
    Path.home() / ".config" / "ai-code-assistant" / "cli-sessions.json"
)


class SessionCache:
    """CLI 本地会话缓存"""

    def __init__(self, path: Optional[Path] = None) -> None:
        self.path = path or CLI_SESSIONS_PATH

    def _load(self) -> dict:
        if not self.path.exists():
            return {}
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}

    def _save(self, data: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(data, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

    def get_last_session(self, working_dir: str) -> Optional[str]:
        """获取指定工作目录的最近会话 ID"""
        data = self._load()
        entry = data.get(working_dir)
        if entry:
            return entry.get("lastSessionId")
        return None

    def save_last_session(
        self, working_dir: str, session_id: str, model: str = ""
    ) -> None:
        """保存会话 ID 到本地缓存"""
        if not session_id:
            return
        data = self._load()
        data[working_dir] = {
            "lastSessionId": session_id,
            "model": model,
            "updatedAt": datetime.now(timezone.utc).isoformat(),
        }
        self._save(data)

    def list_sessions(self) -> dict:
        """列出所有缓存的会话"""
        return self._load()

    def clear(self) -> None:
        """清除所有缓存"""
        if self.path.exists():
            self.path.unlink()

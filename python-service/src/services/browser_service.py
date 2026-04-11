"""
浏览器自动化核心服务 — §10.4 B3

管理 Playwright 实例和多浏览器会话。
三层架构：Java WebBrowserTool → Python FastAPI Router → BrowserService → Playwright。

生命周期：
  startup()  → 启动 Playwright + 浏览器进程
  shutdown() → 关闭所有会话 + 浏览器进程 + Playwright

会话管理：
  - 每个 session_id 对应独立的 BrowserContext + Page
  - 空闲超时自动清理（默认 5 分钟）
  - 最大并发会话数限制（默认 10）
"""

import asyncio
import base64
import logging
import os
from datetime import datetime, timedelta
from typing import Optional

from playwright.async_api import (
    async_playwright,
    Browser,
    BrowserContext,
    Page,
    Playwright,
)

logger = logging.getLogger(__name__)


class BrowserSession:
    """单个浏览器会话 — 对应一个独立的 BrowserContext"""

    def __init__(self, context: BrowserContext, page: Page, created_at: datetime):
        self.context = context
        self.page = page
        self.created_at = created_at
        self.last_activity = created_at
        self.dialog_handler_set = False
        self._pending_dialog = None

    def touch(self):
        """更新最后活动时间"""
        self.last_activity = datetime.now()

    def is_expired(self, idle_timeout: timedelta) -> bool:
        return datetime.now() - self.last_activity > idle_timeout


class BrowserService:
    """
    浏览器自动化服务 — 管理 Playwright 实例和多会话。

    配置项（环境变量覆盖）：
      BROWSER_TYPE            chromium/firefox/webkit（默认 chromium）
      BROWSER_HEADLESS        true/false（默认 true）
      BROWSER_IDLE_TIMEOUT_MIN  空闲超时分钟数（默认 5）
      BROWSER_MAX_SESSIONS    最大并发会话数（默认 10）
      BROWSER_DEFAULT_TIMEOUT_MS  默认操作超时毫秒（默认 30000）
    """

    def __init__(self):
        self._playwright: Optional[Playwright] = None
        self._browser: Optional[Browser] = None
        self._sessions: dict[str, BrowserSession] = {}
        self._lock = asyncio.Lock()
        self._cleanup_task: Optional[asyncio.Task] = None

        # 配置（可通过环境变量覆盖）
        self.browser_type = os.getenv("BROWSER_TYPE", "chromium")
        self.headless = os.getenv("BROWSER_HEADLESS", "true").lower() == "true"
        self.idle_timeout = timedelta(
            minutes=int(os.getenv("BROWSER_IDLE_TIMEOUT_MIN", "5"))
        )
        self.max_sessions = int(os.getenv("BROWSER_MAX_SESSIONS", "10"))
        self.default_timeout = int(os.getenv("BROWSER_DEFAULT_TIMEOUT_MS", "30000"))

    # ═══ 生命周期 ═══

    async def startup(self):
        """启动 Playwright 和浏览器进程"""
        self._playwright = await async_playwright().start()
        launcher = getattr(self._playwright, self.browser_type)
        self._browser = await launcher.launch(
            headless=self.headless,
            args=[
                "--no-sandbox",
                "--disable-dev-shm-usage",  # 容器友好
                "--disable-gpu",
                "--disable-extensions",
            ],
        )
        # 启动定期清理任务
        self._cleanup_task = asyncio.create_task(self._periodic_cleanup())
        logger.info(
            f"BrowserService started: {self.browser_type}, headless={self.headless}"
        )

    async def shutdown(self):
        """关闭所有资源"""
        if self._cleanup_task:
            self._cleanup_task.cancel()
        for sid in list(self._sessions.keys()):
            await self.close_session(sid)
        if self._browser:
            await self._browser.close()
        if self._playwright:
            await self._playwright.stop()
        logger.info("BrowserService shutdown complete")

    # ═══ 会话管理 ═══

    async def get_or_create_session(self, session_id: str) -> BrowserSession:
        """获取现有会话或创建新会话"""
        async with self._lock:
            if session_id in self._sessions:
                session = self._sessions[session_id]
                session.touch()
                return session

            if len(self._sessions) >= self.max_sessions:
                # 清理最久未活动的会话
                oldest_sid = min(
                    self._sessions,
                    key=lambda s: self._sessions[s].last_activity,
                )
                await self._close_session_unsafe(oldest_sid)
                logger.warning(f"Session limit reached, evicted oldest: {oldest_sid}")

            context = await self._browser.new_context(
                viewport={"width": 1280, "height": 720},
                user_agent="ZhikuCode-Browser/1.0",
                ignore_https_errors=True,
            )
            page = await context.new_page()
            page.set_default_timeout(self.default_timeout)
            session = BrowserSession(context, page, datetime.now())
            self._sessions[session_id] = session
            logger.info(
                f"New browser session: {session_id} (total: {len(self._sessions)})"
            )
            return session

    async def close_session(self, session_id: str) -> bool:
        async with self._lock:
            return await self._close_session_unsafe(session_id)

    async def _close_session_unsafe(self, session_id: str) -> bool:
        session = self._sessions.pop(session_id, None)
        if session:
            try:
                await session.context.close()
            except Exception as e:
                logger.warning(f"Error closing session {session_id}: {e}")
            return True
        return False

    async def _periodic_cleanup(self):
        """每 60 秒检查并清理过期会话"""
        while True:
            try:
                await asyncio.sleep(60)
                async with self._lock:
                    expired = [
                        sid
                        for sid, s in self._sessions.items()
                        if s.is_expired(self.idle_timeout)
                    ]
                    for sid in expired:
                        await self._close_session_unsafe(sid)
                        logger.info(f"Expired session cleaned: {sid}")
            except asyncio.CancelledError:
                break  # 正常关闭
            except Exception as e:
                logger.error(f"Cleanup error: {e}")  # 异常不中断清理循环

    # ═══ 浏览器操作方法 ═══

    async def navigate(
        self,
        session_id: str,
        url: str,
        wait_until: str = "load",
        timeout: int = None,
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        resp = await session.page.goto(
            url,
            wait_until=wait_until,
            timeout=timeout or self.default_timeout,
        )
        return {
            "url": session.page.url,
            "title": await session.page.title(),
            "status": resp.status if resp else None,
        }

    async def screenshot(
        self,
        session_id: str,
        full_page: bool = False,
        selector: str = None,
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        if selector:
            element = await session.page.query_selector(selector)
            if not element:
                raise ValueError(f"Element not found: {selector}")
            raw = await element.screenshot(type="png")
        else:
            raw = await session.page.screenshot(full_page=full_page, type="png")
        return {
            "screenshot_base64": base64.b64encode(raw).decode(),
            "size": len(raw),
        }

    async def click(
        self, session_id: str, selector: str, timeout: int = None
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        await session.page.click(selector, timeout=timeout or self.default_timeout)
        return {"clicked": selector, "url": session.page.url}

    async def type_text(
        self,
        session_id: str,
        selector: str,
        text: str,
        timeout: int = None,
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        await session.page.fill(selector, text, timeout=timeout or self.default_timeout)
        return {"filled": selector, "text_length": len(text)}

    async def evaluate(self, session_id: str, script: str) -> dict:
        session = await self.get_or_create_session(session_id)
        result = await session.page.evaluate(script)
        return {"result": str(result) if result is not None else None}

    async def extract_text(self, session_id: str, selector: str = None) -> dict:
        session = await self.get_or_create_session(session_id)
        if selector:
            element = await session.page.query_selector(selector)
            text = await element.inner_text() if element else ""
        else:
            text = await session.page.inner_text("body")
        # 截断过长文本（防止 token 溢出）
        max_len = 50000
        truncated = len(text) > max_len
        return {"text": text[:max_len], "length": len(text), "truncated": truncated}

    async def extract_html(self, session_id: str, selector: str = None) -> dict:
        session = await self.get_or_create_session(session_id)
        if selector:
            element = await session.page.query_selector(selector)
            html = await element.inner_html() if element else ""
        else:
            html = await session.page.content()
        max_len = 100000
        truncated = len(html) > max_len
        return {"html": html[:max_len], "length": len(html), "truncated": truncated}

    async def wait_for_selector(
        self, session_id: str, selector: str, timeout: int = None
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        await session.page.wait_for_selector(
            selector, timeout=timeout or self.default_timeout
        )
        return {"found": selector}

    async def select_option(
        self, session_id: str, selector: str, values: list[str]
    ) -> dict:
        session = await self.get_or_create_session(session_id)
        selected = await session.page.select_option(selector, values)
        return {"selected": selected}

    async def handle_dialog(
        self, session_id: str, accept: bool, text: str = None
    ) -> dict:
        session = await self.get_or_create_session(session_id)

        # 注册对话框处理器（下一次对话框弹出时自动处理）
        async def on_dialog(dialog):
            if accept:
                await dialog.accept(text or "")
            else:
                await dialog.dismiss()

        session.page.once("dialog", on_dialog)
        return {"dialog_handler": "registered", "accept": accept}

    async def get_cookies(self, session_id: str) -> dict:
        session = await self.get_or_create_session(session_id)
        cookies = await session.context.cookies()
        return {"cookies": cookies}

    async def set_cookie(self, session_id: str, cookie: dict) -> dict:
        session = await self.get_or_create_session(session_id)
        await session.context.add_cookies([cookie])
        return {"cookie_set": cookie.get("name")}

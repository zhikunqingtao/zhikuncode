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
import time
from datetime import datetime, timedelta
from functools import partial
from typing import Optional

from playwright.async_api import (
    async_playwright,
    Browser,
    BrowserContext,
    Page,
    Playwright,
    Error as PlaywrightError,
)

logger = logging.getLogger(__name__)


# 语义快照作用域内用于提取交互元素的 DOM 查询脚本。
# 说明：Playwright 1.49+ 移除了 page.accessibility，只保留 locator.aria_snapshot()。
# 为兼顾 "交互元素清单" 的结构化需求，这里用 evaluate 在浏览器端收集。
_INTERACTIVE_QUERY_SCRIPT = """
(args) => {
  const { scope, limit } = args;
  const root = scope ? document.querySelector(scope) : document.body;
  if (!root) return { nodeCount: 0, interactive: [] };
  const selectors = [
    'button', 'a[href]', 'input:not([type=hidden])', 'select', 'textarea',
    '[role=button]', '[role=link]', '[role=textbox]', '[role=combobox]',
    '[role=checkbox]', '[role=radio]', '[role=switch]', '[role=tab]',
    '[role=menuitem]', '[role=option]', '[role=searchbox]', '[role=slider]',
    '[role=spinbutton]'
  ].join(',');
  const tagRole = { BUTTON: 'button', A: 'link', INPUT: 'textbox',
                    SELECT: 'combobox', TEXTAREA: 'textbox' };
  const typeRole = { checkbox: 'checkbox', radio: 'radio',
                     range: 'slider', search: 'searchbox',
                     number: 'spinbutton' };
  const out = [];
  const nodes = root.querySelectorAll(selectors);
  for (let i = 0; i < nodes.length && out.length < limit; i++) {
    const el = nodes[i];
    let role = el.getAttribute('role');
    if (!role) {
      if (el.tagName === 'INPUT') {
        role = typeRole[(el.getAttribute('type') || '').toLowerCase()] || 'textbox';
      } else {
        role = tagRole[el.tagName] || el.tagName.toLowerCase();
      }
    }
    const rawName = (
      el.getAttribute('aria-label') ||
      el.getAttribute('placeholder') ||
      el.getAttribute('title') ||
      (el.textContent || '').trim() ||
      el.getAttribute('name') ||
      ''
    );
    const entry = { role: String(role).toLowerCase(), name: String(rawName).slice(0, 200) };
    const val = (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ||
                 el instanceof HTMLSelectElement) ? el.value : null;
    if (val !== null && val !== undefined && val !== '') {
      entry.value = String(val).slice(0, 200);
    }
    if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') {
      entry.disabled = true;
    }
    out.push(entry);
  }
  // 节点总数（作用域内所有元素） — 给前端/LLM 做粗粒度页面规模指示
  const nodeCount = root.querySelectorAll('*').length + 1;
  return { nodeCount, interactive: out };
}
"""


class BrowserSession:
    """单个浏览器会话 — 对应一个独立的 BrowserContext"""

    def __init__(self, context: BrowserContext, page: Page, created_at: datetime):
        self.context = context
        self.page = page
        self.created_at = created_at
        self.last_activity = created_at
        self.dialog_handler_set = False
        self._pending_dialog = None
        self.closing = False
        self.closed = False
        self.close_task: Optional[asyncio.Task] = None
        self.owner_task: Optional[asyncio.Task] = None

    def touch(self):
        """更新最后活动时间"""
        self.last_activity = datetime.now()

    def is_expired(self, idle_timeout: timedelta) -> bool:
        return self.owner_task is None and datetime.now() - self.last_activity > idle_timeout


class _SessionCreation:
    """A capacity reservation, retained until creation or rollback has finished."""

    def __init__(self):
        self.owner = asyncio.current_task()
        self.cancelled = False
        self.cleanup_confirmed = True
        self.result_ready = asyncio.Event()
        self.done = asyncio.Event()
        self.session: Optional[BrowserSession] = None
        self.error: Optional[BaseException] = None
        self.allocation: Optional[asyncio.Task] = None
        self.rollback: Optional[asyncio.Task] = None


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
        self._creating: dict[str, _SessionCreation] = {}
        self._unclosed_contexts: dict[BrowserContext, str] = {}
        self._resource_close_tasks: dict[object, asyncio.Task] = {}
        self._starting: Optional[asyncio.Task] = None
        self._startup_cleanup: Optional[asyncio.Task] = None
        self._playwright_exit = None
        self._lock = asyncio.Lock()
        self._lifecycle_lock = asyncio.Lock()
        self._stopping = False
        self.cleanup_timeout = 5.0
        self._cleanup_task: Optional[asyncio.Task] = None
        self._js_errors: dict[str, list[dict]] = {}  # session_id → collected JS errors

        # 配置（可通过环境变量覆盖）
        self.browser_type = os.getenv("BROWSER_TYPE", "chromium")
        self.browser_channel = os.getenv("BROWSER_CHANNEL", "") or None  # 空/未设置 → 用 Playwright 自带 chromium；设为 "chrome" → 用系统 Chrome
        self.headless = os.getenv("BROWSER_HEADLESS", "true").lower() == "true"
        self.idle_timeout = timedelta(
            minutes=int(os.getenv("BROWSER_IDLE_TIMEOUT_MIN", "5"))
        )
        self.max_sessions = int(os.getenv("BROWSER_MAX_SESSIONS", "10"))
        self.default_timeout = int(os.getenv("BROWSER_DEFAULT_TIMEOUT_MS", "30000"))

        # 反检测配置（可通过环境变量覆盖）
        self.viewport_width = int(os.getenv("BROWSER_VIEWPORT_WIDTH", "1280"))
        self.viewport_height = int(os.getenv("BROWSER_VIEWPORT_HEIGHT", "800"))
        self.user_agent = os.getenv(
            "BROWSER_USER_AGENT",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        self.locale = os.getenv("BROWSER_LOCALE", "zh-CN")
        self.timezone_id = os.getenv("BROWSER_TIMEZONE", "Asia/Shanghai")

    # ═══ 生命周期 ═══

    async def startup(self):
        """启动 Playwright 和浏览器进程"""
        async with self._lifecycle_lock:
            if (self._browser or self._playwright or self._creating
                    or self._unclosed_contexts or self._resource_close_tasks
                    or self._starting or self._startup_cleanup or self._playwright_exit):
                raise RuntimeError("BrowserService is already started or has unreleased resources")
            self._stopping = False
            manager = async_playwright()
            self._playwright_exit = partial(manager.__aexit__, None, None, None)
            try:
                self._starting = asyncio.create_task(manager.start())
                self._playwright = await asyncio.shield(self._starting)
                self._starting = None
                launcher = getattr(self._playwright, self.browser_type)
                launch_kwargs = {
                    "headless": self.headless,
                    "args": ["--no-sandbox", "--disable-dev-shm-usage",
                             "--disable-gpu", "--disable-extensions"],
                }
                if self.browser_channel:
                    launch_kwargs["channel"] = self.browser_channel
                self._browser = await launcher.launch(**launch_kwargs)
                self._cleanup_task = asyncio.create_task(self._periodic_cleanup())
            except BaseException:
                self._stopping = True
                self._startup_cleanup = asyncio.create_task(self._rollback_startup())
                self._startup_cleanup.add_done_callback(lambda task: None if task.cancelled() else task.exception())
                await self._finish_cleanup(self._wait_cleanup_task(self._startup_cleanup))
                raise
            logger.info(f"BrowserService started: {self.browser_type}, headless={self.headless}")

    async def shutdown(self):
        """关闭所有资源"""
        async with self._lifecycle_lock:
            pending = self._startup_cleanup
            if pending:
                await self._finish_cleanup(self._wait_cleanup_task(pending))
                if not pending.done():
                    return
            await self._finish_cleanup(self._shutdown_resources())

    async def _rollback_startup(self):
        try:
            if self._starting:
                try:
                    self._playwright = await self._starting
                except (Exception, asyncio.CancelledError):
                    pass  # The retained context manager can release partial start.
                finally:
                    self._starting = None
            await self._shutdown_resources()
        finally:
            self._startup_cleanup = None

    async def _shutdown_resources(self):
        async with self._lock:
            self._stopping = True
            session_ids = set(self._sessions) | set(self._creating)
        if self._cleanup_task:
            self._cleanup_task.cancel()
            close_cleanup = lambda: self._cleanup_task
            await self._close_resource(close_cleanup, "cleanup task")
            # Cancellation is the expected completion of the maintenance loop.
            self._forget_released_close(close_cleanup)
            self._cleanup_task = None
        for sid in session_ids:
            try:
                await self.close_session(sid)
            except Exception as exc:
                logger.warning("Shutdown could not close session %s: %s", sid, exc)
        for context, label in list(self._unclosed_contexts.items()):
            await self._close_context(context, label)
        if self._browser:
            if await self._close_resource(self._browser.close, "browser"):
                self._release_browser_ownership()
        if self._playwright:
            if await self._close_resource(self._playwright.stop, "playwright"):
                self._release_browser_ownership()
                self._playwright = None
                self._playwright_exit = None
        elif self._playwright_exit:
            if await self._close_resource(self._playwright_exit, "starting playwright"):
                self._release_browser_ownership()
                self._playwright_exit = None
        # Closing the driver resolves in-flight new_context RPCs. Let their one
        # rollback task settle; retain any still-pending reservation for diagnosis.
        for reservation in list(self._creating.values()):
            if reservation.rollback:
                await self._wait_cleanup_task(reservation.rollback)
        logger.info("BrowserService shutdown cleanup finished")

    def _forget_released_close(self, close):
        """An ancestor released this resource; retain its task until it settles."""
        task = self._resource_close_tasks.get(close)
        if task is None:
            return

        def finished(completed):
            if self._resource_close_tasks.get(close) is completed:
                self._resource_close_tasks.pop(close)

        if task.done():
            finished(task)
        else:
            task.add_done_callback(finished)

    def _release_browser_ownership(self):
        """Only confirmed browser/driver shutdown supersedes failed child closes."""
        if self._browser:
            self._forget_released_close(self._browser.close)
        contexts = {session.context for session in self._sessions.values()}
        contexts.update(self._unclosed_contexts)
        for context in contexts:
            self._forget_released_close(context.close)
        self._browser = None
        self._sessions.clear()
        self._js_errors.clear()
        self._unclosed_contexts.clear()

    async def _finish_cleanup(self, operation):
        """Do not let cancellation interrupt cleanup; re-raise it afterwards."""
        task = asyncio.create_task(operation)
        cancelled = False
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                if task.cancelled():
                    raise
                cancelled = True
            except Exception:
                break
        if cancelled:
            task.exception()  # retrieve cleanup failure without losing cancellation
            raise asyncio.CancelledError
        return task.result()

    async def _close_resource(self, close, label: str) -> bool:
        """Bound each driver call, including a driver that ignores cancellation."""
        task = self._resource_close_tasks.get(close)
        if task is None:
            try:
                task = asyncio.ensure_future(close())
            except Exception as exc:
                logger.warning("Error closing %s: %s", label, exc)
                return False
            self._resource_close_tasks[close] = task

            def finished(completed):
                # A failed close may have latched Playwright's closed flag before
                # releasing anything. Never retry it as a fresh, successful no-op.
                succeeded = not completed.cancelled() and completed.exception() is None
                if succeeded and self._resource_close_tasks.get(close) is completed:
                    self._resource_close_tasks.pop(close)

            task.add_done_callback(finished)
        done, _ = await asyncio.wait({task}, timeout=self.cleanup_timeout)
        if not done:
            # Do not cancel driver cleanup: Playwright can mark itself closed
            # before its await finishes. Retrying a cancelled close could then
            # return success without releasing anything. Retain this one task.
            logger.warning("Timed out closing %s", label)
            return False
        try:
            task.result()
            return True
        except asyncio.CancelledError:
            return False
        except Exception as exc:
            logger.warning("Error closing %s: %s", label, exc)
            return False

    async def _close_context(self, context: BrowserContext, label: str) -> bool:
        browser = self._browser
        closed = await self._close_resource(context.close, label)
        if browser is None or self._browser is not browser:
            # Confirmed ancestor shutdown supersedes this close, including a late
            # allocation or a concurrent waiter resuming after shutdown cleared it.
            self._forget_released_close(context.close)
            closed = True
        if closed:
            self._unclosed_contexts.pop(context, None)
        else:
            self._unclosed_contexts[context] = label
        return closed

    # ═══ 会话管理 ═══

    async def get_or_create_session(self, session_id: str) -> BrowserSession:
        """Reserve capacity atomically; all Playwright I/O stays outside the lock."""
        async def initialize(context):
            page = await context.new_page()
            page.set_default_timeout(self.default_timeout)
            await page.add_init_script("""
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            """)
            page.on("pageerror", lambda exc: self._collect_js_error(session_id, exc))
            page.on("console", lambda msg: self._on_console(session_id, msg))
            return BrowserSession(context, page, datetime.now())

        return await self._create_session(session_id, {
            "viewport": {"width": self.viewport_width, "height": self.viewport_height},
            "user_agent": self.user_agent, "locale": self.locale,
            "timezone_id": self.timezone_id, "ignore_https_errors": True,
        }, initialize)

    async def _create_session(self, session_id, context_kwargs, initialize, *, journey=False):
        async with self._lock:
            if self._stopping or not self._browser:
                raise RuntimeError("BrowserService is not running")
            if session_id in self._sessions:
                existing = self._sessions[session_id]
                if journey or existing.closing:
                    raise RuntimeError(f"Browser session '{session_id}' already exists or is closing")
                existing.touch()
                return existing
            pending = self._creating.get(session_id)
            if pending:
                if journey:
                    raise RuntimeError(f"Browser session '{session_id}' is being created")
            else:
                if session_id in self._unclosed_contexts.values():
                    raise RuntimeError(f"Browser session '{session_id}' has unfinished cleanup")
                # Unclosed rollback contexts still consume capacity; never silently evict a user.
                if len(self._sessions) + len(self._creating) + len(self._unclosed_contexts) >= self.max_sessions:
                    raise RuntimeError("Browser session capacity reached")
                reservation = _SessionCreation()
                self._creating[session_id] = reservation

        if pending:
            # Ordinary callers share the original creation, not another allocation.
            # A cancelled waiter cannot cancel the owner or retry after close.
            await pending.result_ready.wait()
            async with self._lock:
                if pending.error is not None:
                    raise pending.error
                existing = self._sessions.get(session_id)
                if (pending.cancelled or self._stopping or existing is None
                        or existing is not pending.session or existing.closing):
                    raise RuntimeError(f"Browser session '{session_id}' was closed during creation")
                existing.touch()
                return existing

        context = None
        try:
            # Cancelling a Playwright RPC does not cancel creation in Chromium.
            # Keep its result obtainable so rollback can close a late context.
            reservation.allocation = asyncio.create_task(self._browser.new_context(**context_kwargs))
            context = await asyncio.shield(reservation.allocation)
            session = await initialize(context)
            session.owner_task = reservation.owner if journey else None
            async with self._lock:
                if self._stopping or reservation.cancelled or reservation.owner.cancelling():
                    raise asyncio.CancelledError
                self._sessions[session_id] = session
                self._creating.pop(session_id)
                reservation.session = session
                reservation.result_ready.set()
                reservation.done.set()
            logger.info("New browser session: %s (total: %s)", session_id, len(self._sessions))
            return session
        except BaseException as exc:
            reservation.error = exc
            # Notify callers independently of rollback, which may still own a
            # late allocation indefinitely and must continue reserving capacity.
            reservation.result_ready.set()
            reservation.rollback = asyncio.create_task(self._abort_creation(session_id, reservation, context))
            reservation.rollback.add_done_callback(lambda task: None if task.cancelled() else task.exception())
            await self._finish_cleanup(self._wait_cleanup_task(reservation.rollback))
            raise

    async def _wait_cleanup_task(self, task):
        """Wait a bounded time without cancelling the sole late-result owner."""
        done, _ = await asyncio.wait({task}, timeout=self.cleanup_timeout)
        if done:
            task.result()
        else:
            logger.warning("Browser cleanup task remains pending; ownership is retained")

    async def _abort_creation(self, session_id, reservation, context):
        try:
            if context is None and reservation.allocation is not None:
                try:
                    context = await reservation.allocation
                except (Exception, asyncio.CancelledError):
                    # The RPC itself failed (e.g. driver shutdown): no context
                    # was returned. An ordinary caller cancellation is shielded.
                    pass
            if context is not None:
                reservation.cleanup_confirmed = await self._close_context(context, session_id)
        finally:
            async with self._lock:
                if self._creating.get(session_id) is reservation:
                    self._creating.pop(session_id)
                self._js_errors.pop(session_id, None)
                reservation.done.set()

    async def close_session(self, session_id: str, expected_session=None) -> bool:
        return await self._finish_cleanup(self._close_session(
            session_id, expected_session, requester=asyncio.current_task()))

    async def _close_session(self, session_id, expected_session=None, *, expired_only=False, requester=None):
        async with self._lock:
            session = self._sessions.get(session_id)
            if expected_session is not None and session is not expected_session:
                return False
            reservation = self._creating.get(session_id)
            unclosed = [context for context, sid in self._unclosed_contexts.items() if sid == session_id]
            if reservation:
                reservation.cancelled = True
                reservation.owner.cancel()
            elif session:
                if expired_only and not session.is_expired(self.idle_timeout):
                    return False
                if session.closed:
                    return True
                session.closing = True
                # External close must also stop the Journey; otherwise its next
                # step could recreate the just-closed resource through navigate.
                if (expected_session is None and session.owner_task is not None
                        and session.owner_task is not requester):
                    session.owner_task.cancel()
                if session.close_task is None:
                    session.close_task = asyncio.create_task(self._close_registered_session(session_id, session))
                close_task = session.close_task
            elif not unclosed:
                return False
        if reservation:
            closed = await self._close_resource(reservation.done.wait, f"creating session {session_id}")
            closed = closed and reservation.cleanup_confirmed
        elif session:
            closed = await asyncio.shield(close_task)
        else:
            closed = True
            for context in unclosed:
                closed = await self._close_context(context, session_id) and closed
        if not closed:
            raise RuntimeError(f"Browser session '{session_id}' cleanup was not confirmed")
        return True

    async def _close_registered_session(self, session_id, session):
        try:
            closed = await self._close_resource(session.context.close, f"session {session_id}")
            if closed:
                async with self._lock:
                    session.closed = True
                    # Retain a closing entry until the executing Journey releases
                    # its lease, even if a driver swallows task cancellation.
                    if self._sessions.get(session_id) is session and session.owner_task is None:
                        self._sessions.pop(session_id)
                        self._js_errors.pop(session_id, None)
            return closed
        finally:
            session.close_task = None

    async def release_session(self, session_id: str, session: BrowserSession):
        """End the Journey lease without destroying its failure-snapshot context."""
        async with self._lock:
            if self._sessions.get(session_id) is session:
                session.owner_task = None
                if session.closed:
                    self._sessions.pop(session_id)
                    self._js_errors.pop(session_id, None)
                session.touch()

    async def validate_session(self, session_id: str) -> bool:
        """检查 session 是否存在且有效"""
        async with self._lock:
            session = self._sessions.get(session_id)
            return session is not None and not session.closing

    async def _strict_session_guard(self, session_id: str) -> Optional[dict]:
        """strict_session=True 时的守卫，返回错误 dict 或 None 表示通过"""
        if not await self.validate_session(session_id):
            return {
                "success": False,
                "error_code": "SESSION_NOT_FOUND",
                "error_message": (
                    f"Session '{session_id}' does not exist. "
                    "Use navigate first to create a session, or set strict_session=false."
                ),
            }
        return None

    async def _periodic_cleanup(self):
        """每 60 秒检查并清理过期会话"""
        while True:
            try:
                await asyncio.sleep(60)
                await self._cleanup_expired_sessions()
            except asyncio.CancelledError:
                break  # 正常关闭
            except Exception as e:
                logger.error(f"Cleanup error: {e}")  # 异常不中断清理循环

    async def _cleanup_expired_sessions(self):
        async with self._lock:
            expired = [(sid, s) for sid, s in self._sessions.items() if s.is_expired(self.idle_timeout)]
        for sid, session in expired:
            try:
                await self._finish_cleanup(self._close_session(sid, session, expired_only=True))
            except Exception as exc:
                logger.warning("Expired session cleanup failed for %s: %s", sid, exc)
        for context, label in list(self._unclosed_contexts.items()):
            try:
                await self._finish_cleanup(self._close_context(context, label))
            except Exception as exc:
                logger.warning("Unfinished context cleanup failed for %s: %s", label, exc)

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
        strict_session: bool = False,
    ) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        if selector:
            element = await session.page.query_selector(selector)
            if not element:
                raise ValueError(f"Element not found: {selector}")
            raw = await element.screenshot(type="png")
        else:
            raw = await session.page.screenshot(full_page=full_page, type="png")

        # 保存截图到文件
        screenshot_dir = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(__file__))),
            "workspace", "screenshots"
        )
        os.makedirs(screenshot_dir, exist_ok=True)
        timestamp = int(time.time() * 1000)
        filename = f"screenshot_{session_id}_{timestamp}.png"
        filepath = os.path.join(screenshot_dir, filename)
        with open(filepath, "wb") as f:
            f.write(raw)
        logger.info(f"Screenshot saved: {filepath} ({len(raw)} bytes)")

        return {
            "screenshot_base64": base64.b64encode(raw).decode(),
            "screenshot_path": filepath,
            "size": len(raw),
            "filename": filename,
        }

    async def click(
        self, session_id: str, selector: str, timeout: int = None,
        strict_session: bool = False, no_wait_after: bool = False,
        force: bool = False,
    ) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        effective_timeout = timeout or self.default_timeout
        # 先用较短超时尝试 Playwright 原生 click，超时后自动降级 JS click
        quick_timeout = min(effective_timeout, 5000)
        try:
            await session.page.click(
                selector,
                force=force,
                no_wait_after=no_wait_after,
                timeout=quick_timeout,
            )
            return {"clicked": selector, "url": session.page.url, "method": "playwright"}
        except Exception as e:
            error_msg = str(e)
            # 可见性 / 可操作性 / 超时错误 → 自动降级到 JS click
            if any(kw in error_msg for kw in ("not visible", "not stable", "intercept", "Timeout")):
                try:
                    safe_selector = selector.replace("'", "\\'")
                    page = session.page
                    url_before = page.url

                    await page.evaluate(f"""
                        (() => {{
                            const el = document.querySelector('{safe_selector}');
                            if (el) {{ el.click(); return true; }}
                            throw new Error('Element not found: {safe_selector}');
                        }})()
                    """)

                    result = {
                        "clicked": selector,
                        "url": page.url,
                        "method": "js_fallback",
                        "warning": f"Playwright click failed ({error_msg[:200]}), succeeded via JS click",
                    }

                    # JS click 成功后：如果没有 no_wait_after，等待可能的导航完成
                    if not no_wait_after:
                        try:
                            # 短暂等待让浏览器开始处理 click 触发的导航
                            await asyncio.sleep(0.3)
                            current_url = page.url

                            if current_url != url_before:
                                # 导航已触发
                                if current_url == "about:blank":
                                    # 页面正处于导航中间态，等待最终 URL
                                    await page.wait_for_url(
                                        lambda u: u != "about:blank",
                                        timeout=10000,
                                    )
                                # 等待新页面 DOM 加载完成
                                await page.wait_for_load_state("domcontentloaded", timeout=10000)
                                result["navigated_to"] = page.url
                            else:
                                # URL 未变化，可能是延迟导航或纯 AJAX 操作
                                try:
                                    await page.wait_for_url(
                                        lambda u: u != url_before, timeout=3000
                                    )
                                    await page.wait_for_load_state("domcontentloaded", timeout=10000)
                                    result["navigated_to"] = page.url
                                except Exception:
                                    pass  # 确实不触发导航，正常返回
                        except Exception as nav_err:
                            result["navigation_warning"] = (
                                f"Post-click navigation wait: {str(nav_err)[:200]}"
                            )

                    result["url"] = page.url
                    return result
                except Exception as js_err:
                    return {
                        "clicked": False,
                        "selector": selector,
                        "method": "both_failed",
                        "error": f"Both Playwright and JS click failed. Playwright: {error_msg[:200]}. JS: {str(js_err)[:200]}",
                    }
            else:
                raise  # 其他错误重新抛出让外层统一处理

    async def type_text(
        self,
        session_id: str,
        selector: str,
        text: str,
        timeout: int = None,
        strict_session: bool = False,
    ) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        page = session.page
        effective_timeout = timeout or self.default_timeout
        quick_timeout = min(effective_timeout, 5000)  # 最多等 5 秒

        try:
            await page.fill(selector, text, timeout=quick_timeout)
            return {
                "success": True,
                "filled": selector,
                "text_length": len(text),
                "method": "playwright",
            }
        except Exception as e:
            error_msg = str(e)
            # 元素不可见/不可编辑/超时 → JS 降级
            if any(kw in error_msg.lower() for kw in ["not visible", "not editable", "timeout", "not stable"]):
                try:
                    escaped_text = text.replace("\\", "\\\\").replace("'", "\\'")
                    escaped_selector = selector.replace("\\", "\\\\").replace("'", "\\'")
                    await page.evaluate(f"""
                        (() => {{
                            const el = document.querySelector('{escaped_selector}');
                            if (!el) throw new Error('Element not found: {escaped_selector}');
                            el.focus();
                            el.value = '{escaped_text}';
                            el.dispatchEvent(new Event('input',  {{ bubbles: true }} ));
                            el.dispatchEvent(new Event('change', {{ bubbles: true }} ));
                            return el.value;
                        }})()
                    """)
                    return {
                        "success": True,
                        "filled": selector,
                        "text_length": len(text),
                        "method": "js_fallback",
                        "warning": f"Playwright fill failed ({error_msg[:200]}), succeeded via JS",
                    }
                except Exception as js_err:
                    return {
                        "success": False,
                        "filled": selector,
                        "text_length": 0,
                        "method": "both_failed",
                        "error": f"Playwright: {error_msg[:200]}. JS: {str(js_err)[:200]}",
                    }
            else:
                raise  # 其他错误让外层统一处理

    async def evaluate(self, session_id: str, script: str, strict_session: bool = False) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        try:
            result = await session.page.evaluate(script)
            return {
                "result": str(result) if result is not None else None,
                "success": True,
                "js_errors": [],
                "collected_errors": self._js_errors.get(session_id, []),
            }
        except PlaywrightError as e:
            error_msg = str(e)
            return {
                "result": None,
                "success": False,
                "js_errors": [{
                    "type": "EvaluationError",
                    "message": error_msg,
                    "expression": script[:200],
                }],
                "error_message": f"JavaScript evaluation failed: {error_msg}",
                "collected_errors": self._js_errors.get(session_id, []),
            }

    async def extract_text(self, session_id: str, selector: str = None, strict_session: bool = False) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
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

    async def extract_html(self, session_id: str, selector: str = None, strict_session: bool = False) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        if selector:
            element = await session.page.query_selector(selector)
            html = await element.inner_html() if element else ""
        else:
            html = await session.page.content()
        max_len = 100000
        truncated = len(html) > max_len
        return {"html": html[:max_len], "length": len(html), "truncated": truncated}

    async def wait_for(
        self,
        session_id: str,
        selector: str = None,
        state: str = "visible",
        timeout: int = None,
        wait_until: str = None,
        text_contains: str = None,
        strict_session: bool = False,
    ) -> dict:
        effective_timeout = timeout or self.default_timeout

        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard

        session = await self.get_or_create_session(session_id)
        page = session.page

        if wait_until == "networkidle":
            await page.wait_for_load_state("networkidle", timeout=effective_timeout)
            return {"waited_for": "networkidle", "success": True}
        elif wait_until == "load":
            await page.wait_for_load_state("load", timeout=effective_timeout)
            return {"waited_for": "load", "success": True}
        elif wait_until == "domcontentloaded":
            await page.wait_for_load_state("domcontentloaded", timeout=effective_timeout)
            return {"waited_for": "domcontentloaded", "success": True}
        elif text_contains and selector:
            await page.locator(selector).filter(has_text=text_contains).wait_for(
                state=state, timeout=effective_timeout
            )
            return {"waited_for": "text_contains", "selector": selector, "text": text_contains, "success": True}
        elif selector:
            await page.wait_for_selector(selector, state=state, timeout=effective_timeout)
            return {"waited_for": "selector", "selector": selector, "state": state, "success": True}
        else:
            return {"error": "Must provide either 'selector' or 'wait_until' parameter"}

    # 向后兼容别名
    async def wait_for_selector(
        self, session_id: str, selector: str, timeout: int = None
    ) -> dict:
        return await self.wait_for(session_id, selector=selector, timeout=timeout)

    async def select_option(
        self, session_id: str, selector: str, values: list[str],
        strict_session: bool = False,
    ) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        selected = await session.page.select_option(selector, values)
        return {"selected": selected}

    async def handle_dialog(
        self, session_id: str, accept: bool, text: str = None,
        strict_session: bool = False,
    ) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)

        # 注册对话框处理器（下一次对话框弹出时自动处理）
        async def on_dialog(dialog):
            if accept:
                await dialog.accept(text or "")
            else:
                await dialog.dismiss()

        session.page.once("dialog", on_dialog)
        return {"dialog_handler": "registered", "accept": accept}

    async def get_cookies(self, session_id: str, strict_session: bool = False) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        cookies = await session.context.cookies()
        return {"cookies": cookies}

    async def set_cookie(self, session_id: str, cookie: dict, strict_session: bool = False) -> dict:
        if strict_session:
            guard = await self._strict_session_guard(session_id)
            if guard:
                return guard
        session = await self.get_or_create_session(session_id)
        await session.context.add_cookies([cookie])
        return {"cookie_set": cookie.get("name")}

    async def get_js_errors(self, session_id: str) -> list:
        """返回指定会话收集到的所有 JS 错误"""
        return self._js_errors.get(session_id, [])

    # ═══ 语义快照 (ZhikunCode v1.5 升级项 A MVP) ═══

    async def snapshot_semantic(
        self,
        session_id: str,
        selector: Optional[str] = None,
        interesting_only: bool = True,
        include_screenshot: bool = False,
        strict_session: bool = False,
    ) -> dict:
        """产出页面语义快照 — Playwright 1.49+ API。

        组合两种来源：
        - ``locator.aria_snapshot()`` 产出 ARIA YAML 文本（只含有语义的节点）
        - ``page.evaluate`` 执行 DOM 查询提取结构化交互元素清单。

        相比原始 HTML 体积小 10-100 倍；交互清单供 LLM 直接做决策。

        错误语义：
        - ``strict_session=True`` 且会话不存在时返回 guard dict
        - ``selector`` 指向元素不存在时抛 ``ValueError``
        - aria_snapshot 运行时错误并非致命，tree 为空但交互清单仍会返回
        """
        if strict_session:
            # A late failure snapshot must never recreate a resource closed by
            # timeout/cancellation; lookup and retrieval are one atomic action.
            async with self._lock:
                session = self._sessions.get(session_id)
                if session is not None and not session.closing:
                    session.touch()
                else:
                    return {"success": False, "error_code": "SESSION_NOT_FOUND",
                            "error_message": f"Session '{session_id}' does not exist."}
        else:
            session = await self.get_or_create_session(session_id)
        page = session.page

        scope = selector if selector and selector.strip() else None
        # 先校验 selector 有效且有匹配
        if scope:
            try:
                element = await page.query_selector(scope)
            except PlaywrightError as e:
                raise ValueError(f"Invalid selector: {selector}") from e
            if not element:
                raise ValueError(f"Element not found: {selector}")

        # ARIA YAML 文本 — 对应 locator
        locator = page.locator(scope) if scope else page.locator("body")
        aria_yaml = ""
        try:
            aria_yaml = await locator.first.aria_snapshot()
        except PlaywrightError as e:
            logger.warning(f"aria_snapshot failed: {e}")

        # 交互元素提取 + 节点总数
        try:
            stats = await page.evaluate(
                _INTERACTIVE_QUERY_SCRIPT,
                {"scope": scope, "limit": 200},
            )
        except PlaywrightError as e:
            logger.warning(f"interactive query failed: {e}")
            stats = {"nodeCount": 0, "interactive": []}

        node_count = int(stats.get("nodeCount") or 0) if isinstance(stats, dict) else 0
        interactive = stats.get("interactive") or [] if isinstance(stats, dict) else []
        if not isinstance(interactive, list):
            interactive = []

        result: dict = {
            "url": page.url,
            "title": await page.title(),
            "timestamp": datetime.now().isoformat(),
            "selector": selector,
            "interesting_only": interesting_only,
            "node_count": node_count,
            "interactive": interactive,
            "tree": {"aria": aria_yaml},
        }
        if include_screenshot:
            try:
                raw = await page.screenshot(full_page=False, type="png")
                result["screenshot_base64"] = base64.b64encode(raw).decode()
                result["screenshot_size"] = len(raw)
            except Exception as e:
                logger.debug(f"snapshot_semantic screenshot skipped: {e}")
                result["screenshot_base64"] = None
        return result

    # ═══ Journey 专用会话创建 ═══

    async def _create_context_for_journey(self, session_id: str, record_opts: dict, viewport: dict):
        """为 journey 创建带录制能力的新 context（必须在 new_context 时传入 record 参数）"""
        import tempfile

        context_kwargs = {
            "viewport": viewport,
            "ignore_https_errors": True,
        }

        # 录制选项必须在 new_context 时传入
        if record_opts.get("video"):
            video_dir = tempfile.mkdtemp(prefix="rv-video-")
            context_kwargs["record_video_dir"] = video_dir
            context_kwargs["record_video_size"] = viewport

        if record_opts.get("har"):
            har_dir = tempfile.mkdtemp(prefix="rv-har-")
            har_path = os.path.join(har_dir, f"{session_id}.har")
            context_kwargs["record_har_path"] = har_path

        async def initialize(context):
            await context.add_init_script("""
                Object.defineProperty(navigator, 'webdriver', { get: () => false });
            """)
            page = await context.new_page()
            page.set_default_timeout(self.default_timeout)
            js_errors: list[str] = []
            page.on("pageerror", lambda error: js_errors.append(str(error)))
            page.on("console", lambda msg: js_errors.append(msg.text) if msg.type == "error" else None)
            if record_opts.get("trace"):
                await context.tracing.start(screenshots=True, snapshots=True)
            session = BrowserSession(context=context, page=page, created_at=datetime.now())
            session._js_errors = js_errors
            session._record_opts = record_opts
            session._context_kwargs = context_kwargs
            return session

        return await self._create_session(session_id, context_kwargs, initialize, journey=True)

    # ═══ JS 错误收集内部方法 ═══

    def _on_console(self, session_id: str, msg):
        """console 事件入口 — 仅处理 error 级别，非 error 立即返回"""
        try:
            if msg.type != "error":
                return
            self._collect_console_error(session_id, msg)
        except Exception:
            pass  # 回调中绝不抛异常，避免影响 Playwright 事件循环

    def _collect_js_error(self, session_id: str, exc: Exception):
        """页面级 pageerror 事件收集（同步回调，不持锁）"""
        try:
            errors = self._js_errors.setdefault(session_id, [])
            errors.append({
                "type": "PageError",
                "message": str(exc),
                "timestamp": datetime.now().isoformat(),
            })
            if len(errors) > 100:
                self._js_errors[session_id] = errors[-100:]
            logger.debug(f"[{session_id}] JS page error collected: {str(exc)[:200]}")
        except Exception:
            pass  # 回调中绝不抛异常

    def _collect_console_error(self, session_id: str, msg):
        """页面级 console error 事件收集（同步回调，不持锁）"""
        try:
            errors = self._js_errors.setdefault(session_id, [])
            errors.append({
                "type": "ConsoleError",
                "message": msg.text,
                "timestamp": datetime.now().isoformat(),
            })
            if len(errors) > 100:
                self._js_errors[session_id] = errors[-100:]
        except Exception:
            pass  # 回调中绝不抛异常

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
        self._js_errors: dict[str, list[dict]] = {}  # session_id → collected JS errors

        # 配置（可通过环境变量覆盖）
        self.browser_type = os.getenv("BROWSER_TYPE", "chromium")
        self.browser_channel = os.getenv("BROWSER_CHANNEL", "chrome")  # 使用系统 Chrome，避免下载 Playwright 自带浏览器
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
        self._playwright = await async_playwright().start()
        launcher = getattr(self._playwright, self.browser_type)
        launch_kwargs = {
            "headless": self.headless,
            "args": [
                "--no-sandbox",
                "--disable-dev-shm-usage",  # 容器友好
                "--disable-gpu",
                "--disable-extensions",
            ],
        }
        # 使用系统浏览器 channel（如 chrome），避免需要 playwright install
        if self.browser_channel:
            launch_kwargs["channel"] = self.browser_channel
        self._browser = await launcher.launch(**launch_kwargs)
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
        """获取现有会话或创建新会话

        关键设计：Playwright I/O (new_context / new_page) 必须在锁外执行，
        否则会长时间持有锁导致事件循环饥饿，阻塞后续 HTTP 请求。
        """
        # ── 快速路径：会话已存在，短暂持锁 ──
        async with self._lock:
            if session_id in self._sessions:
                session = self._sessions[session_id]
                session.touch()
                return session

            # 会话数达上限，先驱逐最老的
            if len(self._sessions) >= self.max_sessions:
                oldest_sid = min(
                    self._sessions,
                    key=lambda s: self._sessions[s].last_activity,
                )
                await self._close_session_unsafe(oldest_sid)
                logger.warning(f"Session limit reached, evicted oldest: {oldest_sid}")

        # ── 慢速路径：Playwright I/O 在锁外执行 ──
        context = await self._browser.new_context(
            viewport={"width": self.viewport_width, "height": self.viewport_height},
            user_agent=self.user_agent,
            locale=self.locale,
            timezone_id=self.timezone_id,
            ignore_https_errors=True,
        )
        page = await context.new_page()
        page.set_default_timeout(self.default_timeout)

        # 注入反 webdriver 检测脚本（在任何页面加载前生效）
        await page.add_init_script("""
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
        """)

        # 注入 JS 错误收集（同步回调，不持锁，不做 I/O）
        self._js_errors.setdefault(session_id, [])
        page.on("pageerror", lambda exc, sid=session_id: self._collect_js_error(sid, exc))
        page.on("console", lambda msg, sid=session_id: self._on_console(sid, msg))

        session = BrowserSession(context, page, datetime.now())

        # ── 短暂持锁写入 dict（双重检查防并发重复创建）──
        async with self._lock:
            if session_id in self._sessions:
                # 另一个协程已抢先创建，丢弃当前的
                await context.close()
                existing = self._sessions[session_id]
                existing.touch()
                return existing
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
            # 清理该 session 收集的 JS 错误
            self._js_errors.pop(session_id, None)
            return True
        return False

    async def validate_session(self, session_id: str) -> bool:
        """检查 session 是否存在且有效"""
        async with self._lock:
            return session_id in self._sessions

    async def _strict_session_guard(self, session_id: str) -> Optional[dict]:
        """strict_session=True 时的守卫，返回错误 dict 或 None 表示通过"""
        if session_id not in self._sessions:
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

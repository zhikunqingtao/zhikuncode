"""Opt-in real Chromium regression in an isolated Linux container, with --init.

Mount python-service/src and this file read-only into a browser-capable image;
set PYTHONPATH to the mounted src and ZHIKUN_BROWSER_CONTAINER_TEST=1. No network,
production workspace, credentials, or application database is needed.
"""
import asyncio
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch


def resources():
    tasks = zombies = 0
    for status in Path("/proc").glob("[0-9]*/status"):
        try:
            fields = dict(line.split(":", 1) for line in status.read_text().splitlines() if ":" in line)
        except FileNotFoundError:
            continue
        tasks += int(fields.get("Threads", "0"))
        zombies += fields.get("State", "").strip().startswith("Z")
    event_file = Path("/sys/fs/cgroup/pids.events")
    events = dict(line.split() for line in event_file.read_text().splitlines()) if event_file.exists() else {}
    return {"tasks": tasks, "zombies": zombies, "limit_events": int(events.get("max", "0"))}


@unittest.skipUnless(os.getenv("ZHIKUN_BROWSER_CONTAINER_TEST") == "1", "isolated Linux container only")
class BrowserContainerLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_repeated_success_failure_timeout_cancel_and_restart_converge(self):
        from fastapi import HTTPException
        from routers import browser, journey
        from services.browser_service import BrowserService
        from services.journey_models import JourneyRunRequest

        baseline = resources()
        checkpoints = []
        for generation in range(2):
            service = BrowserService()
            await service.startup()
            try:
                with patch.object(browser, "browser_service", service):
                    for index in range(5):
                        for scenario in ("success", "failure", "timeout", "cancel"):
                            sid = f"rv-container-{generation}-{index}-{scenario}"
                            steps = [{"action": "navigate", "url": "data:text/html,<button>ready</button>"}]
                            if scenario != "success":
                                steps.append({"action": "click", "selector": "#absent",
                                              "timeout": 30 if scenario == "failure" else 30000})
                            request = JourneyRunRequest(session_id=sid, base_url="http://unused/", steps=steps)
                            if scenario == "timeout":
                                with patch.object(journey, "JOURNEY_EXECUTION_TIMEOUT_SECONDS", 0.2):
                                    with self.assertRaises(HTTPException) as error:
                                        await journey.journey_run(request)
                                    self.assertEqual(error.exception.status_code, 504)
                            elif scenario == "cancel":
                                task = asyncio.create_task(journey.journey_run(request))
                                try:
                                    async with asyncio.timeout(5):
                                        while sid not in service._sessions:
                                            if task.done():
                                                await task
                                                self.fail("Journey ended before cancellation")
                                            await asyncio.sleep(0.01)
                                    task.cancel()
                                    with self.assertRaises(asyncio.CancelledError):
                                        await task
                                finally:
                                    if not task.done():
                                        task.cancel()
                                    await asyncio.gather(task, return_exceptions=True)
                            else:
                                result = await journey.journey_run(request)
                                self.assertEqual(result.passed, scenario == "success")
                                self.assertIn(sid, service._sessions)
                                if scenario == "failure":
                                    snapshot = await service.snapshot_semantic(sid, strict_session=True,
                                                                              include_screenshot=False)
                                    self.assertNotEqual(snapshot.get("error_code"), "SESSION_NOT_FOUND")
                                await service.close_session(sid)
                            self.assertFalse(service._sessions)
                            self.assertFalse(service._creating)
                            self.assertFalse(service._unclosed_contexts)
                            self.assertFalse(service._browser.contexts)

                        # Cancel before new_context's result is assigned to the
                        # service, not just after registration. The late result
                        # must be closed without restarting the shared browser.
                        for delayed_result in (True, False):
                            entered, release = asyncio.Event(), asyncio.Event()
                            new_context = service._browser.new_context

                            async def in_flight_context(**kwargs):
                                if not delayed_result:
                                    entered.set()
                                context = await new_context(**kwargs)
                                if delayed_result:
                                    entered.set()
                                    await release.wait()
                                return context

                            with patch.object(service._browser, "new_context", in_flight_context):
                                task = asyncio.create_task(service.get_or_create_session(
                                    f"early-{generation}-{index}-{delayed_result}"))
                                try:
                                    await asyncio.wait_for(entered.wait(), 5)
                                    await asyncio.sleep(0.001)
                                    task.cancel()
                                    await asyncio.sleep(0)
                                    release.set()
                                    with self.assertRaises(asyncio.CancelledError):
                                        await asyncio.wait_for(task, 10)
                                finally:
                                    release.set()
                                    if not task.done():
                                        task.cancel()
                                    await asyncio.gather(task, return_exceptions=True)
                            self.assertFalse(service._sessions)
                            self.assertFalse(service._creating)
                            self.assertFalse(service._unclosed_contexts)
                            self.assertFalse(service._browser.contexts)
                        await asyncio.sleep(0.1)
                        checkpoints.append(resources())

                # Exercise the real SDK's one-way close latch: dispose fails
                # before the driver receives a context-close RPC. Repeating
                # close must not free its capacity until browser shutdown.
                if generation == 0:
                    session = await service.get_or_create_session("failed-close")
                    with patch.object(session.context._impl_obj.request, "dispose",
                                      side_effect=RuntimeError("injected dispose failure")):
                        for _ in range(2):
                            with self.assertRaises(RuntimeError):
                                await service.close_session("failed-close")
                            self.assertIn("failed-close", service._sessions)
                            self.assertIn(session.context, service._browser.contexts)
                else:
                    # The owner and shared waiter finish on cancellation, while
                    # a late allocation remains tracked until rollback finishes.
                    entered, release = asyncio.Event(), asyncio.Event()
                    new_context = service._browser.new_context

                    async def late_context(**kwargs):
                        context = await new_context(**kwargs)
                        entered.set()
                        await release.wait()
                        return context

                    service.cleanup_timeout = 0.02
                    with patch.object(service._browser, "new_context", late_context):
                        owner = asyncio.create_task(service.get_or_create_session("shared-cancel"))
                        waiter = None
                        try:
                            await asyncio.wait_for(entered.wait(), 5)
                            waiter = asyncio.create_task(service.get_or_create_session("shared-cancel"))
                            await asyncio.sleep(0)
                            owner.cancel()
                            for task in (owner, waiter):
                                with self.assertRaises(asyncio.CancelledError):
                                    await asyncio.wait_for(task, 1)
                            reservation = service._creating["shared-cancel"]
                            self.assertFalse(reservation.done.is_set())
                            self.assertEqual(len(service._browser.contexts), 1)
                            release.set()
                            await asyncio.wait_for(reservation.done.wait(), 5)
                        finally:
                            release.set()
                            for task in (owner, waiter):
                                if task is not None and not task.done():
                                    task.cancel()
                            await asyncio.gather(*(task for task in (owner, waiter) if task is not None),
                                                 return_exceptions=True)
                            service.cleanup_timeout = 5
            finally:
                await service.shutdown()
            async with asyncio.timeout(5):
                while resources()["tasks"] > baseline["tasks"] + 3 or resources()["zombies"]:
                    await asyncio.sleep(0.05)
            self.assertFalse(service._sessions)
            self.assertIsNone(service._browser)
            self.assertIsNone(service._playwright)
            self.assertFalse(service._resource_close_tasks)

        # Driver start has the same late-assignment window as new_context.
        for _ in range(5):
            service = BrowserService()
            task = asyncio.create_task(service.startup())
            try:
                await asyncio.sleep(0.001)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 10)
            finally:
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                await service.shutdown()
            async with asyncio.timeout(5):
                while resources()["tasks"] > baseline["tasks"] + 3 or resources()["zombies"]:
                    await asyncio.sleep(0.05)
            self.assertIsNone(service._starting)
            self.assertIsNone(service._startup_cleanup)
            self.assertFalse(service._resource_close_tasks)
        final = resources()
        self.assertEqual(final["limit_events"], baseline["limit_events"])
        self.assertEqual(final["zombies"], 0)
        self.assertFalse([task for task in asyncio.all_tasks()
                          if task is not asyncio.current_task() and not task.done()])
        print(json.dumps({"scenarios": 67, "generations": 2, "baseline": baseline,
                          "checkpoints": checkpoints, "final": final}))


if __name__ == "__main__":
    unittest.main()

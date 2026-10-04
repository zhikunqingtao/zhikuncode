"""Journey evidence retains interaction method and reasons for missing screenshots."""
import asyncio
import base64
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from routers import browser, journey
from services.journey_models import JourneyRunRequest, StepResultModel


@pytest.fixture
def owned_journey(monkeypatch):
    screenshot = b"\xff\xd8\xfftest-jpeg\xff\xd9"
    page = SimpleNamespace(
        url="http://test/", screenshot=AsyncMock(return_value=screenshot), click=AsyncMock(),
    )
    session = SimpleNamespace(page=page, _js_errors=[], _record_opts={})
    service = SimpleNamespace(
        default_timeout=30000,
        _create_context_for_journey=AsyncMock(return_value=session),
        close_session=AsyncMock(), release_session=AsyncMock(),
        click=AsyncMock(), type_text=AsyncMock(),
    )
    monkeypatch.setattr(browser, "browser_service", service)
    return service, session, screenshot


async def run_steps(steps):
    return await journey.journey_run(JourneyRunRequest(
        session_id="evidence-test", base_url="http://test/", steps=steps,
    ))


@pytest.mark.asyncio
async def test_native_click_records_actual_method_and_preserves_screenshot_bytes(owned_journey):
    service, session, screenshot = owned_journey
    response = await run_steps([{"action": "click", "selector": "#target"}])
    assert response.passed
    result = response.step_results[0]
    assert result.method == "playwright"
    assert result.warning is None
    assert result.screenshot_error is None
    assert base64.b64decode(result.screenshot_base64) == screenshot
    session.page.click.assert_awaited_once_with("#target", timeout=30000)
    service.click.assert_not_awaited()
    service.release_session.assert_awaited_once_with("evidence-test", session)
    service.close_session.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("success,method,warning", [
    (True, "playwright", None),
    (True, "js_fallback", "Playwright fill failed; succeeded via JS"),
    (False, "both_failed", "Both paths attempted"),
])
async def test_input_keeps_method_and_warning_even_on_failure(owned_journey, success, method, warning):
    service, _, _ = owned_journey
    data = {"success": success, "method": method, "warning": warning}
    if not success:
        data["error"] = "Both fills failed"
    service.type_text.return_value = data
    response = await run_steps([
        {"action": "type", "selector": "#input", "text": "value"},
        {"action": "screenshot"},
    ])
    assert response.passed is success
    step = response.step_results[0]
    assert step.method == method
    assert step.warning == warning
    assert step.error == data.get("error")
    assert len(response.step_results) == (2 if success else 1)
    service.type_text.assert_awaited_once_with("evidence-test", "#input", "value", timeout=30000)


@pytest.mark.asyncio
async def test_capture_error_is_reported_without_changing_step_verdict(owned_journey):
    _, session, _ = owned_journey
    session.page.screenshot.side_effect = RuntimeError("Target page closed")
    response = await run_steps([{"action": "screenshot"}])
    assert response.passed
    result = response.step_results[0]
    assert result.ok
    assert result.screenshot_base64 is None
    assert result.screenshot_error == "RuntimeError: Target page closed"


@pytest.mark.asyncio
@pytest.mark.parametrize("error_type", [TimeoutError, RuntimeError])
async def test_step_exception_marks_screenshot_not_collected_and_does_not_retry(
    owned_journey, error_type,
):
    service, session, _ = owned_journey
    session.page.click.side_effect = error_type("click completion failed")
    response = await run_steps([
        {"action": "click", "selector": "#target"},
        {"action": "type", "selector": "#later", "text": "must not run"},
    ])
    assert not response.passed
    assert len(response.step_results) == 1
    result = response.step_results[0]
    assert result.error == "click completion failed"
    assert result.method == "playwright"
    assert result.screenshot_base64 is None
    assert result.screenshot_error == "Not captured: screenshot collection was not reached"
    session.page.click.assert_awaited_once()
    session.page.screenshot.assert_not_awaited()
    service.click.assert_not_awaited()
    service.type_text.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("error_type", [TimeoutError, RuntimeError])
async def test_native_click_preserves_original_exception_and_attempt_evidence(
    owned_journey, error_type,
):
    service, session, _ = owned_journey
    failure = error_type("native click failed after invocation")
    session.page.click.side_effect = failure
    interaction_evidence = {}
    with pytest.raises(error_type) as caught:
        await journey._execute_step(
            service, "evidence-test", session, {"action": "click", "selector": "#target"},
            30000, interaction_evidence=interaction_evidence,
        )
    assert caught.value is failure
    assert interaction_evidence == {"method": "playwright"}
    session.page.click.assert_awaited_once_with("#target", timeout=30000)
    session.page.screenshot.assert_not_awaited()
    service.click.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["selector_missing", "click_unavailable"])
async def test_click_setup_failure_does_not_invent_an_attempt(owned_journey, failure):
    service, session, _ = owned_journey
    native_click = session.page.click
    step = {"action": "click", "selector": "#target"}
    if failure == "selector_missing":
        del step["selector"]
    else:
        del session.page.click
    response = await run_steps([step])
    assert not response.passed
    result = response.step_results[0]
    assert result.method is None
    assert result.screenshot_base64 is None
    assert result.screenshot_error == "Not captured: screenshot collection was not reached"
    native_click.assert_not_awaited()
    session.page.screenshot.assert_not_awaited()
    service.click.assert_not_awaited()


@pytest.mark.asyncio
async def test_click_cancellation_is_not_converted_into_a_failed_step(owned_journey):
    service, session, _ = owned_journey
    cancelled = asyncio.CancelledError("cancelled during native click")
    session.page.click.side_effect = cancelled
    request = JourneyRunRequest(base_url="http://test/", steps=[
        {"action": "click", "selector": "#target"}, {"action": "screenshot"},
    ])
    with pytest.raises(asyncio.CancelledError) as caught:
        await journey._execute_journey(service, request, "evidence-test", session)
    assert caught.value is cancelled
    session.page.click.assert_awaited_once()
    session.page.screenshot.assert_not_awaited()
    service.click.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("metadata,expected_method,expected_warning", [
    ({"method": "js_fallback", "warning": 123}, "js_fallback", None),
    ({"method": {"invalid": True}, "warning": "Native input timed out"},
     None, "Native input timed out"),
])
async def test_invalid_metadata_fails_but_preserves_captured_image(
    owned_journey, metadata, expected_method, expected_warning,
):
    service, session, screenshot = owned_journey
    service.type_text.return_value = {"success": True, **metadata}
    response = await run_steps([
        {"action": "type", "selector": "#input", "text": "value"},
        {"action": "screenshot"},
    ])
    assert not response.passed
    assert len(response.step_results) == 1
    result = response.step_results[0]
    assert not result.ok
    assert "validation error" in result.error
    assert result.method == expected_method
    assert result.warning == expected_warning
    assert base64.b64decode(result.screenshot_base64) == screenshot
    assert result.screenshot_error is None
    session.page.screenshot.assert_awaited_once()
    service.type_text.assert_awaited_once()


@pytest.mark.asyncio
async def test_capture_error_survives_a_later_result_validation_error(owned_journey):
    service, session, _ = owned_journey
    service.type_text.return_value = {"success": True, "method": "js_fallback", "warning": 123}
    session.page.screenshot.side_effect = RuntimeError("Target page closed")
    response = await run_steps([{"action": "type", "selector": "#input", "text": "value"}])
    assert not response.passed
    result = response.step_results[0]
    assert result.method == "js_fallback"
    assert result.warning is None
    assert "validation error" in result.error
    assert result.screenshot_base64 is None
    assert result.screenshot_error == "RuntimeError: Target page closed"
    session.page.screenshot.assert_awaited_once()
    service.type_text.assert_awaited_once()


@pytest.mark.asyncio
async def test_post_capture_result_error_keeps_native_method_and_image(owned_journey):
    _, session, screenshot = owned_journey
    session._js_errors = [123]  # Invalid console payload rejects the result model.
    response = await run_steps([{"action": "click", "selector": "#target"}])
    assert not response.passed
    result = response.step_results[0]
    assert result.method == "playwright"
    assert "console_errors" in result.error
    assert base64.b64decode(result.screenshot_base64) == screenshot
    assert result.screenshot_error is None
    session.page.click.assert_awaited_once()
    session.page.screenshot.assert_awaited_once()


@pytest.mark.asyncio
async def test_step_failure_does_not_reuse_previous_step_evidence(owned_journey):
    service, session, screenshot = owned_journey
    service.type_text.return_value = {
        "success": True, "method": "js_fallback", "warning": "Input used JS",
    }
    response = await run_steps([
        {"action": "type", "selector": "#input", "text": "value"},
        {"action": "click"},  # Parameter failure before a native click is attempted.
        {"action": "screenshot"},
    ])
    assert not response.passed
    assert len(response.step_results) == 2
    first, failed = response.step_results
    assert first.method == "js_fallback"
    assert base64.b64decode(first.screenshot_base64) == screenshot
    assert failed.method is None
    assert failed.warning is None
    assert failed.screenshot_base64 is None
    assert failed.screenshot_error == "Not captured: screenshot collection was not reached"
    session.page.screenshot.assert_awaited_once()
    session.page.click.assert_not_awaited()
    service.type_text.assert_awaited_once()


@pytest.mark.asyncio
async def test_legacy_input_response_does_not_invent_a_method(owned_journey):
    service, _, _ = owned_journey
    service.type_text.return_value = {"success": True}
    response = await run_steps([{"action": "type", "selector": "#input", "text": "value"}])
    assert response.passed
    assert response.step_results[0].method is None
    assert response.step_results[0].warning is None


def test_legacy_step_payload_keeps_new_evidence_fields_unknown():
    result = StepResultModel.model_validate({"index": 0, "action": "type", "ok": True})
    assert result.method is None
    assert result.warning is None
    assert result.screenshot_error is None

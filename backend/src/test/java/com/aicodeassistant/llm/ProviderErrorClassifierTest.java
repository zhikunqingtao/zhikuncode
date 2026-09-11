package com.aicodeassistant.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderErrorClassifierTest {

    @Test
    @DisplayName("402 → PROVIDER_PAYMENT_REQUIRED，携带余额不足中文提示且不可重试")
    void classifies402AsPaymentRequired() {
        var classified = ProviderErrorClassifier.classify(
                new LlmApiException("Insufficient Balance", false, 402));

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_PAYMENT_REQUIRED, classified.errorCode());
        assertEquals(402, classified.httpStatus());
        assertEquals("模型账户余额不足，请充值或切换模型", classified.message());
        assertFalse(classified.retryable());
    }

    @Test
    @DisplayName("403 → PROVIDER_FORBIDDEN")
    void classifies403AsForbidden() {
        var classified = ProviderErrorClassifier.classify(
                new LlmApiException("Forbidden", false, 403));

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_FORBIDDEN, classified.errorCode());
        assertEquals(403, classified.httpStatus());
        assertFalse(classified.retryable());
    }

    @Test
    @DisplayName("429 → PROVIDER_RATE_LIMITED，可重试")
    void classifies429AsRateLimited() {
        var classified = ProviderErrorClassifier.classify(
                new LlmApiException("Too Many Requests", true, 429));

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_RATE_LIMITED, classified.errorCode());
        assertEquals(429, classified.httpStatus());
        assertTrue(classified.retryable());
    }

    @Test
    @DisplayName("其他 4xx/5xx → PROVIDER_ERROR，5xx 可重试、4xx 不可重试")
    void classifiesOtherHttpErrorsAsProviderError() {
        var badRequest = ProviderErrorClassifier.classify(
                new LlmApiException("Bad Request", false, 400));
        assertNotNull(badRequest);
        assertEquals(ProviderErrorClassifier.PROVIDER_ERROR, badRequest.errorCode());
        assertEquals(400, badRequest.httpStatus());
        assertFalse(badRequest.retryable());
        assertTrue(badRequest.message().contains("HTTP 400"));
        assertTrue(badRequest.message().contains("Bad Request"));

        var serverError = ProviderErrorClassifier.classify(
                new LlmApiException("Internal Server Error", true, 500));
        assertNotNull(serverError);
        assertEquals(ProviderErrorClassifier.PROVIDER_ERROR, serverError.errorCode());
        assertEquals(500, serverError.httpStatus());
        assertTrue(serverError.retryable());
    }

    @Test
    @DisplayName("cause 链中包裹的 LlmApiException 也能被识别")
    void unwrapsCauseChainToFindLlmApiException() {
        Throwable wrapped = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new LlmApiException("Insufficient Balance", false, 402)));

        var classified = ProviderErrorClassifier.classify(wrapped);

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_PAYMENT_REQUIRED, classified.errorCode());
        assertEquals(402, classified.httpStatus());
    }

    @Test
    @DisplayName("ConnectException → PROVIDER_UNREACHABLE，提取目标地址、可重试且无 HTTP 状态")
    void classifiesConnectExceptionAsUnreachableWithAddress() {
        var classified = ProviderErrorClassifier.classify(
                new ConnectException("Failed to connect to /127.0.0.1:17890"));

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_UNREACHABLE, classified.errorCode());
        assertEquals(0, classified.httpStatus());
        assertEquals("无法连接 LLM 服务（127.0.0.1:17890 连接失败），请检查本地代理或网络设置",
                classified.message());
        assertTrue(classified.retryable());
    }

    @Test
    @DisplayName("cause 链深层包裹的 ConnectException 也能识别；提取不到地址时省略括号部分")
    void unwrapsCauseChainToFindConnectException() {
        Throwable wrapped = new LlmApiException("OpenAI stream error: Connection refused",
                new RuntimeException("middle",
                        new ConnectException("Connection refused")), true);

        var classified = ProviderErrorClassifier.classify(wrapped);

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_UNREACHABLE, classified.errorCode());
        assertEquals(0, classified.httpStatus());
        assertEquals("无法连接 LLM 服务，请检查本地代理或网络设置", classified.message());
        assertTrue(classified.retryable());
    }

    @Test
    @DisplayName("分类优先级：HTTP 状态码优先于 cause 链中的连接异常")
    void httpStatusTakesPriorityOverConnectException() {
        var llm = new LlmApiException("Insufficient Balance", false, 402);
        llm.initCause(new ConnectException("Failed to connect to /127.0.0.1:17890"));

        var classified = ProviderErrorClassifier.classify(llm);

        assertNotNull(classified);
        assertEquals(ProviderErrorClassifier.PROVIDER_PAYMENT_REQUIRED, classified.errorCode());
        assertEquals(402, classified.httpStatus());
    }

    @Test
    @DisplayName("非 Provider HTTP 错误返回 null（普通异常 / httpStatus=0 / httpStatus<400）")
    void returnsNullForNonProviderHttpErrors() {
        assertNull(ProviderErrorClassifier.classify(new RuntimeException("boom")));
        assertNull(ProviderErrorClassifier.classify(
                new LlmApiException("no http status", true)));
        assertNull(ProviderErrorClassifier.classify(
                new LlmApiException("weird status", false, 200)));
        assertNull(ProviderErrorClassifier.classify(null));
    }
}

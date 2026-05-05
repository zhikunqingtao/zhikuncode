package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 QueryEngine 的媒体错误判断逻辑
 */
class QueryEngineMediaRecoveryTest {

    @ParameterizedTest
    @DisplayName("isMediaRelatedError 应识别媒体相关错误消息")
    @ValueSource(strings = {
            "image is invalid or too_large",
            "file_too_large: exceeds 10MB limit",
            "invalid_image format detected",
            "could not process image in request",
            "media_type_not_supported: audio/wav"
    })
    void isMediaRelatedError_shouldReturnTrue(String errorMessage) throws Exception {
        var exception = new LlmApiException(errorMessage, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertTrue(result, "Should detect media error: " + errorMessage);
    }

    @ParameterizedTest
    @DisplayName("isMediaRelatedError 应排除非媒体错误")
    @ValueSource(strings = {
            "prompt is too long",
            "rate_limit_exceeded",
            "model_not_found",
            "insufficient_quota",
            "context_length_exceeded"
    })
    void isMediaRelatedError_shouldReturnFalse(String errorMessage) throws Exception {
        var exception = new LlmApiException(errorMessage, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertFalse(result, "Should not detect as media error: " + errorMessage);
    }

    @Test
    @DisplayName("isMediaRelatedError 对null消息返回false")
    void isMediaRelatedError_nullMessage_shouldReturnFalse() throws Exception {
        var exception = new LlmApiException(null, false, 413);
        boolean result = invokeIsMediaRelatedError(exception);
        assertFalse(result);
    }

    /**
     * 通过反射调用 QueryEngine.isMediaRelatedError() 私有方法。
     */
    private boolean invokeIsMediaRelatedError(LlmApiException e) throws Exception {
        QueryEngine engine = createMinimalQueryEngine();
        Method method = QueryEngine.class.getDeclaredMethod("isMediaRelatedError", LlmApiException.class);
        method.setAccessible(true);
        return (boolean) method.invoke(engine, e);
    }

    /**
     * 创建最小化的QueryEngine实例（所有依赖为null，仅用于测试private方法）
     */
    private QueryEngine createMinimalQueryEngine() throws Exception {
        var constructor = QueryEngine.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        return (QueryEngine) constructor.newInstance(args);
    }
}

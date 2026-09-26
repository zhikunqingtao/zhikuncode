package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly enabled real DeepSeek official calls. Sends synthetic data only; never logs credentials. */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_SUMMARY_LIVE_TEST", matches = "true")
@Timeout(180)
class DeepSeekSummaryLiveTest {
    private static final String MODEL = "deepseek-flash";
    private static final String ENDPOINT = "https://api.deepseek.com/v1";
    private static final String SYSTEM_PROMPT =
            "你是编程会话的事实压缩器，只输出一个完整的 <summary>...</summary>，保留文件路径与待处理事项。";
    private static final String USER_CONTENT = "用户要求把重试次数从 2 改为 3；测试尚未执行。";
    private final ObjectMapper mapper = new ObjectMapper();
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        String key = System.getenv("LLM_PROVIDER_DEEPSEEK_API_KEY");
        assertNotNull(key, "DeepSeek API key required");
        assertFalse(key.isBlank(), "DeepSeek API key required");
        provider = new OpenAiCompatibleProvider("deepseek", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 15, 30, true),
                new ApiKeyRotationManager(key), key, ENDPOINT, MODEL, List.of(MODEL));
    }

    @Test
    void summarizesWithMaxThinking() {
        summarize(SummaryRequest.ThinkingMode.MAX);
    }

    @Test
    void summarizesWithLowThinking() {
        summarize(SummaryRequest.ThinkingMode.LOW);
    }

    @Test
    void summarizesWithThinkingOff() {
        summarize(SummaryRequest.ThinkingMode.OFF);
    }

    private void summarize(SummaryRequest.ThinkingMode mode) {
        assertTrue(provider.supportsSummary(MODEL, mode));
        long startedNanos = System.nanoTime();
        var result = provider.summarize(new SummaryRequest(MODEL, mode, SYSTEM_PROMPT, USER_CONTENT,
                4096, System.nanoTime() + TimeUnit.SECONDS.toNanos(150)), LlmCallContext.unscoped());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertNull(result.failureReason(), () -> "DEEPSEEK_LIVE summary[" + mode + "] failed: " + result.failureReason());
        assertNotNull(result.content(), "DEEPSEEK_LIVE summary[" + mode + "] content must not be null");
        assertFalse(result.content().isBlank(), "DEEPSEEK_LIVE summary[" + mode + "] content must not be blank");
        System.out.println("DEEPSEEK_LIVE summary[" + mode + "]: finishReason=" + result.finishReason()
                + ", usage=" + result.usage() + ", model=" + result.responseModel()
                + ", elapsedMs=" + elapsedMillis + ", contentLength=" + result.content().length());
    }
}

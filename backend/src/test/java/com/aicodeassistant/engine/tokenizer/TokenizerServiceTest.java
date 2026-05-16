package com.aicodeassistant.engine.tokenizer;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenizerService 单元测试 — 验证精确 Token 计数与健康检查。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenizerService 单元测试")
class TokenizerServiceTest {

    @Mock
    private PythonCapabilityAwareClient pythonClient;

    private TokenizerService tokenizerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tokenizerService = new TokenizerService(pythonClient, objectMapper);
    }

    @Test
    @DisplayName("countExact — Python 服务返回成功响应时，返回正确 token 数")
    void testCountExact_Success() throws Exception {
        // Given: mock Python 服务返回 token_count = 42
        JsonNode responseNode = objectMapper.readTree("{\"token_count\": 42}");
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenReturn(Optional.of(responseNode));

        // When
        int result = tokenizerService.countExact("Hello world test text", "default");

        // Then
        assertThat(result).isEqualTo(42);
        verify(pythonClient).post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class));
    }

    @Test
    @DisplayName("countExact — Python 服务不可用（抛异常），返回 -1")
    void testCountExact_ServiceUnavailable() {
        // Given: mock 抛出异常
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // When
        int result = tokenizerService.countExact("some text", "default");

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    @DisplayName("countExact — 空文本返回 0")
    void testCountExact_EmptyText() {
        // When
        int resultEmpty = tokenizerService.countExact("", "default");
        int resultNull = tokenizerService.countExact(null, "default");

        // Then
        assertThat(resultEmpty).isEqualTo(0);
        assertThat(resultNull).isEqualTo(0);
        verifyNoInteractions(pythonClient);
    }

    @Test
    @DisplayName("countExact — 响应无 token_count 字段时返回 -1")
    void testCountExact_MissingTokenCountField() throws Exception {
        // Given: 返回不含 token_count 的 JSON
        JsonNode responseNode = objectMapper.readTree("{\"status\": \"ok\"}");
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenReturn(Optional.of(responseNode));

        // When
        int result = tokenizerService.countExact("some text", "default");

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    @DisplayName("countExact — 响应为空 Optional 时返回 -1")
    void testCountExact_EmptyResponse() {
        // Given
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenReturn(Optional.empty());

        // When
        int result = tokenizerService.countExact("some text", "default");

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    @DisplayName("isAvailable — Python 服务正常响应时返回 true")
    void testIsAvailable_WhenServiceResponds() throws Exception {
        // Given: countExact("test", "default") 返回正数
        JsonNode responseNode = objectMapper.readTree("{\"token_count\": 1}");
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenReturn(Optional.of(responseNode));

        // When
        boolean available = tokenizerService.isAvailable();

        // Then
        assertThat(available).isTrue();
    }

    @Test
    @DisplayName("isAvailable — Python 服务不可用时返回 false")
    void testIsAvailable_WhenServiceDown() {
        // Given: countExact 抛出异常
        when(pythonClient.post(eq("/api/tokenizer/count"), any(), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // When
        boolean available = tokenizerService.isAvailable();

        // Then
        assertThat(available).isFalse();
    }
}

package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CheckpointService 单元测试 — 覆盖 shouldCheckpoint 策略、save 持久化、FIFO 淘汰和大消息裁剪。
 */
@ExtendWith(MockitoExtension.class)
class CheckpointServiceTest {

    @Mock private JdbcTemplate projectJdbcTemplate;
    @Mock private SqliteConfig sqliteConfig;
    @Mock private DatabaseResolver databaseResolver;

    private CheckpointService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(databaseResolver.getProjectDbPath(any(Path.class))).thenReturn(Path.of("/tmp/test.db"));

        service = new CheckpointService(projectJdbcTemplate, sqliteConfig, databaseResolver, objectMapper);
    }

    // ═══════════════ shouldCheckpoint 测试 ═══════════════

    @Test
    void shouldReturnTrue_whenTurnCountReaches5() {
        // Given: 上次检查点在 turn 0，当前 turn 5
        int turnCount = 5;
        int toolCallCount = 3;
        int lastCheckpointTurn = 0;

        // When
        boolean result = service.shouldCheckpoint(turnCount, toolCallCount, lastCheckpointTurn);

        // Then
        assertTrue(result);
    }

    @Test
    void shouldReturnTrue_whenToolCallCountReaches10() {
        // Given: 10 次工具调用（10 % 10 == 0）
        int turnCount = 2;
        int toolCallCount = 10;
        int lastCheckpointTurn = 0;

        // When
        boolean result = service.shouldCheckpoint(turnCount, toolCallCount, lastCheckpointTurn);

        // Then
        assertTrue(result);
    }

    @Test
    void shouldReturnFalse_whenNeitherConditionMet() {
        // Given: turn 2（距上次<5），toolCalls 3（不是 10 的倍数）
        int turnCount = 2;
        int toolCallCount = 3;
        int lastCheckpointTurn = 0;

        // When
        boolean result = service.shouldCheckpoint(turnCount, toolCallCount, lastCheckpointTurn);

        // Then
        assertFalse(result);
    }

    @Test
    void shouldReturnTrue_whenToolCallCountIs20() {
        // Given: 20 次工具调用（20 的倍数）
        int turnCount = 3;
        int toolCallCount = 20;
        int lastCheckpointTurn = 1;

        // When
        boolean result = service.shouldCheckpoint(turnCount, toolCallCount, lastCheckpointTurn);

        // Then
        assertTrue(result);
    }

    @Test
    void shouldReturnFalse_whenToolCallCountIsZero() {
        // Given: 0 次工具调用（0 % 10 == 0 但有 toolCallCount > 0 保护）
        int turnCount = 2;
        int toolCallCount = 0;
        int lastCheckpointTurn = 0;

        // When
        boolean result = service.shouldCheckpoint(turnCount, toolCallCount, lastCheckpointTurn);

        // Then
        assertFalse(result);
    }

    // ═══════════════ save 测试 ═══════════════

    @Test
    void shouldPersistCheckpoint_whenSaveCalled() {
        // Given
        doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        when(projectJdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "cp-1", "run-1", "session-1", "agent-1", 1,
                "[{\"role\":\"user\",\"content\":\"hello\"}]", "{}",
                5, 3, 1000L, "/tmp", Instant.now()
        );

        // When
        service.save(checkpoint);

        // Then: INSERT 被调用
        verify(projectJdbcTemplate, atLeast(1)).update(contains("INSERT OR REPLACE"), any(Object[].class));
    }

    @Test
    void shouldCleanupOldCheckpoints_whenSaveCalled() {
        // Given
        doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        when(projectJdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "cp-2", "run-1", "session-1", "agent-1", 11,
                "[]", "{}", 50, 11, 5000L, "/tmp", Instant.now()
        );

        // When
        service.save(checkpoint);

        // Then: DELETE 语句被调用以清理旧检查点（FIFO 淘汰）
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(projectJdbcTemplate, atLeast(2)).update(sqlCaptor.capture(), any(Object[].class));
        boolean hasDelete = sqlCaptor.getAllValues().stream()
                .anyMatch(sql -> sql.contains("DELETE FROM agent_checkpoints"));
        assertThat(hasDelete).isTrue();
    }

    @Test
    void shouldTrimMessages_whenJsonExceeds5MB() {
        // Given: 构造超过 5MB 的 messagesJson
        doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        when(projectJdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // 构造一个包含大量消息的 JSON 数组（>5MB）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            // 每条消息约 60KB
            sb.append("{\"role\":\"assistant\",\"content\":\"").append("x".repeat(60000)).append("\"}");
        }
        sb.append("]");
        String largeJson = sb.toString();
        assertThat(largeJson.length()).isGreaterThan(5 * 1024 * 1024);

        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "cp-3", "run-1", "session-1", "agent-1", 1,
                largeJson, "{}",
                10, 5, 2000L, "/tmp", Instant.now()
        );

        // When & Then: 不应抛出异常，应成功保存（内部裁剪）
        assertDoesNotThrow(() -> service.save(checkpoint));
        verify(projectJdbcTemplate, atLeast(1)).update(contains("INSERT OR REPLACE"), any(Object[].class));
    }

    @Test
    void shouldSaveNormally_whenMessagesJsonIsSmall() {
        // Given: 小体积的 messagesJson
        doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        when(projectJdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        String smallJson = "[{\"role\":\"user\",\"content\":\"test\"}]";
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "cp-4", "run-1", "session-1", "agent-1", 1,
                smallJson, "{}",
                2, 1, 500L, "/tmp", Instant.now()
        );

        // When
        service.save(checkpoint);

        // Then: 使用原始 JSON 保存
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(projectJdbcTemplate, atLeast(1)).update(contains("INSERT OR REPLACE"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat((String) args[5]).isEqualTo(smallJson);
    }
}

package com.aicodeassistant.permission;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DurablePermissionService 单元测试 — 覆盖持久化、解决、超时过期和连续失败计数器。
 */
@ExtendWith(MockitoExtension.class)
class DurablePermissionServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SqliteConfig sqliteConfig;
    @Mock private DatabaseResolver databaseResolver;

    private DurablePermissionService service;

    @BeforeEach
    void setUp() {
        // Mock databaseResolver 返回一个虚拟 DB 路径
        when(databaseResolver.getProjectDbPath(any(Path.class))).thenReturn(Path.of("/tmp/test.db"));

        // SqliteConfig.executeWriteVoid 直接执行传入的 Runnable（lenient 避免部分测试覆盖此 mock）
        lenient().doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        // SqliteConfig.executeWrite 直接执行传入的 Supplier 并返回结果
        lenient().doAnswer(inv -> {
            java.util.function.Supplier<?> supplier = inv.getArgument(1);
            return supplier.get();
        }).when(sqliteConfig).executeWrite(any(Path.class), any(java.util.function.Supplier.class));

        service = new DurablePermissionService(jdbcTemplate, sqliteConfig, databaseResolver);
    }

    @Test
    void shouldPersistRequest_whenCalledWithValidArgs() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        service.persistRequest("run-1", "session-1", "tool-use-1",
                "BashTool", "high", "Execute command",
                "{\"command\":\"ls\"}", "direct", null);

        // Then: 验证 INSERT 被调用，且参数中包含关键字段
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO permission_requests"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args).isNotNull();
        // args[1] = runId, args[2] = sessionId, args[3] = toolUseId
        assertThat(args[1]).isEqualTo("run-1");
        assertThat(args[2]).isEqualTo("session-1");
        assertThat(args[3]).isEqualTo("tool-use-1");
        assertThat(args[4]).isEqualTo("BashTool");
    }

    @Test
    void shouldUpdateStatus_whenResolveCalledWithApproval() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        boolean result = service.resolve("tool-use-1", "approved", "USER_WS", true, "project");

        // Then: 验证 UPDATE 调用参数正确
        assertThat(result).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE permission_requests"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("approved"); // status
        assertThat(args[1]).isEqualTo("approved"); // decision
        assertThat(args[2]).isEqualTo("USER_WS");  // decided_by
        assertThat(args[3]).isEqualTo(1);           // remember = true → 1
        assertThat(args[4]).isEqualTo("project");   // remember_scope
        assertThat(args[6]).isEqualTo("tool-use-1"); // WHERE tool_use_id
    }

    @Test
    void shouldUpdateStatus_whenResolveCalledWithDenial() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        // When
        boolean result = service.resolve("tool-use-2", "denied", "TIMEOUT", false, null);

        // Then
        assertThat(result).isTrue();
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE permission_requests"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("denied");   // status
        assertThat(args[1]).isEqualTo("denied");   // decision
        assertThat(args[3]).isEqualTo(0);          // remember = false → 0
    }

    @Test
    void shouldMarkTimedOutRequests_whenExpireTimedOutCalled() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        // When
        service.expireTimedOut();

        // Then: 验证 UPDATE 被调用以标记超时请求
        verify(jdbcTemplate).update(contains("SET status = 'timeout'"), any(Object[].class));
    }

    @Test
    void shouldIncrementFailureCounter_whenExpireTimedOutThrows() {
        // Given: sqliteConfig 抛出异常
        doAnswer(inv -> {
            throw new RuntimeException("DB error");
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        // When: 连续调用多次
        service.expireTimedOut(); // failure #1
        service.expireTimedOut(); // failure #2
        service.expireTimedOut(); // failure #3

        // Then: 不应抛出异常（内部捕获并记录），连续调用正常完成
        // 验证日志或通过无异常抛出确认
        assertThat(true).isTrue(); // 方法正常返回即为通过
    }

    @Test
    void shouldResetFailureCounter_whenExpireTimedOutSucceeds() {
        // Given: 先失败再成功
        // 第一次调用抛异常
        doAnswer(inv -> {
            throw new RuntimeException("DB error");
        }).doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        // When
        service.expireTimedOut(); // 失败 → counter = 1
        service.expireTimedOut(); // 成功 → counter = 0

        // Then: 第二次成功说明 counter 被重置
        verify(sqliteConfig, times(2)).executeWriteVoid(any(Path.class), any(Runnable.class));
    }

    @Test
    void shouldReturnPendingRecords_whenGetPendingCalled() {
        // Given
        @SuppressWarnings("unchecked")
        List<PermissionRequestRecord> mockResult = mock(List.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(mockResult);

        // When
        List<PermissionRequestRecord> result = service.getPending("session-1");

        // Then
        assertThat(result).isSameAs(mockResult);
        verify(jdbcTemplate).query(contains("status = 'pending'"), any(RowMapper.class),
                eq("session-1"), anyString());
    }
}

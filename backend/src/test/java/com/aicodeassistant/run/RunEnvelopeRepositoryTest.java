package com.aicodeassistant.run;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RunEnvelopeRepository 单元测试 — 覆盖 CRUD 和状态更新操作。
 */
@ExtendWith(MockitoExtension.class)
class RunEnvelopeRepositoryTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SqliteConfig sqliteConfig;
    @Mock private DatabaseResolver databaseResolver;

    private RunEnvelopeRepository repository;

    @BeforeEach
    void setUp() {
        when(databaseResolver.getProjectDbPath(any(Path.class))).thenReturn(Path.of("/tmp/test.db"));

        // SqliteConfig.executeWriteVoid 直接执行传入的 Runnable
        lenient().doAnswer(inv -> {
            Runnable runnable = inv.getArgument(1);
            runnable.run();
            return null;
        }).when(sqliteConfig).executeWriteVoid(any(Path.class), any(Runnable.class));

        repository = new RunEnvelopeRepository(jdbcTemplate, sqliteConfig, databaseResolver);
    }

    @Test
    void shouldInsert_whenCreateCalled() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        RunEnvelope envelope = RunEnvelope.start("session-1", null, "main", "qwen-max");

        // When
        repository.insert(envelope);

        // Then: INSERT 被执行
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO run_envelopes"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo(envelope.id());
        assertThat(args[1]).isEqualTo("session-1");
        assertThat(args[3]).isEqualTo("running"); // status dbValue
        assertThat(args[4]).isEqualTo("main");    // agentType
        assertThat(args[5]).isEqualTo("qwen-max"); // model
    }

    @Test
    void shouldReturnEnvelope_whenFindByIdCalled() {
        // Given
        Instant now = Instant.now();
        RunEnvelope mockEnvelope = new RunEnvelope(
                "run-1", "session-1", null, RunEnvelope.RunStatus.RUNNING,
                "main", "qwen-max", null, now, null, null,
                0, 0.0, 0, 0, null, now, now
        );
        when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("run-1")))
                .thenReturn(List.of(mockEnvelope));

        // When
        Optional<RunEnvelope> result = repository.findById("run-1");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("run-1");
        assertThat(result.get().sessionId()).isEqualTo("session-1");
        assertThat(result.get().status()).isEqualTo(RunEnvelope.RunStatus.RUNNING);
    }

    @Test
    void shouldReturnEmpty_whenFindByIdNotFound() {
        // Given
        when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("non-existent")))
                .thenReturn(List.of());

        // When
        Optional<RunEnvelope> result = repository.findById("non-existent");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnList_whenFindBySessionCalled() {
        // Given
        Instant now = Instant.now();
        RunEnvelope envelope1 = new RunEnvelope(
                "run-1", "session-1", null, RunEnvelope.RunStatus.COMPLETED,
                "main", "qwen-max", null, now, now, null,
                1000, 0.05, 5, 3, null, now, now
        );
        RunEnvelope envelope2 = new RunEnvelope(
                "run-2", "session-1", null, RunEnvelope.RunStatus.RUNNING,
                "sub-agent", "qwen-max", null, now, null, null,
                0, 0.0, 0, 0, null, now, now
        );
        when(jdbcTemplate.query(contains("WHERE session_id = ?"), any(RowMapper.class),
                eq("session-1"), eq(10)))
                .thenReturn(List.of(envelope1, envelope2));

        // When
        List<RunEnvelope> result = repository.findBySession("session-1", 10);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("run-1");
        assertThat(result.get(1).id()).isEqualTo("run-2");
    }

    @Test
    void shouldUpdateStatus_whenCalled() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        Instant now = Instant.now();
        RunEnvelope updated = new RunEnvelope(
                "run-1", "session-1", null, RunEnvelope.RunStatus.COMPLETED,
                "main", "qwen-max", null, now, now, null,
                2000, 0.1, 10, 5, null, now, now
        );

        // When
        repository.updateStatus("run-1", updated);

        // Then: UPDATE 被调用
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE run_envelopes SET"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("completed"); // status dbValue
        assertThat(args[3]).isEqualTo(2000);        // total_tokens
        assertThat(args[4]).isEqualTo(0.1);         // total_cost_usd
        assertThat(args[5]).isEqualTo(10);          // tool_call_count
        assertThat(args[6]).isEqualTo(5);           // turn_count
        assertThat(args[9]).isEqualTo("run-1");     // WHERE id
    }

    @Test
    void shouldUpdateStatus_whenAborted() {
        // Given
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        RunEnvelope running = RunEnvelope.start("session-1", null, "main", "qwen-max");
        RunEnvelope aborted = running.abort("user_interrupt");

        // When
        repository.updateStatus(running.id(), aborted);

        // Then
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("UPDATE run_envelopes SET"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("aborted");        // status
        assertThat(args[2]).isEqualTo("user_interrupt");  // abort_reason
    }
}

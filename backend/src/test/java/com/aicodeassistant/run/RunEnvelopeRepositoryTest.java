package com.aicodeassistant.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
    private RunEnvelopeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RunEnvelopeRepository(jdbcTemplate);
    }

    @Test
    void shouldReturnEnvelope_whenFindByIdCalled() {
        // Given
        Instant now = Instant.now();
        RunEnvelope mockEnvelope = new RunEnvelope(
                "run-1", "session-1", null, RunEnvelope.RunStatus.RUNNING,
                "main", "qwen-max", null, now, null, null,
                0, 0.0, 0, 0, null, now, now,
                0, null, null, RunEnvelope.VerificationStatus.NOT_REQUESTED, null, null
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
                1000, 0.05, 5, 3, null, now, now,
                0, RunEnvelope.RunExitReason.MODEL_FINISHED, null,
                RunEnvelope.VerificationStatus.NOT_REQUESTED, now, null
        );
        RunEnvelope envelope2 = new RunEnvelope(
                "run-2", "session-1", null, RunEnvelope.RunStatus.RUNNING,
                "sub-agent", "qwen-max", null, now, null, null,
                0, 0.0, 0, 0, null, now, now,
                0, null, null, RunEnvelope.VerificationStatus.NOT_REQUESTED, null, null
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

}

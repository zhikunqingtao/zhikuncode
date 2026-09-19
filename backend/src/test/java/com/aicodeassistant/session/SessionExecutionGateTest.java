package com.aicodeassistant.session;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionExecutionGateTest {
    @Test
    void sameDataSourceAndSessionIsExclusiveAndCloseIsIdempotent() {
        SessionExecutionGate gate = new SessionExecutionGate();
        DataSource dataSource = mock(DataSource.class);
        SessionExecutionGate.Token first = gate.tryAcquire(dataSource, "s1");

        assertThat(first).isNotNull();
        assertThat(gate.tryAcquire(dataSource, "s1")).isNull();
        assertThat(gate.tryAcquire(dataSource, "s2")).isNotNull();
        first.close();
        first.close();
        assertThat(gate.tryAcquire(dataSource, "s1")).isNotNull();
    }

    @Test
    void sameSessionOnDifferentDataSourcesDoesNotConflict() {
        SessionExecutionGate gate = new SessionExecutionGate();
        assertThat(gate.tryAcquire(mock(DataSource.class), "s1")).isNotNull();
        assertThat(gate.tryAcquire(mock(DataSource.class), "s1")).isNotNull();
    }
}

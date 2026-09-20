package com.aicodeassistant.session;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionExecutionGateTest {
    @Test
    void mergeExcludesActualBackgroundLifetimeButNormalQueriesStillWork() throws Exception {
        var gate = new SessionExecutionGate();
        var ds = mock(DataSource.class);
        var background = gate.acquireBackground(ds, "A");
        var normal = gate.tryAcquire(ds, "A");
        assertThat(normal).isNotNull();
        normal.close();
        assertThat(gate.tryAcquireMerge(ds, "A", "op")).isNull();
        Thread.ofVirtual().start(background::close).join();
        var merge = gate.tryAcquireMerge(ds, "A", "op");
        assertThat(merge).isNotNull();
        assertThat(gate.mergeOperationId(ds, "A")).isEqualTo("op");
        assertThat(gate.tryAcquire(ds, "A")).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate.acquireBackground(ds, "A"))
                .isInstanceOf(SessionExecutionBusyException.class);
        assertThat(gate.tryAcquire(ds, "C")).isNotNull();
        merge.close();
        assertThat(gate.tryAcquire(ds, "A")).isNotNull();
    }
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

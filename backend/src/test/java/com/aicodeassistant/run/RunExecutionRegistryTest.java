package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunExecutionRegistryTest {

    @Test
    void unregisterRemovesImmediatelyWhenNoWorkIsActive() {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());

        registry.unregister("run");

        assertThat(registry.isRegistered("run")).isFalse();
        assertThat(registry.activeRunForSession("session")).isEmpty();
    }

    @Test
    void lateLeaseReleaseCompletesDeferredUnregister() throws Exception {
        RunExecutionRegistry registry = new RunExecutionRegistry();
        registry.register("run", "session", new AbortContext());
        RunExecutionRegistry.WorkLease lease = registry.acquireWork("run", "tool", "tool-1");

        Thread unregister = Thread.ofVirtual().start(() -> registry.unregister("run"));
        unregister.join(3_000);
        assertThat(unregister.isAlive()).isFalse();
        assertThat(registry.isRegistered("run")).isTrue();
        assertThatThrownBy(() -> registry.acquireWork("run", "tool", "tool-2"))
                .isInstanceOf(RunExecutionRegistry.WorkRejectedException.class)
                .hasMessage("RUN_WORK_ADMISSION_CLOSED");

        lease.close();

        assertThat(registry.isRegistered("run")).isFalse();
        assertThat(registry.activeRunForSession("session")).isEmpty();
    }
}

package com.aicodeassistant.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BestEffortObservabilityRecorderTest {
    private BestEffortObservabilityRecorder recorder;

    @AfterEach
    void stopRecorder() {
        if (recorder != null) recorder.shutdown();
    }

    @Test
    void saturationDropsWithoutRunningPersistenceOnCaller() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicReference<String> writerThread = new AtomicReference<>();
        BestEffortObservabilityRecorder.EventSink sink = event -> {
            writerThread.set(Thread.currentThread().getName());
            writerEntered.countDown();
            releaseWriter.await(5, TimeUnit.SECONDS);
        };
        recorder = new BestEffortObservabilityRecorder(registry, sink);

        assertThat(recorder.record("run-1", "event", null, Map.of("value", 1))).isTrue();
        assertThat(writerEntered.await(2, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < BestEffortObservabilityRecorder.QUEUE_CAPACITY; i++) {
            assertThat(recorder.record("run-1", "event", null, Map.of("index", i))).isTrue();
        }

        long started = System.nanoTime();
        assertThat(recorder.record("run-1", "event", null, Map.of("overflow", true))).isFalse();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).isLessThan(100);
        assertThat(writerThread.get()).isEqualTo("observability-event-writer");
        assertThat(registry.get("zhiku.observability.events.dropped").counter().count()).isEqualTo(1.0);
        releaseWriter.countDown();
    }

    @Test
    void persistenceFailureIsSwallowedAndCounted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch attempted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        recorder = new BestEffortObservabilityRecorder(registry, event -> {
            if (writes.getAndIncrement() == 0) throw new IllegalStateException("sink unavailable");
            attempted.countDown();
        });

        assertThat(recorder.record("run-1", "event", null, Map.of("safe", true))).isTrue();
        // The second item lets the test observe that the worker survived the first failure.
        assertThat(recorder.record("run-1", "event", null, Map.of("safe", true))).isTrue();
        assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.get("zhiku.observability.events.write_failures").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shutdownRejectsImmediatelyWithoutThrowing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        recorder = new BestEffortObservabilityRecorder(registry, event -> { });
        recorder.shutdown();

        assertThat(recorder.record("run-1", "event", null, Map.of())).isFalse();
        assertThat(recorder.isShutdown()).isTrue();
        assertThat(registry.get("zhiku.observability.events.dropped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void plaintextFieldsAreConvertedToLengthAndFingerprintBeforeQueueing() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch persisted = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        recorder = new BestEffortObservabilityRecorder(registry, event -> {
            captured.set(event.payload());
            persisted.countDown();
        });

        String secret = "synthetic-sensitive-value-that-must-never-enter-the-queue";
        assertThat(recorder.record("run-1", "event", null,
                Map.of("prompt", secret, "status", "ok"))).isTrue();
        assertThat(persisted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(captured.get()).doesNotContainKey("prompt");
        assertThat(captured.get()).containsEntry("promptLength", secret.length());
        assertThat(String.valueOf(captured.get())).doesNotContain(secret);
    }
}

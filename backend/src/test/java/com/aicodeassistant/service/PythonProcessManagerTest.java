package com.aicodeassistant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class PythonProcessManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void processBuilderUsesConfiguredRuntimeAndBindAddress() {
        PythonProcessManager manager = configuredManager();

        ProcessBuilder builder = manager.createProcessBuilder();

        assertThat(builder.directory().toPath()).isEqualTo(tempDir.toAbsolutePath());
        assertThat(builder.command()).containsExactly(
                "/opt/python/bin/python", "-m", "uvicorn", "src.main:app",
                "--host", "0.0.0.0", "--port", "8123");
        assertThat(builder.environment().get("PYTHONPATH"))
                .startsWith(tempDir.resolve("src").toAbsolutePath().toString());
    }

    @Test
    void missingServiceDirectoryFailsBeforeStartingAChild() {
        PythonProcessManager manager = configuredManager();
        ReflectionTestUtils.setField(manager, "pythonServicePath",
                tempDir.resolve("missing").toString());

        assertThatThrownBy(manager::createProcessBuilder)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void healthCheckUsesCanonicalApiHealthEndpoint() {
        PythonProcessManager manager = configuredManager();

        assertThat(manager.healthUri().toString())
                .isEqualTo("http://127.0.0.1:8123/api/health");
    }

    @Test
    void scheduledHealthCheckRestartsOnceAndClearsFailureCountOnSuccess() {
        StubManager manager = new StubManager(true);
        stateOf(manager).set(PythonProcessManager.ProcessState.RUNNING);

        manager.runHealthCheckCycle();

        assertThat(manager.restartCalls()).isEqualTo(1);
        assertThat(manager.getState()).isEqualTo(PythonProcessManager.ProcessState.RUNNING);
        assertThat(manager.getRestartCount()).isZero();
    }

    @Test
    void scheduledHealthCheckStopsAfterThreeFailedIntervals() {
        StubManager manager = new StubManager(false, false, false);
        stateOf(manager).set(PythonProcessManager.ProcessState.RUNNING);

        manager.runHealthCheckCycle();
        manager.runHealthCheckCycle();
        manager.runHealthCheckCycle();

        assertThat(manager.restartCalls()).isEqualTo(3);
        assertThat(manager.getState()).isEqualTo(PythonProcessManager.ProcessState.FAILED);
        assertThat(manager.getRestartCount()).isEqualTo(3);
    }

    @Test
    void applicationReadyHonorsAutoStartFlag() {
        StubManager manager = new StubManager();
        ReflectionTestUtils.setField(manager, "autoStart", false);

        manager.onApplicationReady();

        assertThat(manager.asyncStartCalls()).isZero();
        ReflectionTestUtils.setField(manager, "autoStart", true);
        manager.onApplicationReady();
        assertThat(manager.asyncStartCalls()).isEqualTo(1);
    }

    @Test
    void scheduledHealthCheckLeavesSchedulerThreadAndDeduplicatesWorkers()
            throws InterruptedException {
        BlockingHealthManager manager = new BlockingHealthManager();
        stateOf(manager).set(PythonProcessManager.ProcessState.RUNNING);

        assertTimeout(Duration.ofSeconds(1), manager::scheduledHealthCheck);
        assertThat(manager.healthEntered.await(1, TimeUnit.SECONDS)).isTrue();

        assertTimeout(Duration.ofSeconds(1), manager::scheduledHealthCheck);
        assertThat(manager.healthCalls.get()).isEqualTo(1);

        manager.releaseHealth.countDown();
        assertThat(manager.healthFinished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void autoStartRetriesTransientFailureAndClearsCountAfterSuccess() {
        StartupStubManager manager = new StartupStubManager(false, true);

        manager.startWithRetry();

        assertThat(manager.startCalls()).isEqualTo(2);
        assertThat(manager.getState()).isEqualTo(PythonProcessManager.ProcessState.RUNNING);
        assertThat(manager.getRestartCount()).isZero();
    }

    @Test
    void autoStartStopsAfterThreeFailedRetries() {
        StartupStubManager manager = new StartupStubManager(false, false, false, false);

        manager.startWithRetry();

        assertThat(manager.startCalls()).isEqualTo(4);
        assertThat(manager.getState()).isEqualTo(PythonProcessManager.ProcessState.FAILED);
        assertThat(manager.getRestartCount()).isEqualTo(3);
    }

    @Test
    void autoStartStopsRetryingWhenShutdownIsRequested() {
        StartupStubManager manager = new StartupStubManager(false, false);
        manager.shutdownAfterFirstAttempt();

        manager.startWithRetry();

        assertThat(manager.startCalls()).isEqualTo(1);
        assertThat(manager.getState()).isEqualTo(PythonProcessManager.ProcessState.STOPPED);
        assertThat(manager.getRestartCount()).isZero();
    }

    private PythonProcessManager configuredManager() {
        PythonProcessManager manager = new PythonProcessManager();
        ReflectionTestUtils.setField(manager, "pythonHost", "127.0.0.1");
        ReflectionTestUtils.setField(manager, "pythonBindHost", "0.0.0.0");
        ReflectionTestUtils.setField(manager, "pythonPort", 8123);
        ReflectionTestUtils.setField(manager, "pythonServicePath", tempDir.toString());
        ReflectionTestUtils.setField(manager, "pythonExecutable", "/opt/python/bin/python");
        ReflectionTestUtils.setField(manager, "startupTimeoutSeconds", 1);
        ReflectionTestUtils.setField(manager, "restartDelayMs", 0L);
        return manager;
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<PythonProcessManager.ProcessState> stateOf(
            PythonProcessManager manager) {
        return (AtomicReference<PythonProcessManager.ProcessState>)
                ReflectionTestUtils.getField(manager, "stateRef");
    }

    private static AtomicReference<PythonProcessManager.ProcessState> staticStateOf(
            PythonProcessManager manager) {
        @SuppressWarnings("unchecked")
        AtomicReference<PythonProcessManager.ProcessState> state =
                (AtomicReference<PythonProcessManager.ProcessState>)
                        ReflectionTestUtils.getField(manager, "stateRef");
        return state;
    }

    private static AtomicInteger restartCountOf(PythonProcessManager manager) {
        return (AtomicInteger) ReflectionTestUtils.getField(manager, "restartCount");
    }

    private static final class StubManager extends PythonProcessManager {
        private final List<Boolean> restartResults;
        private final AtomicInteger restartCalls = new AtomicInteger();
        private final AtomicInteger asyncStartCalls = new AtomicInteger();

        private StubManager(Boolean... restartResults) {
            this.restartResults = List.of(restartResults);
        }

        @Override
        public boolean checkHealth() {
            return false;
        }

        @Override
        public synchronized boolean restart() {
            int call = restartCalls.getAndIncrement();
            return call < restartResults.size() && restartResults.get(call);
        }

        @Override
        void startAsync() {
            asyncStartCalls.incrementAndGet();
        }

        int restartCalls() {
            return restartCalls.get();
        }

        int asyncStartCalls() {
            return asyncStartCalls.get();
        }
    }

    private static final class StartupStubManager extends PythonProcessManager {
        private final List<Boolean> startResults;
        private final AtomicInteger startCalls = new AtomicInteger();
        private boolean shutdownAfterFirstAttempt;

        private StartupStubManager(Boolean... startResults) {
            this.startResults = List.of(startResults);
            ReflectionTestUtils.setField(this, "restartDelayMs", 0L);
        }

        @Override
        public synchronized boolean start() {
            int call = startCalls.getAndIncrement();
            boolean started = call < startResults.size() && startResults.get(call);
            if (started) {
                staticStateOf(this).set(ProcessState.RUNNING);
                restartCountOf(this).set(0);
            } else {
                staticStateOf(this).set(ProcessState.FAILED);
            }
            if (shutdownAfterFirstAttempt && call == 0) {
                onShutdown();
            }
            return started;
        }

        void shutdownAfterFirstAttempt() {
            shutdownAfterFirstAttempt = true;
        }

        int startCalls() {
            return startCalls.get();
        }
    }

    private static final class BlockingHealthManager extends PythonProcessManager {
        private final AtomicInteger healthCalls = new AtomicInteger();
        private final CountDownLatch healthEntered = new CountDownLatch(1);
        private final CountDownLatch releaseHealth = new CountDownLatch(1);
        private final CountDownLatch healthFinished = new CountDownLatch(1);

        @Override
        public boolean checkHealth() {
            healthCalls.incrementAndGet();
            healthEntered.countDown();
            try {
                return releaseHealth.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                healthFinished.countDown();
            }
        }
    }

}

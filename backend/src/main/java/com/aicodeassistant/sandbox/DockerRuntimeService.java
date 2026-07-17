package com.aicodeassistant.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Owns bounded Docker control-plane probes and cleanup commands.
 * Tool commands themselves are deliberately not executed here; they remain
 * owned by ManagedProcessRunner.
 */
@Component
public class DockerRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeService.class);
    private final Semaphore controls = new Semaphore(2);

    public boolean isAvailable() {
        return runControl(List.of("docker", "info"), 10_000) == 0;
    }

    public boolean ensureRemoved(String containerName, long deadlineNanos) {
        if (!safeContainerName(containerName)) return false;
        long remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) return false;
        int remove = runControl(List.of("docker", "rm", "-f", containerName), Math.min(remaining, 2_000));
        if (remove == 0) return true;
        remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) return false;
        return runControl(List.of("docker", "inspect", containerName), Math.min(remaining, 1_000)) != 0;
    }

    private int runControl(List<String> command, long timeoutMs) {
        if (timeoutMs <= 0 || !controls.tryAcquire()) return -1;
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(1_000, timeoutMs), TimeUnit.MILLISECONDS);
                return -1;
            }
            return process.exitValue();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Docker control command failed: {}", e.getMessage());
            if (process != null && process.isAlive()) process.destroyForcibly();
            return -1;
        } finally {
            controls.release();
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static boolean safeContainerName(String name) {
        return name != null && name.matches("zhikun-[a-z0-9_.-]{1,120}");
    }
}

package com.aicodeassistant.tool.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * ProcessTreeManager — 进程树管理器。
 * <p>
 * 杀死主进程及其所有子进程，使用梯度终止策略:
 * <ol>
 *   <li>SIGTERM 所有子进程和主进程</li>
 *   <li>等待 gracePeriod</li>
 *   <li>如仍存活，SIGKILL 强制杀死</li>
 * </ol>
 * <p>
 * 使用 Java 9+ {@code ProcessHandle.descendants()} 获取完整进程树。
 */
@Component
public class ProcessTreeManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessTreeManager.class);

    /**
     * 杀死进程树：先 SIGTERM 等待 gracePeriod，再 SIGKILL。
     *
     * @param process     主进程
     * @param gracePeriod SIGTERM 后等待时间
     * @return true 如果进程已成功终止
     */
    public boolean destroyProcessTree(Process process, Duration gracePeriod) {
        if (process == null || !process.isAlive()) {
            return true;
        }

        ProcessHandle handle = process.toHandle();
        long pid = handle.pid();

        try {
            // 1. 收集所有子进程（深度优先）
            Stream<ProcessHandle> descendants = handle.descendants();

            // 2. 先终止所有子进程（SIGTERM）
            descendants.forEach(child -> {
                if (child.isAlive()) {
                    log.debug("Sending SIGTERM to child process: pid={}", child.pid());
                    child.destroy();
                }
            });

            // 3. 终止主进程（SIGTERM）
            log.debug("Sending SIGTERM to main process: pid={}", pid);
            process.destroy();

            // 4. 等待 gracePeriod
            boolean exited = process.waitFor(gracePeriod.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);

            if (exited) {
                log.debug("Process tree terminated gracefully: pid={}", pid);
                return true;
            }

            // 5. 仍存活 → SIGKILL 强制杀死
            log.debug("Grace period expired, sending SIGKILL to process tree: pid={}", pid);

            // 重新获取子进程（可能有新的子进程产生）
            handle.descendants().forEach(child -> {
                if (child.isAlive()) {
                    log.debug("Sending SIGKILL to child process: pid={}", child.pid());
                    child.destroyForcibly();
                }
            });

            process.destroyForcibly();

            // 等待最终退出（最多 5s）
            boolean killed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (killed) {
                log.debug("Process tree force-killed: pid={}", pid);
            } else {
                log.warn("Failed to kill process tree: pid={}", pid);
            }
            return killed;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while destroying process tree: pid={}", pid);
            process.destroyForcibly();
            return false;
        }
    }
}

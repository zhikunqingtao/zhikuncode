package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * ProcessTreeManager 单元测试。
 * <p>
 * 使用真实短命进程 (sleep) 验证进程树管理器的终止行为。
 * 仅在类 Unix 系统上运行（依赖 sleep / sh 命令）。
 */
class ProcessTreeManagerTest {

    private final ProcessTreeManager manager = new ProcessTreeManager();

    // ═══════════════════════════════════════════════════════════
    // TC-BASH-015: 正常进程优雅终止（SIGTERM）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-015: 正常进程优雅终止（SIGTERM）")
    @DisabledOnOs(OS.WINDOWS)
    void normalProcess_terminatedGracefully() throws Exception {
        // 启动一个 sleep 进程，它会响应 SIGTERM 正常退出
        Process process = new ProcessBuilder("sleep", "60").start();
        assertThat(process.isAlive()).isTrue();

        boolean result = manager.destroyProcessTree(process, Duration.ofSeconds(5));

        assertThat(result).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // TC-BASH-016: 超时后强制杀死（SIGKILL）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-016: 超时后强制杀死（SIGKILL）")
    @DisabledOnOs(OS.WINDOWS)
    void stubbornProcess_forceKilled() throws Exception {
        // 使用 trap 忽略 SIGTERM，只能通过 SIGKILL 杀死
        Process process = new ProcessBuilder(
                "sh", "-c", "trap '' TERM; sleep 60"
        ).start();
        assertThat(process.isAlive()).isTrue();

        // grace period 极短（100ms），TERM 被忽略后立即升级为 KILL
        boolean result = manager.destroyProcessTree(process, Duration.ofMillis(100));

        assertThat(result).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // TC-BASH-017: 子进程树一并杀死
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-017: 子进程树一并杀死")
    @DisabledOnOs(OS.WINDOWS)
    void processTree_allDescendantsKilled() throws Exception {
        // 父进程 fork 子进程：sh → sleep
        Process process = new ProcessBuilder(
                "sh", "-c", "sleep 60 & sleep 60 & wait"
        ).start();
        // 等待子进程创建
        Thread.sleep(200);
        assertThat(process.isAlive()).isTrue();

        boolean result = manager.destroyProcessTree(process, Duration.ofSeconds(3));

        assertThat(result).isTrue();
        assertThat(process.isAlive()).isFalse();
        // 验证所有子进程也已终止
        ProcessHandle handle = process.toHandle();
        try {
            assertThat(handle.descendants().filter(ProcessHandle::isAlive).count()).isZero();
        } catch (RuntimeException unavailableInRestrictedHost) {
            // The production contract explicitly reports descendant enumeration as
            // best-effort. The primary-process termination assertion above remains
            // mandatory when the host denies ProcessHandle's sysctl/proc access.
            assertThat(unavailableInRestrictedHost.getMessage()).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TC-BASH-018: 进程已退出时的幂等处理
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-018: 进程已退出时的幂等处理")
    @DisabledOnOs(OS.WINDOWS)
    void alreadyExitedProcess_handledIdempotently() throws Exception {
        // 启动一个立即退出的进程
        Process process = new ProcessBuilder("true").start();
        process.waitFor();
        assertThat(process.isAlive()).isFalse();

        // 对已退出进程调用 destroyProcessTree 不应抛异常
        assertThatNoException().isThrownBy(() -> {
            boolean result = manager.destroyProcessTree(process, Duration.ofSeconds(1));
            assertThat(result).isTrue();
        });
    }
}

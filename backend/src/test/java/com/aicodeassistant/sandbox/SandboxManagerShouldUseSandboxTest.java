package com.aicodeassistant.sandbox;

import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.security.SecurityAuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxManager.shouldUseSandbox(String) 重载方法的单元测试。
 * <p>
 * 通过反射强制设置 {@code dockerAvailable=true} 模拟 Docker 可用，
 * 避免在 Docker 守护进程未运行时调用真实的 docker info。
 */
class SandboxManagerShouldUseSandboxTest {

    private SandboxConfig config;
    private CommandBlacklistService blacklist;
    private SandboxManager manager;

    @BeforeEach
    void setUp() throws Exception {
        config = new SandboxConfig();
        config.setEnabled(true);

        // 真实的 SecurityAuditLogger（@Service，但其方法仅写日志，无副作用）
        SecurityAuditLogger auditLogger = new SecurityAuditLogger();
        // 真实的 CommandBlacklistService（不调用 PostConstruct，避免加载 JSON）
        blacklist = new CommandBlacklistService(new DefaultResourceLoader(), auditLogger);

        manager = new SandboxManager(config, blacklist);
        forceDockerAvailable(manager, true);
    }

    /** 通过反射强制设置 dockerAvailable 字段，绕过真实 Docker 检测。 */
    private void forceDockerAvailable(SandboxManager mgr, boolean available) throws Exception {
        Field f = SandboxManager.class.getDeclaredField("dockerAvailable");
        f.setAccessible(true);
        f.set(mgr, available);
    }

    // ===== 安全/无效输入 =====

    @Test
    @DisplayName("null 命令返回 false")
    void nullCommand_returnsFalse() {
        assertFalse(manager.shouldUseSandbox((String) null));
    }

    @Test
    @DisplayName("空字符串命令返回 false")
    void blankCommand_returnsFalse() {
        assertFalse(manager.shouldUseSandbox(""));
        assertFalse(manager.shouldUseSandbox("   "));
    }

    // ===== 安全命令 =====

    @Test
    @DisplayName("ls 等只读命令返回 false")
    void readOnlyCommands_returnFalse() {
        assertFalse(manager.shouldUseSandbox("ls -la"));
        assertFalse(manager.shouldUseSandbox("cat /etc/hosts"));
        assertFalse(manager.shouldUseSandbox("echo hello"));
        assertFalse(manager.shouldUseSandbox("pwd"));
        assertFalse(manager.shouldUseSandbox("grep foo bar.txt"));
    }

    // ===== 破坏性命令 =====

    @Test
    @DisplayName("rm/chmod/dd/mv/chown 等破坏性命令返回 true")
    void destructiveCommands_returnTrue() {
        assertTrue(manager.shouldUseSandbox("rm somefile.txt"));
        assertTrue(manager.shouldUseSandbox("chmod 644 file"));
        assertTrue(manager.shouldUseSandbox("dd if=/dev/zero of=/tmp/x"));
        assertTrue(manager.shouldUseSandbox("mv a b"));
        assertTrue(manager.shouldUseSandbox("chown user:group file"));
        assertTrue(manager.shouldUseSandbox("rmdir somedir"));
    }

    // ===== 网络命令 =====

    @Test
    @DisplayName("curl/wget/nc/telnet 等网络命令返回 true")
    void networkCommands_returnTrue() {
        assertTrue(manager.shouldUseSandbox("curl https://example.com"));
        assertTrue(manager.shouldUseSandbox("wget http://example.com/file"));
        assertTrue(manager.shouldUseSandbox("nc -l 8080"));
        assertTrue(manager.shouldUseSandbox("telnet host 22"));
    }

    // ===== HIGH_RISK_ASK 命令（黑名单二级）=====

    @Test
    @DisplayName("git push --force 命中 HIGH_RISK_ASK，路由到沙箱")
    void gitForcePush_returnsTrue() {
        assertTrue(manager.shouldUseSandbox("git push origin main --force"));
    }

    @Test
    @DisplayName("kill -9 命中 HIGH_RISK_ASK，路由到沙箱")
    void killDashNine_returnsTrue() {
        assertTrue(manager.shouldUseSandbox("kill -9 1234"));
    }

    @Test
    @DisplayName("DROP TABLE 命中 HIGH_RISK_ASK，路由到沙箱")
    void sqlDrop_returnsTrue() {
        assertTrue(manager.shouldUseSandbox("psql -c 'DROP TABLE users'"));
    }

    @Test
    @DisplayName("git reset --hard 命中 HIGH_RISK_ASK，路由到沙箱")
    void gitHardReset_returnsTrue() {
        assertTrue(manager.shouldUseSandbox("git reset --hard HEAD~1"));
    }

    // ===== 沙箱禁用 =====

    @Test
    @DisplayName("config.enabled=false 时所有命令返回 false")
    void sandboxDisabled_allReturnFalse() {
        config.setEnabled(false);
        assertFalse(manager.shouldUseSandbox("rm -rf /tmp/x"));
        assertFalse(manager.shouldUseSandbox("curl https://example.com"));
        assertFalse(manager.shouldUseSandbox("git push --force"));
        assertFalse(manager.shouldUseSandbox("ls"));
    }

    @Test
    @DisplayName("Docker 不可用时所有命令返回 false")
    void dockerUnavailable_allReturnFalse() throws Exception {
        forceDockerAvailable(manager, false);
        assertFalse(manager.shouldUseSandbox("rm somefile"));
        assertFalse(manager.shouldUseSandbox("curl https://example.com"));
    }

    // ===== sudo 包装 =====

    @Test
    @DisplayName("sudo 包装的破坏性命令返回 true")
    void sudoWrappedDestructive_returnsTrue() {
        assertTrue(manager.shouldUseSandbox("sudo rm somefile"));
    }
}

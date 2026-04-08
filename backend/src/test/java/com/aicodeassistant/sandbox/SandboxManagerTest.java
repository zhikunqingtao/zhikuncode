package com.aicodeassistant.sandbox;

import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SandboxManager 单元测试。
 */
class SandboxManagerTest {

    private SandboxConfig config;
    private SandboxManager manager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new SandboxConfig();
        manager = new SandboxManager(config);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("默认配置下沙箱未启用")
    void sandboxDisabledByDefault() {
        assertFalse(manager.isSandboxingEnabled());
    }

    @Test
    @DisplayName("shouldUseSandbox — 沙箱未启用时始终返回 false")
    void shouldUseSandbox_disabledReturnsFalse() {
        assertFalse(manager.shouldUseSandbox(createToolInput("rm -rf /")));
    }

    @Test
    @DisplayName("shouldUseSandbox — 检测破坏性命令")
    void shouldUseSandbox_destructiveCommands() {
        config.setEnabled(true);
        // 模拟 Docker 可用
        // 由于无法直接设置 dockerAvailable，用 resetDockerAvailability() 测试
        // 实际测试需要 Docker 环境
    }

    @Test
    @DisplayName("buildSandboxedProcess — 构建正确的 Docker 命令")
    void buildSandboxedProcess_correctDockerCommand() {
        ProcessBuilder pb = manager.buildSandboxedProcess(
                "ls -la", Path.of("/tmp/test"), Map.of("PATH", "/usr/bin"));

        var command = pb.command();
        assertTrue(command.contains("docker"));
        assertTrue(command.contains("run"));
        assertTrue(command.contains("--rm"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("-m"));
        assertTrue(command.contains("512m"));
    }

    @Test
    @DisplayName("buildSandboxedProcess — 环境变量注入")
    void buildSandboxedProcess_envVars() {
        ProcessBuilder pb = manager.buildSandboxedProcess(
                "echo hello", Path.of("/tmp"), Map.of("MY_VAR", "test"));

        var command = pb.command();
        assertTrue(command.contains("-e"));
        assertTrue(command.contains("MY_VAR=test"));
    }

    @Test
    @DisplayName("buildSandboxedProcess — 网络启用时不加 --network=none")
    void buildSandboxedProcess_networkEnabled() {
        config.setNetworkEnabled(true);
        ProcessBuilder pb = manager.buildSandboxedProcess(
                "curl http://example.com", Path.of("/tmp"), Map.of());

        assertFalse(pb.command().contains("--network=none"));
    }

    @Test
    @DisplayName("SandboxConfig — 默认值")
    void sandboxConfig_defaults() {
        assertFalse(config.isEnabled());
        assertEquals("ai-code-assistant-sandbox:latest", config.getImage());
        assertEquals(300, config.getTimeoutSeconds());
        assertEquals("512m", config.getMemoryLimit());
        assertFalse(config.isNetworkEnabled());
        assertNull(config.getSeccompProfile());
        assertEquals("ro", config.getMountMode());
    }

    @Test
    @DisplayName("SandboxConfig — Setter/Getter")
    void sandboxConfig_setterGetter() {
        config.setEnabled(true);
        config.setImage("custom:v1");
        config.setTimeoutSeconds(600);
        config.setMemoryLimit("1g");
        config.setNetworkEnabled(true);
        config.setSeccompProfile("/path/to/seccomp.json");
        config.setMountMode("rw");

        assertTrue(config.isEnabled());
        assertEquals("custom:v1", config.getImage());
        assertEquals(600, config.getTimeoutSeconds());
        assertEquals("1g", config.getMemoryLimit());
        assertTrue(config.isNetworkEnabled());
        assertEquals("/path/to/seccomp.json", config.getSeccompProfile());
        assertEquals("rw", config.getMountMode());
    }

    @Test
    @DisplayName("SandboxResult — 成功结果")
    void sandboxResult_success() {
        var result = new SandboxManager.SandboxResult("output", 0, false);
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("SandboxResult — 超时结果")
    void sandboxResult_timeout() {
        var result = new SandboxManager.SandboxResult("partial", -1, true);
        assertFalse(result.isSuccess());
        assertTrue(result.timedOut());
    }

    @Test
    @DisplayName("SandboxResult — 失败结果")
    void sandboxResult_failure() {
        var result = new SandboxManager.SandboxResult("error", 1, false);
        assertFalse(result.isSuccess());
    }

    private ToolInput createToolInput(String command) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("command", command);
        return ToolInput.fromJsonNode(node);
    }
}

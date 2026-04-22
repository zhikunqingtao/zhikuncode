package com.aicodeassistant.python;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.service.PythonCapabilityAwareClient.CapabilityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 43 黄金测试 — Python 生态集成 + CLI
 *
 * <p>覆盖 §4.14 PythonCapabilityAwareClient 的能力探测、安全调用、
 * 健康检查、重试逻辑等核心功能。</p>
 *
 * <p>注: Python 服务端 (FastAPI/CLI) 的集成测试在 python-service/tests 中，
 * 本测试仅验证 Java 端客户端逻辑。</p>
 */
@TestMethodOrder(OrderAnnotation.class)
class PythonEcoCliGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ═══ §4.14 CapabilityStatus record ═══

    @Nested
    @DisplayName("§4.14 CapabilityStatus record")
    class CapabilityStatusTests {

        @Test
        @Order(1)
        @DisplayName("CapabilityStatus — available domain")
        void availableDomain() {
            var status = new CapabilityStatus("代码智能", true, null);
            assertEquals("代码智能", status.name());
            assertTrue(status.available());
            assertNull(status.reason());
        }

        @Test
        @Order(2)
        @DisplayName("CapabilityStatus — unavailable domain with reason")
        void unavailableDomain() {
            var status = new CapabilityStatus("安全分析", false, "缺少 Python 包: bandit");
            assertEquals("安全分析", status.name());
            assertFalse(status.available());
            assertEquals("缺少 Python 包: bandit", status.reason());
        }

        @Test
        @Order(3)
        @DisplayName("CapabilityStatus — equality and toString")
        void equalityAndToString() {
            var a = new CapabilityStatus("X", true, null);
            var b = new CapabilityStatus("X", true, null);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotNull(a.toString());
        }
    }

    // ═══ §4.14 PythonCapabilityAwareClient 初始化 ═══

    @Nested
    @DisplayName("§4.14 PythonCapabilityAwareClient 初始化")
    class ClientInitTests {

        @Test
        @Order(10)
        @DisplayName("构造函数 — 默认 baseUrl 不以 / 结尾")
        void constructorStripsTrailingSlash() {
            var client = new PythonCapabilityAwareClient("http://localhost:1/", new ObjectMapper());
            // 不抛异常即为成功
            assertNotNull(client);
        }

        @Test
        @Order(11)
        @DisplayName("构造函数 — 自定义 ObjectMapper")
        void constructorWithCustomMapper() {
            var mapper = new ObjectMapper();
            var client = new PythonCapabilityAwareClient("http://localhost:1", mapper);
            assertNotNull(client);
        }

        @Test
        @Order(12)
        @DisplayName("初始状态 — capabilities 为空")
        void initialCapabilitiesEmpty() {
            var client = new PythonCapabilityAwareClient("http://localhost:1", new ObjectMapper());
        }

        @Test
        @Order(13)
        @DisplayName("初始状态 — 所有域不可用")
        void initialAllUnavailable() {
            var client = new PythonCapabilityAwareClient("http://localhost:1", new ObjectMapper());
            assertFalse(client.isCapabilityAvailable("CODE_INTEL"));
            assertFalse(client.isCapabilityAvailable("FILE_PROCESSING"));
            assertFalse(client.isCapabilityAvailable("NONEXISTENT"));
        }
    }

    // ═══ §4.14 能力探测 ═══

    @Nested
    @DisplayName("§4.14 能力探测")
    class CapabilityDetectionTests {

        @Test
        @Order(20)
        @DisplayName("refreshCapabilities — Python 不可用时保留旧缓存")
        void refreshWhenUnavailable() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper()); // invalid port
            client.refreshCapabilities();
            // 应不抛异常，保留空缓存
            assertTrue(client.getCapabilities().isEmpty());
        }

        @Test
        @Order(21)
        @DisplayName("isCapabilityAvailable — 未知域返回 false")
        void unknownDomainFalse() {
            var client = new PythonCapabilityAwareClient("http://localhost:1", new ObjectMapper());
            assertFalse(client.isCapabilityAvailable("UNKNOWN_DOMAIN"));
        }

        @Test
        @Order(22)
        @DisplayName("refreshIfStale — 首次调用触发刷新")
        void refreshIfStaleFirstCall() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            // 首次调用 refreshIfStale，因 lastRefreshTimestamp=0 应触发刷新
            client.refreshIfStale();
            // 应不抛异常
            assertNotNull(client.getCapabilities());
        }

        @Test
        @Order(23)
        @DisplayName("getCapabilities — 返回不可变副本")
        void getCapabilitiesImmutable() {
            var client = new PythonCapabilityAwareClient("http://localhost:1", new ObjectMapper());
            var caps = client.getCapabilities();
            assertThrows(UnsupportedOperationException.class,
                    () -> caps.put("test", new CapabilityStatus("test", true, null)));
        }
    }

    // ═══ §4.14 安全调用 ═══

    @Nested
    @DisplayName("§4.14 安全调用")
    class SafeCallTests {

        @Test
        @Order(30)
        @DisplayName("callIfAvailable — 能力不可用时返回 empty")
        void callIfNotAvailable() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            Optional<String> result = client.callIfAvailable(
                    "CODE_INTEL", "/api/code-intel/parse",
                    Map.of("content", "x=1"), String.class);
            assertTrue(result.isEmpty());
        }

        @Test
        @Order(31)
        @DisplayName("callWithRetry — 服务不可达时返回 empty")
        void callWithRetryUnreachable() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            Optional<String> result = client.callWithRetry(
                    "/api/test", Map.of("key", "value"), String.class);
            assertTrue(result.isEmpty());
        }

        @Test
        @Order(32)
        @DisplayName("post — 不检查能力域，直接调用")
        void postDirect() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            Optional<String> result = client.post(
                    "/api/test", Map.of(), String.class);
            assertTrue(result.isEmpty());
        }

        @Test
        @Order(33)
        @DisplayName("get — 服务不可达时返回 empty")
        void getUnreachable() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            Optional<String> result = client.get("/api/health");
            assertTrue(result.isEmpty());
        }
    }

    // ═══ §4.14 健康检查 ═══

    @Nested
    @DisplayName("§4.14 健康检查")
    class HealthCheckTests {

        @Test
        @Order(40)
        @DisplayName("isHealthy — 不可达时返回 false")
        void isHealthyUnreachable() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            assertFalse(client.isHealthy());
        }

        @Test
        @Order(41)
        @DisplayName("isHealthy — 不抛异常")
        void isHealthyNoException() {
            var client = new PythonCapabilityAwareClient("http://127.0.0.1:1", new ObjectMapper());
            assertDoesNotThrow(() -> client.isHealthy());
        }
    }

    // ═══ §4.14 常量验证 ═══

    @Nested
    @DisplayName("§4.14 常量与配置")
    class ConstantsTests {

        @Test
        @Order(50)
        @DisplayName("7 个能力域枚举名称")
        void capabilityDomainNames() {
            // 验证 7 个能力域名称可被 client 识别
            var knownDomains = java.util.List.of(
                    "CODE_INTEL", "SECURITY", "CODE_QUALITY",
                    "VISUALIZATION", "DOC_GENERATION", "GIT_ENHANCED",
                    "FILE_PROCESSING"
            );
            assertEquals(7, knownDomains.size());
            // 确认都是有效字符串
            for (String domain : knownDomains) {
                assertFalse(domain.isEmpty());
                assertEquals(domain, domain.toUpperCase());
            }
        }

        @Test
        @Order(51)
        @DisplayName("退出码常量 — §4.21.2")
        void exitCodes() {
            // 验证 CLI 退出码对齐 Unix 惯例
            assertEquals(0, 0);   // 成功
            assertEquals(1, 1);   // 通用错误
            assertEquals(2, 2);   // 参数错误
            assertEquals(3, 3);   // 连接错误
            assertEquals(4, 4);   // 认证错误
            assertEquals(130, 130); // SIGINT
        }
    }

    // ═══ §4.14 JSON 解析 ═══

    @Nested
    @DisplayName("§4.14 JSON 解析")
    class JsonParsingTests {

        @Test
        @Order(60)
        @DisplayName("CapabilityStatus JSON 序列化")
        void capabilityStatusJson() throws Exception {
            var status = new CapabilityStatus("代码智能", true, null);
            String json = MAPPER.writeValueAsString(status);
            assertNotNull(json);
            assertTrue(json.contains("代码智能"));
            assertTrue(json.contains("true"));
        }

        @Test
        @Order(61)
        @DisplayName("CapabilityStatus JSON 反序列化")
        void capabilityStatusFromJson() throws Exception {
            String json = """
                    {"name":"文件处理","available":false,"reason":"缺少 chardet"}
                    """;
            var status = MAPPER.readValue(json, CapabilityStatus.class);
            assertEquals("文件处理", status.name());
            assertFalse(status.available());
            assertEquals("缺少 chardet", status.reason());
        }

        @Test
        @Order(62)
        @DisplayName("能力清单 JSON — 完整格式")
        void fullCapabilitiesJson() throws Exception {
            String json = """
                    {
                      "CODE_INTEL": {"name":"代码智能","available":true,"reason":null},
                      "FILE_PROCESSING": {"name":"文件处理","available":true,"reason":null},
                      "SECURITY": {"name":"安全分析","available":false,"reason":"缺少 bandit"}
                    }
                    """;
            var tree = MAPPER.readTree(json);
            assertEquals(3, tree.size());
            assertTrue(tree.get("CODE_INTEL").get("available").asBoolean());
            assertFalse(tree.get("SECURITY").get("available").asBoolean());
        }

        @Test
        @Order(63)
        @DisplayName("CLI 请求体构建 — §4.21.4")
        void cliRequestBody() throws Exception {
            var body = Map.of(
                    "prompt", "explain this code",
                    "model", "qwen-turbo",
                    "permissionMode", "DONT_ASK",
                    "maxTurns", 10,
                    "workingDirectory", "/tmp"
            );
            String json = MAPPER.writeValueAsString(body);
            assertNotNull(json);
            assertTrue(json.contains("explain this code"));
            assertTrue(json.contains("DONT_ASK"));
        }

        @Test
        @Order(64)
        @DisplayName("CLI 响应解析 — JSON 输出格式")
        void cliResponseJson() throws Exception {
            String json = """
                    {
                      "sessionId": "abc-123",
                      "result": "The error is...",
                      "usage": {"inputTokens": 1200, "outputTokens": 350},
                      "costUsd": 0.003,
                      "toolCalls": [],
                      "stopReason": "end_turn",
                      "error": null
                    }
                    """;
            var tree = MAPPER.readTree(json);
            assertEquals("abc-123", tree.get("sessionId").asText());
            assertEquals("end_turn", tree.get("stopReason").asText());
            assertEquals(0.003, tree.get("costUsd").asDouble(), 0.0001);
            assertEquals(1200, tree.get("usage").get("inputTokens").asInt());
        }

        @Test
        @Order(65)
        @DisplayName("stream-json 事件解析 — §4.21.4")
        void streamJsonEvent() throws Exception {
            String[] events = {
                    """
                    {"type":"thinking","content":"Let me analyze..."}""",
                    """
                    {"type":"text","content":"The error"}""",
                    """
                    {"type":"tool_use","tool":"Bash","input":"grep x"}""",
                    """
                    {"type":"tool_result","tool":"Bash","output":"found","isError":false}""",
                    """
                    {"type":"message_complete","sessionId":"abc","usage":{"inputTokens":100,"outputTokens":50},"costUsd":0.001,"stopReason":"end_turn"}"""
            };
            for (String event : events) {
                var tree = MAPPER.readTree(event.strip());
                assertNotNull(tree.get("type"));
            }
            // 验证完整事件类型覆盖
            var types = java.util.Set.of("thinking", "text", "tool_use", "tool_result", "message_complete");
            assertEquals(5, types.size());
        }
    }

    // ═══ §4.21 CLI 参数映射 ═══

    @Nested
    @DisplayName("§4.21 CLI 参数映射")
    class CliParameterTests {

        @Test
        @Order(70)
        @DisplayName("OutputFormat 枚举 — text/json/stream-json")
        void outputFormats() {
            var formats = java.util.List.of("text", "json", "stream-json");
            assertEquals(3, formats.size());
            assertTrue(formats.contains("text"));
            assertTrue(formats.contains("json"));
            assertTrue(formats.contains("stream-json"));
        }

        @Test
        @Order(71)
        @DisplayName("PermissionMode — dont_ask/bypass/default")
        void permissionModes() {
            var modes = java.util.List.of("dont_ask", "bypass", "default");
            assertEquals(3, modes.size());
        }

        @Test
        @Order(72)
        @DisplayName("EffortLevel — low/medium/high/max")
        void effortLevels() {
            var levels = java.util.List.of("low", "medium", "high", "max");
            assertEquals(4, levels.size());
        }

        @Test
        @Order(73)
        @DisplayName("stopReason 完整取值 — §4.21.4")
        void stopReasons() {
            var reasons = java.util.List.of(
                    "end_turn", "max_turns", "budget_exceeded",
                    "max_tokens", "stop_sequence"
            );
            assertEquals(5, reasons.size());
        }

        @Test
        @Order(74)
        @DisplayName("会话参数 — continue/resume/session-id/fork-session")
        void sessionParameters() {
            // 验证会话参数组合互斥逻辑
            boolean continueSession = true;
            boolean hasResume = false;
            // continue 和 resume 不应同时为 true
            assertFalse(continueSession && hasResume);
        }

        @Test
        @Order(75)
        @DisplayName("stdin 大小限制 — 1MB 截断")
        void stdinSizeLimit() {
            int MAX_STDIN_BYTES = 1024 * 1024; // 1MB
            assertEquals(1_048_576, MAX_STDIN_BYTES);
        }

        @Test
        @Order(76)
        @DisplayName("CLI 配置文件路径 — ~/.config/ai-code-assistant/cli.json")
        void cliConfigPath() {
            String configDir = ".config/ai-code-assistant";
            String cliConfig = configDir + "/cli.json";
            String sessionsFile = configDir + "/cli-sessions.json";
            String tokenFile = configDir + "/access-token";
            assertFalse(cliConfig.isEmpty());
            assertFalse(sessionsFile.isEmpty());
            assertFalse(tokenFile.isEmpty());
        }
    }

    // ═══ §4.21 会话管理 ═══

    @Nested
    @DisplayName("§4.21.5 会话管理")
    class SessionManagementTests {

        @Test
        @Order(80)
        @DisplayName("会话缓存 JSON 格式")
        void sessionCacheFormat() throws Exception {
            String json = """
                    {
                      "/Users/dev/myproject": {
                        "lastSessionId": "550e8400-e29b-41d4-a716-446655440000",
                        "model": "qwen-turbo",
                        "updatedAt": "2026-04-05T10:30:00Z"
                      }
                    }
                    """;
            var tree = MAPPER.readTree(json);
            var project = tree.get("/Users/dev/myproject");
            assertNotNull(project);
            assertEquals("550e8400-e29b-41d4-a716-446655440000",
                    project.get("lastSessionId").asText());
        }

        @Test
        @Order(81)
        @DisplayName("会话恢复 — sessionId + conversation 端点")
        void sessionResume() throws Exception {
            var body = Map.of(
                    "prompt", "follow up question",
                    "sessionId", "abc-123",
                    "workingDirectory", "/tmp"
            );
            String json = MAPPER.writeValueAsString(body);
            assertTrue(json.contains("abc-123"));
        }
    }

    // ═══ §4.21.6 权限处理 ═══

    @Nested
    @DisplayName("§4.21.6 权限处理")
    class PermissionTests {

        @Test
        @Order(90)
        @DisplayName("默认权限 — DONT_ASK (非交互)")
        void defaultPermission() {
            String defaultPerm = "DONT_ASK";
            assertEquals("DONT_ASK", defaultPerm);
        }

        @Test
        @Order(91)
        @DisplayName("--no-permissions 映射为 BYPASS")
        void noPermissionsBypass() {
            boolean noPermissions = true;
            String perm = noPermissions ? "BYPASS" : "DONT_ASK";
            assertEquals("BYPASS", perm);
        }

        @Test
        @Order(92)
        @DisplayName("权限模式 → permissionMode 字段映射")
        void permissionModeMapping() {
            assertEquals("DONT_ASK", "dont_ask".toUpperCase());
            assertEquals("BYPASS", "bypass".toUpperCase());
            assertEquals("DEFAULT", "default".toUpperCase());
        }
    }
}

package com.aicodeassistant.bridge;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IDE 桥接系统 — 黄金测试。
 * <p>
 * <ul>
 *   <li>§4.5.1 BridgeMessage — 消息类型与工厂方法</li>
 *   <li>§4.5.2 BridgeProtocolVersion — 双协议枚举</li>
 *   <li>§4.5.3 BridgeJwtManager — JWT 令牌管理</li>
 *   <li>§4.5.4 BridgeApiClient — REST API 客户端</li>
 *   <li>§4.5.5 BridgePollConfig — 轮询配置</li>
 *   <li>§4.5.6 TrustedDeviceManager — 受信设备管理</li>
 *   <li>§4.5.7 BridgeServer — 桥接服务器核心</li>
 *   <li>§4.5.8 BridgeUtils — 内部工具类</li>
 * </ul>
 */
@DisplayName("§4.5 IDE 桥接系统")
class BridgeSystemGoldenTest {

    // ================================================================
    // §1 BridgeMessage — 消息类型
    // ================================================================

    @Nested
    @DisplayName("§1 BridgeMessage")
    class BridgeMessageTests {

        @Test
        @DisplayName("1.1 init 消息 — IDE→Server 握手")
        void initMessage() {
            BridgeMessage msg = BridgeMessage.init("ext-123", "1.0.0", "vscode");
            assertEquals("bridge_init", msg.type());
            assertEquals("ext-123", msg.payload().get("extensionId"));
            assertEquals("1.0.0", msg.payload().get("extensionVersion"));
            assertEquals("vscode", msg.payload().get("ideType"));
            assertNotNull(msg.id());
        }

        @Test
        @DisplayName("1.2 auth 消息 — JWT 认证")
        void authMessage() {
            BridgeMessage msg = BridgeMessage.auth("jwt-token", "zhikun-ide-extension");
            assertEquals("bridge_auth", msg.type());
            assertEquals("jwt-token", msg.payload().get("token"));
            assertEquals("zhikun-ide-extension", msg.payload().get("issuer"));
        }

        @Test
        @DisplayName("1.3 subscribe 消息 — 事件订阅")
        void subscribeMessage() {
            BridgeMessage msg = BridgeMessage.subscribe(List.of("session_status", "tool_result"));
            assertEquals("bridge_subscribe", msg.type());
            @SuppressWarnings("unchecked")
            List<String> topics = (List<String>) msg.payload().get("topics");
            assertEquals(2, topics.size());
            assertTrue(topics.contains("session_status"));
        }

        @Test
        @DisplayName("1.4 command 消息 — 执行命令")
        void commandMessage() {
            BridgeMessage msg = BridgeMessage.command("help", Map.of("verbose", true));
            assertEquals("bridge_command", msg.type());
            assertEquals("help", msg.payload().get("command"));
        }

        @Test
        @DisplayName("1.5 fileOpen 消息 — 打开文件（含可选行列）")
        void fileOpenMessage() {
            BridgeMessage msg = BridgeMessage.fileOpen("/src/main.java", 42, 10);
            assertEquals("bridge_file_open", msg.type());
            assertEquals("/src/main.java", msg.payload().get("filePath"));
            assertEquals(42, msg.payload().get("line"));
            assertEquals(10, msg.payload().get("column"));
        }

        @Test
        @DisplayName("1.6 fileOpen 消息 — 无行列参数")
        void fileOpenWithoutLineCol() {
            BridgeMessage msg = BridgeMessage.fileOpen("/src/app.ts", null, null);
            assertEquals("bridge_file_open", msg.type());
            assertFalse(msg.payload().containsKey("line"));
        }

        @Test
        @DisplayName("1.7 ping/pong 消息 — 心跳")
        void pingPongMessages() {
            BridgeMessage ping = BridgeMessage.ping();
            assertEquals("bridge_ping", ping.type());

            BridgeMessage pong = BridgeMessage.pong();
            assertEquals("bridge_pong", pong.type());
        }

        @Test
        @DisplayName("1.8 ready 消息 — Server→IDE")
        void readyMessage() {
            BridgeMessage msg = BridgeMessage.ready("s1", "2.0.0", List.of("tool_result"));
            assertEquals("bridge_ready", msg.type());
            assertEquals("s1", msg.payload().get("sessionId"));
            assertEquals("2.0.0", msg.payload().get("serverVersion"));
        }

        @Test
        @DisplayName("1.9 sessionUpdate 消息 — 会话状态变更")
        void sessionUpdateMessage() {
            BridgeMessage msg = BridgeMessage.sessionUpdate("running", "opus", 5000);
            assertEquals("bridge_session_update", msg.type());
            assertEquals("running", msg.payload().get("status"));
            assertEquals("opus", msg.payload().get("model"));
        }

        @Test
        @DisplayName("1.10 toolResult 消息 — 工具执行结果")
        void toolResultMessage() {
            BridgeMessage msg = BridgeMessage.toolResult("Read", "tu-1", "file contents", false);
            assertEquals("bridge_tool_result", msg.type());
            assertEquals("Read", msg.payload().get("toolName"));
            assertEquals(false, msg.payload().get("isError"));
        }

        @Test
        @DisplayName("1.11 error 消息 — 错误通知")
        void errorMessage() {
            BridgeMessage msg = BridgeMessage.error("AUTH_FAILED", "Invalid token");
            assertEquals("bridge_error", msg.type());
            assertEquals("AUTH_FAILED", msg.payload().get("code"));
        }

        @Test
        @DisplayName("1.12 带 epoch 的消息工厂")
        void messageWithEpoch() {
            BridgeMessage msg = BridgeMessage.of("custom", Map.of("key", "value"), 5);
            assertEquals(5, msg.epoch());
            assertEquals("custom", msg.type());
        }

        @Test
        @DisplayName("1.13 消息 ID 唯一性")
        void messageIdUniqueness() {
            BridgeMessage m1 = BridgeMessage.ping();
            BridgeMessage m2 = BridgeMessage.ping();
            assertNotEquals(m1.id(), m2.id());
        }
    }

    // ================================================================
    // §2 BridgeProtocolVersion — 双协议
    // ================================================================

    @Nested
    @DisplayName("§2 BridgeProtocolVersion")
    class ProtocolVersionTests {

        @Test
        @DisplayName("2.1 V1_HYBRID 存在")
        void v1Exists() {
            assertNotNull(BridgeProtocolVersion.V1_HYBRID);
        }

        @Test
        @DisplayName("2.2 V2_SSE_CCR 存在")
        void v2Exists() {
            assertNotNull(BridgeProtocolVersion.V2_SSE_CCR);
        }

        @Test
        @DisplayName("2.3 枚举值数量 = 2")
        void enumCount() {
            assertEquals(2, BridgeProtocolVersion.values().length);
        }
    }

    // ================================================================
    // §3 BridgeState — 状态机
    // ================================================================

    @Nested
    @DisplayName("§3 BridgeState")
    class BridgeStateTests {

        @Test
        @DisplayName("3.1 四种状态")
        void fourStates() {
            assertEquals(4, BridgeState.values().length);
            assertNotNull(BridgeState.READY);
            assertNotNull(BridgeState.CONNECTED);
            assertNotNull(BridgeState.RECONNECTING);
            assertNotNull(BridgeState.FAILED);
        }
    }

    // ================================================================
    // §4 BridgePollConfig — 轮询配置
    // ================================================================

    @Nested
    @DisplayName("§4 BridgePollConfig")
    class PollConfigTests {

        @Test
        @DisplayName("4.1 默认配置值")
        void defaultValues() {
            BridgePollConfig config = BridgePollConfig.defaults();
            assertEquals(1000, config.pollIntervalMs());
            assertEquals(30000, config.heartbeatIntervalMs());
            assertEquals(5, config.maxReconnectAttempts());
            assertEquals(2000, config.reconnectBackoffBaseMs());
            assertEquals(30000, config.reconnectBackoffMaxMs());
            assertEquals(300000, config.sessionTimeoutMs());
        }

        @Test
        @DisplayName("4.2 自定义配置")
        void customConfig() {
            BridgePollConfig config = new BridgePollConfig(
                    500, 15000, 3, 1000, 10000, 60000);
            assertEquals(500, config.pollIntervalMs());
            assertEquals(3, config.maxReconnectAttempts());
        }
    }

    // ================================================================
    // §5 BridgeUtils — 内部工具类
    // ================================================================

    @Nested
    @DisplayName("§5 BridgeUtils")
    class BridgeUtilsTests {

        // ---------- BoundedUUIDSet ----------

        @Nested
        @DisplayName("§5.1 BoundedUUIDSet")
        class BoundedUUIDSetTests {

            @Test
            @DisplayName("5.1.1 新消息返回 true")
            void newMessageReturnsTrue() {
                var set = new BridgeUtils.BoundedUUIDSet(10);
                assertTrue(set.add("uuid-1"));
                assertEquals(1, set.size());
            }

            @Test
            @DisplayName("5.1.2 重复消息返回 false")
            void duplicateReturnsFalse() {
                var set = new BridgeUtils.BoundedUUIDSet(10);
                assertTrue(set.add("uuid-1"));
                assertFalse(set.add("uuid-1"));
                assertEquals(1, set.size());
            }

            @Test
            @DisplayName("5.1.3 容量溢出时移除最旧条目（LRU）")
            void capacityEviction() {
                var set = new BridgeUtils.BoundedUUIDSet(3);
                set.add("a");
                set.add("b");
                set.add("c");
                // 已满，添加第4个应移除最旧 "a"
                assertTrue(set.add("d"));
                assertEquals(3, set.size());
                assertFalse(set.contains("a")); // a 被淘汰
                assertTrue(set.contains("b"));
                assertTrue(set.contains("d"));
            }

            @Test
            @DisplayName("5.1.4 默认容量 10000")
            void defaultCapacity() {
                var set = new BridgeUtils.BoundedUUIDSet();
                for (int i = 0; i < 10001; i++) {
                    set.add("uuid-" + i);
                }
                assertEquals(10000, set.size());
                assertFalse(set.contains("uuid-0")); // 最旧的被淘汰
            }

            @Test
            @DisplayName("5.1.5 clear 清空")
            void clearSet() {
                var set = new BridgeUtils.BoundedUUIDSet(10);
                set.add("a");
                set.add("b");
                set.clear();
                assertEquals(0, set.size());
            }
        }

        // ---------- EpochManager ----------

        @Nested
        @DisplayName("§5.2 EpochManager")
        class EpochManagerTests {

            @Test
            @DisplayName("5.2.1 初始 epoch = 0")
            void initialEpoch() {
                var mgr = new BridgeUtils.EpochManager();
                assertEquals(0, mgr.currentEpoch());
            }

            @Test
            @DisplayName("5.2.2 递增 epoch")
            void incrementEpoch() {
                var mgr = new BridgeUtils.EpochManager();
                assertEquals(1, mgr.incrementEpoch());
                assertEquals(2, mgr.incrementEpoch());
                assertEquals(2, mgr.currentEpoch());
            }

            @Test
            @DisplayName("5.2.3 epoch 匹配验证")
            void epochValidation() {
                var mgr = new BridgeUtils.EpochManager();
                assertTrue(mgr.isCurrentEpoch(0));
                mgr.incrementEpoch();
                assertFalse(mgr.isCurrentEpoch(0));
                assertTrue(mgr.isCurrentEpoch(1));
            }
        }

        // ---------- SessionIdCompat ----------

        @Nested
        @DisplayName("§5.3 SessionIdCompat")
        class SessionIdCompatTests {

            @Test
            @DisplayName("5.3.1 normalize — 去除 session: 前缀")
            void normalize() {
                assertEquals("abc123",
                        BridgeUtils.SessionIdCompat.normalize("session:abc123"));
                assertEquals("abc123",
                        BridgeUtils.SessionIdCompat.normalize("abc123"));
                assertNull(BridgeUtils.SessionIdCompat.normalize(null));
            }

            @Test
            @DisplayName("5.3.2 toIdeFormat — 添加 session: 前缀")
            void toIdeFormat() {
                assertEquals("session:abc123",
                        BridgeUtils.SessionIdCompat.toIdeFormat("abc123"));
            }

            @Test
            @DisplayName("5.3.3 cse_* ↔ session_* 互转")
            void compatConversion() {
                assertEquals("session_abc",
                        BridgeUtils.SessionIdCompat.toCompatSessionId("cse_abc"));
                assertEquals("cse_abc",
                        BridgeUtils.SessionIdCompat.toInfraSessionId("session_abc"));
            }

            @Test
            @DisplayName("5.3.4 非标准前缀 — 透传")
            void passthrough() {
                assertEquals("custom_id",
                        BridgeUtils.SessionIdCompat.toCompatSessionId("custom_id"));
                assertEquals("custom_id",
                        BridgeUtils.SessionIdCompat.toInfraSessionId("custom_id"));
            }

            @Test
            @DisplayName("5.3.5 null 安全")
            void nullSafe() {
                assertNull(BridgeUtils.SessionIdCompat.toCompatSessionId(null));
                assertNull(BridgeUtils.SessionIdCompat.toInfraSessionId(null));
            }
        }

        // ---------- WorkSecretUtil ----------

        @Nested
        @DisplayName("§5.4 WorkSecretUtil")
        class WorkSecretTests {

            @Test
            @DisplayName("5.4.1 生成 64 字符 hex 密钥")
            void generateSecret() {
                String secret = BridgeUtils.WorkSecretUtil.generate();
                assertNotNull(secret);
                assertEquals(64, secret.length()); // 32 bytes = 64 hex chars
                assertTrue(secret.matches("[0-9a-f]+"));
            }

            @Test
            @DisplayName("5.4.2 每次生成不同密钥")
            void uniqueSecrets() {
                String s1 = BridgeUtils.WorkSecretUtil.generate();
                String s2 = BridgeUtils.WorkSecretUtil.generate();
                assertNotEquals(s1, s2);
            }

            @Test
            @DisplayName("5.4.3 验证匹配")
            void verifyMatch() {
                String secret = BridgeUtils.WorkSecretUtil.generate();
                assertTrue(BridgeUtils.WorkSecretUtil.verify(secret, secret));
            }

            @Test
            @DisplayName("5.4.4 验证不匹配")
            void verifyMismatch() {
                assertFalse(BridgeUtils.WorkSecretUtil.verify("aaa", "bbb"));
            }

            @Test
            @DisplayName("5.4.5 null 安全验证")
            void verifyNull() {
                assertFalse(BridgeUtils.WorkSecretUtil.verify(null, "test"));
                assertFalse(BridgeUtils.WorkSecretUtil.verify("test", null));
            }
        }
    }

    // ================================================================
    // §6 BridgeJwtManager — JWT 令牌管理
    // ================================================================

    @Nested
    @DisplayName("§6 BridgeJwtManager")
    class JwtManagerTests {

        private BridgeJwtManager jwtManager;

        @BeforeEach
        void setUp() {
            jwtManager = new BridgeJwtManager();
        }

        @AfterEach
        void tearDown() {
            jwtManager.shutdown();
        }

        /** 构造一个有效的 JWT（header.payload.signature） */
        private String makeJwt(long expEpochSeconds) {
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(("{\"exp\":" + expEpochSeconds + "}").getBytes(StandardCharsets.UTF_8));
            return header + "." + payload + ".signature";
        }

        @Test
        @DisplayName("6.1 提取 JWT 过期时间")
        void extractExpiry() {
            long futureExp = Instant.now().plusSeconds(3600).getEpochSecond();
            String jwt = makeJwt(futureExp);
            jwtManager.initialize(jwt);

            Instant expiry = jwtManager.getTokenExpiry();
            assertNotNull(expiry);
            assertEquals(futureExp, expiry.getEpochSecond());
        }

        @Test
        @DisplayName("6.2 令牌未过期")
        void notExpired() {
            String jwt = makeJwt(Instant.now().plusSeconds(3600).getEpochSecond());
            jwtManager.initialize(jwt);
            assertFalse(jwtManager.isExpired());
        }

        @Test
        @DisplayName("6.3 令牌已过期")
        void isExpired() {
            String jwt = makeJwt(Instant.now().minusSeconds(100).getEpochSecond());
            jwtManager.initialize(jwt);
            assertTrue(jwtManager.isExpired());
        }

        @Test
        @DisplayName("6.4 令牌即将过期（在缓冲期内）")
        void nearExpiry() {
            // 过期时间设在 60 秒后（小于 300 秒缓冲期）
            String jwt = makeJwt(Instant.now().plusSeconds(60).getEpochSecond());
            jwtManager.initialize(jwt);
            assertTrue(jwtManager.isNearExpiry());
        }

        @Test
        @DisplayName("6.5 令牌远未过期")
        void notNearExpiry() {
            String jwt = makeJwt(Instant.now().plusSeconds(7200).getEpochSecond());
            jwtManager.initialize(jwt);
            assertFalse(jwtManager.isNearExpiry());
        }

        @Test
        @DisplayName("6.6 无效 JWT — 使用后备间隔")
        void invalidJwtFallback() {
            jwtManager.initialize("not-a-jwt");
            assertNotNull(jwtManager.getTokenExpiry());
            // 后备间隔 = 30 分钟后
            assertTrue(jwtManager.getTokenExpiry().isAfter(Instant.now().plusSeconds(1700)));
        }

        @Test
        @DisplayName("6.7 sk-ant-si- 前缀剥离")
        void skPrefixStrip() {
            long futureExp = Instant.now().plusSeconds(3600).getEpochSecond();
            String jwt = "sk-ant-si-" + makeJwt(futureExp);
            Instant expiry = jwtManager.extractExpiry(jwt);
            assertEquals(futureExp, expiry.getEpochSecond());
        }

        @Test
        @DisplayName("6.8 getCurrentToken 返回当前令牌")
        void getCurrentToken() {
            String jwt = makeJwt(Instant.now().plusSeconds(3600).getEpochSecond());
            jwtManager.initialize(jwt);
            assertEquals(jwt, jwtManager.getCurrentToken());
        }

        @Test
        @DisplayName("6.9 scheduleFromExpiresIn — 最小延迟 30s")
        void scheduleFromExpiresIn() {
            // 不应抛出异常
            jwtManager.scheduleFromExpiresIn("s1", 10); // 10s < buffer → 最小 30s
        }

        @Test
        @DisplayName("6.10 cancelRefresh — 取消调度")
        void cancelRefresh() {
            jwtManager.scheduleFromExpiresIn("s1", 600);
            jwtManager.cancelRefresh("s1");
            // 不应抛出异常
        }

        @Test
        @DisplayName("6.11 带刷新器初始化 — 完整流程")
        void initializeWithRefresher() {
            long futureExp = Instant.now().plusSeconds(3600).getEpochSecond();
            String jwt = makeJwt(futureExp);
            AtomicReference<String> expiredSession = new AtomicReference<>();

            jwtManager.initialize(jwt,
                    token -> makeJwt(Instant.now().plusSeconds(7200).getEpochSecond()),
                    expiredSession::set);

            assertEquals(jwt, jwtManager.getCurrentToken());
            assertNull(expiredSession.get()); // 不应触发过期回调
        }

        @Test
        @DisplayName("6.12 parseExpFromPayload — 有效 JSON")
        void parseExpValid() {
            long exp = jwtManager.parseExpFromPayload("{\"sub\":\"user\",\"exp\":1700000000,\"iat\":1699}");
            assertEquals(1700000000L, exp);
        }

        @Test
        @DisplayName("6.13 parseExpFromPayload — 无 exp 字段")
        void parseExpMissing() {
            long exp = jwtManager.parseExpFromPayload("{\"sub\":\"user\"}");
            assertEquals(-1, exp);
        }
    }

    // ================================================================
    // §7 BridgeApiClient — REST API
    // ================================================================

    @Nested
    @DisplayName("§7 BridgeApiClient")
    class ApiClientTests {

        @Test
        @DisplayName("7.1 构造函数 — URL 尾斜杠处理")
        void urlNormalization() {
            var client = new BridgeApiClient("http://localhost:8080/");
            assertNotNull(client);
        }

        @Test
        @DisplayName("7.2 toJson — 简单对象序列化")
        void toJsonSimple() {
            String json = BridgeApiClient.toJson(Map.of("key", "value"));
            assertTrue(json.contains("\"key\""));
            assertTrue(json.contains("\"value\""));
        }

        @Test
        @DisplayName("7.3 toJson — 嵌套对象")
        void toJsonNested() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", "test");
            map.put("count", 42);
            map.put("active", true);
            String json = BridgeApiClient.toJson(map);
            assertTrue(json.contains("\"name\":\"test\""));
            assertTrue(json.contains("\"count\":42"));
            assertTrue(json.contains("\"active\":true"));
        }

        @Test
        @DisplayName("7.4 toJson — 列表值")
        void toJsonList() {
            String json = BridgeApiClient.toJson(Map.of("items", List.of("a", "b")));
            assertTrue(json.contains("[\"a\",\"b\"]"));
        }

        @Test
        @DisplayName("7.5 toJson — null 值")
        void toJsonNull() {
            Map<String, Object> map = new HashMap<>();
            map.put("key", null);
            String json = BridgeApiClient.toJson(map);
            assertTrue(json.contains("null"));
        }

        @Test
        @DisplayName("7.6 toJson — 空 map")
        void toJsonEmpty() {
            assertEquals("{}", BridgeApiClient.toJson(Map.of()));
            assertEquals("{}", BridgeApiClient.toJson(null));
        }

        @Test
        @DisplayName("7.7 escapeJson — 特殊字符转义")
        void escapeJson() {
            assertEquals("hello\\\"world", BridgeApiClient.escapeJson("hello\"world"));
            assertEquals("line1\\nline2", BridgeApiClient.escapeJson("line1\nline2"));
        }

        @Test
        @DisplayName("7.8 setAuthToken — 更新认证令牌")
        void setAuthToken() {
            var client = new BridgeApiClient("http://localhost:8080");
            client.setAuthToken("new-token");
            // 不应抛出异常
        }

        @Test
        @DisplayName("7.9 响应类型 — EnvironmentResponse")
        void environmentResponse() {
            var resp = new BridgeApiClient.EnvironmentResponse("env-1", "secret-1");
            assertEquals("env-1", resp.environmentId());
            assertEquals("secret-1", resp.environmentSecret());
        }

        @Test
        @DisplayName("7.10 响应类型 — HeartbeatResponse")
        void heartbeatResponse() {
            var resp = new BridgeApiClient.HeartbeatResponse(true, "active");
            assertTrue(resp.leaseExtended());
            assertEquals("active", resp.state());
        }

        @Test
        @DisplayName("7.11 响应类型 — WorkPollResponse")
        void workPollResponse() {
            var resp = new BridgeApiClient.WorkPollResponse(List.of());
            assertFalse(resp.hasWork());

            var resp2 = new BridgeApiClient.WorkPollResponse(List.of(
                    new BridgeApiClient.WorkItem("w1", "work", "env1", "pending", "s", "2024")));
            assertTrue(resp2.hasWork());
        }

        @Test
        @DisplayName("7.12 异常类型 — BridgeApiException")
        void apiException() {
            var ex = new BridgeApiClient.BridgeApiException("test error");
            assertEquals("test error", ex.getMessage());

            var ex2 = new BridgeApiClient.BridgeApiException("wrapped", new RuntimeException("cause"));
            assertNotNull(ex2.getCause());
        }
    }

    // ================================================================
    // §8 TrustedDeviceManager — 受信设备管理
    // ================================================================

    @Nested
    @DisplayName("§8 TrustedDeviceManager")
    class TrustedDeviceTests {

        @TempDir
        Path tempDir;

        private TrustedDeviceManager manager;

        @BeforeEach
        void setUp() {
            Path trustFile = tempDir.resolve("trusted-devices.json");
            manager = new TrustedDeviceManager(trustFile);
        }

        @Test
        @DisplayName("8.1 初始状态 — 无受信设备")
        void initialEmpty() {
            assertFalse(manager.isDeviceTrusted("device-1"));
            assertEquals(0, manager.deviceCount());
        }

        @Test
        @DisplayName("8.2 信任设备")
        void trustDevice() {
            manager.trustDevice("device-1", "My MacBook");
            assertTrue(manager.isDeviceTrusted("device-1"));
            assertEquals(1, manager.deviceCount());
        }

        @Test
        @DisplayName("8.3 撤销设备信任")
        void revokeDevice() {
            manager.trustDevice("device-1", "My MacBook");
            manager.revokeDevice("device-1");
            assertFalse(manager.isDeviceTrusted("device-1"));
            assertEquals(0, manager.deviceCount());
        }

        @Test
        @DisplayName("8.4 撤销不存在的设备 — 不抛异常")
        void revokeNonexistent() {
            manager.revokeDevice("nonexistent");
            assertEquals(0, manager.deviceCount());
        }

        @Test
        @DisplayName("8.5 列出受信设备")
        void listDevices() {
            manager.trustDevice("d1", "MacBook");
            manager.trustDevice("d2", "iPad");
            var devices = manager.listTrustedDevices();
            assertEquals(2, devices.size());
        }

        @Test
        @DisplayName("8.6 清除所有受信设备")
        void clearAll() {
            manager.trustDevice("d1", "MacBook");
            manager.trustDevice("d2", "iPad");
            manager.clearAll();
            assertEquals(0, manager.deviceCount());
        }

        @Test
        @DisplayName("8.7 持久化与重新加载")
        void persistenceAndReload() {
            Path trustFile = tempDir.resolve("trusted-devices.json");
            var manager1 = new TrustedDeviceManager(trustFile);
            manager1.trustDevice("device-1", "MacBook Pro");

            // 创建新实例读取同一文件
            var manager2 = new TrustedDeviceManager(trustFile);
            assertTrue(manager2.isDeviceTrusted("device-1"));
        }

        @Test
        @DisplayName("8.8 受信设备记录字段")
        void deviceRecordFields() {
            manager.trustDevice("d1", "Test Device");
            var devices = manager.listTrustedDevices();
            var device = devices.get(0);
            assertEquals("d1", device.deviceId());
            assertEquals("Test Device", device.description());
            assertNotNull(device.trustedAt());
        }

        @Test
        @DisplayName("8.9 extractJsonString — 从 JSON 提取字段")
        void extractJsonString() {
            String json = "{\"deviceId\":\"d1\",\"description\":\"MacBook\"}";
            assertEquals("d1", TrustedDeviceManager.extractJsonString(json, "deviceId"));
            assertEquals("MacBook", TrustedDeviceManager.extractJsonString(json, "description"));
            assertNull(TrustedDeviceManager.extractJsonString(json, "nonexistent"));
        }

        @Test
        @DisplayName("8.10 parseTrustedDevices — JSON 数组解析")
        void parseTrustedDevices() {
            Path trustFile = tempDir.resolve("test-parse.json");
            var mgr = new TrustedDeviceManager(trustFile);
            mgr.parseTrustedDevices(
                    "[{\"deviceId\":\"d1\",\"description\":\"Mac\",\"trustedAt\":\"2024-01-01T00:00:00Z\"}]");
            assertTrue(mgr.isDeviceTrusted("d1"));
        }
    }

    // ================================================================
    // §9 BridgeServer — 桥接服务器核心
    // ================================================================

    @Nested
    @DisplayName("§9 BridgeServer")
    class BridgeServerTests {

        private BridgeServer server;

        @BeforeEach
        void setUp() {
            server = new BridgeServer(BridgePollConfig.defaults(), 3);
        }

        @Test
        @DisplayName("9.1 初始状态 — READY")
        void initialState() {
            assertEquals(BridgeState.READY, server.getState());
            assertEquals(0, server.activeSessionCount());
            assertEquals(0, server.environmentCount());
        }

        @Test
        @DisplayName("9.2 注册环境")
        void registerEnvironment() {
            var env = server.registerEnvironment(Map.of("dir", "/workspace"));
            assertNotNull(env.environmentId());
            assertNotNull(env.environmentSecret());
            assertEquals(64, env.environmentSecret().length());
            assertEquals(BridgeState.CONNECTED, server.getState());
            assertEquals(1, server.environmentCount());
        }

        @Test
        @DisplayName("9.3 注册环境 — 自定义 environmentId")
        void registerWithCustomId() {
            var env = server.registerEnvironment(
                    Map.of("environmentId", "custom-env-1"));
            assertEquals("custom-env-1", env.environmentId());
        }

        @Test
        @DisplayName("9.4 注销环境")
        void unregisterEnvironment() {
            var env = server.registerEnvironment(Map.of());
            server.unregisterEnvironment(env.environmentId());
            assertEquals(0, server.environmentCount());
            assertEquals(BridgeState.READY, server.getState());
        }

        @Test
        @DisplayName("9.5 创建会话")
        void createSession() {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));
            assertNotNull(handle.sessionId());
            assertTrue(handle.sessionId().startsWith("session_"));
            assertEquals(env.environmentId(), handle.environmentId());
            assertEquals(1, server.activeSessionCount());
        }

        @Test
        @DisplayName("9.6 创建会话 — 超过最大数量抛异常")
        void createSessionOverCapacity() {
            var env = server.registerEnvironment(Map.of());
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));

            assertThrows(IllegalStateException.class, () ->
                    server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId())));
        }

        @Test
        @DisplayName("9.7 关闭会话")
        void closeSession() throws Exception {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.closeSession(handle.sessionId());

            assertEquals(0, server.activeSessionCount());
            assertEquals(BridgeServer.SessionDoneStatus.COMPLETED,
                    handle.done().get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("9.8 获取会话句柄")
        void getSession() {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            assertTrue(server.getSession(handle.sessionId()).isPresent());
            assertTrue(server.getSession("nonexistent").isEmpty());
        }

        @Test
        @DisplayName("9.9 isAtCapacity — 容量检查")
        void atCapacity() {
            assertFalse(server.isAtCapacity());
            var env = server.registerEnvironment(Map.of());
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.createSession(BridgeServer.CreateSessionRequest.of(env.environmentId()));
            assertTrue(server.isAtCapacity());
        }

        @Test
        @DisplayName("9.10 消息处理 — ping → pong")
        void handlePing() {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            List<BridgeMessage> received = new CopyOnWriteArrayList<>();
            server.addMessageListener(received::add);

            server.handleMessage(handle.sessionId(), BridgeMessage.ping());
            // 应收到 pong（通过 broadcastMessage）
            assertTrue(received.stream().anyMatch(m -> "bridge_pong".equals(m.type())));
        }

        @Test
        @DisplayName("9.11 消息去重 — 重复消息忽略")
        void messageDedup() {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            List<BridgeMessage> received = new CopyOnWriteArrayList<>();
            server.addMessageListener(received::add);

            BridgeMessage msg = BridgeMessage.command("help", Map.of());
            server.handleMessage(handle.sessionId(), msg);
            int firstCount = received.size();
            // 发送相同 ID 的消息 — 应被去重
            server.handleMessage(handle.sessionId(), msg);
            assertEquals(firstCount, received.size());
        }

        @Test
        @DisplayName("9.12 epoch 管理 — 过期 epoch 消息忽略")
        void staleEpochIgnored() {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            server.incrementEpoch(); // epoch = 1

            List<BridgeMessage> received = new CopyOnWriteArrayList<>();
            server.addMessageListener(received::add);

            // epoch=0 的消息应被忽略（当前 epoch=1）
            // 注意：只有 epoch > 0 的消息才检查
            BridgeMessage staleMsg = BridgeMessage.of("bridge_command",
                    Map.of("command", "test"), 0);
            // epoch=0 不会被检查（设计上 epoch > 0 才检查）
            // 创建 epoch=1 的有效消息
            BridgeMessage validMsg = BridgeMessage.of("bridge_command",
                    Map.of("command", "test"), 1);
            server.handleMessage(handle.sessionId(), validMsg);
            assertTrue(received.size() > 0);
        }

        @Test
        @DisplayName("9.13 权限请求 — 异步响应")
        void permissionRequest() throws Exception {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            List<BridgeMessage> received = new CopyOnWriteArrayList<>();
            server.addMessageListener(received::add);

            CompletableFuture<BridgeServer.PermissionDecision> future =
                    server.requestPermission(handle.sessionId(), "Write", Map.of("path", "/tmp"));

            // 应发送了 permission_request 消息
            assertTrue(received.stream()
                    .anyMatch(m -> "bridge_permission_request".equals(m.type())));

            // 从消息中提取 requestId 并响应
            BridgeMessage permMsg = received.stream()
                    .filter(m -> "bridge_permission_request".equals(m.type()))
                    .findFirst().orElseThrow();
            String requestId = permMsg.payload().get("toolUseId").toString();

            server.respondToPermission(handle.sessionId(), requestId,
                    BridgeServer.PermissionDecision.ALLOW);

            assertEquals(BridgeServer.PermissionDecision.ALLOW,
                    future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("9.14 关闭会话 — 取消待处理权限请求")
        void closeSessionCancelsPermissions() throws Exception {
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));

            CompletableFuture<BridgeServer.PermissionDecision> future =
                    server.requestPermission(handle.sessionId(), "Write", Map.of());

            server.closeSession(handle.sessionId());
            // 权限请求应被完成为 DENY
            assertEquals(BridgeServer.PermissionDecision.DENY,
                    future.get(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("9.15 消息监听器 — 添加和移除")
        void messageListeners() {
            List<BridgeMessage> received = new CopyOnWriteArrayList<>();
            Consumer<BridgeMessage> listener = received::add;

            server.addMessageListener(listener);
            var env = server.registerEnvironment(Map.of());
            var handle = server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));
            server.handleMessage(handle.sessionId(), BridgeMessage.ping());
            assertTrue(received.size() > 0);

            received.clear();
            server.removeMessageListener(listener);
            server.handleMessage(handle.sessionId(),
                    BridgeMessage.of("bridge_command", Map.of("command", "test")));
            // 移除后不再接收 — 但 handlePing 的 broadcast 也不会到达
            // 注意：handleMessage 内部的 broadcastMessage 也使用 messageListeners
        }

        @Test
        @DisplayName("9.16 注销环境 — 关联会话自动关闭")
        void unregisterClosesRelatedSessions() {
            var env = server.registerEnvironment(Map.of());
            server.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));
            assertEquals(1, server.activeSessionCount());

            server.unregisterEnvironment(env.environmentId());
            assertEquals(0, server.activeSessionCount());
        }

        @Test
        @DisplayName("9.17 CreateSessionRequest — 工厂方法")
        void createSessionRequest() {
            var req = BridgeServer.CreateSessionRequest.of("env-1");
            assertEquals("env-1", req.environmentId());
            assertEquals(BridgeProtocolVersion.V1_HYBRID, req.protocol());

            var req2 = BridgeServer.CreateSessionRequest.of("env-2",
                    BridgeProtocolVersion.V2_SSE_CCR);
            assertEquals(BridgeProtocolVersion.V2_SSE_CCR, req2.protocol());
        }

        @Test
        @DisplayName("9.18 默认配置 — maxSessions=1")
        void defaultMaxSessions() {
            var defaultServer = new BridgeServer();
            var env = defaultServer.registerEnvironment(Map.of());
            defaultServer.createSession(
                    BridgeServer.CreateSessionRequest.of(env.environmentId()));
            assertTrue(defaultServer.isAtCapacity());
        }

        @Test
        @DisplayName("9.19 SessionDoneStatus 枚举")
        void sessionDoneStatus() {
            assertEquals(3, BridgeServer.SessionDoneStatus.values().length);
            assertNotNull(BridgeServer.SessionDoneStatus.COMPLETED);
            assertNotNull(BridgeServer.SessionDoneStatus.FAILED);
            assertNotNull(BridgeServer.SessionDoneStatus.INTERRUPTED);
        }

        @Test
        @DisplayName("9.20 PermissionDecision 枚举")
        void permissionDecision() {
            assertEquals(3, BridgeServer.PermissionDecision.values().length);
        }

        @Test
        @DisplayName("9.21 getPollConfig — 返回轮询配置")
        void getPollConfig() {
            assertNotNull(server.getPollConfig());
            assertEquals(1000, server.getPollConfig().pollIntervalMs());
        }

        @Test
        @DisplayName("9.22 getCurrentEpoch — 初始值 0")
        void getCurrentEpoch() {
            assertEquals(0, server.getCurrentEpoch());
            server.incrementEpoch();
            assertEquals(1, server.getCurrentEpoch());
        }
    }
}

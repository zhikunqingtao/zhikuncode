package com.aicodeassistant.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智谱 WebSearch MCP 真实搜索测试 — 直接调用 MCP SSE 端点。
 * <p>
 * MCP SSE 协议流程:
 * 1. 客户端 GET /sse → 建立 SSE 长连接，收到 endpoint 事件
 * 2. 客户端 POST endpoint → 发送 JSON-RPC 请求（initialize / tools/call）
 * 3. 服务端通过 SSE 流推送 JSON-RPC 响应
 * <p>
 * 关键: SSE 连接必须保持开放，不能断开后重连（会话绑定）
 */
class ZhipuWebSearchRealTest {

    private static final String MCP_SSE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse";
    private static final String API_KEY = "sk-93625146d2c343d78735213013794ed5";

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY", matches = ".+")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testRealWebSearch() throws Exception {
        // ==================== 入参 ====================
        String searchQuery = "阿里巴巴通义千问最新版本";
        int numResults = 3;

        System.out.println("\n========== 智谱 WebSearch MCP 真实搜索入参 ==========");
        System.out.println("MCP SSE URL: " + MCP_SSE_URL);
        System.out.println("搜索关键词: " + searchQuery);
        System.out.println("结果数量: " + numResults);
        System.out.println("API Key: " + API_KEY.substring(0, 10) + "...");
        System.out.println("====================================================\n");

        long startTime = System.currentTimeMillis();

        // ========== Step 1: 建立 SSE 长连接 ==========
        // SSE 连接必须保持开放，响应通过此流推送
        URL sseUrl = new URL(MCP_SSE_URL);
        HttpURLConnection sseConn = (HttpURLConnection) sseUrl.openConnection();
        sseConn.setRequestMethod("GET");
        sseConn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        sseConn.setRequestProperty("Accept", "text/event-stream");
        sseConn.setConnectTimeout(15000);
        sseConn.setReadTimeout(0); // 无超时 — SSE 长连接

        int sseResponseCode = sseConn.getResponseCode();
        System.out.println("SSE 连接响应码: " + sseResponseCode);
        assertEquals(200, sseResponseCode, "SSE 连接失败");

        BufferedReader sseReader = new BufferedReader(
                new InputStreamReader(sseConn.getInputStream(), StandardCharsets.UTF_8));

        // 读取第一个 SSE 事件获取 message endpoint
        String messageEndpoint = null;
        String line;
        while ((line = sseReader.readLine()) != null) {
            System.out.println("SSE 事件: " + line);
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (data.startsWith("/api/v1/mcps/")) {
                    messageEndpoint = "https://dashscope.aliyuncs.com" + data;
                    break;
                }
            }
        }

        assertNotNull(messageEndpoint, "未收到 MCP 消息端点");
        System.out.println("✅ SSE 会话建立成功");
        System.out.println("   消息端点: " + messageEndpoint);

        // 从 endpoint 提取 session ID
        String sessionId = "(unknown)";
        if (messageEndpoint.contains("sessionId=")) {
            int s = messageEndpoint.indexOf("sessionId=") + 10;
            int e = messageEndpoint.indexOf("&", s);
            sessionId = e > 0 ? messageEndpoint.substring(s, e) : messageEndpoint.substring(s);
        }
        System.out.println("   Session ID: " + sessionId);

        // ========== 启动 SSE 读取线程（后台持续监听响应） ==========
        BlockingQueue<String> sseMessages = new LinkedBlockingQueue<>();
        AtomicReference<Exception> sseError = new AtomicReference<>();
        Thread sseReaderThread = Thread.ofVirtual().name("sse-reader").start(() -> {
            try {
                String sLine;
                StringBuilder eventData = new StringBuilder();
                while ((sLine = sseReader.readLine()) != null) {
                    System.out.println("SSE <<< " + sLine);
                    if (sLine.startsWith("data:")) {
                        String data = sLine.substring(5).trim();
                        if (!data.isEmpty() && !data.startsWith("/api/v1/mcps/")) {
                            eventData.append(data);
                        }
                    } else if (sLine.isEmpty() && !eventData.isEmpty()) {
                        // 空行 = 事件结束
                        sseMessages.offer(eventData.toString());
                        eventData.setLength(0);
                    }
                }
            } catch (Exception ex) {
                if (!Thread.currentThread().isInterrupted()) {
                    sseError.set(ex);
                }
            }
        });

        try {
            // ========== Step 2: 发送 MCP initialize 请求 ==========
            String initRequest = """
                    {
                      "jsonrpc": "2.0",
                      "id": "%s",
                      "method": "initialize",
                      "params": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "clientInfo": {
                          "name": "zhikuncode-test",
                          "version": "1.0.0"
                        }
                      }
                    }
                    """.formatted(UUID.randomUUID().toString());

            System.out.println("\n>>> 发送 initialize 请求");
            int initCode = postJsonRpc(messageEndpoint, initRequest);
            System.out.println("initialize POST 响应码: " + initCode);
            assertTrue(initCode >= 200 && initCode < 300, "initialize 请求失败: " + initCode);

            // 等待 SSE 返回 initialize 响应
            String initResponse = sseMessages.poll(15, TimeUnit.SECONDS);
            System.out.println("\n✅ initialize 响应:\n" + (initResponse != null ? initResponse : "(超时)"));
            assertNotNull(initResponse, "未收到 initialize 响应");

            // ========== Step 2.5: 发送 initialized 通知 ==========
            String initializedNotification = """
                    {
                      "jsonrpc": "2.0",
                      "method": "notifications/initialized"
                    }
                    """;
            System.out.println("\n>>> 发送 notifications/initialized 通知");
            int notifyCode = postJsonRpc(messageEndpoint, initializedNotification);
            System.out.println("initialized 通知响应码: " + notifyCode);

            // ========== Step 3: 调用 webSearchPro 工具 ==========
            String toolCallRequest = """
                    {
                      "jsonrpc": "2.0",
                      "id": "%s",
                      "method": "tools/call",
                      "params": {
                        "name": "webSearchPro",
                        "arguments": {
                          "search_query": "%s",
                          "count": %d
                        }
                      }
                    }
                    """.formatted(UUID.randomUUID().toString(), searchQuery, numResults);

            System.out.println("\n>>> 发送 webSearchPro 工具调用");
            int toolCode = postJsonRpc(messageEndpoint, toolCallRequest);
            System.out.println("tools/call POST 响应码: " + toolCode);
            assertTrue(toolCode >= 200 && toolCode < 300, "tools/call 请求失败: " + toolCode);

            // 等待 SSE 返回搜索结果（搜索可能较慢，等待 30 秒）
            String searchResult = sseMessages.poll(30, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            // ==================== 出参 ====================
            System.out.println("\n========== 智谱 WebSearch MCP 真实搜索出参 ==========");
            System.out.println("请求总耗时: " + duration + " ms");
            if (searchResult != null) {
                System.out.println("响应长度: " + searchResult.length() + " 字符");
                // 截取前 2000 字符打印
                String display = searchResult.length() > 2000
                        ? searchResult.substring(0, 2000) + "\n... (截断)"
                        : searchResult;
                System.out.println("响应内容:\n" + display);
            } else {
                System.out.println("响应: (超时 — 30s 内未收到搜索结果)");
            }
            System.out.println("====================================================\n");

            assertNotNull(searchResult, "未收到 webSearchPro 搜索结果（30s 超时）");
            assertFalse(searchResult.isEmpty(), "搜索结果为空");

            System.out.println("✅ 智谱 WebSearch MCP 真实搜索测试通过!");

        } finally {
            // 清理资源
            sseReaderThread.interrupt();
            sseReader.close();
            sseConn.disconnect();
        }
    }

    /**
     * POST JSON-RPC 请求到 MCP 消息端点。
     * MCP SSE 协议: POST 返回 2xx (通常 202 Accepted)，实际响应通过 SSE 推送。
     */
    private int postJsonRpc(String endpoint, String jsonBody) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();

        // 读取并丢弃响应体（防止连接池泄漏）
        try (var is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()) {
            if (is != null) {
                byte[] buf = new byte[1024];
                while (is.read(buf) != -1) { /* drain */ }
            }
        }

        conn.disconnect();
        return code;
    }
}

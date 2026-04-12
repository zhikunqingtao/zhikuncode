package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP STDIO 传输层 — 通过子进程 stdin/stdout 进行 JSON-RPC 行协议通信。
 * <p>
 * 从 McpServerConnection 提取的 STDIO 相关代码，统一为 McpTransport 接口。
 *
 * @see McpTransport
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private volatile Process process;
    private volatile BufferedReader stdoutReader;
    private volatile OutputStream stdinWriter;
    private Consumer<JsonNode> notificationHandler;

    public McpStdioTransport(McpServerConfig config) {
        this.command = config.command();
        this.args = config.args() != null ? config.args() : List.of();
        this.env = config.env() != null ? config.env() : Map.of();
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(command);
                cmd.addAll(args);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                if (!env.isEmpty()) pb.environment().putAll(env);

                this.process = pb.start();
                this.stdinWriter = process.getOutputStream();
                this.stdoutReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                connected.set(true);
                log.info("STDIO transport connected (pid={})", process.pid());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start STDIO process: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public JsonNode sendRequest(String method, Object params, long timeoutMs) throws McpProtocolException {
        if (!connected.get()) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED, "STDIO not connected"));
        }
        long id = requestIdSequence.getAndIncrement();
        try {
            JsonRpcMessage.Request request = new JsonRpcMessage.Request(id, method, params);
            String json = objectMapper.writeValueAsString(request);
            stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdinWriter.flush();

            String responseLine = readResponseWithTimeout(timeoutMs);
            if (responseLine == null) {
                throw new McpProtocolException(new JsonRpcError(
                        JsonRpcError.REQUEST_TIMEOUT, "STDIO timeout: " + method));
            }
            JsonNode node = objectMapper.readTree(responseLine);
            if (node.has("error") && !node.get("error").isNull()) {
                throw new McpProtocolException(
                        objectMapper.treeToValue(node.get("error"), JsonRpcError.class));
            }
            return node.has("result") ? node.get("result") : null;
        } catch (McpProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new McpProtocolException("STDIO communication error: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        try {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
            String json = objectMapper.writeValueAsString(notification);
            stdinWriter.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdinWriter.flush();
        } catch (IOException e) {
            log.warn("Failed to send STDIO notification '{}': {}", method, e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void close() {
        connected.set(false);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try { if (stdoutReader != null) stdoutReader.close(); } catch (Exception ignored) {}
        try { if (stdinWriter != null) stdinWriter.close(); } catch (Exception ignored) {}
    }

    /** 带超时的响应读取 — 从原 McpServerConnection.readResponseWithTimeout() 提取 */
    private String readResponseWithTimeout(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stdoutReader != null && stdoutReader.ready()) {
                return stdoutReader.readLine();
            }
            try { Thread.sleep(10); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}

package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 服务能力感知客户端 — §4.14 / §2.4.3
 *
 * <p>启动时探测 Python 服务的可用能力域，仅调用已安装的能力域。
 * 缺少依赖的能力域自动降级（返回 Optional.empty()），不抛异常。</p>
 *
 * <p>能力清单缓存在内存中，每 5 分钟刷新。</p>
 */
public class PythonCapabilityAwareClient {

    private static final Logger log = LoggerFactory.getLogger(PythonCapabilityAwareClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEAVY_READ_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final long REFRESH_INTERVAL_MS = 300_000; // 5 minutes

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private volatile Map<String, CapabilityStatus> capabilities = Map.of();
    private volatile long lastRefreshTimestamp = 0;

    /**
     * 能力域可用状态。
     *
     * @param name      能力域显示名称
     * @param available 是否可用
     * @param reason    不可用原因 (available=true 时为 null)
     */
    public record CapabilityStatus(String name, boolean available, String reason) {
    }

    public PythonCapabilityAwareClient(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    public PythonCapabilityAwareClient(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // ═══ 能力探测 ═══

    /**
     * 刷新能力清单 — GET /api/health/capabilities
     * 启动时 + 每 5 分钟自动刷新。
     */
    public void refreshCapabilities() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/health/capabilities"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                this.capabilities = parseCapabilities(response.body());
                this.lastRefreshTimestamp = System.currentTimeMillis();
                log.info("Python 能力清单已刷新: {} 个域", capabilities.size());
            }
        } catch (Exception e) {
            log.warn("Python 服务能力探测失败，保留旧缓存", e);
        }
    }

    /**
     * 如果距上次刷新超过 REFRESH_INTERVAL_MS，自动刷新。
     */
    public void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshTimestamp > REFRESH_INTERVAL_MS) {
            refreshCapabilities();
        }
    }

    /**
     * 检查某能力域是否可用。
     */
    public boolean isCapabilityAvailable(String domain) {
        var status = capabilities.get(domain);
        return status != null && status.available();
    }

    /**
     * 获取所有能力域状态。
     */
    public Map<String, CapabilityStatus> getCapabilities() {
        return Map.copyOf(capabilities);
    }

    // ═══ 安全调用 ═══

    /**
     * 安全调用 — 能力不可用时返回 Optional.empty() 而非抛异常。
     *
     * @param domain     能力域名称 (如 "CODE_INTEL", "FILE_PROCESSING")
     * @param endpoint   API 端点路径 (如 "/api/code-intel/parse")
     * @param body       请求体 (将被序列化为 JSON)
     * @param resultType 响应类型
     * @return 结果，或 empty() 如果能力不可用
     */
    public <T> Optional<T> callIfAvailable(String domain, String endpoint,
                                           Object body, Class<T> resultType) {
        if (!isCapabilityAvailable(domain)) {
            log.debug("Python 能力域 [{}] 不可用，跳过调用 {}", domain, endpoint);
            return Optional.empty();
        }
        return callWithRetry(endpoint, body, resultType);
    }

    /**
     * 带重试的 HTTP POST 调用。
     */
    public <T> Optional<T> callWithRetry(String endpoint, Object body,
                                         Class<T> resultType) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String jsonBody = objectMapper.writeValueAsString(body);
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + endpoint))
                        .timeout(READ_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    T result = objectMapper.readValue(response.body(), resultType);
                    return Optional.ofNullable(result);
                }
                log.warn("Python 调用 {} 返回 HTTP {}: {}", endpoint,
                        response.statusCode(), response.body());
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.debug("Python 调用 {} 失败 (尝试 {}/{}), 重试中...",
                            endpoint, attempt + 1, MAX_RETRIES);
                    try {
                        Thread.sleep(RETRY_DELAY.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("Python 调用 {} 最终失败", endpoint, e);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 直接 POST 调用（不检查能力域）。
     */
    public <T> Optional<T> post(String endpoint, Object body, Class<T> resultType) {
        return callWithRetry(endpoint, body, resultType);
    }

    /**
     * 直接 GET 调用。
     */
    public Optional<String> get(String endpoint) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + endpoint))
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
        } catch (Exception e) {
            log.error("Python GET {} 失败", endpoint, e);
        }
        return Optional.empty();
    }

    /**
     * 健康检查 — GET /api/health
     */
    public boolean isHealthy() {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══ 内部方法 ═══

    private Map<String, CapabilityStatus> parseCapabilities(String json) {
        Map<String, CapabilityStatus> result = new ConcurrentHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String domain = entry.getKey();
                JsonNode value = entry.getValue();
                result.put(domain, new CapabilityStatus(
                        value.path("name").asText(""),
                        value.path("available").asBoolean(false),
                        value.path("reason").isNull() ? null : value.path("reason").asText()
                ));
            }
        } catch (Exception e) {
            log.error("解析 Python 能力清单失败", e);
        }
        return result;
    }
}

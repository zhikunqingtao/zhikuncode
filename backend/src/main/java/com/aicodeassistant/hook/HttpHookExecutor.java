package com.aicodeassistant.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Hook 执行器 — 对齐原版 execHttpHook.ts（243行）。
 * <p>
 * 关键安全特性:
 * <ol>
 *   <li>SSRF 防护（DNS 解析后检查 IP）</li>
 *   <li>Header 值环境变量插值（仅白名单变量）</li>
 *   <li>CRLF 注入防护（sanitize header values）</li>
 *   <li>超时控制（默认 10 分钟）</li>
 *   <li>无重定向（maxRedirects=0）</li>
 *   <li>HMAC-SHA256 签名</li>
 * </ol>
 *
 * @see <a href="SPEC §11.5.4B">HttpHookExecutor 规格</a>
 */
@Component
public class HttpHookExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpHookExecutor.class);

    private final RestTemplate restTemplate; // ★ 使用 SsrfGuard.createSsrfSafeRestTemplate()
    private final ObjectMapper objectMapper;

    public HttpHookExecutor(ObjectMapper objectMapper) {
        this.restTemplate = SsrfGuard.createSsrfSafeRestTemplate();
        this.objectMapper = objectMapper;
    }

    public record HttpHookConfig(
            String url,
            Duration timeout,          // 默认 10 分钟
            Map<String, String> headers,
            List<String> allowedEnvVars, // 允许插值的环境变量名
            String hmacSecret          // HMAC-SHA256 签名密钥(可选)
    ) {}

    /**
     * 适配为 HookRegistry 的 Function&lt;HookContext, HookResult&gt;。
     */
    public Function<HookRegistry.HookContext, HookRegistry.HookResult> toHandler(
            HttpHookConfig config) {
        return ctx -> executeHttpHook(config, ctx);
    }

    private HookRegistry.HookResult executeHttpHook(
            HttpHookConfig config, HookRegistry.HookContext ctx) {
        // SSRF 防护已通过 DnsResolver 回调内置在 RestTemplate 中

        // 2. 构建请求头（环境变量插值 + CRLF 清理）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.headers() != null) {
            Set<String> allowed = config.allowedEnvVars() != null
                    ? new HashSet<>(config.allowedEnvVars()) : Set.of();
            config.headers().forEach((k, v) -> {
                String interpolated = interpolateEnvVars(v, allowed);
                headers.set(k, sanitizeHeaderValue(interpolated));
            });
        }

        // 3. HMAC 签名
        String payload = buildPayload(ctx);
        if (config.hmacSecret() != null && !config.hmacSecret().isEmpty()) {
            String signature = hmacSha256(config.hmacSecret(), payload);
            headers.set("X-Hook-Signature", "sha256=" + signature);
        }

        // 4. 发送 POST 请求
        RequestEntity<String> request = RequestEntity
                .post(URI.create(config.url()))
                .headers(headers)
                .body(payload);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return parseHookResponse(response.getBody());
            }
            return HookRegistry.HookResult.passThrough(); // 非2xx → 默认放行
        } catch (Exception e) {
            log.warn("HTTP hook failed: url={}, error={}", config.url(), e.getMessage());
            return HookRegistry.HookResult.passThrough(); // 超时/错误 → 默认放行
        }
    }

    // 环境变量插值 — 对齐原版 interpolateEnvVars()
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile(
            "\\$\\{([A-Z_][A-Z0-9_]*)\\}|\\$([A-Z_][A-Z0-9_]*)");

    private String interpolateEnvVars(String value, Set<String> allowed) {
        return ENV_VAR_PATTERN.matcher(value).replaceAll(mr -> {
            String varName = mr.group(1) != null ? mr.group(1) : mr.group(2);
            if (!allowed.contains(varName)) return "";
            String env = System.getenv(varName);
            return env != null ? Matcher.quoteReplacement(env) : "";
        });
    }

    // CRLF 注入防护
    private String sanitizeHeaderValue(String value) {
        return value.replaceAll("[\\r\\n\\x00]", "");
    }

    // 构建请求 payload
    private String buildPayload(HookRegistry.HookContext ctx) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", ctx.toolName());
            payload.put("input", ctx.input());
            payload.put("output", ctx.output());
            payload.put("sessionId", ctx.sessionId());
            payload.put("metadata", ctx.metadata());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    // HMAC-SHA256
    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            log.error("HMAC-SHA256 failed", e);
            return "";
        }
    }

    // 解析 hook 响应
    private HookRegistry.HookResult parseHookResponse(String body) {
        if (body == null || body.isBlank()) return HookRegistry.HookResult.passThrough();
        try {
            var node = objectMapper.readTree(body);
            boolean proceed = !node.has("proceed") || node.get("proceed").asBoolean(true);
            String message = node.has("message") ? node.get("message").asText() : null;
            String modifiedInput = node.has("modifiedInput") ? node.get("modifiedInput").asText() : null;
            if (!proceed) {
                return HookRegistry.HookResult.deny(message != null ? message : "Hook denied");
            }
            if (modifiedInput != null) {
                return HookRegistry.HookResult.modifyInput(modifiedInput);
            }
            return HookRegistry.HookResult.passThrough();
        } catch (Exception e) {
            log.warn("Failed to parse hook response: {}", e.getMessage());
            return HookRegistry.HookResult.passThrough();
        }
    }
}

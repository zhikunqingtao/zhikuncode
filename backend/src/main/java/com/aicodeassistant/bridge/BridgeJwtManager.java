package com.aicodeassistant.bridge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 桥接 JWT 管理器 — 令牌解码、主动刷新、失败重试。
 * <p>
 * 核心职责:
 * <ol>
 *   <li>令牌解码与验证（不验证签名，仅提取 payload.exp）</li>
 *   <li>主动刷新调度（过期前 {@value #TOKEN_REFRESH_BUFFER_SECONDS} 秒提前刷新）</li>
 *   <li>失败重试（刷新失败时指数退避重试，最多 {@value #MAX_REFRESH_FAILURES} 次）</li>
 * </ol>
 * <p>
 * 令牌刷新调度器支持代际计数器防止过时刷新，
 * 支持从 expires_in 秒数调度（不透明 JWT）。
 *
 * @see <a href="SPEC §4.5.3">JWT 令牌管理</a>
 * @see <a href="SPEC §4.5.8a.6">令牌刷新调度器</a>
 */
public class BridgeJwtManager {

    private static final Logger log = LoggerFactory.getLogger(BridgeJwtManager.class);

    /** 过期前提前刷新缓冲时间 — 5 分钟 */
    public static final long TOKEN_REFRESH_BUFFER_SECONDS = 300;

    /** 后备刷新间隔 — 30 分钟（无法解码 exp 时使用） */
    public static final long FALLBACK_REFRESH_INTERVAL_MS = 30 * 60 * 1000L;

    /** 最大刷新失败次数 */
    public static final int MAX_REFRESH_FAILURES = 3;

    /** 刷新重试间隔 — 60 秒 */
    public static final long REFRESH_RETRY_DELAY_MS = 60_000;

    /** sk-ant-si- 前缀（部分不透明 JWT） */
    private static final String SK_PREFIX = "sk-ant-si-";

    private volatile String currentToken;
    private volatile Instant tokenExpiry;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> sessionTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> generations = new ConcurrentHashMap<>();

    private Function<String, String> tokenRefresher;
    private Consumer<String> onAuthExpired;

    public BridgeJwtManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bridge-jwt-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== 公开 API ====================

    /**
     * 初始化令牌并调度主动刷新。
     *
     * @param jwt            JWT 令牌
     * @param tokenRefresher 刷新函数（传入旧 token，返回新 token）
     * @param onAuthExpired  认证过期回调（刷新彻底失败时调用）
     */
    public void initialize(String jwt, Function<String, String> tokenRefresher,
                           Consumer<String> onAuthExpired) {
        this.currentToken = jwt;
        this.tokenRefresher = tokenRefresher;
        this.onAuthExpired = onAuthExpired;
        this.tokenExpiry = extractExpiry(jwt);
        scheduleRefresh("default");
    }

    /** 简化初始化 — 仅设置令牌 */
    public void initialize(String jwt) {
        this.currentToken = jwt;
        this.tokenExpiry = extractExpiry(jwt);
    }

    /** 获取当前令牌 */
    public String getCurrentToken() {
        return currentToken;
    }

    /** 获取令牌过期时间 */
    public Instant getTokenExpiry() {
        return tokenExpiry;
    }

    /** 检查令牌是否已过期 */
    public boolean isExpired() {
        return tokenExpiry != null && Instant.now().isAfter(tokenExpiry);
    }

    /** 检查令牌是否即将过期（在缓冲期内） */
    public boolean isNearExpiry() {
        if (tokenExpiry == null) return false;
        return Instant.now().isAfter(tokenExpiry.minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS));
    }

    /**
     * 从 expires_in 秒数调度刷新（用于不透明 JWT）。
     *
     * @param sessionId       会话 ID
     * @param expiresInSeconds 令牌有效期（秒）
     */
    public void scheduleFromExpiresIn(String sessionId, int expiresInSeconds) {
        long delayMs = Math.max(
                expiresInSeconds * 1000L - TOKEN_REFRESH_BUFFER_SECONDS * 1000L,
                30_000);
        scheduleTimer(sessionId, delayMs);
    }

    /** 取消指定会话的刷新调度 */
    public void cancelRefresh(String sessionId) {
        AtomicInteger gen = generations.get(sessionId);
        if (gen != null) {
            gen.incrementAndGet(); // 递增代际，使飞行中的刷新失效
        }
        ScheduledFuture<?> future = sessionTimers.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 关闭调度器 */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 从 JWT payload 提取过期时间（不验证签名）。
     * 支持 "sk-ant-si-" 前缀剥离。
     */
    Instant extractExpiry(String jwt) {
        try {
            String token = jwt;
            if (token.startsWith(SK_PREFIX)) {
                token = token.substring(SK_PREFIX.length());
            }
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                log.warn("Invalid JWT format, using fallback refresh interval");
                return Instant.now().plusMillis(FALLBACK_REFRESH_INTERVAL_MS);
            }
            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            // 简单解析 "exp" 字段 — 避免引入 JSON 库依赖
            long exp = parseExpFromPayload(payload);
            if (exp <= 0) {
                return Instant.now().plusMillis(FALLBACK_REFRESH_INTERVAL_MS);
            }
            return Instant.ofEpochSecond(exp);
        } catch (Exception e) {
            log.warn("Failed to extract JWT expiry, using fallback", e);
            return Instant.now().plusMillis(FALLBACK_REFRESH_INTERVAL_MS);
        }
    }

    /** 简单解析 JSON payload 中的 exp 字段 */
    long parseExpFromPayload(String payload) {
        // 简易 JSON 解析 "exp": <number>
        int idx = payload.indexOf("\"exp\"");
        if (idx < 0) return -1;
        int colonIdx = payload.indexOf(':', idx + 5);
        if (colonIdx < 0) return -1;
        int start = colonIdx + 1;
        while (start < payload.length() && payload.charAt(start) == ' ') start++;
        int end = start;
        while (end < payload.length() && (Character.isDigit(payload.charAt(end)) || payload.charAt(end) == '-')) end++;
        if (end == start) return -1;
        return Long.parseLong(payload.substring(start, end));
    }

    /** 主动刷新调度 — 过期前缓冲时间触发 */
    private void scheduleRefresh(String sessionId) {
        if (tokenExpiry == null) return;
        long delayMs = Duration.between(Instant.now(), tokenExpiry)
                .minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS).toMillis();
        if (delayMs <= 0) delayMs = 0;
        scheduleTimer(sessionId, delayMs);
    }

    /** 调度定时器（带代际管理） */
    private void scheduleTimer(String sessionId, long delayMs) {
        AtomicInteger gen = generations.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int currentGen = gen.incrementAndGet();

        ScheduledFuture<?> oldFuture = sessionTimers.get(sessionId);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.schedule(
                () -> doRefresh(sessionId, currentGen),
                delayMs, TimeUnit.MILLISECONDS);
        sessionTimers.put(sessionId, future);
        log.debug("Scheduled token refresh for session {} in {}ms (gen={})",
                sessionId, delayMs, currentGen);
    }

    /** 刷新令牌 — 失败时指数退避重试 */
    private void doRefresh(String sessionId, int expectedGen) {
        AtomicInteger gen = generations.get(sessionId);
        if (gen == null || gen.get() != expectedGen) {
            log.debug("Skipping stale refresh for session {} (expected gen={}, current={})",
                    sessionId, expectedGen, gen != null ? gen.get() : -1);
            return;
        }

        if (tokenRefresher == null) {
            log.warn("No token refresher configured, cannot refresh");
            return;
        }

        for (int attempt = 1; attempt <= MAX_REFRESH_FAILURES; attempt++) {
            // 再次检查代际
            if (gen.get() != expectedGen) return;

            try {
                String newToken = tokenRefresher.apply(currentToken);
                this.currentToken = newToken;
                this.tokenExpiry = extractExpiry(newToken);
                log.info("Token refreshed successfully for session {}", sessionId);
                scheduleRefresh(sessionId);
                return;
            } catch (Exception e) {
                log.warn("Token refresh attempt {}/{} failed for session {}",
                        attempt, MAX_REFRESH_FAILURES, sessionId, e);
                if (attempt < MAX_REFRESH_FAILURES) {
                    try {
                        Thread.sleep(REFRESH_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        // 所有重试失败
        log.error("Token refresh exhausted all {} attempts for session {}",
                MAX_REFRESH_FAILURES, sessionId);
        if (onAuthExpired != null) {
            onAuthExpired.accept(sessionId);
        }
    }
}

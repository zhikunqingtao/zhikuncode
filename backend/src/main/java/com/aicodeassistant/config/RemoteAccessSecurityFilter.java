package com.aicodeassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 远程访问安全过滤器 (§9.1.0a) — 三层递进认证。
 * <p>
 * 请求认证流程:
 * <ol>
 *   <li>检查请求来源 IP — 127.0.0.1/::1 → 直接放行 (层级 1: localhost 免认证)</li>
 *   <li>检查 Cookie 中是否有有效 session — 有 → 放行</li>
 *   <li>检查 Authorization: Bearer {token} 头 — 有且匹配 → 签发 Cookie + 放行</li>
 *   <li>检查 URL 参数 ?token={token} — 有且匹配 → 签发 Cookie + 重定向</li>
 *   <li>以上都不满足 → 返回 401</li>
 * </ol>
 */
@Component
@Order(1)
public class RemoteAccessSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RemoteAccessSecurityFilter.class);

    /** 启动时生成的随机 Access Token — 32 字节 Base64URL 编码 */
    private final String accessToken;

    /** Session 存储 — Caffeine Cache（P1-03: 限容 10_000 + 30天过期，防止无限增长） */
    private final Cache<String, Instant> sessionStore = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.DAYS)
            .build();

    /** Token 文件路径 */
    private static final Path TOKEN_FILE = Path.of(
            System.getProperty("user.home"), ".config", "ai-code-assistant", "access-token");

    /** Cookie 名称 */
    private static final String COOKIE_NAME = "ai-coder-session";

    /** Cookie 有效期 */
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(30);

    /**
     * 是否允许私有网络免认证访问（Docker 部署场景）。
     * 通过环境变量 ALLOW_PRIVATE_NETWORK=true 启用，默认 false。
     * 启用后，来自 Docker 桥接网络（172.17.x.x 等）的请求将视同 localhost 免认证放行。
     */
    private final boolean allowPrivateNetworkAccess;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    public RemoteAccessSecurityFilter() {
        // 读取 Docker 环境变量：是否允许私有网络免认证
        this.allowPrivateNetworkAccess = Boolean.parseBoolean(
                System.getenv().getOrDefault("ALLOW_PRIVATE_NETWORK", "false"));
        if (allowPrivateNetworkAccess) {
            log.info("Private network access enabled (ALLOW_PRIVATE_NETWORK=true). "
                    + "Requests from Docker bridge network will bypass token authentication.");
        }

        // 尝试加载已有 token，否则生成新 token
        String existingToken = loadExistingToken();
        if (existingToken != null) {
            this.accessToken = existingToken;
            log.info("Reusing existing access token");
        } else {
            byte[] tokenBytes = new byte[32];
            new SecureRandom().nextBytes(tokenBytes);
            this.accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            saveToken();
        }

        String localIp = getLocalIp();
        String tokenPreview = accessToken.substring(0, Math.min(4, accessToken.length()))
                + "..." + accessToken.substring(Math.max(0, accessToken.length() - 4));
        log.info("Mobile access: http://{}:{}?token={}", localIp, serverPort, tokenPreview);
        log.info("Remote control: http://{}:{}/remote.html?token={}", localIp, serverPort, tokenPreview);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws jakarta.servlet.ServletException, IOException {

        String remoteAddr = request.getRemoteAddr();

        // ========== 层级 1: localhost 免认证 ==========
        if ("127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr)) {
            chain.doFilter(request, response);
            return;
        }

        // ========== 层级 1.5: Docker 部署模式免认证 ==========
        // Docker Desktop (Mac/Windows) 端口映射时，容器看到的来源 IP 可能是
        // Docker 虚拟网关的 IP（甚至可能被 NAT 为公网 IP），无法通过私有网段判断。
        // 当 ALLOW_PRIVATE_NETWORK=true 时，信任所有通过 Docker 端口映射进来的请求，
        // 安全性由 Docker 网络隔离和宿主机防火墙保障。
        if (allowPrivateNetworkAccess) {
            log.debug("Docker mode: access granted for IP: {} (ALLOW_PRIVATE_NETWORK=true)", remoteAddr);
            chain.doFilter(request, response);
            return;
        }

        // ========== 非私有网段 → 直接拒绝 ==========
        if (!isPrivateNetwork(remoteAddr)) {
            log.warn("Access denied for non-private IP: {}", remoteAddr);
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Access denied: only private network allowed");
            return;
        }

        // ========== 层级 2: Cookie/Bearer/URL Token 认证 ==========

        // 尝试 1: Cookie
        String sessionCookie = getCookieValue(request, COOKIE_NAME);
        if (sessionCookie != null && validateSessionCookie(sessionCookie)) {
            chain.doFilter(request, response);
            return;
        }

        // 尝试 2: Authorization: Bearer {token}
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (accessToken.equals(token)) {
                issueSessionCookie(response);
                chain.doFilter(request, response);
                return;
            }
        }

        // 尝试 3: URL 参数 ?token={token}
        String urlToken = request.getParameter("token");
        if (urlToken != null && accessToken.equals(urlToken)) {
            issueSessionCookie(response);
            // 重定向到去掉 token 参数的 URL (避免 token 泄露到浏览器历史)
            String cleanUrl = removeTokenParam(request);
            response.sendRedirect(cleanUrl);
            return;
        }

        // 全部失败 → 401
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "Authentication required. Use token URL or Bearer header.");
    }

    // ───── Cookie 管理 ─────

    /**
     * 签发 HttpOnly Cookie — 后续请求不再需要传递 Token。
     */
    private void issueSessionCookie(HttpServletResponse response) {
        String sessionId = UUID.randomUUID().toString();
        sessionStore.put(sessionId, Instant.now().plus(COOKIE_MAX_AGE));

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(sslEnabled)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_MAX_AGE)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private boolean validateSessionCookie(String sessionId) {
        Instant expiry = sessionStore.getIfPresent(sessionId);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry)) {
            sessionStore.invalidate(sessionId);
            return false;
        }
        return true;
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    // ───── URL 处理 ─────

    private String removeTokenParam(HttpServletRequest request) {
        String requestUrl = request.getRequestURL().toString();
        String queryString = request.getQueryString();
        if (queryString == null) return requestUrl;

        String cleanQuery = Arrays.stream(queryString.split("&"))
                .filter(p -> !p.startsWith("token="))
                .reduce((a, b) -> a + "&" + b)
                .orElse(null);

        return cleanQuery != null ? requestUrl + "?" + cleanQuery : requestUrl;
    }

    // ───── 网络检查 ─────

    /**
     * P1-07: 使用 InetAddress 进行科学的私有网络判断，替代字符串前缀匹配。
     */
    private boolean isPrivateNetwork(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            log.warn("Failed to resolve IP address: {}", ip);
            return false;
        }
    }

    // ───── Token 持久化 ─────

    private String loadExistingToken() {
        try {
            if (!Files.exists(TOKEN_FILE)) return null;
            return Files.readString(TOKEN_FILE).trim();
        } catch (Exception e) {
            log.warn("Failed to load existing token: {}", e.getMessage());
            return null;
        }
    }

    private void saveToken() {
        try {
            Files.createDirectories(TOKEN_FILE.getParent());
            Files.writeString(TOKEN_FILE, accessToken);
            // POSIX 系统: chmod 600
            try {
                Files.setPosixFilePermissions(TOKEN_FILE,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: 跳过 POSIX 权限设置
            }
        } catch (IOException e) {
            log.warn("Failed to save access token: {}", e.getMessage());
        }
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isSiteLocalAddress() && !addr.getHostAddress().contains(":")) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }

    /**
     * 获取当前 Access Token — 供其他组件使用（如 WebSocket 认证）。
     */
    public String getAccessToken() {
        return accessToken;
    }

    /** 静态资源路径不拦截 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/assets/") || path.startsWith("/icons/")
                || path.equals("/favicon.ico");
    }
}

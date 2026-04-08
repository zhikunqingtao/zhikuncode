package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * McpAuthTool — 动态生成的 MCP OAuth 认证工具。
 * <p>
 * 每个需要 OAuth 认证的 MCP 服务器自动生成一个实例。
 * 工具名格式: mcp__&lt;serverName&gt;__authenticate
 * <p>
 * OAuth 流程 (RFC 9728 + RFC 8414 + PKCE):
 * <ol>
 *     <li>OAuth 发现 — Protected Resource Metadata / Authorization Server Metadata</li>
 *     <li>PKCE 参数生成 — code_verifier + code_challenge (S256)</li>
 *     <li>构建授权 URL — 打开浏览器</li>
 *     <li>启动本地回调服务器 — 等待授权码</li>
 *     <li>令牌交换 — authorization_code → access_token + refresh_token</li>
 *     <li>令牌存储 — 加密存储到本地文件</li>
 *     <li>重新连接 MCP 服务器 — 携带 Bearer token</li>
 * </ol>
 *
 * @see <a href="SPEC section 4.1.8">McpAuthTool</a>
 */
public class McpAuthTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpAuthTool.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);

    private final String serverName;
    private final String serverUrl;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 令牌存储目录 */
    private static final Path TOKEN_DIR = Path.of(
            System.getProperty("user.home"), ".claude", "mcp-tokens");

    public McpAuthTool(String serverName, String serverUrl,
                       McpClientManager mcpClientManager, ObjectMapper objectMapper) {
        this.serverName = serverName;
        this.serverUrl = serverUrl;
        this.mcpClientManager = mcpClientManager;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Override
    public String getName() {
        return "mcp__" + serverName + "__authenticate";
    }

    @Override
    public String getDescription() {
        return "Authenticate with MCP server '" + serverName +
                "' using OAuth. This will open a browser window for authorization.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    @Override
    public String getGroup() {
        return "mcp";
    }

    @Override
    public boolean isMcp() {
        return true;
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.CONDITIONAL;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return false; // 认证涉及浏览器交互
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        try {
            log.info("Starting OAuth authentication for MCP server: {}", serverName);

            // Step 1: OAuth 发现
            OAuthEndpoints endpoints = discoverOAuthEndpoints();
            if (endpoints == null) {
                return ToolResult.error("Failed to discover OAuth endpoints for server: " + serverName);
            }

            // Step 2: PKCE 参数生成
            PkceParams pkce = generatePkceParams();

            // Step 3: 构建授权 URL
            int callbackPort = findAvailablePort();
            String redirectUri = "http://localhost:" + callbackPort + "/callback";
            String state = generateRandomString(32);

            String authUrl = buildAuthorizationUrl(
                    endpoints.authorizationEndpoint(), pkce.codeChallenge(),
                    redirectUri, state);

            // Step 4: 启动本地回调服务器并打开浏览器
            CompletableFuture<String> codeFuture = startCallbackServer(callbackPort, state);

            // 打开浏览器
            openBrowser(authUrl);

            // Step 5: 等待授权码
            String authCode;
            try {
                authCode = codeFuture.get(CALLBACK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                return ToolResult.error("OAuth authorization timed out. Please try again.");
            }

            // Step 6: 令牌交换
            OAuthTokens tokens = exchangeCodeForTokens(
                    endpoints.tokenEndpoint(), authCode,
                    redirectUri, pkce.codeVerifier());

            if (tokens == null) {
                return ToolResult.error("Failed to exchange authorization code for tokens.");
            }

            // Step 7: 令牌存储
            storeTokens(tokens);

            // Step 8: 重新连接
            log.info("OAuth authentication successful for MCP server: {}", serverName);
            return ToolResult.success(
                    "Successfully authenticated with MCP server '" + serverName + "'. " +
                    "The connection will be re-established with the new credentials.");

        } catch (Exception e) {
            log.error("OAuth authentication failed for {}: {}", serverName, e.getMessage(), e);
            return ToolResult.error("Authentication failed: " + e.getMessage());
        }
    }

    // ==================== OAuth 发现 ====================

    /**
     * OAuth 发现流程 (RFC 9728 + RFC 8414)。
     */
    private OAuthEndpoints discoverOAuthEndpoints() {
        try {
            // 1. 尝试 RFC 9728 Protected Resource Metadata
            String prMetadataUrl = serverUrl + "/.well-known/oauth-protected-resource";
            var prResponse = httpGet(prMetadataUrl);
            if (prResponse != null) {
                var prJson = objectMapper.readTree(prResponse);
                if (prJson.has("authorization_servers")) {
                    String authServer = prJson.get("authorization_servers").get(0).asText();
                    return discoverFromAuthServer(authServer);
                }
            }

            // 2. 尝试 RFC 8414 Authorization Server Metadata
            return discoverFromAuthServer(serverUrl);

        } catch (Exception e) {
            log.warn("OAuth discovery failed for {}: {}", serverName, e.getMessage());
            return null;
        }
    }

    private OAuthEndpoints discoverFromAuthServer(String authServer) throws Exception {
        String metadataUrl = authServer + "/.well-known/oauth-authorization-server";
        String response = httpGet(metadataUrl);
        if (response != null) {
            var json = objectMapper.readTree(response);
            return new OAuthEndpoints(
                    json.has("authorization_endpoint") ? json.get("authorization_endpoint").asText() : null,
                    json.has("token_endpoint") ? json.get("token_endpoint").asText() : null,
                    json.has("revocation_endpoint") ? json.get("revocation_endpoint").asText() : null
            );
        }
        return null;
    }

    // ==================== PKCE ====================

    private PkceParams generatePkceParams() {
        String codeVerifier = generateRandomString(32);
        String codeChallenge = computeS256Challenge(codeVerifier);
        return new PkceParams(codeVerifier, codeChallenge);
    }

    private String computeS256Challenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== 授权 URL ====================

    private String buildAuthorizationUrl(String authEndpoint, String codeChallenge,
                                          String redirectUri, String state) {
        return authEndpoint + "?" +
                "client_id=" + URLEncoder.encode("mcp-" + serverName, StandardCharsets.UTF_8) +
                "&response_type=code" +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8) +
                "&code_challenge_method=S256" +
                "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    // ==================== 回调服务器 ====================

    private CompletableFuture<String> startCallbackServer(int port, String expectedState) {
        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            try (var server = new ServerSocket(port)) {
                server.setSoTimeout((int) CALLBACK_TIMEOUT.toMillis());
                try (var socket = server.accept()) {
                    var reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream()));
                    String requestLine = reader.readLine();

                    // 解析 GET /callback?code=xxx&state=yyy
                    if (requestLine != null && requestLine.startsWith("GET /callback?")) {
                        String queryString = requestLine.split(" ")[1].substring("/callback?".length());
                        Map<String, String> params = parseQueryString(queryString);

                        String code = params.get("code");
                        String state = params.get("state");

                        // 发送响应
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                                "<html><body><h1>Authentication Successful</h1>" +
                                "<p>You can close this window.</p></body></html>";
                        socket.getOutputStream().write(response.getBytes());

                        if (expectedState.equals(state) && code != null) {
                            codeFuture.complete(code);
                        } else {
                            codeFuture.completeExceptionally(
                                    new RuntimeException("State mismatch or missing code"));
                        }
                    }
                }
            } catch (Exception e) {
                codeFuture.completeExceptionally(e);
            }
        });

        return codeFuture;
    }

    // ==================== 令牌交换 ====================

    private OAuthTokens exchangeCodeForTokens(String tokenEndpoint, String code,
                                                String redirectUri, String codeVerifier) {
        try {
            String body = "grant_type=authorization_code" +
                    "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                    "&client_id=" + URLEncoder.encode("mcp-" + serverName, StandardCharsets.UTF_8) +
                    "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var json = objectMapper.readTree(response.body());
                return new OAuthTokens(
                        json.get("access_token").asText(),
                        json.has("refresh_token") ? json.get("refresh_token").asText() : null,
                        json.has("expires_in") ? json.get("expires_in").asLong() : 3600,
                        Instant.now()
                );
            }

            log.error("Token exchange failed: {} {}", response.statusCode(), response.body());
            return null;
        } catch (Exception e) {
            log.error("Token exchange error: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 令牌存储 ====================

    private void storeTokens(OAuthTokens tokens) throws IOException {
        Files.createDirectories(TOKEN_DIR);
        String hash = serverName.hashCode() + "";
        Path tokenFile = TOKEN_DIR.resolve(hash + ".json");

        Map<String, Object> tokenData = Map.of(
                "server_name", serverName,
                "server_url", serverUrl,
                "access_token", tokens.accessToken(),
                "refresh_token", tokens.refreshToken() != null ? tokens.refreshToken() : "",
                "expires_at", tokens.createdAt().plusSeconds(tokens.expiresIn()).toString(),
                "created_at", tokens.createdAt().toString()
        );

        Files.writeString(tokenFile, objectMapper.writeValueAsString(tokenData));
        log.info("OAuth tokens stored for server: {}", serverName);
    }

    // ==================== 工具方法 ====================

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(HTTP_TIMEOUT)
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            log.debug("HTTP GET failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                // 回退: 使用系统命令
                Runtime.getRuntime().exec(new String[]{"open", url});
            }
        } catch (Exception e) {
            log.warn("Failed to open browser: {}", e.getMessage());
        }
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String generateRandomString(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new HashMap<>();
        for (String param : queryString.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                params.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    // ==================== 内部记录类型 ====================

    record OAuthEndpoints(String authorizationEndpoint, String tokenEndpoint,
                          String revocationEndpoint) {}

    record PkceParams(String codeVerifier, String codeChallenge) {}

    record OAuthTokens(String accessToken, String refreshToken,
                       long expiresIn, Instant createdAt) {}
}

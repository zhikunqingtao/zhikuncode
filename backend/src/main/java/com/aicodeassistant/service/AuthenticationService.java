package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LLM API 认证服务 (§9.1.1) — 认证优先级链。
 * <p>
 * 按优先级从高到低依次检查:
 * <ol>
 *   <li>LLM_API_KEY 环境变量 — 通用 LLM API 密钥 (P0 推荐)</li>
 *   <li>OPENAI_API_KEY 环境变量 — OpenAI 兼容密钥</li>
 *   <li>配置文件 API Key — llm.openai.api-key</li>
 *   <li>CLAUDE_CODE_OAUTH_TOKEN 环境变量 — OAuth Token</li>
 * </ol>
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    @Value("${llm.openai.api-key:}")
    private String configApiKey;

    private static final Path CONFIG_FILE = Path.of(
            System.getProperty("user.home"), ".config", "ai-code-assistant", "config.json");

    /**
     * LLM API 认证 — 按优先级链检查，首个命中的源为最终认证方式。
     */
    public AuthResult authenticate() {

        // 1. LLM_API_KEY — 通用 LLM 密钥 (P0 推荐)
        String llmKey = System.getenv("LLM_API_KEY");
        if (llmKey != null && !llmKey.isBlank()) {
            log.debug("Auth: using LLM_API_KEY");
            return AuthResult.apiKey(llmKey, "LLM_API_KEY");
        }

        // 2. OPENAI_API_KEY — OpenAI 兼容 API 密钥
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            log.debug("Auth: using OPENAI_API_KEY");
            return AuthResult.apiKey(openaiKey, "OPENAI_API_KEY");
        }

        // 3. 配置文件 API Key (Spring 配置值)
        if (configApiKey != null && !configApiKey.isBlank()) {
            log.debug("Auth: using config api-key");
            return AuthResult.apiKey(configApiKey, "config");
        }

        // 4. 从配置文件读取
        String fileKey = loadApiKeyFromConfig();
        if (fileKey != null) {
            log.debug("Auth: using config file api-key");
            return AuthResult.apiKey(fileKey, "config_file");
        }

        // 5. CLAUDE_CODE_OAUTH_TOKEN — OAuth Token 注入
        String oauthToken = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        if (oauthToken != null && !oauthToken.isBlank()) {
            log.debug("Auth: using CLAUDE_CODE_OAUTH_TOKEN");
            return AuthResult.oauthToken(oauthToken);
        }

        log.warn("No LLM API authentication found");
        return AuthResult.unauthenticated();
    }

    /**
     * 快速检查是否已配置 API Key。
     */
    public boolean hasApiKey() {
        return authenticate().isAuthenticated();
    }

    private String loadApiKeyFromConfig() {
        try {
            if (!Files.exists(CONFIG_FILE)) return null;
            String content = Files.readString(CONFIG_FILE);
            // 简单 JSON 提取: "apiKey": "xxx"
            int idx = content.indexOf("\"apiKey\"");
            if (idx < 0) return null;
            int colonIdx = content.indexOf(":", idx);
            if (colonIdx < 0) return null;
            int startQuote = content.indexOf("\"", colonIdx + 1);
            if (startQuote < 0) return null;
            int endQuote = content.indexOf("\"", startQuote + 1);
            if (endQuote < 0) return null;
            String key = content.substring(startQuote + 1, endQuote);
            return key.isBlank() ? null : key;
        } catch (Exception e) {
            log.warn("Failed to load API key from config file: {}", e.getMessage());
            return null;
        }
    }

    // ───── 认证结果 ─────

    /**
     * 认证结果。
     */
    public record AuthResult(
            boolean isAuthenticated,
            String apiKey,
            String oauthToken,
            String source
    ) {
        public static AuthResult apiKey(String key, String source) {
            return new AuthResult(true, key, null, source);
        }

        public static AuthResult oauthToken(String token) {
            return new AuthResult(true, null, token, "oauth");
        }

        public static AuthResult unauthenticated() {
            return new AuthResult(false, null, null, "none");
        }

        /**
         * 获取有效的认证凭据（API Key 或 OAuth Token）。
         */
        public String getCredential() {
            return apiKey != null ? apiKey : oauthToken;
        }
    }
}

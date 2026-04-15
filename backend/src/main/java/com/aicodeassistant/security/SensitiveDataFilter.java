package com.aicodeassistant.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感信息过滤器 — 在工具结果返回前过滤敏感信息。
 * <p>
 * 支持 10 种敏感信息模式检测与自动脱敏：
 * <ol>
 *   <li>OpenAI API Key (sk-...)</li>
 *   <li>AWS Access Key ID (AKIA...)</li>
 *   <li>GitHub Personal Access Token (ghp_...)</li>
 *   <li>GitLab Personal Access Token (glpat-...)</li>
 *   <li>Anthropic API Key (sk-ant-...)</li>
 *   <li>Slack Token (xox...)</li>
 *   <li>通用凭证 key=value 格式 (api_key, secret, password, token, auth, credential)</li>
 *   <li>JWT Token (eyJ...)</li>
 *   <li>PEM 私钥头 (RSA/EC/DSA/OPENSSH)</li>
 *   <li>数据库连接串 (mongodb/postgres/mysql/redis)</li>
 * </ol>
 *
 * @see <a href="SPEC §3.5">敏感信息过滤</a>
 */
@Component
public class SensitiveDataFilter {

    private static final String REDACTED = "***REDACTED***";

    private static final List<Pattern> PATTERNS = List.of(
            // OpenAI API Key
            Pattern.compile("(sk-[a-zA-Z0-9]{20,})"),
            // AWS Access Key ID
            Pattern.compile("(AKIA[0-9A-Z]{16})"),
            // GitHub Personal Access Token
            Pattern.compile("(ghp_[a-zA-Z0-9]{36})"),
            // GitLab Personal Access Token
            Pattern.compile("(glpat-[a-zA-Z0-9_-]{20,})"),
            // Anthropic API Key
            Pattern.compile("(sk-ant-[a-zA-Z0-9_-]{20,})"),
            // Slack Token
            Pattern.compile("(xox[bpsar]-[a-zA-Z0-9-]{10,})"),
            // Generic key=value secrets
            Pattern.compile("(?i)(api[_-]?key|secret|password|token|auth|credential)\\s*[=:]\\s*['\"]?([^\\s'\"]{8,})"),
            // JWT Token
            Pattern.compile("(eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,})"),
            // PEM Private Key header
            Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
            // Connection strings with passwords
            Pattern.compile("(?i)(mongodb|postgres|mysql|redis)://[^:]+:([^@]{4,})@")
    );

    /**
     * 过滤敏感信息 — 将匹配的内容替换为 ***REDACTED***。
     *
     * @param content 原始内容
     * @return 过滤后的内容
     */
    public String filter(String content) {
        if (content == null) return null;
        String result = content;
        for (Pattern p : PATTERNS) {
            result = p.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }
}

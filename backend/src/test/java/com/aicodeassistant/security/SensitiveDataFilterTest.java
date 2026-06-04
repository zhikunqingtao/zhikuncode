package com.aicodeassistant.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SensitiveDataFilter 单元测试 — 覆盖 16 种敏感信息模式的脱敏。
 */
class SensitiveDataFilterTest {

    private final SensitiveDataFilter filter = new SensitiveDataFilter();

    // ===== 基础行为 =====

    @Test
    @DisplayName("null 输入安全处理")
    void nullInput_returnsNull() {
        assertNull(filter.filter(null));
    }

    @Test
    @DisplayName("空字符串保持不变")
    void emptyInput_returnsEmpty() {
        assertEquals("", filter.filter(""));
    }

    @Test
    @DisplayName("非敏感内容不受影响")
    void plainText_unchanged() {
        String input = "Hello World, this is a normal log line with no secrets.";
        assertEquals(input, filter.filter(input));
    }

    // ===== 16 种模式逐一覆盖 =====

    @Test
    @DisplayName("OpenAI sk-proj-* 脱敏 — 前缀取前 4 字符 sk-p")
    void openAiProjectKey_redacted() {
        String token = "sk-proj-abcdef1234567890123456789";
        String out = filter.filter("prefix " + token + " end");
        assertFalse(out.contains(token));
        assertTrue(out.contains("sk-p***REDACTED-"),
                "应包含 sk-p 前缀的脱敏标记: " + out);
        assertTrue(out.startsWith("prefix "));
        assertTrue(out.endsWith(" end"));
    }

    @Test
    @DisplayName("OpenAI 通用 sk-* 脱敏 — 前缀取前 4 字符 sk-a")
    void openAiGenericKey_redacted() {
        String token = "sk-abcdefghijklmnopqrstuvwxyz";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("sk-a***REDACTED-"));
    }

    @Test
    @DisplayName("AWS Access Key ID 脱敏 → AKIA***REDACTED-XX***")
    void awsAccessKey_redacted() {
        String token = "AKIAIOSFODNN7EXAMPLE";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("AKIA***REDACTED-"));
    }

    @Test
    @DisplayName("GitHub PAT ghp_* 脱敏 → ghp_***REDACTED-XX***")
    void githubPat_redacted() {
        String token = "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; // 36 chars after ghp_
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("ghp_***REDACTED-"));
    }

    @Test
    @DisplayName("GitLab PAT glpat-* 脱敏 → glpa***REDACTED-XX***")
    void gitlabPat_redacted() {
        String token = "glpat-xxxxxxxxxxxxxxxxxxxx";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("glpa***REDACTED-"));
    }

    @Test
    @DisplayName("Anthropic sk-ant-* 脱敏 — 由 sk- 模式优先命中，前缀 sk-a")
    void anthropicKey_redacted() {
        String token = "sk-ant-api03-xxxxxxxxxxxxxxxxxxxxx";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("sk-a"));
        assertTrue(out.contains("***REDACTED-"));
    }

    @Test
    @DisplayName("Slack xoxb-* 脱敏 → xoxb***REDACTED-XX***")
    void slackToken_redacted() {
        String token = "xoxb-xxxxxxxxxxxx";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("xoxb***REDACTED-"));
    }

    @Test
    @DisplayName("通用 key=value 凭证 — 整个匹配被替换，密钥值不再可见")
    void genericKeyValue_redacted() {
        // 注意：正则会消费 "api_key=" 前缀，replaceAll 用脱敏值替换整个匹配。
        String secret = "supersecretvalue123";
        String out = filter.filter("api_key=" + secret);
        assertFalse(out.contains(secret));
        assertTrue(out.contains("***REDACTED-"));
    }

    @Test
    @DisplayName("JWT Token 脱敏 — eyJ 前缀")
    void jwtToken_redacted() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.abc123def456";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("eyJh"));
        assertTrue(out.contains("***REDACTED-"));
    }

    @Test
    @DisplayName("PEM 私钥头部脱敏 — group(1) 为算法标记 'RSA '")
    void pemPrivateKey_redacted() {
        String header = "-----BEGIN RSA PRIVATE KEY-----";
        String out = filter.filter(header + "\nMIIEvQIBADANB...");
        assertFalse(out.contains(header));
        // 替换格式：prefix("RSA ") + ***REDACTED-N***
        assertTrue(out.contains("***REDACTED-"),
                "PEM 头部应被脱敏: " + out);
    }

    @Test
    @DisplayName("数据库连接串密码脱敏 — 整段 URL 前缀+@ 被消费，密码消失")
    void dbConnectionString_redacted() {
        // 正则匹配整个 "postgres://user:mypassword@" → 替换为 prefix("mypa") + ***REDACTED-N***
        String pwd = "mypassword";
        String out = filter.filter("postgres://user:" + pwd + "@host/db");
        assertFalse(out.contains(pwd));
        assertTrue(out.contains("***REDACTED-"));
        assertTrue(out.endsWith("host/db"));
    }

    @Test
    @DisplayName("HuggingFace hf_* 脱敏 → hf_***REDACTED-XX***")
    void huggingFaceToken_redacted() {
        String token = "hf_abcdefghijklmnopqrstuvwx";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("hf_***REDACTED-"));
    }

    @Test
    @DisplayName("GCP AIza* 脱敏 → AIza***REDACTED-XX***")
    void gcpApiKey_redacted() {
        // AIza + 35 chars = 39 chars 总长
        String token = "AIzaTESTONLY00000000000000000000000FAKE"; // 39
        assertEquals(39, token.length(), "测试数据应严格 39 字符");
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("AIza***REDACTED-"));
    }

    @Test
    @DisplayName("Stripe sk_live_* 脱敏 — 前缀以 sk_ 开头")
    void stripeKey_redacted() {
        // 注意：源码中通过字符串拼接构造，避免触发 GitHub Push Protection 的 Stripe Key 检测；
        //       运行时拼接后仍可命中正则 sk_live_[a-zA-Z0-9]{24,}。
        String token = "sk_" + "live_" + "TESTONLY00000000000000000";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        // 前缀截取规则：第一个下划线在第3位（sk_），前3+1=4字符 → "sk_l"... 实际逻辑：
        // underscoreIdx=2，substring(0,3)="sk_"。文档允许 "sk_l" 或 "sk_"
        assertTrue(out.startsWith("sk_"));
        assertTrue(out.contains("***REDACTED-"));
    }

    @Test
    @DisplayName("Vercel vercel_* 脱敏 — 下划线在第 6 位 (<8)，前缀为 vercel_")
    void vercelToken_redacted() {
        String token = "vercel_abcdefghijklmnopqrstuvwx";
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("vercel_***REDACTED-"));
    }

    @Test
    @DisplayName("Supabase sbp_* 脱敏 → sbp_***REDACTED-XX***")
    void supabaseKey_redacted() {
        String token = "sbp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; // sbp_ + 44 chars
        String out = filter.filter(token);
        assertFalse(out.contains(token));
        assertTrue(out.startsWith("sbp_***REDACTED-"));
    }

    // ===== 组合场景 =====

    @Test
    @DisplayName("多个敏感信息在同一字符串中均被脱敏")
    void multipleSecrets_allRedacted() {
        String input = "openai=sk-abcdefghijklmnopqrstuvwxyz "
                + "aws=AKIAIOSFODNN7EXAMPLE "
                + "gh=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        String out = filter.filter(input);
        assertFalse(out.contains("sk-abcdefghijklmnopqrstuvwxyz"));
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"));
        assertFalse(out.contains("ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));
        // sk- 无下划线，前缀为前 4 字符 "sk-a"
        assertTrue(out.contains("sk-a***REDACTED-"));
        assertTrue(out.contains("AKIA***REDACTED-"));
        assertTrue(out.contains("ghp_***REDACTED-"));
    }

    @Test
    @DisplayName("脱敏格式包含长度标记")
    void redactedFormat_includesLength() {
        String token = "sk-abcdefghijklmnopqrstuvwxyz"; // 长度 29
        String out = filter.filter(token);
        // 格式应为 prefix + ***REDACTED-<len>***
        assertTrue(out.matches(".*\\*\\*\\*REDACTED-\\d+\\*\\*\\*.*"),
                "脱敏输出应包含长度标记: " + out);
    }
}

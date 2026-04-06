package com.aicodeassistant.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RemoteAccessSecurityFilter 集成测试 — 验证三层递进认证流程。
 * <p>
 * 测试场景:
 * <ol>
 *   <li>localhost (127.0.0.1) 免认证通过</li>
 *   <li>Bearer Token 认证 → 签发 Cookie</li>
 *   <li>Cookie 认证通过</li>
 *   <li>无认证 → 401</li>
 *   <li>错误 Token → 401</li>
 *   <li>静态资源不拦截</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RemoteAccessSecurityFilter securityFilter;

    // ═══════════════ 层级 1: localhost 免认证 ═══════════════

    @Test
    @DisplayName("localhost 请求应直接放行 (层级 1 免认证)")
    void localhost_shouldBypass() throws Exception {
        // MockMvc 默认 remoteAddr=127.0.0.1 → localhost 免认证
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("localhost 请求无需 Token 也能访问任何 API")
    void localhost_shouldAccessAnyEndpoint() throws Exception {
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    // ═══════════════ 层级 2: Bearer Token 认证 ═══════════════

    @Test
    @DisplayName("Bearer Token 正确 → 放行并签发 Cookie")
    void bearerToken_valid_shouldPassAndIssueCookie() throws Exception {
        String token = securityFilter.getAccessToken();

        MvcResult result = mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // 验证 Set-Cookie 头
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("ai-coder-session");
        assertThat(setCookie).contains("HttpOnly");
    }

    @Test
    @DisplayName("Bearer Token 错误 → 401")
    void bearerToken_invalid_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        })
                        .header("Authorization", "Bearer invalid-token-value"))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════ Cookie 认证 ═══════════════

    @Test
    @DisplayName("有效 Cookie → 认证通过")
    void cookie_valid_shouldPass() throws Exception {
        String token = securityFilter.getAccessToken();

        // 第一步: Bearer 认证获取 Cookie
        MvcResult first = mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // 提取 cookie 值
        String setCookie = first.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        String cookieValue = setCookie.split(";")[0].split("=", 2)[1];

        // 第二步: 使用 Cookie 认证
        mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        })
                        .cookie(new Cookie("ai-coder-session", cookieValue)))
                .andExpect(status().isOk());
    }

    // ═══════════════ 无认证 → 401 ═══════════════

    @Test
    @DisplayName("非 localhost 无 Token 无 Cookie → 401")
    void noAuth_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════ URL Token 认证 ═══════════════

    @Test
    @DisplayName("URL 参数 token 正确 → 重定向 (去掉 token 参数)")
    void urlToken_valid_shouldRedirect() throws Exception {
        String token = securityFilter.getAccessToken();

        mockMvc.perform(get("/api/health?token=" + token)
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        }))
                .andExpect(status().is3xxRedirection());
    }

    // ═══════════════ 非私有网段 → 403 ═══════════════

    @Test
    @DisplayName("公网 IP → 403 Forbidden")
    void publicNetwork_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/health")
                        .with(request -> {
                            request.setRemoteAddr("8.8.8.8");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    // ═══════════════ 静态资源 shouldNotFilter ═══════════════

    @Test
    @DisplayName("静态资源路径不拦截 (/assets/, /favicon.ico)")
    void staticResources_shouldNotFilter() throws Exception {
        // /favicon.ico 不经过安全过滤器 — 不会返回 401/403（安全拒绝）
        // 可能返回 404（无文件）或 500（其他内部异常），但绝不应是 401/403
        mockMvc.perform(get("/favicon.ico")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.100");
                            return request;
                        }))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isNotIn(401, 403);
                });
    }
}

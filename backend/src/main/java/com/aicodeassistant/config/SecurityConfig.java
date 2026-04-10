package com.aicodeassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置 (§9.1)。
 * <p>
 * 三层递进认证:
 * <ul>
 *   <li>层级 1 (P0): localhost 免认证 — 由 {@link RemoteAccessSecurityFilter} 处理</li>
 *   <li>层级 2 (P1): 局域网 Token 认证 — 由 {@link RemoteAccessSecurityFilter} 处理</li>
 *   <li>层级 3 (P2): 云端 JWT + 用户体系 — 本期不实现</li>
 * </ul>
 * <p>
 * Spring Security FilterChain 配置:
 * <ul>
 *   <li>CSRF 禁用 (API-only 无状态服务)</li>
 *   <li>CORS 开放 (前端 dev server 跨域)</li>
 *   <li>所有请求 permitAll (认证由 RemoteAccessSecurityFilter 处理)</li>
 *   <li>无 Session 创建 (STATELESS)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 禁用 — API-only 服务，使用 Token 认证
                .csrf(csrf -> csrf.disable())
                // CORS 配置
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态 — 不创建 HttpSession
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 所有请求放行 — 认证由 RemoteAccessSecurityFilter 处理
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                // CSP 安全头 + 禁止 iframe 嵌入
                .headers(headers -> headers
                    .contentSecurityPolicy(csp -> csp
                        .policyDirectives(
                            "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "connect-src 'self' ws: wss:; " +
                            "img-src 'self' data: blob:; " +
                            "font-src 'self' data:;")
                    )
                    .frameOptions(frame -> frame.deny())
                );
        return http.build();
    }

    /**
     * CORS 配置 — 显式白名单，禁止全开放。
     * <p>
     * 默认允许 localhost:5173 (Vite dev) 和 localhost:8080 (后端)。
     * 额外来源通过环境变量 CORS_ALLOWED_ORIGINS 配置。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 显式指定允许的来源 — 对齐最小权限原则
        List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:5173",   // Vite dev server
            "http://localhost:8080",   // 后端前端
            "http://127.0.0.1:5173",
            "http://127.0.0.1:8080"
        ));
        // 从环境变量读取额外允许来源
        String extraOrigins = System.getenv("CORS_ALLOWED_ORIGINS");
        if (extraOrigins != null && !extraOrigins.isBlank()) {
            Arrays.stream(extraOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(allowedOrigins::add);
        }
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Session-Id"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Set-Cookie", "X-Session-Id"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

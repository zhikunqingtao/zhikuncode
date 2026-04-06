package com.aicodeassistant.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AuthController — 三层递进认证。
 * <p>
 * P0 (localhost): 无需认证，此 Controller 仅返回状态信息。
 * P1 (lan_token): 提供 /api/auth/token 端点用于获取/验证 Bearer Token。
 * P2 (jwt): 提供完整 login/logout/refresh 流程 (暂不实现)。
 *
 * @see <a href="SPEC §6.1.1">认证 API</a>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${auth.mode:localhost}")
    private String authMode;

    @Value("${auth.lan-token:}")
    private String lanToken;

    /**
     * 获取当前认证状态 — P0/P1/P2 均可用。
     */
    @GetMapping("/status")
    public ResponseEntity<AuthStatusResponse> status(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return switch (authMode) {
            case "localhost" -> ResponseEntity.ok(
                    new AuthStatusResponse(true, "localhost", "localhost-user"));
            case "lan_token" -> {
                boolean valid = authHeader != null
                        && authHeader.startsWith("Bearer ")
                        && lanToken.equals(authHeader.substring(7));
                yield ResponseEntity.ok(
                        new AuthStatusResponse(valid, "lan_token", valid ? "lan-user" : null));
            }
            default -> ResponseEntity.ok(
                    new AuthStatusResponse(false, authMode, null));
        };
    }

    /**
     * 获取 LAN Token — P1 专用，仅 localhost 可调用。
     */
    @GetMapping("/token")
    public ResponseEntity<?> getToken(HttpServletRequest request) {
        if (!"lan_token".equals(authMode)) {
            return ResponseEntity.status(404).body(Map.of("error", "Token auth not enabled"));
        }
        // 安全: 仅允许 localhost 获取 token
        String remoteAddr = request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddr) && !"::1".equals(remoteAddr)) {
            return ResponseEntity.status(403).body(
                    Map.of("error", "Token can only be retrieved from localhost"));
        }
        return ResponseEntity.ok(Map.of("token", lanToken));
    }

    // ═══ DTO Records ═══

    public record AuthStatusResponse(
            boolean authenticated,
            String authMode,
            String username
    ) {}
}

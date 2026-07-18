package com.aicodeassistant.controller;

import com.aicodeassistant.authorization.PermissionGrantRepository;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 已完成权限交互所创建授权的查询与撤销 API。 */
@RestController
@RequestMapping("/api/permissions/grants")
public class PermissionGrantController {
    private static final Logger log = LoggerFactory.getLogger(PermissionGrantController.class);

    private final PermissionGrantRepository grants;
    private final SessionAccessAuthorizer access;

    public PermissionGrantController(PermissionGrantRepository grants,
                                     SessionAccessAuthorizer access) {
        this.grants = grants;
        this.access = access;
    }

    @GetMapping
    public Map<String, List<PermissionGrantRepository.GrantView>> listActive(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestParam(defaultValue = "200") int limit) {
        requireAccessibleSession(sessionId);
        return Map.of("grants", grants.listActiveForSession(sessionId, limit));
    }

    @DeleteMapping("/{grantId}")
    public ResponseEntity<Void> revoke(@PathVariable String grantId,
                                       @RequestHeader("X-Session-Id") String sessionId) {
        if (grantId == null || grantId.isBlank() || grantId.length() > 128) {
            return ResponseEntity.badRequest().build();
        }
        if (!access.canAccessSession(sessionId, sessionId)) {
            log.warn("Permission grant revoke access denied: grantId={}, sessionId={}", grantId, sessionId);
            return ResponseEntity.status(403).build();
        }
        if (grants.revokeForSession(grantId, sessionId) == 1) {
            log.info("Permission grant revoked through API: grantId={}, sessionId={}", grantId, sessionId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private void requireAccessibleSession(String sessionId) {
        if (!access.canAccessSession(sessionId, sessionId)) {
            log.warn("Permission grant list access denied: sessionId={}", sessionId);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "SESSION_ACCESS_DENIED");
        }
    }
}

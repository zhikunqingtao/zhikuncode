package com.aicodeassistant.controller;

import com.aicodeassistant.permission.PersistentPermissionGrantStore;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read/revoke API for grants created by resolved permission interactions. */
@RestController
@RequestMapping("/api/permissions/grants")
public class PermissionGrantController {

    private final PersistentPermissionGrantStore grants;
    private final SessionAccessAuthorizer access;

    public PermissionGrantController(PersistentPermissionGrantStore grants,
                                     SessionAccessAuthorizer access) {
        this.grants = grants;
        this.access = access;
    }

    @GetMapping
    public Map<String, List<PersistentPermissionGrantStore.GrantView>> listActive(
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
            return ResponseEntity.status(403).build();
        }
        return grants.revokeForSession(grantId, sessionId) == 1
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private void requireAccessibleSession(String sessionId) {
        if (!access.canAccessSession(sessionId, sessionId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "SESSION_ACCESS_DENIED");
        }
    }
}

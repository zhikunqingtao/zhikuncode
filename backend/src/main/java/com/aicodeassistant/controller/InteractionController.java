package com.aicodeassistant.controller;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 交互恢复查询与数据库 CAS 决策入口。 */
@RestController
@RequestMapping("/api/interactions")
public class InteractionController {
    private static final Logger log = LoggerFactory.getLogger(InteractionController.class);
    private final DurableInteractionService interactions;
    private final SessionAccessAuthorizer access;
    private final ObjectMapper json;

    public InteractionController(DurableInteractionService interactions,
                                 SessionAccessAuthorizer access, ObjectMapper json) {
        this.interactions = interactions;
        this.access = access;
        this.json = json;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<InteractionView>> pending(
            @RequestParam String sessionId,
            @RequestHeader("X-Session-Id") String headerSessionId) {
        if (!access.canAccessSession(sessionId, headerSessionId)) {
            log.warn("Pending interaction access denied: requestedSessionId={}, callerSessionId={}",
                    sessionId, headerSessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(interactions.pendingViews(sessionId));
    }

    @PostMapping("/{interactionId}/decisions")
    public ResponseEntity<?> decide(@PathVariable String interactionId,
                                    @RequestHeader("X-Session-Id") String sessionId,
                                    @RequestBody DecisionRequest body) {
        InteractionRequest current;
        try {
            current = interactions.findById(interactionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            return ResponseEntity.notFound().build();
        }
        if (!access.canAccessSession(current.sessionId(), sessionId)) {
            log.warn("Interaction decision access denied: interactionId={}, ownerSessionId={}, callerSessionId={}",
                    interactionId, current.sessionId(), sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String effectiveDecision = body.decision();
        String effectiveScope = body.scope();
        boolean effectiveRemember = body.remember();
        if (current.type() == InteractionRequest.Type.PERMISSION) {
            try {
                if (body.optionId() == null || body.operationHash() == null) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "PERMISSION_PROTOCOL_MISMATCH"));
                }
                InteractionView authoritative = interactions.view(current);
                if (authoritative.protocolVersion() != 3
                        || !body.operationHash().equals(authoritative.operationHash())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "PERMISSION_PROTOCOL_MISMATCH"));
                }
                var selected = authoritative.options().stream()
                        .filter(option -> body.optionId().equals(option.get("optionId")))
                        .findFirst().orElse(null);
                if (selected == null) return ResponseEntity.badRequest().body(Map.of("code", "PERMISSION_OPTION_NOT_ALLOWED"));
                effectiveDecision = selected.get("decision");
                effectiveScope = selected.getOrDefault("scope", "once");
                effectiveRemember = effectiveDecision.equals("allow") && !effectiveScope.equals("once");
            } catch (Exception invalid) {
                log.error("Stored permission interaction protocol is invalid: interactionId={}",
                        interactionId, invalid);
                return ResponseEntity.internalServerError().body(Map.of("code", "INTERACTION_PROTOCOL_INVALID"));
            }
        }
        InteractionRequest.Status requested;
        if ("allow".equalsIgnoreCase(effectiveDecision) || "answer".equalsIgnoreCase(effectiveDecision)) {
            requested = InteractionRequest.Status.ANSWERED;
        } else if ("deny".equalsIgnoreCase(effectiveDecision)) {
            requested = InteractionRequest.Status.DENIED;
        } else if ("cancel".equalsIgnoreCase(effectiveDecision)) {
            requested = InteractionRequest.Status.CANCELLED;
        } else {
            return ResponseEntity.badRequest().body(Map.of("code", "INTERACTION_DECISION_INVALID"));
        }
        if (current.status() != InteractionRequest.Status.PENDING) {
            return current.status() == requested ? ResponseEntity.ok(current)
                    : ResponseEntity.status(HttpStatus.CONFLICT).body(current);
        }
        String normalizedDecision = requested == InteractionRequest.Status.ANSWERED
                ? (current.type() == InteractionRequest.Type.PERMISSION ? "allow" : "answer")
                : requested == InteractionRequest.Status.DENIED ? "deny" : "cancel";
        try {
            if (current.type() != InteractionRequest.Type.PERMISSION) {
                boolean allowed = false;
                for (var option : json.readTree(current.allowedDecisionsJson())) {
                    if (normalizedDecision.equalsIgnoreCase(option.asText())) allowed = true;
                }
                if (!allowed) return ResponseEntity.badRequest().body(Map.of("code", "INTERACTION_DECISION_NOT_ALLOWED"));
            }
            if (effectiveRemember && current.type() != InteractionRequest.Type.PERMISSION) {
                String requestedScope = effectiveScope == null ? "session" : effectiveScope.toLowerCase();
                boolean scopeAllowed = false;
                for (var option : json.readTree(current.scopeOptionsJson())) {
                    if (requestedScope.equalsIgnoreCase(option.asText())) scopeAllowed = true;
                }
                if (!scopeAllowed) return ResponseEntity.badRequest().body(Map.of("code", "PERMISSION_SCOPE_NOT_ALLOWED"));
            }
        } catch (Exception invalidStoredProtocol) {
            log.error("Stored interaction options are invalid: interactionId={}, type={}",
                    interactionId, current.type(), invalidStoredProtocol);
            return ResponseEntity.internalServerError().body(Map.of("code", "INTERACTION_PROTOCOL_INVALID"));
        }
        Object response;
        if (current.type() == InteractionRequest.Type.PERMISSION) {
            Map<String, Object> permissionResponse = new java.util.LinkedHashMap<>();
            permissionResponse.put("decision", normalizedDecision);
            permissionResponse.put("remember", effectiveRemember);
            permissionResponse.put("scope", effectiveRemember
                    ? (effectiveScope == null ? "session" : effectiveScope.toLowerCase())
                    : "once");
            permissionResponse.put("optionId", body.optionId());
            permissionResponse.put("operationHash", body.operationHash());
            permissionResponse.put("deliveryGeneration", body.deliveryGeneration());
            response = Map.copyOf(permissionResponse);
        } else {
            response = body.response() == null
                    ? Map.of("decision", normalizedDecision)
                    : body.response();
        }
        InteractionRequest decided;
        try {
            String reasonCode = requested == InteractionRequest.Status.ANSWERED
                    ? "USER_APPROVED" : requested == InteractionRequest.Status.DENIED
                    ? "USER_DENIED" : "USER_CANCELLED";
            decided = interactions.decideRequest(interactionId, body.expectedVersion(),
                    requested, response, reasonCode);
        } catch (com.aicodeassistant.config.database.SqliteConfig.DatabaseWriteUnavailableException busy) {
            log.warn("Interaction decision database unavailable: interactionId={}, code={}",
                    interactionId, busy.code());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", busy.code()));
        } catch (IllegalArgumentException | IllegalStateException invalidDecision) {
            String code = invalidDecision.getMessage() == null
                    ? "INTERACTION_DECISION_INVALID" : invalidDecision.getMessage();
            HttpStatus status = code.startsWith("PERMISSION_")
                    ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            log.debug("Interaction decision rejected: interactionId={}, code={}, expectedVersion={}",
                    interactionId, code, body.expectedVersion());
            return ResponseEntity.status(status).body(Map.of("code", code));
        }
        if (decided.status() != requested) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(decided);
        }
        return ResponseEntity.ok(decided);
    }

    public record DecisionRequest(long expectedVersion, String decision, Object response,
                                  boolean remember, String scope, String optionId, String operationHash,
                                  int deliveryGeneration) {}
}

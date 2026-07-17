package com.aicodeassistant.controller;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Read-only recovery endpoint; interaction decisions remain database-CAS commands. */
@RestController
@RequestMapping("/api/interactions")
public class InteractionController {
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
        } catch (Exception missing) {
            return ResponseEntity.notFound().build();
        }
        if (!access.canAccessSession(current.sessionId(), sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        InteractionRequest.Status requested;
        if ("allow".equalsIgnoreCase(body.decision()) || "answer".equalsIgnoreCase(body.decision())) {
            requested = InteractionRequest.Status.ANSWERED;
        } else if ("deny".equalsIgnoreCase(body.decision())) {
            requested = InteractionRequest.Status.DENIED;
        } else if ("cancel".equalsIgnoreCase(body.decision())) {
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
            boolean allowed = false;
            for (var option : json.readTree(current.allowedDecisionsJson())) {
                if (normalizedDecision.equalsIgnoreCase(option.asText())) allowed = true;
            }
            if (!allowed) return ResponseEntity.badRequest().body(Map.of("code", "INTERACTION_DECISION_NOT_ALLOWED"));
            if (body.remember()) {
                String requestedScope = body.scope() == null ? "session" : body.scope().toLowerCase();
                boolean scopeAllowed = false;
                for (var option : json.readTree(current.scopeOptionsJson())) {
                    if (requestedScope.equalsIgnoreCase(option.asText())) scopeAllowed = true;
                }
                if (!scopeAllowed) return ResponseEntity.badRequest().body(Map.of("code", "PERMISSION_SCOPE_NOT_ALLOWED"));
            }
        } catch (Exception invalidStoredProtocol) {
            return ResponseEntity.internalServerError().body(Map.of("code", "INTERACTION_PROTOCOL_INVALID"));
        }
        Object response;
        if (current.type() == InteractionRequest.Type.PERMISSION) {
            Map<String, Object> permissionResponse = new java.util.LinkedHashMap<>();
            permissionResponse.put("decision", normalizedDecision);
            permissionResponse.put("remember", body.remember());
            permissionResponse.put("scope", body.remember()
                    ? (body.scope() == null ? "session" : body.scope().toLowerCase())
                    : "once");
            response = Map.copyOf(permissionResponse);
        } else {
            response = body.response() == null
                    ? Map.of("decision", normalizedDecision)
                    : body.response();
        }
        InteractionRequest decided = interactions.decideRequest(interactionId, body.expectedVersion(),
                requested, response, "user_rest");
        if (decided.status() != requested) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(decided);
        }
        return ResponseEntity.ok(decided);
    }

    public record DecisionRequest(long expectedVersion, String decision, Object response,
                                  boolean remember, String scope) {}
}

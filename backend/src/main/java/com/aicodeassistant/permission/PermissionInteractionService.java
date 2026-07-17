package com.aicodeassistant.permission;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Permission-specific facade over the durable interaction authority.
 *
 * <p>This service owns no secondary permission state. It translates permission
 * requests and decisions to and from {@link DurableInteractionService}, which is
 * the sole authority for interaction lifecycle and terminal-state arbitration.</p>
 */
@Service
public class PermissionInteractionService {
    public static final int TIMEOUT_SECONDS = DurableInteractionService.DECISION_SECONDS;
    private final DurableInteractionService interactions;
    private final ObjectMapper json;

    public PermissionInteractionService(DurableInteractionService interactions, ObjectMapper json) {
        this.interactions = interactions;
        this.json = json;
    }

    public void persistRequest(String runId, String sessionId, String toolUseId,
                               String toolName, String riskLevel, String reason,
                               String inputSummary, List<String> scopeOptions,
                               String source, String childSessionId) {
        interactions.create(toolUseId, sessionId, runId, InteractionRequest.Type.PERMISSION,
                Map.of("toolName", value(toolName), "riskLevel", value(riskLevel),
                        "reason", value(reason), "inputSummary", value(inputSummary)),
                List.of("allow", "deny"),
                scopeOptions == null ? List.of() : List.copyOf(scopeOptions),
                source, childSessionId);
    }

    public List<InteractionRequest> getPendingInteractions(String sessionId) {
        return interactions.pending(sessionId);
    }

    public InteractionRequest findInteraction(String interactionId) {
        return interactions.findById(interactionId);
    }

    public InteractionView view(InteractionRequest request) {
        return interactions.view(request);
    }

    public boolean markInteractionDispatched(String interactionId, String transportId) {
        return interactions.markDispatched(interactionId, transportId);
    }

    public List<InteractionRequest> redeliveryCandidates(Instant now) {
        return interactions.redeliveryCandidates(now);
    }

    public boolean claimRedelivery(String interactionId, int expectedAttempts, String transportId) {
        return interactions.claimRedelivery(interactionId, expectedAttempts, transportId);
    }

    public boolean acknowledgeInteraction(String interactionId, int deliveryGeneration, String transportId) {
        return interactions.acknowledgeReceived(interactionId, deliveryGeneration, transportId);
    }

    public InteractionRequest prepareRecoveryDelivery(String interactionId, String transportId) {
        return interactions.prepareRecoveryDelivery(interactionId, transportId);
    }

    public int cancelAll(String reason) { return interactions.cancelAll(reason); }

    public String interactionId(String runId, String toolUseId) {
        InteractionRequest request = interactions.findByCorrelationKey(runId, toolUseId);
        return request == null ? null : request.interactionId();
    }

    public java.util.concurrent.CompletableFuture<com.aicodeassistant.model.PermissionDecision> awaitDecision(
            String runId, String toolUseId) {
        InteractionRequest request = interactions.findByCorrelationKey(runId, toolUseId);
        if (request == null) throw new IllegalArgumentException("Unknown permission interaction: " + toolUseId);
        return interactions.awaitTerminal(request.interactionId()).thenApply(status -> {
            if (status != InteractionRequest.Status.ANSWERED) {
                com.aicodeassistant.model.PermissionDecisionReason reason = switch (status) {
                    case DENIED -> com.aicodeassistant.model.PermissionDecisionReason.USER_DENIED;
                    case EXPIRED -> com.aicodeassistant.model.PermissionDecisionReason.INTERACTION_EXPIRED;
                    case UNDELIVERABLE -> com.aicodeassistant.model.PermissionDecisionReason.INTERACTION_UNDELIVERABLE;
                    case CANCELLED -> com.aicodeassistant.model.PermissionDecisionReason.INTERACTION_CANCELLED;
                    default -> com.aicodeassistant.model.PermissionDecisionReason.OTHER;
                };
                return com.aicodeassistant.model.PermissionDecision.denyByInteraction(
                        reason, "Permission interaction ended: " + status.name().toLowerCase());
            }
            InteractionRequest terminal = interactions.findById(request.interactionId());
            try {
                JsonNode response = terminal.responseJson() == null ? null : json.readTree(terminal.responseJson());
                boolean remember = response != null && response.path("remember").asBoolean(false);
                String scopeValue = response == null ? "session" : response.path("scope").asText("session");
                com.aicodeassistant.model.PermissionScope scope = "workspace".equals(scopeValue)
                        ? com.aicodeassistant.model.PermissionScope.WORKSPACE
                        : com.aicodeassistant.model.PermissionScope.SESSION;
                return com.aicodeassistant.model.PermissionDecision.allow(
                        com.aicodeassistant.model.PermissionDecisionReason.OTHER, null)
                        .withRemember(remember, scope);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid permission response", e);
            }
        });
    }

    private static String value(String value) { return value == null ? "" : value; }
}

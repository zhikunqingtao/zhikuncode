package com.aicodeassistant.engine;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable elicitation adapter; the database is the only decision authority. */
@Service
public class ElicitationService {
    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);
    private final DurableInteractionService interactions;
    private final ObjectMapper json;

    public ElicitationService(DurableInteractionService interactions, ObjectMapper json) {
        this.interactions = interactions;
        this.json = json;
    }

    public ElicitationResponse requestAndWait(String sessionId, String runId, String question,
                                               List<ElicitationOption> options, long ignoredTimeoutMs) {
        String correlation = UUID.randomUUID().toString();
        InteractionRequest request;
        try {
            request = interactions.create(correlation, sessionId, runId,
                    InteractionRequest.Type.ELICITATION,
                    Map.of("question", question, "options", options),
                    List.of("answer", "cancel"), List.of(), "direct", null);
            InteractionRequest.Status status = interactions.awaitTerminal(request.interactionId()).join();
            InteractionRequest terminal = interactions.findById(request.interactionId());
            return switch (status) {
                case ANSWERED -> ElicitationResponse.success(terminal.responseJson() == null ? null
                        : json.readValue(terminal.responseJson(), Object.class));
                case CANCELLED, DENIED -> ElicitationResponse.cancelled();
                case EXPIRED, UNDELIVERABLE -> ElicitationResponse.timeout();
                default -> ElicitationResponse.error("Unexpected interaction state: " + status);
            };
        } catch (Exception e) {
            log.warn("Durable elicitation failed: session={}, error={}", sessionId, e.getMessage());
            return ElicitationResponse.error(e.getMessage());
        }
    }

    /**
     * Source-compatible API for extensions compiled against V1. Durable
     * interactions require a Run id, so callers must migrate instead of
     * creating an unowned database row.
     */
    @Deprecated
    public ElicitationResponse requestAndWait(String sessionId, String question,
                                               List<ElicitationOption> options, long timeoutMs) {
        return ElicitationResponse.error("RUN_ID_REQUIRED");
    }

    public void resolveElicitation(String interactionId, Object response) {
        decide(interactionId, InteractionRequest.Status.ANSWERED, response, "user_answered");
    }
    public void cancelElicitation(String interactionId) {
        decide(interactionId, InteractionRequest.Status.CANCELLED, null, "user_cancelled");
    }
    private void decide(String id, InteractionRequest.Status status, Object response, String reason) {
        try {
            InteractionRequest current = interactions.findById(id);
            interactions.decide(id, current.version(), status, response, reason);
        } catch (Exception e) {
            log.info("Elicitation decision ignored/failed: interactionId={}, error={}", id, e.getMessage());
        }
    }

    public record ElicitationOption(String label, String value, String description) {}
    public record ElicitationResponse(Status status, Object value, String error) {
        public enum Status { SUCCESS, CANCELLED, TIMEOUT, ERROR }
        public static ElicitationResponse success(Object v) { return new ElicitationResponse(Status.SUCCESS,v,null); }
        public static ElicitationResponse cancelled() { return new ElicitationResponse(Status.CANCELLED,null,null); }
        public static ElicitationResponse timeout() { return new ElicitationResponse(Status.TIMEOUT,null,"Request timed out"); }
        public static ElicitationResponse error(String e) { return new ElicitationResponse(Status.ERROR,null,e); }
    }
}

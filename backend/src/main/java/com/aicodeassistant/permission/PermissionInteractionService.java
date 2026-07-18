package com.aicodeassistant.permission;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 持久化交互权威之上的权限专用门面。
 *
 * <p>本服务不保存第二份权限状态，仅负责与 {@link DurableInteractionService} 之间的权限协议转换；
 * 后者是交互生命周期和终态裁决的唯一权威。</p>
 */
@Service
public class PermissionInteractionService {
    private final DurableInteractionService interactions;

    public PermissionInteractionService(DurableInteractionService interactions) {
        this.interactions = interactions;
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

}

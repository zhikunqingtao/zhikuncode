package com.aicodeassistant.service;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.session.SessionData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes provider-private continuation data before messages leave the backend.
 * The persisted representation deliberately remains untouched.
 */
@Component
public class PublicMessageProjection {

    public SessionData project(SessionData data) {
        return new SessionData(data.sessionId(), data.model(), data.workingDir(),
                data.title(), data.status(), project(data.messages()), data.config(),
                data.totalUsage(), data.totalCostUsd(), data.summary(),
                data.createdAt(), data.updatedAt());
    }

    public List<Message> project(List<Message> messages) {
        if (messages == null) return List.of();
        return messages.stream().map(this::project).toList();
    }

    public Message project(Message message) {
        return switch (message) {
            case Message.UserMessage user -> new Message.UserMessage(
                    user.uuid(), user.timestamp(), projectBlocks(user.content()),
                    user.toolUseResult(), user.sourceToolAssistantUUID());
            case Message.AssistantMessage assistant -> new Message.AssistantMessage(
                    assistant.uuid(), assistant.timestamp(), projectBlocks(assistant.content()),
                    assistant.stopReason(), assistant.usage());
            case Message.SystemMessage system -> system;
        };
    }

    private List<ContentBlock> projectBlocks(List<ContentBlock> blocks) {
        if (blocks == null) return List.of();
        List<ContentBlock> result = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.ProviderResponseStateBlock state) {
                if (state.displayThinking() == null || state.displayThinking().isBlank()) {
                    result.add(new ContentBlock.RedactedThinkingBlock(""));
                } else {
                    result.add(new ContentBlock.ThinkingBlock(state.displayThinking()));
                }
            } else if (block instanceof ContentBlock.RedactedThinkingBlock) {
                result.add(new ContentBlock.RedactedThinkingBlock(""));
            } else {
                result.add(block);
            }
        }
        return List.copyOf(result);
    }
}

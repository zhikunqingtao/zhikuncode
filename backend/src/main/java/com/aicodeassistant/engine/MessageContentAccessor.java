package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Read-only canonical interpretation of the existing persisted Message model. */
@Component
public class MessageContentAccessor {
    private static final Logger log = LoggerFactory.getLogger(MessageContentAccessor.class);

    public MessageContentView view(Message message) { return viewOf(message); }

    public static MessageContentView viewOf(Message message) {
        List<ContentBlock> blocks = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String role;
        if (message instanceof Message.UserMessage user) {
            role = "user";
            if (user.content() != null) blocks.addAll(user.content());
            if (user.toolUseResult() != null && !user.toolUseResult().isBlank()) {
                String legacyHash = hash(user.toolUseResult());
                boolean hasEquivalentToolResult = blocks.stream()
                        .filter(ContentBlock.ToolResultBlock.class::isInstance)
                        .map(ContentBlock.ToolResultBlock.class::cast)
                        .map(ContentBlock.ToolResultBlock::content)
                        .map(MessageContentAccessor::hash)
                        .anyMatch(legacyHash::equals);
                if (!hasEquivalentToolResult) {
                    blocks.add(new ContentBlock.ToolResultBlock(
                            user.sourceToolAssistantUUID() == null ? "legacy" : user.sourceToolAssistantUUID(),
                            user.toolUseResult(), false));
                    diagnostics.add(blocks.stream().filter(ContentBlock.ToolResultBlock.class::isInstance).count() > 1
                            ? "TOOL_RESULT_CONFLICT:" + legacyHash : "LEGACY_TOOL_RESULT_FALLBACK");
                }
            }
        } else if (message instanceof Message.AssistantMessage assistant) {
            role = "assistant";
            if (assistant.content() != null) blocks.addAll(assistant.content());
        } else if (message instanceof Message.SystemMessage system) {
            role = "system";
            if (system.content() != null) blocks.add(new ContentBlock.TextBlock(system.content()));
        } else throw new IllegalArgumentException("Unsupported message type");
        return new MessageContentView(message.uuid(), role, List.copyOf(blocks), List.copyOf(diagnostics));
    }

    /** Canonical legacy fallback. Null when a content ToolResult already wins. */
    public static String legacyToolResult(Message.UserMessage user) {
        if (user == null || user.toolUseResult() == null) return null;
        String legacyHash = hash(user.toolUseResult());
        boolean canonicalBlock = user.content() != null && user.content().stream()
                .filter(ContentBlock.ToolResultBlock.class::isInstance)
                .map(ContentBlock.ToolResultBlock.class::cast)
                .anyMatch(block -> legacyHash.equals(hash(block.content())));
        return canonicalBlock ? null : user.toolUseResult();
    }
    /** Structural copy only; context readers must use {@link #viewOf(Message)}. */
    public static String rawLegacyToolResult(Message.UserMessage user) {
        return user == null ? null : user.toolUseResult();
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record MessageContentView(String sourceMessageId, String role,
                                     List<ContentBlock> blocks, List<String> diagnostics) {}
}

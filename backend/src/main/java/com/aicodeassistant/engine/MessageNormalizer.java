package com.aicodeassistant.engine;

import com.aicodeassistant.llm.MessageParam;
import com.aicodeassistant.llm.MessageParam.ContentPart;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息API标准化 — 对齐原版 normalizeMessagesForAPI()。
 * 将内部 Message 列表转为 API 兼容格式，处理各种边界情况。
 * <p>
 * 处理管线:
 * 1. 过滤 SystemMessage
 * 2. 转换 + 合并连续同角色消息
 * 3. thinking 块处理（orphan过滤 + 尾部thinking移除）
 * 4. tool_use/tool_result 配对保证
 * 5. 空内容 assistant 消息过滤
 *
 * @see <a href="messages.ts:1989-2369">原版 normalizeMessagesForAPI</a>
 */
@Service
public class MessageNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MessageNormalizer.class);

    /**
     * 完整标准化管线。
     * @deprecated 使用 {@link #normalizeTyped(List)} 代替，返回强类型 MessageParam
     */
    @Deprecated
    public List<Map<String, Object>> normalize(List<Message> messages) {
        // Phase 1: 过滤不应发送给API的消息类型
        List<Message> filtered = filterMessages(messages);

        // Phase 2: 转换为 API 格式 + 合并连续同角色消息
        List<Map<String, Object>> apiMessages = convertAndMerge(filtered);

        // Phase 3: thinking 块处理
        apiMessages = processThinkingBlocks(apiMessages);

        // Phase 4: 确保 tool_use/tool_result 配对
        apiMessages = ensureToolResultPairing(apiMessages);

        // Phase 5: 过滤空内容助手消息
        apiMessages = filterEmptyAssistantMessages(apiMessages);

        return apiMessages;
    }

    /**
     * Phase 1: 过滤消息。
     * 过滤 SystemMessage（已在 systemPrompt 中处理）。
     */
    private List<Message> filterMessages(List<Message> messages) {
        return messages.stream()
                .filter(msg -> !(msg instanceof Message.SystemMessage))
                .toList();
    }

    /**
     * Phase 2: 转换 + 合并连续同角色消息。
     * 对齐原版: 合并连续 user 消息（Bedrock 兼容）。
     */
    private List<Map<String, Object>> convertAndMerge(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Message msg : messages) {
            switch (msg) {
                case Message.UserMessage user -> {
                    Map<String, Object> apiMsg = convertUserMessage(user);

                    // 合并连续 user 消息
                    if (!result.isEmpty()) {
                        Map<String, Object> last = result.getLast();
                        if ("user".equals(last.get("role"))) {
                            mergeUserApiMessages(last, apiMsg);
                            continue;
                        }
                    }
                    result.add(apiMsg);
                }
                case Message.AssistantMessage assistant -> {
                    Map<String, Object> apiMsg = convertAssistantMessage(assistant);
                    result.add(apiMsg);
                }
                case Message.SystemMessage ignored -> {
                    // 已在 Phase 1 过滤
                }
            }
        }
        return result;
    }

    /**
     * Phase 3: thinking 块处理规则。
     * 1. 仅含 thinking 的 assistant 消息 → 移除（orphan）
     * 2. 最后一条 assistant 消息的尾部 thinking → 移除
     * 3. redacted_thinking 块始终保留原样
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> processThinkingBlocks(
            List<Map<String, Object>> messages) {
        // Step 1: filterOrphanedThinkingOnlyMessages
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) {
                result.add(msg);
                continue;
            }

            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) msg.get("content");
            if (content == null || content.isEmpty()) {
                continue;
            }

            boolean onlyThinking = content.stream().allMatch(block -> {
                String type = (String) block.get("type");
                return "thinking".equals(type) || "redacted_thinking".equals(type);
            });

            if (onlyThinking) {
                log.debug("Skipping orphaned thinking-only assistant message");
                continue;
            }

            result.add(msg);
        }

        // Step 2: 最后一条 assistant 的尾部 thinking 移除
        if (!result.isEmpty()) {
            Map<String, Object> last = result.getLast();
            if ("assistant".equals(last.get("role"))) {
                List<Map<String, Object>> content =
                        (List<Map<String, Object>>) last.get("content");
                if (content != null && !content.isEmpty()) {
                    List<Map<String, Object>> trimmed = new ArrayList<>(content);
                    while (!trimmed.isEmpty()
                            && "thinking".equals(trimmed.getLast().get("type"))) {
                        trimmed.removeLast();
                    }
                    if (trimmed.size() != content.size()) {
                        Map<String, Object> cleanLast = new HashMap<>(last);
                        cleanLast.put("content", trimmed);
                        result.set(result.size() - 1, cleanLast);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Phase 4: 确保 tool_use/tool_result 配对。
     * API 要求每个 tool_use 必须有对应 tool_result。
     * 这是最后一道安全网 — 正常流程中 FIX-02 的 generateSyntheticResults() 已处理。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ensureToolResultPairing(
            List<Map<String, Object>> messages) {
        Set<String> toolUseIds = new LinkedHashSet<>();
        Set<String> toolResultIds = new HashSet<>();

        for (Map<String, Object> msg : messages) {
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) msg.get("content");
            if (content == null) continue;

            for (Map<String, Object> block : content) {
                String type = (String) block.get("type");
                if ("tool_use".equals(type)) {
                    toolUseIds.add((String) block.get("id"));
                } else if ("tool_result".equals(type)) {
                    toolResultIds.add((String) block.get("tool_use_id"));
                }
            }
        }

        Set<String> missing = new LinkedHashSet<>(toolUseIds);
        missing.removeAll(toolResultIds);

        if (!missing.isEmpty()) {
            log.warn("Found {} tool_use without matching tool_result, injecting synthetic errors",
                    missing.size());
            // 从后向前插入，避免索引偏移
            List<int[]> insertions = new ArrayList<>();
            List<Map<String, Object>> syntheticMsgs = new ArrayList<>();
            int mi = 0;
            for (String id : missing) {
                Map<String, Object> syntheticMsg = new HashMap<>(Map.of(
                        "role", "user",
                        "content", List.of(new HashMap<>(Map.of(
                                "type", "tool_result",
                                "tool_use_id", id,
                                "content", "<tool_use_error>No result received</tool_use_error>",
                                "is_error", true
                        )))
                ));
                int insertIdx = findInsertionPointForToolResult(messages, id);
                insertions.add(new int[]{insertIdx >= 0 ? insertIdx : messages.size(), mi});
                syntheticMsgs.add(syntheticMsg);
                mi++;
            }
            insertions.sort((a, b) -> Integer.compare(b[0], a[0]));
            for (int[] ins : insertions) {
                messages.add(ins[0], syntheticMsgs.get(ins[1]));
            }
        }

        return messages;
    }

    @SuppressWarnings("unchecked")
    private int findInsertionPointForToolResult(
            List<Map<String, Object>> messages, String toolUseId) {
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) msg.get("content");
            if (content == null) continue;
            boolean hasToolUse = content.stream().anyMatch(block ->
                    "tool_use".equals(block.get("type"))
                    && toolUseId.equals(block.get("id")));
            if (hasToolUse) return i + 1;
        }
        return messages.size();
    }

    /**
     * Phase 5: 过滤空内容 assistant 消息。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> filterEmptyAssistantMessages(
            List<Map<String, Object>> messages) {
        return messages.stream()
                .filter(msg -> {
                    if (!"assistant".equals(msg.get("role"))) return true;
                    List<Map<String, Object>> content =
                            (List<Map<String, Object>>) msg.get("content");
                    if (content == null || content.isEmpty()) return false;
                    boolean allWhitespace = content.stream().allMatch(block -> {
                        String type = (String) block.get("type");
                        if (!"text".equals(type)) return false;
                        String text = (String) block.get("text");
                        return text == null || text.isBlank();
                    });
                    return !allWhitespace;
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // ==================== 转换辅助方法 ====================

    private Map<String, Object> convertUserMessage(Message.UserMessage user) {
        // 优先使用 content blocks 中的 ToolResultBlock（保留 isError 信息）
        if (user.content() != null && !user.content().isEmpty()
                && user.content().getFirst() instanceof ContentBlock.ToolResultBlock) {
            List<Map<String, Object>> contentBlocks = new ArrayList<>();
            for (ContentBlock block : user.content()) {
                contentBlocks.add(contentBlockToMap(block));
            }
            return new HashMap<>(Map.of("role", "user", "content", contentBlocks));
        }
        if (user.toolUseResult() != null && user.sourceToolAssistantUUID() != null) {
            // tool_result fallback（丢失 isError 信息）
            return new HashMap<>(Map.of(
                    "role", "user",
                    "content", List.of(new HashMap<>(Map.of(
                            "type", "tool_result",
                            "tool_use_id", user.sourceToolAssistantUUID(),
                            "content", user.toolUseResult() != null ? user.toolUseResult() : ""
                    )))
            ));
        }
        // 普通用户消息
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (user.content() != null) {
            for (ContentBlock block : user.content()) {
                contentBlocks.add(contentBlockToMap(block));
            }
        }
        return new HashMap<>(Map.of("role", "user", "content", contentBlocks));
    }

    private Map<String, Object> convertAssistantMessage(Message.AssistantMessage assistant) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (assistant.content() != null) {
            for (ContentBlock block : assistant.content()) {
                contentBlocks.add(contentBlockToMap(block));
            }
        }
        return new HashMap<>(Map.of("role", "assistant", "content", contentBlocks));
    }

    private Map<String, Object> contentBlockToMap(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text ->
                    new HashMap<>(Map.of("type", "text", "text",
                            text.text() != null ? text.text() : ""));
            case ContentBlock.ToolUseBlock toolUse -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_use");
                map.put("id", toolUse.id());
                map.put("name", toolUse.name());
                map.put("input", toolUse.input() != null ? toolUse.input() : Map.of());
                yield map;
            }
            case ContentBlock.ToolResultBlock result -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_result");
                map.put("tool_use_id", result.toolUseId());
                map.put("content", result.content() != null ? result.content() : "");
                if (result.isError()) map.put("is_error", true);
                yield map;
            }
            case ContentBlock.ImageBlock image ->
                    new HashMap<>(Map.of("type", "image", "source", Map.of(
                            "type", "base64",
                            "media_type", image.mediaType(),
                            "data", image.base64Data())));
            case ContentBlock.ThinkingBlock thinking ->
                    new HashMap<>(Map.of("type", "thinking", "thinking",
                            thinking.thinking() != null ? thinking.thinking() : ""));
            case ContentBlock.RedactedThinkingBlock redacted ->
                    new HashMap<>(Map.of("type", "redacted_thinking", "data",
                            redacted.data() != null ? redacted.data() : ""));
        };
    }

    @SuppressWarnings("unchecked")
    private void mergeUserApiMessages(Map<String, Object> target, Map<String, Object> source) {
        List<Map<String, Object>> targetContent =
                new ArrayList<>((List<Map<String, Object>>) target.get("content"));
        List<Map<String, Object>> sourceContent =
                (List<Map<String, Object>>) source.get("content");
        targetContent.addAll(sourceContent);
        target.put("content", targetContent);
    }

    // ==================== 强类型转换 API ====================

    /**
     * 单条消息转换 — Message → MessageParam。
     * 使用 Java 21 pattern matching 覆盖所有 ContentBlock 子类型。
     *
     * @param msg 内部消息对象
     * @return 强类型 API 参数，SystemMessage 返回 null
     */
    public static MessageParam fromMessage(Message msg) {
        return switch (msg) {
            case Message.UserMessage user -> {
                List<ContentPart> parts = convertContentParts(user.content());
                // 兼容 toolUseResult 旧路径
                if (parts.isEmpty() && user.toolUseResult() != null
                        && user.sourceToolAssistantUUID() != null) {
                    parts = List.of(new ContentPart.ToolResultPart(
                            user.sourceToolAssistantUUID(),
                            user.toolUseResult() != null ? user.toolUseResult() : "",
                            false));
                }
                yield new MessageParam.UserParam(parts);
            }
            case Message.AssistantMessage assistant -> {
                List<ContentPart> parts = convertContentParts(assistant.content());
                yield new MessageParam.AssistantParam(parts);
            }
            case Message.SystemMessage ignored -> null;
        };
    }

    /**
     * 强类型标准化管线 — 返回 List&lt;MessageParam&gt; 替代 List&lt;Map&gt;。
     * <p>
     * 复用现有5阶段逻辑：过滤 → 转换合并 → thinking处理 → 配对保证 → 空内容过滤。
     * 最后通过 fromMessage() 将 Map 安全转为 MessageParam。
     *
     * @param messages 内部消息列表
     * @return 强类型消息参数列表
     */
    public List<MessageParam> normalizeTyped(List<Message> messages) {
        // 复用旧管线确保一致性
        List<Message> filtered = filterMessages(messages);

        // 直接转为强类型 + 合并连续同角色
        List<MessageParam> result = new ArrayList<>();
        for (Message msg : filtered) {
            MessageParam param = fromMessage(msg);
            if (param == null) continue;

            // 合并连续 user 消息
            if (!result.isEmpty() && param instanceof MessageParam.UserParam up) {
                MessageParam last = result.getLast();
                if (last instanceof MessageParam.UserParam lastUp) {
                    List<ContentPart> merged = new ArrayList<>(lastUp.content());
                    merged.addAll(up.content());
                    result.set(result.size() - 1, new MessageParam.UserParam(merged));
                    continue;
                }
            }
            result.add(param);
        }

        // thinking 处理: 过滤 orphan thinking-only, 移除尾部 thinking
        result = processThinkingBlocksTyped(result);

        // tool_use/tool_result 配对保证
        result = ensureToolResultPairingTyped(result);

        // 过滤空内容 assistant
        result = result.stream()
                .filter(mp -> {
                    if (!(mp instanceof MessageParam.AssistantParam ap)) return true;
                    if (ap.content() == null || ap.content().isEmpty()) return false;
                    boolean allBlankText = ap.content().stream().allMatch(p ->
                            p instanceof ContentPart.TextPart tp
                                    && (tp.text() == null || tp.text().isBlank()));
                    return !allBlankText;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return result;
    }

    /**
     * ContentBlock 列表 → ContentPart 列表。
     */
    private static List<ContentPart> convertContentParts(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        return blocks.stream().map(MessageNormalizer::convertContentPart).toList();
    }

    private static ContentPart convertContentPart(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text ->
                    new ContentPart.TextPart(text.text() != null ? text.text() : "");
            case ContentBlock.ToolUseBlock toolUse -> {
                    Map<String, Object> inputMap = jsonNodeToMap(toolUse.input());
                    yield new ContentPart.ToolUsePart(toolUse.id(), toolUse.name(), inputMap);
            }
            case ContentBlock.ToolResultBlock result ->
                    new ContentPart.ToolResultPart(result.toolUseId(),
                            result.content() != null ? result.content() : "",
                            result.isError());
            case ContentBlock.ImageBlock image ->
                    new ContentPart.ImagePart(image.mediaType(), image.base64Data());
            case ContentBlock.ThinkingBlock thinking ->
                    new ContentPart.ThinkingPart(
                            thinking.thinking() != null ? thinking.thinking() : "");
            case ContentBlock.RedactedThinkingBlock redacted ->
                    new ContentPart.RedactedThinkingPart(
                            redacted.data() != null ? redacted.data() : "");
        };
    }

    /**
     * 强类型 thinking 块处理。
     */
    private List<MessageParam> processThinkingBlocksTyped(List<MessageParam> messages) {
        List<MessageParam> result = new ArrayList<>();
        for (MessageParam mp : messages) {
            if (!(mp instanceof MessageParam.AssistantParam ap)) {
                result.add(mp);
                continue;
            }
            // 过滤 orphaned thinking-only
            boolean onlyThinking = ap.content().stream().allMatch(p ->
                    p instanceof ContentPart.ThinkingPart
                            || p instanceof ContentPart.RedactedThinkingPart);
            if (onlyThinking) continue;
            result.add(mp);
        }

        // 移除最后一条 assistant 的尾部 thinking
        if (!result.isEmpty() && result.getLast() instanceof MessageParam.AssistantParam ap) {
            List<ContentPart> trimmed = new ArrayList<>(ap.content());
            while (!trimmed.isEmpty()
                    && trimmed.getLast() instanceof ContentPart.ThinkingPart) {
                trimmed.removeLast();
            }
            if (trimmed.size() != ap.content().size()) {
                result.set(result.size() - 1, new MessageParam.AssistantParam(trimmed));
            }
        }
        return result;
    }

    /**
     * 强类型 tool_use/tool_result 配对保证。
     */
    private List<MessageParam> ensureToolResultPairingTyped(List<MessageParam> messages) {
        Set<String> toolUseIds = new LinkedHashSet<>();
        Set<String> toolResultIds = new HashSet<>();

        for (MessageParam mp : messages) {
            List<ContentPart> parts = switch (mp) {
                case MessageParam.UserParam up -> up.content();
                case MessageParam.AssistantParam ap -> ap.content();
                default -> List.of();
            };
            for (ContentPart part : parts) {
                if (part instanceof ContentPart.ToolUsePart tup) {
                    toolUseIds.add(tup.id());
                } else if (part instanceof ContentPart.ToolResultPart trp) {
                    toolResultIds.add(trp.toolUseId());
                }
            }
        }

        Set<String> missing = new LinkedHashSet<>(toolUseIds);
        missing.removeAll(toolResultIds);

        if (!missing.isEmpty()) {
            log.warn("[typed] Found {} tool_use without matching tool_result", missing.size());
            for (String id : missing) {
                // 找到包含该 tool_use 的 assistant 消息，在其后插入 synthetic result
                int insertIdx = -1;
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i) instanceof MessageParam.AssistantParam ap) {
                        boolean hasIt = ap.content().stream().anyMatch(p ->
                                p instanceof ContentPart.ToolUsePart tup && tup.id().equals(id));
                        if (hasIt) { insertIdx = i + 1; break; }
                    }
                }
                MessageParam synthetic = new MessageParam.UserParam(List.of(
                        new ContentPart.ToolResultPart(id,
                                "<tool_use_error>No result received</tool_use_error>", true)));
                if (insertIdx >= 0 && insertIdx <= messages.size()) {
                    messages.add(insertIdx, synthetic);
                } else {
                    messages.add(synthetic);
                }
            }
        }
        return messages;
    }

    /**
     * JsonNode → Map 转换（用于 ToolUseBlock.input → ToolUsePart.input）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

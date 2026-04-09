package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 微压缩服务 — 基于 COMPACTABLE_TOOLS 白名单清除旧工具结果内容。
 * <p>
 * 对标原版 microCompact.ts: 对旧的可压缩工具结果替换为
 * {@code [Old tool result content cleared]}，释放 token 空间。
 * 保护最近 N 条消息不被清除 (protected tail)。
 *
 * @see <a href="SPEC §3.1.6">压缩级联 - MicroCompact</a>
 */
@Service
public class MicroCompactService {

    private static final Logger log = LoggerFactory.getLogger(MicroCompactService.class);

    static final String CLEARED_MESSAGE = "[Old tool result content cleared]";

    /** 可微压缩的工具集合 (对标原版 COMPACTABLE_TOOLS) */
    static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "FileReadTool", "BashTool", "GrepTool", "GlobTool",
            "WebSearchTool", "WebFetchTool", "FileEditTool", "FileWriteTool"
    );

    private final TokenCounter tokenCounter;

    public MicroCompactService(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 遍历消息列表，清除旧的可压缩工具结果内容。
     * <p>
     * 使用 collectCompactableToolIds 预扫描白名单工具 ID，
     * 保留最近 protectedTailSize 条消息的工具结果不清除。
     * 非白名单工具的结果永不清除。
     *
     * @param messages          消息列表
     * @param protectedTailSize 保护的尾部消息数 (不清除)
     * @return 微压缩结果 (新消息列表 + 释放的 token 数)
     */
    public MicroCompactResult compactMessages(List<Message> messages, int protectedTailSize) {
        // 预扫描: 只收集白名单内工具的 toolUseId
        Set<String> compactableIds = collectCompactableToolIds(messages);

        int tokensFreed = 0;
        int boundary = Math.max(0, messages.size() - protectedTailSize);
        List<Message> result = new ArrayList<>(messages.size());
        int clearedCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            // 在保护区域内的消息不清除
            if (i >= boundary) {
                result.add(msg);
                continue;
            }

            if (msg instanceof Message.UserMessage user
                    && user.toolUseResult() != null
                    && !user.toolUseResult().equals(CLEARED_MESSAGE)) {
                // 检查是否属于可压缩工具 — 通过预扫描的 ID 集合判断
                boolean isCompactable = isCompactableByIds(user, compactableIds, messages);
                if (isCompactable) {
                    tokensFreed += tokenCounter.estimateTokens(user.toolUseResult());
                    result.add(new Message.UserMessage(
                            user.uuid(), user.timestamp(), user.content(),
                            CLEARED_MESSAGE, user.sourceToolAssistantUUID()));
                    clearedCount++;
                } else {
                    result.add(msg);
                }
            } else {
                result.add(msg);
            }
        }

        if (tokensFreed > 0) {
            log.info("MicroCompact: cleared {} old tool results, freed ~{} tokens",
                    clearedCount, tokensFreed);
        }

        return new MicroCompactResult(result, tokensFreed);
    }

    /**
     * 预扫描: 只收集白名单内工具的 toolUseId。
     * 对齐原版 collectCompactableToolIds()。
     */
    private Set<String> collectCompactableToolIds(List<Message> messages) {
        Set<String> ids = new HashSet<>();
        for (Message msg : messages) {
            if (!(msg instanceof Message.AssistantMessage am) || am.content() == null) continue;
            for (ContentBlock block : am.content()) {
                if (block instanceof ContentBlock.ToolUseBlock tub
                        && COMPACTABLE_TOOLS.contains(tub.name())) {
                    ids.add(tub.id());
                }
            }
        }
        return ids;
    }

    /**
     * 通过预扫描的 ID 集合判断工具结果是否可压缩。
     * 如果无法确定工具名，回退到原有的关联查找逻辑。
     */
    private boolean isCompactableByIds(Message.UserMessage user,
                                        Set<String> compactableIds,
                                        List<Message> messages) {
        // 先尝试从 content blocks 中查找 ToolResultBlock
        if (user.content() != null) {
            for (ContentBlock block : user.content()) {
                if (block instanceof ContentBlock.ToolResultBlock trb) {
                    return compactableIds.contains(trb.toolUseId());
                }
            }
        }
        // 回退到原有逻辑: 通过 sourceToolAssistantUUID 查找
        return isCompactableTool(user, messages);
    }

    /**
     * 判断工具结果是否属于可压缩工具。
     * 通过 sourceToolAssistantUUID 查找关联的 AssistantMessage，
     * 从其 ToolUseBlock 中获取工具名。
     * 注意: 无法确定工具名时默认不可压缩（安全保守策略）。
     */
    private boolean isCompactableTool(Message.UserMessage user, List<Message> messages) {
        if (user.sourceToolAssistantUUID() == null) {
            // 无法确定工具名，默认不可压缩（安全保守，避免丢失重要上下文）
            return false;
        }

        // 查找关联的 AssistantMessage
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant
                    && assistant.uuid().equals(user.sourceToolAssistantUUID())
                    && assistant.content() != null) {
                // 从 AssistantMessage 的 content 中查找 ToolUseBlock
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof ContentBlock.ToolUseBlock toolUse) {
                        return COMPACTABLE_TOOLS.contains(toolUse.name());
                    }
                }
            }
        }

        // 找不到关联消息，默认不可压缩（安全保守）
        return false;
    }

    /**
     * 创建压缩边界消息 — 对齐原版 createMicrocompactBoundaryMessage。
     * 压缩后插入，用于标记压缩点。
     */
    public Message createBoundaryMessage(int clearedCount) {
        return new Message.UserMessage(
                java.util.UUID.randomUUID().toString(),
                java.time.Instant.now(),
                List.of(new ContentBlock.TextBlock(
                        String.format("[Auto-compacted: %d old tool results cleared to save context]",
                                clearedCount))),
                null, null);
    }

    /**
     * 微压缩结果。
     *
     * @param messages    压缩后的消息列表
     * @param tokensFreed 释放的估算 token 数
     */
    public record MicroCompactResult(List<Message> messages, int tokensFreed) {}
}

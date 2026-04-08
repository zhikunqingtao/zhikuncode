package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
     * 保留最近 protectedTailSize 条消息的工具结果不清除。
     * 工具结果在 UserMessage.toolUseResult() 中，
     * 工具名通过关联的 AssistantMessage 的 ToolUseBlock 确定。
     *
     * @param messages          消息列表
     * @param protectedTailSize 保护的尾部消息数 (不清除)
     * @return 微压缩结果 (新消息列表 + 释放的 token 数)
     */
    public MicroCompactResult compactMessages(List<Message> messages, int protectedTailSize) {
        int tokensFreed = 0;
        int boundary = Math.max(0, messages.size() - protectedTailSize);
        List<Message> result = new ArrayList<>(messages.size());

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i < boundary && msg instanceof Message.UserMessage user
                    && user.toolUseResult() != null
                    && !user.toolUseResult().equals(CLEARED_MESSAGE)
                    && isCompactableTool(user, messages)) {
                tokensFreed += tokenCounter.estimateTokens(user.toolUseResult());
                result.add(new Message.UserMessage(
                        user.uuid(), user.timestamp(), user.content(),
                        CLEARED_MESSAGE, user.sourceToolAssistantUUID()));
            } else {
                result.add(msg);
            }
        }

        if (tokensFreed > 0) {
            log.info("MicroCompact: cleared {} old tool results, freed ~{} tokens",
                    result.stream().filter(m -> m instanceof Message.UserMessage u
                            && CLEARED_MESSAGE.equals(u.toolUseResult())).count(),
                    tokensFreed);
        }

        return new MicroCompactResult(result, tokensFreed);
    }

    /**
     * 判断工具结果是否属于可压缩工具。
     * 通过 sourceToolAssistantUUID 查找关联的 AssistantMessage，
     * 从其 ToolUseBlock 中获取工具名。
     */
    private boolean isCompactableTool(Message.UserMessage user, List<Message> messages) {
        if (user.sourceToolAssistantUUID() == null) {
            // 无法确定工具名，默认可压缩
            return true;
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

        // 找不到关联消息，默认可压缩
        return true;
    }

    /**
     * 微压缩结果。
     *
     * @param messages    压缩后的消息列表
     * @param tokensFreed 释放的估算 token 数
     */
    public record MicroCompactResult(List<Message> messages, int tokensFreed) {}
}

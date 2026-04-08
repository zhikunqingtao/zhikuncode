package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 用户输入处理结果 — UserInputProcessor 的返回类型。
 * <p>
 * 两种结果:
 * <ul>
 *     <li>command — 用户输入是一个斜杠命令</li>
 *     <li>message — 用户输入是一个普通消息（可能附带上下文附件）</li>
 * </ul>
 *
 * @see <a href="SPEC section 3.1.0">UserInputProcessor</a>
 */
public record ProcessedInput(
        Type type,
        String commandName,
        String commandArgs,
        Message.UserMessage message,
        List<ContextAttachment> attachments
) {

    public enum Type {
        COMMAND,
        MESSAGE
    }

    /**
     * 上下文附件 — @file 引用解析后的文件内容。
     */
    public record ContextAttachment(
            String filePath,
            String content,
            int startLine,
            int endLine
    ) {
        public static ContextAttachment ofFile(String filePath, String content) {
            return new ContextAttachment(filePath, content, 0, 0);
        }

        public static ContextAttachment ofRange(String filePath, String content,
                                                  int startLine, int endLine) {
            return new ContextAttachment(filePath, content, startLine, endLine);
        }
    }

    /**
     * 创建命令类型的处理结果。
     */
    public static ProcessedInput command(String commandName, String commandArgs) {
        return new ProcessedInput(Type.COMMAND, commandName, commandArgs, null, List.of());
    }

    /**
     * 创建消息类型的处理结果。
     */
    public static ProcessedInput message(String normalizedText, List<ContextAttachment> attachments) {
        List<ContentBlock> content = List.of(new ContentBlock.TextBlock(normalizedText));
        Message.UserMessage msg = new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                content, null, null);
        return new ProcessedInput(Type.MESSAGE, null, null, msg, attachments);
    }

    /**
     * 创建纯文本消息（无附件）。
     */
    public static ProcessedInput message(String normalizedText) {
        return message(normalizedText, List.of());
    }

    /** 是否为命令 */
    public boolean isCommand() {
        return type == Type.COMMAND;
    }

    /** 是否为消息 */
    public boolean isMessage() {
        return type == Type.MESSAGE;
    }
}

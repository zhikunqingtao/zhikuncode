package com.aicodeassistant.command;

import java.util.List;
import java.util.Set;

/**
 * PromptCommand 扩展接口 — 生成提示词发送给 LLM 的命令。
 * <p>
 * PromptCommand 的 execute() 应返回包含提示词的 CommandResult，
 * 由引擎将提示词注入对话并发送给 LLM。
 *
 * @see <a href="SPEC §3.3.1">命令接口定义</a>
 */
public interface PromptCommand extends Command {

    @Override
    default CommandType getType() { return CommandType.PROMPT; }

    /** 期望的内容长度范围 */
    default ContentLength getContentLength() { return ContentLength.NORMAL; }

    /** 命令参数名列表 */
    default List<String> getArgNames() { return List.of(); }

    /** 命令执行时允许 LLM 使用的工具集（null=全部） */
    default Set<String> getAllowedTools() { return null; }

    /** 命令使用的模型（null=使用会话默认模型） */
    default String getModel() { return null; }

    /** 命令来源标识 */
    default String getSource() { return null; }
}

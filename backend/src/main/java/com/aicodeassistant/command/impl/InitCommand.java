package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * /init — 交互式创建/更新 zhikun.md、技能和钩子。
 * <p>
 * PromptCommand: 生成多阶段提示词发送给 LLM。
 *
 * @see <a href="SPEC §3.3.4a.7">/init 命令</a>
 */
@Component
public class InitCommand implements PromptCommand {

    @Override public String getName() { return "init"; }
    @Override public String getDescription() { return "Initialize project configuration (zhikun.md, skills, hooks)"; }
    @Override public ContentLength getContentLength() { return ContentLength.LONG; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        // 生成 init 提示词 — 让 LLM 扫描项目并生成配置
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please help initialize this project. ");
        prompt.append("Scan the project structure and detect:\n");
        prompt.append("1. Build system and commands (build, test, lint)\n");
        prompt.append("2. Programming languages and frameworks\n");
        prompt.append("3. Project conventions and coding standards\n\n");
        prompt.append("Then create or update the zhikun.md file with relevant project information ");
        prompt.append("that would be helpful for an AI assistant working on this project.\n\n");

        if (context.workingDir() != null) {
            prompt.append("Project directory: ").append(context.workingDir()).append("\n");
        }

        return CommandResult.text(prompt.toString());
    }
}

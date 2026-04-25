package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /skill <name> [args] — 执行指定技能。
 * <p>
 * 从 SkillRegistry 解析技能定义，渲染模板后返回提示词，
 * 由 WebSocketController 的 PROMPT 路径注入 LLM 对话。
 */
@Component
public class SkillCommand implements PromptCommand {

    private final SkillRegistry skillRegistry;

    public SkillCommand(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() { return "skill"; }

    @Override
    public String getDescription() { return "执行指定技能"; }

    @Override
    public boolean isHidden() { return true; }

    @Override
    public List<String> getArgNames() { return List.of("name"); }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args == null || args.isBlank()) {
            return CommandResult.error("用法: /skill <技能名称> [参数]");
        }

        // 解析技能名称和附加参数
        String[] parts = args.trim().split("\\s+", 2);
        String skillName = parts[0];
        String skillArgs = parts.length > 1 ? parts[1] : "";

        // 从 SkillRegistry 解析技能定义
        SkillDefinition skill = skillRegistry.resolve(skillName);
        if (skill == null) {
            return CommandResult.error("技能未找到: " + skillName
                    + "。可用技能: " + skillRegistry.getAllSkills().stream()
                    .map(SkillDefinition::effectiveName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("无"));
        }

        // 解析参数并渲染模板
        Map<String, String> params = skill.parseArgs(skillArgs);
        String renderedPrompt = skill.renderTemplate(params);

        // 返回渲染后的提示词，由 PROMPT 路径注入 LLM 对话
        return CommandResult.text(renderedPrompt);
    }
}

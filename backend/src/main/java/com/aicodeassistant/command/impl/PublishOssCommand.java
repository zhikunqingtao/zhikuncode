package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.PromptCommand;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Direct /publish-oss adapter for the bundled skill with a strict tool allowlist. */
@Component
public final class PublishOssCommand implements PromptCommand {
    private static final Set<String> ALLOWED_TOOLS = Set.of("PublishArtifact", "AskUserQuestion");

    private final SkillRegistry skills;
    private final OssPublishProperties properties;

    public PublishOssCommand(SkillRegistry skills, OssPublishProperties properties) {
        this.skills = skills;
        this.properties = properties;
    }

    @Override public String getName() { return "publish-oss"; }

    @Override public boolean isAvailable() { return skills.resolve("publish-oss") != null; }

    @Override public boolean isHidden() { return !isAvailable(); }

    @Override public String getDescription() {
        return "经逐次权限确认，将当前会话中的一个已验证产物发布到 OSS";
    }

    @Override public Set<String> getAllowedTools() { return ALLOWED_TOOLS; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (!properties.isEnabled()) {
            return CommandResult.error("OSS 产物发布未在当前部署启用");
        }
        SkillDefinition skill = skills.resolve("publish-oss");
        if (skill == null) {
            return CommandResult.error("内置 publish-oss Skill 未加载");
        }
        Map<String, String> parameters = skill.parseArgs(args);
        return CommandResult.text(skill.renderTemplate(parameters));
    }
}

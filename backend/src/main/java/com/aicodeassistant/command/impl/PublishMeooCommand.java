package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.PromptCommand;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Direct /publish-meoo adapter for the bundled skill with a strict tool allowlist. */
@Component
public final class PublishMeooCommand implements PromptCommand {
    private static final Set<String> ALLOWED_TOOLS = Set.of("InspectMeooDeployment", "PublishMeoo", "VerifyJourney", "AskUserQuestion");

    private final SkillRegistry skills;
    private final MeooPublishProperties properties;

    public PublishMeooCommand(SkillRegistry skills, MeooPublishProperties properties) {
        this.skills = skills;
        this.properties = properties;
    }

    @Override public String getName() { return "publish-meoo"; }

    @Override public String getDescription() {
        return "经逐次权限确认，将当前会话中的已验证应用发布到 秒悟";
    }

    @Override public Set<String> getAllowedTools() { return ALLOWED_TOOLS; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (!properties.isEnabled()) {
            return CommandResult.error("秒悟 产物发布未在当前部署启用");
        }
        SkillDefinition skill = skills.resolve("publish-meoo");
        if (skill == null) {
            return CommandResult.error("内置 publish-meoo Skill 未加载");
        }
        Map<String, String> parameters = skill.parseArgs(args);
        return CommandResult.text(skill.renderTemplate(parameters));
    }
}

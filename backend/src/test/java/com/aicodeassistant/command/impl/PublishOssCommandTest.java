package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishOssCommandTest {

    @ParameterizedTest
    @CsvSource({
            "false, false, false",
            "false, true, false",
            "true, false, false",
            "true, true, true"
    })
    void commandVisibilityRequiresEnabledDeploymentAndAvailableSkill(
            boolean deploymentEnabled, boolean skillAvailable, boolean expectedAvailable) {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(deploymentEnabled);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.resolve("publish-oss")).thenReturn(skillAvailable ? publishSkill() : null);
        PublishOssCommand command = new PublishOssCommand(skills, properties);

        assertThat(command.isAvailable()).isEqualTo(expectedAvailable);
        assertThat(command.isHidden()).isEqualTo(!expectedAvailable);
    }

    @Test
    void directCommandInjectsSelectedPathAndRestrictsAvailableTools() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.resolve("publish-oss")).thenReturn(publishSkill());
        PublishOssCommand command = new PublishOssCommand(skills, properties);

        CommandResult result = command.execute("reports/result.html", context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains("reports/result.html").doesNotContain("{{file_path}}");
        assertThat(command.getAllowedTools())
                .containsExactlyInAnyOrder("PublishArtifact", "AskUserQuestion");
    }

    @Test
    void disabledDeploymentRejectsCommandBeforeLoadingSkill() {
        SkillRegistry skills = mock(SkillRegistry.class);
        PublishOssCommand command = new PublishOssCommand(skills, new OssPublishProperties());

        CommandResult result = command.execute("report.html", context());

        assertThat(result.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(result.error()).contains("未在当前部署启用");
        verifyNoInteractions(skills);
    }

    @Test
    void enabledDeploymentRejectsUnavailableSkill() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        PublishOssCommand command = new PublishOssCommand(mock(SkillRegistry.class), properties);

        CommandResult result = command.execute("report.html", context());

        assertThat(result.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(result.error()).contains("publish-oss Skill 未加载");
    }

    private static SkillDefinition publishSkill() {
        return SkillDefinition.fromMarkdown("publish-oss.md", """
                ---
                name: publish-oss
                description: explicit publish
                arguments: file_path
                ---
                Publish exactly `{{file_path}}`.
                """, SkillDefinition.SkillSource.BUNDLED, null);
    }

    private static CommandContext context() {
        return CommandContext.of("session-1", "/app/workspace", null, null);
    }
}

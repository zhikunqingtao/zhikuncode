package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishMeooCommandTest {

    @ParameterizedTest
    @CsvSource({
            "false, false, false",
            "false, true, false",
            "true, false, false",
            "true, true, true"
    })
    void commandVisibilityRequiresEnabledDeploymentAndAvailableSkill(
            boolean deploymentEnabled, boolean skillAvailable, boolean expectedAvailable) {
        MeooPublishProperties properties = new MeooPublishProperties();
        properties.setEnabled(deploymentEnabled);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.resolve("publish-meoo")).thenReturn(skillAvailable ? publishSkill() : null);
        PublishMeooCommand command = new PublishMeooCommand(skills, properties);

        assertThat(command.isAvailable()).isEqualTo(expectedAvailable);
        assertThat(command.isHidden()).isEqualTo(!expectedAvailable);
    }

    @Test
    void directCommandInjectsSelectedPathAndRestrictsAvailableTools() {
        MeooPublishProperties properties = new MeooPublishProperties();
        properties.setEnabled(true);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(skills.resolve("publish-meoo")).thenReturn(publishSkill());
        PublishMeooCommand command = new PublishMeooCommand(skills, properties);

        CommandResult result = command.execute("reports/result.html", context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains("reports/result.html").doesNotContain("{{path}}");
        assertThat(command.getAllowedTools())
                .containsExactlyInAnyOrder("InspectMeooDeployment", "PublishMeoo", "VerifyJourney", "AskUserQuestion");
    }

    @Test
    void disabledDeploymentRejectsCommandBeforeLoadingSkill() {
        SkillRegistry skills = mock(SkillRegistry.class);
        PublishMeooCommand command = new PublishMeooCommand(skills, new MeooPublishProperties());

        CommandResult result = command.execute("report.html", context());

        assertThat(result.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(result.error()).contains("未在当前部署启用");
        verifyNoInteractions(skills);
    }

    @Test
    void enabledDeploymentRejectsUnavailableSkill() {
        MeooPublishProperties properties = new MeooPublishProperties();
        properties.setEnabled(true);
        PublishMeooCommand command = new PublishMeooCommand(mock(SkillRegistry.class), properties);

        CommandResult result = command.execute("report.html", context());

        assertThat(result.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(result.error()).contains("publish-meoo Skill 未加载");
    }

    private static SkillDefinition publishSkill() {
        return SkillDefinition.fromMarkdown("publish-meoo.md", """
                ---
                name: publish-meoo
                description: explicit publish
                arguments: path
                ---
                Publish exactly `{{path}}`.
                """, SkillDefinition.SkillSource.BUNDLED, null);
    }

    private static CommandContext context() {
        return CommandContext.of("session-1", "/app/workspace", null, null);
    }
}

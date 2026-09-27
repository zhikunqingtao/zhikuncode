package com.aicodeassistant.skill;

import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.command.impl.HelpCommand;
import com.aicodeassistant.command.impl.PublishMeooCommand;
import com.aicodeassistant.command.impl.PublishOssCommand;
import com.aicodeassistant.command.impl.SkillCommand;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SkillAvailabilityTest {
    private final SkillRegistry registry = new SkillRegistry();

    @AfterEach void close() { registry.stopWatching(); }

    @Test
    void disabledDefinitionCannotResolveByInternalNameCaseOrAliasAndReloadKeepsChoice() {
        SkillDefinition builtin = skill("Example", "display-name", "old body", SkillDefinition.SkillSource.BUNDLED);
        registry.registerBuiltin(builtin);
        assertThat(registry.resolve("/EXAMPLE")).isSameAs(builtin);
        registry.setEnabled("example", false);
        assertThat(registry.resolve("Example")).isNull();
        assertThat(registry.resolve("/DISPLAY-NAME")).isNull();
        assertThat(registry.getEnabledSkills()).isEmpty();
        assertThat(registry.getAllSkills()).containsExactly(builtin);
        assertThat(registry.getManagedSkill("example")).isSameAs(builtin);

        SkillDefinition replacement = skill("example", "new-alias", "new body", SkillDefinition.SkillSource.PROJECT);
        registry.register(replacement);
        assertThat(registry.resolve("new-alias")).isNull();
        assertThat(registry.hasEnabledSkills()).isFalse();
        registry.setEnabled("EXAMPLE", true);
        assertThat(registry.resolve("new-alias")).isSameAs(replacement);
        assertThat(registry.resolve("example")).isSameAs(replacement);
        assertThat(registry.getBuiltinSkills()).containsExactly(builtin);
    }

    @Test
    void newDefinitionsDefaultEnabledAndUnknownManagementIdsCannotCreatePreferences() {
        assertThatThrownBy(() -> registry.setEnabled("unknown", false))
                .isInstanceOf(ResourceNotFoundException.class);
        registry.register(skill("unknown", "unknown", "body", SkillDefinition.SkillSource.USER));
        assertThat(registry.resolve("unknown")).isNotNull();
    }

    @Test
    void invalidNamesAreSkippedWithoutBlockingOtherRegistrations() {
        assertThatCode(() -> {
            registry.register(skill(" ", "invalid", "body", SkillDefinition.SkillSource.USER));
            registry.registerBuiltin(skill("", "invalid", "body", SkillDefinition.SkillSource.BUNDLED));
            registry.register(skill("valid", "valid", "body", SkillDefinition.SkillSource.USER));
        }).doesNotThrowAnyException();
        assertThat(registry.getEnabledSkills()).extracting(SkillDefinition::id).containsExactly("valid");
        assertThat(registry.getBuiltinSkills()).isEmpty();
    }

    @Test
    void quotedIdsPreserveWhitespaceAndEscapesWithoutExecutingAShorterId() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        registry.register(skill("my", "short", "WRONG_BODY", SkillDefinition.SkillSource.USER));
        SkillCommand command = new SkillCommand(registry);
        for (String id : List.of("my skill", " leading ", "quoted\"name", "back\\slash", "line\nbreak")) {
            registry.register(skill(id, "display-alias", "EXPECTED {{args}}", SkillDefinition.SkillSource.USER));
            String token = mapper.writeValueAsString(id);
            assertThat(command.execute(token + " work item", null).value()).isEqualTo("EXPECTED work item");
            assertThat(command.execute(token, null).value()).isEqualTo("EXPECTED {{args}}");
            registry.setEnabled(id, false);
            assertThat(command.execute(token + " work item", null).isSuccess()).isFalse();
        }
        assertThat(command.execute("my argument", null).value()).isEqualTo("WRONG_BODY");
        assertThat(command.execute("\"my skill", null).isSuccess()).isFalse();
        assertThat(command.execute("\"my\"suffix", null).isSuccess()).isFalse();
    }

    @Test
    void slashCommandRejectsDisabledAliasesAndNeverListsThemAsAvailable() {
        registry.register(skill("hidden", "hidden-alias", "SECRET_BODY", SkillDefinition.SkillSource.USER));
        registry.register(skill("visible", "visible-alias", "VISIBLE_BODY", SkillDefinition.SkillSource.USER));
        registry.setEnabled("hidden", false);
        SkillCommand command = new SkillCommand(registry);
        assertThat(command.execute("missing", null).error())
                .contains("visible-alias").doesNotContain("hidden", "SECRET_BODY");
        assertThat(command.execute("hidden-alias", null).isSuccess()).isFalse();
        assertThat(command.execute("hidden-alias", null).error()).doesNotContain("SECRET_BODY");
    }

    @Test
    void publishAdaptersDisappearFromVisibleCommandsAndSuggestionsAndRejectInvocation() {
        for (String name : List.of("publish-oss", "publish-meoo")) {
            registry.register(skill(name, name, "PUBLISH_BODY", SkillDefinition.SkillSource.BUNDLED));
        }
        OssPublishProperties ossProperties = new OssPublishProperties();
        ossProperties.setEnabled(true);
        MeooPublishProperties meooProperties = new MeooPublishProperties();
        meooProperties.setEnabled(true);
        var oss = new PublishOssCommand(registry, ossProperties);
        var meoo = new PublishMeooCommand(registry, meooProperties);
        var commands = new CommandRegistry(List.of(oss, meoo));
        assertThat(commands.getVisibleCommands()).hasSize(2);
        registry.setEnabled("publish-oss", false);
        registry.setEnabled("publish-meoo", false);
        assertThat(commands.getVisibleCommands()).isEmpty();
        assertThat(commands.suggestCommands("publish-os")).doesNotContain("publish-oss", "publish-meoo");
        assertThat(oss.execute("", null).isSuccess()).isFalse();
        assertThat(meoo.execute("", null).isSuccess()).isFalse();
        registry.setEnabled("publish-oss", true);
        assertThat(commands.getVisibleCommands()).containsExactly(oss);
    }

    @Test
    void helpListAndDetailsHideDisabledPublishSkillsAndRecoverWhenEnabled() {
        for (String name : List.of("publish-oss", "publish-meoo")) {
            registry.register(skill(name, name, "PUBLISH_BODY", SkillDefinition.SkillSource.BUNDLED));
        }
        var oss = new PublishOssCommand(registry, new OssPublishProperties());
        var meoo = new PublishMeooCommand(registry, new MeooPublishProperties());
        HelpCommand help = new HelpCommand(new CommandRegistry(List.of(oss, meoo)));
        assertThat(help.execute("", null).data()).containsEntry("total", 2);
        assertThat(help.execute("publish-oss", null).value()).contains(oss.getDescription());
        assertThat(help.execute("publish-meoo", null).value()).contains(meoo.getDescription());

        registry.setEnabled("publish-oss", false);
        registry.setEnabled("publish-meoo", false);
        var list = help.execute("", null);
        assertThat(list.data()).containsEntry("total", 0);
        assertThat(list.data().toString()).doesNotContain("publish-oss", "publish-meoo");
        for (String name : List.of("publish-oss", "publish-meoo", "PUBLISH-OSS")) {
            var detail = help.execute(name, null);
            assertThat(detail.isSuccess()).isFalse();
            assertThat(detail.value()).isNull();
            assertThat(detail.error()).contains("Unknown command")
                    .doesNotContain(oss.getDescription(), meoo.getDescription(),
                            "PublishArtifact", "InspectMeooDeployment");
        }

        registry.setEnabled("publish-oss", true);
        assertThat(help.execute("", null).data()).containsEntry("total", 1);
        assertThat(help.execute("publish-oss", null).value()).contains(oss.getDescription());
        assertThat(help.execute("publish-meoo", null).isSuccess()).isFalse();
    }

    @Test
    void explicitlyRequestedHelpStillWorksForOrdinaryHiddenCommands() {
        Command hidden = new Command() {
            @Override public String getName() { return "heapdump"; }
            @Override public String getDescription() { return "Hidden diagnostics"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public boolean isHidden() { return true; }
            @Override public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("unused");
            }
        };
        HelpCommand help = new HelpCommand(new CommandRegistry(List.of(hidden, new SkillCommand(registry))));
        assertThat(help.execute("", null).data()).containsEntry("total", 0);
        assertThat(help.execute("heapdump", null).value()).contains("Hidden diagnostics");
        assertThat(help.execute("skill", null).value()).contains("执行指定技能");
    }

    private SkillDefinition skill(String name, String alias, String body, SkillDefinition.SkillSource source) {
        return SkillDefinition.fromMarkdown(name + ".md", "---\nname: " + alias
                + "\ndescription: description\n---\n" + body, source, null);
    }
}

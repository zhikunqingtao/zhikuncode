package com.aicodeassistant.skill;

import com.aicodeassistant.command.impl.SkillCommand;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BundledSkillPromptTest {
    private static final String INSTRUCTIONS = "backend 只检查 `FooService`，不要执行命令\n"
            + "按字面分析 $(example)、${value}、a | b; echo value && rm file\n"
            + "```java\nString value = \"example\";\n```\n"
            + "不修改文件、不安装依赖；保留 {{literal}} 和 C:\\repo\\a b.txt";

    @TempDir Path workspace;
    private SkillRegistry registry;
    private SkillCommand command;
    private SkillExecutor executor;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry(new SkillStateService(
                new ObjectMapper(), workspace.resolve("states.json").toString()));
        command = new SkillCommand(registry);
        executor = new SkillExecutor(registry, new SkillToolValidator());
        context = ToolUseContext.of(workspace.toString(), "bundled-instructions");
    }

    @AfterEach
    void close() {
        registry.stopWatching();
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "pr", "test", "verify", "debug", "remember",
            "software-architecture", "prompt-engineering", "csv-data-summarizer", "test-driven-development"})
    void bothEntrypointsPreserveCompleteInstructionsForActualBundledSkills(String name) throws Exception {
        registerBuiltin(name);

        var commandResult = command.execute(name + " " + INSTRUCTIONS, null);
        var toolResult = executor.execute(name, INSTRUCTIONS, context);

        assertThat(commandResult.isSuccess()).isTrue();
        assertThat(toolResult.isError()).isFalse();
        assertThat(commandResult.value()).isEqualTo(toolResult.content())
                .containsOnlyOnce(INSTRUCTIONS).endsWith(INSTRUCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"software-architecture", "prompt-engineering", "csv-data-summarizer"})
    void domainSkillsRenderTheirDeclaredNamedParameters(String name) throws Exception {
        SkillDefinition skill = registerBuiltin(name);
        String parameter = skill.frontmatter().arguments().getFirst();
        List<String> expectedTools = switch (name) {
            case "software-architecture" -> List.of("Read", "Write");
            case "csv-data-summarizer" -> List.of("Bash", "Read", "Write");
            default -> List.of();
        };

        assertThat(skill.frontmatter().allowedTools()).isEqualTo(expectedTools);
        assertThat(skill.content()).contains("{{" + parameter + "}}");
        String instructions = parameter + "=ignored " + parameter + "=sample-input 仅分析，不执行修改";
        var commandResult = command.execute(name + " " + instructions, null);
        var toolResult = executor.execute(name, instructions, context);

        assertThat(commandResult.isSuccess()).isTrue();
        assertThat(toolResult.isError()).isFalse();
        assertThat(commandResult.value()).isEqualTo(toolResult.content()).endsWith(instructions);
        String template = commandResult.value().substring(0,
                commandResult.value().indexOf("\n\n## 本次用户补充说明"));
        assertThat(template).contains("sample-input")
                .doesNotContain("ignored", "{{" + parameter + "}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"debug", "remember", "prompt-engineering"})
    void emptyInputAddsNoSupplementarySection(String name) throws Exception {
        SkillDefinition skill = registerBuiltin(name);

        assertThat(command.execute(name, null).value()).isEqualTo(skill.content());
        for (String args : new String[] {null, "", " \t\n "}) {
            assertThat(executor.execute(name, args, context).content()).isEqualTo(skill.content());
        }
    }

    @Test
    void customOverridesAndUnrelatedAliasesKeepTheirOwnParameterContract() {
        for (var source : new SkillDefinition.SkillSource[] {
                SkillDefinition.SkillSource.PROJECT, SkillDefinition.SkillSource.USER}) {
            registry.register(SkillDefinition.fromMarkdown("debug.md", "Custom {{scope}}", source, null));
            assertThat(command.execute("debug scope=backend scope=frontend", null).value()).isEqualTo("Custom frontend");
            assertThat(executor.execute("debug", "scope=frontend", context).content()).isEqualTo("Custom frontend");
        }
        registry.registerBuiltin(SkillDefinition.fromMarkdown("unrelated.md",
                "---\nname: prompt-engineering\n---\nCustom {{scope}}", SkillDefinition.SkillSource.BUNDLED, null));
        assertThat(command.execute("prompt-engineering scope=frontend", null).value()).isEqualTo("Custom frontend");
    }

    @Test
    void canonicalBundledIdentityPreservesInstructionsWhenInvokedThroughAlias() {
        registry.registerBuiltin(SkillDefinition.fromMarkdown("debug.md",
                "---\nname: diagnose\n---\nDiagnosis", SkillDefinition.SkillSource.BUNDLED, null));

        assertThat(command.execute("/DIAGNOSE " + INSTRUCTIONS, null).value()).endsWith(INSTRUCTIONS);
        assertThat(executor.execute("diagnose", INSTRUCTIONS, context).content()).endsWith(INSTRUCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify", "debug"})
    void toolChecksTheCompleteInstructionLength(String name) throws Exception {
        registerBuiltin(name);

        // Each parsed value is below the limit; the complete input must still be bounded.
        String limit = "a=" + "x".repeat(998) + " b=" + "y".repeat(997);
        assertThat(limit).hasSize(2000);
        var accepted = executor.execute(name, limit, context);
        assertThat(accepted.isError()).isFalse();
        assertThat(accepted.content()).endsWith(limit);
        assertThat(executor.execute(name, limit + "x", context).failureCode())
                .isEqualTo("SKILL_ARGUMENTS_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify", "review", "prompt-engineering"})
    void slashPreservesLongInputWithoutAdoptingTheToolArgumentLimit(String name) throws Exception {
        registerBuiltin(name);
        // Keep both entry points' existing contracts: only the model tool caps the full input.
        String instructions = "scope=backend note=" + "x".repeat(1982);
        assertThat(instructions).hasSize(2001);

        var commandResult = command.execute(name + " " + instructions, null);

        assertThat(commandResult.isSuccess()).isTrue();
        assertThat(commandResult.value()).containsOnlyOnce(instructions);
        assertThat(executor.execute(name, instructions, context).failureCode())
                .isEqualTo("SKILL_ARGUMENTS_INVALID");
    }

    @Test
    void toolKeepsNamedParameterPrecedenceWithCodeReferences() throws Exception {
        registerBuiltin("verify");
        String instructions = "backend scope=ignored scope=`FooService` 不要执行命令";

        var result = executor.execute("verify", instructions, context);
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("可选范围：`FooService`。").endsWith(instructions);
    }

    @ParameterizedTest
    @EnumSource(value = SkillDefinition.SkillSource.class, names = "BUNDLED", mode = EnumSource.Mode.EXCLUDE)
    void nonBundledSkillsKeepExistingArgumentValidation(SkillDefinition.SkillSource source) {
        for (String name : new String[] {"test", "verify", "review"}) {
            registry.register(SkillDefinition.fromMarkdown(name + ".md", "Custom {{scope}}", source, null));
            assertThat(executor.execute(name, "scope=backend scope=frontend", context).content())
                    .isEqualTo("Custom frontend");
            assertThat(executor.execute(name, "scope=`FooService`", context).failureCode())
                    .isEqualTo("SKILL_ARGUMENTS_INVALID");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify", "review"})
    void unrelatedBundledAliasesKeepExistingArgumentValidation(String alias) {
        registry.registerBuiltin(SkillDefinition.fromMarkdown("other.md",
                "---\nname: " + alias + "\n---\nCustom {{scope}}", SkillDefinition.SkillSource.BUNDLED, null));

        assertThat(executor.execute(alias, "scope=`FooService`", context).failureCode())
                .isEqualTo("SKILL_ARGUMENTS_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish-oss", "publish-meoo"})
    void publishingSkillsKeepExistingArgumentValidation(String name) throws Exception {
        registerBuiltin(name);

        assertThat(executor.execute(name, "dist/index.html", context).isError()).isFalse();
        assertThat(executor.execute(name, "`example`", context).failureCode())
                .isEqualTo("SKILL_ARGUMENTS_INVALID");
    }

    @Test
    void proseValidationDoesNotBypassForkDepthLimit() {
        registry.registerBuiltin(SkillDefinition.fromMarkdown("debug.md",
                "---\ncontext: fork\n---\nDiagnosis", SkillDefinition.SkillSource.BUNDLED, null));

        var allowed = executor.execute("debug", INSTRUCTIONS, context.withNestingDepth(2));
        assertThat(allowed.isError()).isFalse();
        assertThat(allowed.metadata().get("executionMode")).isEqualTo("fork");
        assertThat(executor.execute("debug", INSTRUCTIONS, context.withNestingDepth(3)).failureCode())
                .isEqualTo("SKILL_FORK_PERMISSION_DENIED");
    }

    @Test
    void bundledCommitPromptHonorsValidAuthorizationAndExplicitStaging() throws Exception {
        SkillDefinition skill = registerBuiltin("commit");

        assertThat(skill.content())
                .contains("此前明确提出且仍然有效")
                .contains("用户已明确指定要提交的文件时，只对这些文件执行 `git add <文件>`")
                .contains("不得使用 `git add -A` 或 `git add .`")
                .contains("将纳入的文件超出授权范围")
                .contains("先展示拟定的提交信息和文件清单并等待确认")
                .doesNotContain("仅当用户在本轮明确要求直接提交", "授权仅来自");
    }

    @Test
    void disabledSkillStillCannotBeInvoked() throws Exception {
        registerBuiltin("debug");
        registry.setEnabled("debug", false);

        assertThat(command.execute("debug " + INSTRUCTIONS, null).isSuccess()).isFalse();
        assertThat(executor.execute("debug", INSTRUCTIONS, context).failureCode()).isEqualTo("SKILL_NOT_FOUND");
    }

    private SkillDefinition registerBuiltin(String name) throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/skills/bundled/" + name + ".md"))) {
            SkillDefinition skill = SkillDefinition.fromMarkdown(name + ".md",
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8), SkillDefinition.SkillSource.BUNDLED, null);
            registry.registerBuiltin(skill);
            return skill;
        }
    }
}

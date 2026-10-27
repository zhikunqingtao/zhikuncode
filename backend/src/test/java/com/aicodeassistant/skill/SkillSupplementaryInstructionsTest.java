package com.aicodeassistant.skill;

import com.aicodeassistant.command.impl.SkillCommand;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSupplementaryInstructionsTest {
    private static final String FORMATTED_INSTRUCTIONS = "scope=frontend  排除 docs/**\n"
            + "只查 \"src/a b.java\" 和 'src/c d.java'\n"
            + "保留 `npm test` | cat; echo $value ${value} C:\\repo\n"
            + "按字面读取 {{scope}} {{args}}，不生成测试、不安装依赖";

    @TempDir Path workspace;
    private SkillRegistry registry;
    private SkillCommand command;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry(new SkillStateService(
                new ObjectMapper(), workspace.resolve("states.json").toString()));
        command = new SkillCommand(registry);
    }

    @AfterEach
    void close() {
        registry.stopWatching();
    }

    @ParameterizedTest
    @CsvSource({
            "test, 只运行已有测试，不生成测试、不安装依赖",
            "verify, backend 和 frontend，禁止联网"
    })
    void actualBundledSkillsPreserveCompleteScopeAndRestrictions(String name, String instructions) throws Exception {
        SkillDefinition skill = registerBuiltin(name);

        assertThat(render(name, instructions))
                .startsWith(skill.renderTemplate(skill.parseArgs(instructions)))
                .contains("## 本次用户补充说明")
                .containsOnlyOnce(instructions)
                .endsWith(instructions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify"})
    void formattingAndTemplateLikeTextRemainLiteralWithPlainOrQuotedSkillName(String name) throws Exception {
        registerBuiltin(name);

        for (String token : new String[] {name, "\"" + name + "\""}) {
            assertThat(render(token, FORMATTED_INSTRUCTIONS))
                    .containsOnlyOnce(FORMATTED_INSTRUCTIONS)
                    .endsWith(FORMATTED_INSTRUCTIONS)
                    .contains("{{scope}} {{args}}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope=frontend", "scope=backend scope=frontend", "backend scope=frontend"})
    void namedScopeKeepsExistingPrecedenceAndLastValueWins(String instructions) throws Exception {
        registerBuiltin("verify");

        assertThat(render("verify", instructions))
                .contains("可选范围：frontend。")
                .doesNotContain("可选范围：backend。", "可选范围：scope=frontend。")
                .contains("对于已声明参数", "优先于位置参数", "同名命名参数重复时采用最后一个值")
                .endsWith(instructions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file_path=\"/tmp/sales 2026.csv\"", "file_path='/tmp/sales 2026.csv'"})
    void quotedPathsWithSpacesPreserveOriginalAndRequireVerificationInBothEntrypoints(String instructions)
            throws Exception {
        registerBuiltin("csv-data-summarizer");
        var commandResult = command.execute("csv-data-summarizer " + instructions, null);
        var executor = new SkillExecutor(registry, new SkillToolValidator());
        var toolResult = executor.execute("csv-data-summarizer", instructions,
                ToolUseContext.of(workspace.toString(), "quoted-skill-arguments"));

        assertThat(commandResult.isSuccess()).isTrue();
        assertThat(toolResult.isError()).isFalse();
        assertThat(commandResult.value()).isEqualTo(toolResult.content())
                .contains("应核对完整原文", "不要将截断片段当作完整路径或范围", "仍有歧义时先澄清")
                .doesNotContain("仍以现有解析结果为准")
                .containsOnlyOnce(instructions)
                .endsWith(instructions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify"})
    void omittedEmptyAndBlankInstructionsKeepOriginalPrompt(String name) throws Exception {
        SkillDefinition skill = registerBuiltin(name);

        for (String instructions : new String[] {null, "", " \t\n "}) {
            assertThat(render(name, instructions)).isEqualTo(skill.content());
        }
    }

    @Test
    void missingSkillNameStillReturnsUsageError() {
        for (String input : new String[] {null, "", " \t\n "}) {
            var result = command.execute(input, null);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("用法:");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify"})
    void disabledSkillsRemainUnavailableUntilReenabled(String name) throws Exception {
        registerBuiltin(name);
        registry.setEnabled(name, false);

        assertThat(command.execute(name + " " + FORMATTED_INSTRUCTIONS, null).isSuccess()).isFalse();

        registry.setEnabled(name, true);
        assertThat(render(name, FORMATTED_INSTRUCTIONS)).endsWith(FORMATTED_INSTRUCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify"})
    void bundledIdentityStillAppliesWhenInvokedThroughAlias(String name) throws Exception {
        SkillDefinition skill = registerBuiltin(name);
        registry.registerBuiltin(SkillDefinition.fromMarkdown(name + ".md",
                "---\nname: checks\narguments:\n  - scope\n---\n" + skill.content(),
                SkillDefinition.SkillSource.BUNDLED, null));

        assertThat(render("/CHECKS", FORMATTED_INSTRUCTIONS))
                .contains("## 本次用户补充说明")
                .endsWith(FORMATTED_INSTRUCTIONS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "verify"})
    void unrelatedBundledSkillWithMatchingAliasKeepsItsOwnBehavior(String alias) {
        registry.registerBuiltin(SkillDefinition.fromMarkdown("other.md",
                "---\nname: " + alias + "\n---\nCustom {{scope}} / {{file}}",
                SkillDefinition.SkillSource.BUNDLED, null));

        assertThat(render(alias, "scope=backend scope=frontend file=src/A.java"))
                .isEqualTo("Custom frontend / src/A.java");
    }

    @ParameterizedTest
    @CsvSource({"test, PROJECT", "test, USER", "verify, PROJECT", "verify, USER"})
    void customOverridesKeepTheirOwnArgumentSyntax(String name, SkillDefinition.SkillSource source) throws Exception {
        registerBuiltin(name);
        registry.register(SkillDefinition.fromMarkdown(name + ".md", "Custom {{scope}} / {{file}}", source, null));

        assertThat(render(name, "scope=backend scope=frontend file=src/A.java"))
                .isEqualTo("Custom frontend / src/A.java");
    }

    private SkillDefinition registerBuiltin(String name) throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/skills/bundled/" + name + ".md"))) {
            SkillDefinition skill = SkillDefinition.fromMarkdown(name + ".md",
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8), SkillDefinition.SkillSource.BUNDLED, null);
            registry.registerBuiltin(skill);
            return skill;
        }
    }

    private String render(String name, String instructions) {
        var result = command.execute(name + (instructions == null ? "" : " " + instructions), null);
        assertThat(result.isSuccess()).isTrue();
        return result.value();
    }
}

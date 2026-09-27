package com.aicodeassistant.skill;

import com.aicodeassistant.command.impl.SkillCommand;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SkillReviewScopeTest {
    private static final String SCOPE = "commit=abc123  排除 docs/**\n只查 \"src/a b.java\" x=y C:\\repo $value "
            + "{{args}} {{review_scope}} review_scope=ignored";

    @TempDir Path workspace;
    private SkillRegistry registry;
    private SkillDefinition review;
    private SkillCommand command;
    private SkillExecutor executor;
    private ToolUseContext context;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry(new SkillStateService(
                new com.fasterxml.jackson.databind.ObjectMapper(), workspace.resolve("states.json").toString()));
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/skills/bundled/review.md"))) {
            review = SkillDefinition.fromMarkdown("review.md", new String(resource.readAllBytes(), StandardCharsets.UTF_8),
                    SkillDefinition.SkillSource.BUNDLED, null);
        }
        registry.registerBuiltin(review);
        command = new SkillCommand(registry);
        executor = new SkillExecutor(registry, new SkillToolValidator());
        context = ToolUseContext.of(workspace.toString(), "review-scope");
    }

    @AfterEach void close() { registry.stopWatching(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualBundledReviewReceivesWholeScopeExactlyOnceWithoutRecursiveSubstitution(boolean tool) {
        String rendered = render(tool, "review", SCOPE);
        assertThat(rendered).containsOnlyOnce(SCOPE);
        assertThat(rendered).contains("{{args}} {{review_scope}} review_scope=ignored");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedAndEmptyScopeDoNotLeaveAnUnfilledTemplate(boolean tool) {
        for (String args : new String[] {null, ""}) {
            assertThat(render(tool, "review", args)).doesNotContain("{{review_scope}}");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customReviewKeepsItsOwnArgumentSyntax(boolean tool) {
        for (var source : new SkillDefinition.SkillSource[] {
                SkillDefinition.SkillSource.PROJECT, SkillDefinition.SkillSource.USER}) {
            registry.register(SkillDefinition.fromMarkdown("review.md", "Custom {{review_scope}} / {{file}}",
                    source, null));
            assertThat(render(tool, "review", "review_scope=own file=src/A.java"))
                    .isEqualTo("Custom own / src/A.java");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvedIdentityControlsSpecialHandlingNotTheInvokedAlias(boolean tool) {
        registry.registerBuiltin(SkillDefinition.fromMarkdown("review.md",
                "---\nname: audit\n---\nScope: {{review_scope}}", SkillDefinition.SkillSource.BUNDLED, null));
        assertThat(render(tool, "/AUDIT", SCOPE)).isEqualTo("Scope: " + SCOPE);

        registry = replacementRegistry();
        registry.registerBuiltin(SkillDefinition.fromMarkdown("other.md",
                "---\nname: review\n---\nOther: {{review_scope}} / {{file}}", SkillDefinition.SkillSource.BUNDLED, null));
        command = new SkillCommand(registry);
        executor = new SkillExecutor(registry, new SkillToolValidator());
        assertThat(render(tool, "review", "review_scope=own file=src/A.java"))
                .isEqualTo("Other: own / src/A.java");
    }

    @Test
    void disabledReviewIsRejectedBeforeRenderingAndReenabledReviewWorks() {
        registry.setEnabled("review", false);
        assertThat(command.execute("review " + SCOPE, null).isSuccess()).isFalse();
        assertThat(executor.execute("/REVIEW", SCOPE, context).failureCode()).isEqualTo("SKILL_NOT_FOUND");
        registry.setEnabled("review", true);
        assertThat(render(true, "review", SCOPE)).containsOnlyOnce(SCOPE);
    }

    @Test
    void toolValidatesFullScopeBeforeRendering() {
        // Each legacy key=value is under 2,000 chars; only the full scope exposes the aggregate boundary.
        String limit = "a=" + "x".repeat(998) + " b=" + "y".repeat(997);
        assertThat(limit).hasSize(2000);
        assertThat(render(true, "review", limit)).containsOnlyOnce(limit);
        ToolResult rejected = executor.execute("review", limit + "y", context);
        assertThat(rejected.failureCode()).isEqualTo("SKILL_ARGUMENTS_INVALID");
        assertThat(rejected.content()).contains("review_scope", "2000");
    }

    @Test
    void dangerousScopeStillFails() {
        assertThat(executor.execute("review", "只审查 $(touch /tmp/example)", context).failureCode())
                .isEqualTo("SKILL_ARGUMENTS_INVALID");
    }

    @Test
    void repeatedBundledReviewKeepsWholeScopeInTheSameSession() {
        String expected = review.content().replace("{{review_scope}}", SCOPE);
        // Measure only the fixture: enough real bundled loads to cross both removed cumulative limits.
        int estimatedTokens = new TokenCounter(null, null, null).estimateTokens(expected);
        assertThat(estimatedTokens).isPositive();
        int repetitions = 25_000 / estimatedTokens + 1;
        for (int i = 0; i < repetitions; i++) {
            ToolResult result = executor.execute("review", SCOPE, context);
            assertThat(result.isError()).as("review invocation %s", i + 1).isFalse();
            assertThat(result.content()).isEqualTo(expected).containsOnlyOnce(SCOPE);
            assertThat(result.metadata().get("executionMode")).isEqualTo("inline");
        }
    }

    @Test
    void quotedCanonicalIdAndScopeFormattingStayCompatibleWithSkillManagement() {
        assertThat(command.execute("  \"review\"   " + SCOPE + "  ", null).value()).containsOnlyOnce(SCOPE);
        String padded = "  " + SCOPE + "\n  ";
        assertThat(render(true, "review", padded)).containsOnlyOnce(padded);
    }

    private SkillRegistry replacementRegistry() {
        registry.stopWatching();
        return new SkillRegistry(new SkillStateService(
                new com.fasterxml.jackson.databind.ObjectMapper(), workspace.resolve("other-states.json").toString()));
    }

    private String render(boolean tool, String name, String args) {
        if (tool) {
            ToolResult result = executor.execute(name, args, context);
            assertThat(result.isError()).isFalse();
            return result.content();
        }
        var result = command.execute(name + (args == null || args.isEmpty() ? "" : " " + args), null);
        assertThat(result.isSuccess()).isTrue();
        return result.value();
    }
}

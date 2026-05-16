package com.aicodeassistant.skill;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SkillToolValidatorTest {

    private SkillToolValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SkillToolValidator();
    }

    // ======== helper: build a SkillDefinition with custom allowedTools / context ========

    private SkillDefinition skillWith(List<String> allowedTools, String context) {
        FrontmatterData fm = new FrontmatterData(
                "desc", "test-skill", allowedTools, null, List.of(),
                null, null, null, false, true,
                Map.of(), null, context, null, List.of(), "bash"
        );
        return new SkillDefinition("test-skill", "test-skill.md", fm, "body", SkillDefinition.SkillSource.PROJECT, null);
    }

    private SkillDefinition skillWithAllowedTools(List<String> allowedTools) {
        return skillWith(allowedTools, "inline");
    }

    // ====================== validate() tests ======================

    @Test
    void testAllowedToolInWhitelist_Passes() {
        SkillDefinition skill = skillWithAllowedTools(List.of("Bash", "FileRead"));
        ToolInput input = ToolInput.from(Map.of());

        SkillToolValidator.ValidationResult result = validator.validate(skill, "Bash", input);
        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void testAllowedToolCaseInsensitive_Passes() {
        SkillDefinition skill = skillWithAllowedTools(List.of("Bash", "FileRead"));
        ToolInput input = ToolInput.from(Map.of());

        SkillToolValidator.ValidationResult result = validator.validate(skill, "bash", input);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testDisallowedTool_Rejected() {
        SkillDefinition skill = skillWithAllowedTools(List.of("Bash", "FileRead"));
        ToolInput input = ToolInput.from(Map.of());

        SkillToolValidator.ValidationResult result = validator.validate(skill, "FileWrite", input);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("not in allowed-tools");
    }

    @Test
    void testNullAllowedTools_AllowsAll() {
        SkillDefinition skill = skillWith(null, "inline");
        ToolInput input = ToolInput.from(Map.of());

        SkillToolValidator.ValidationResult result = validator.validate(skill, "AnyTool", input);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testEmptyAllowedTools_AllowsAll() {
        SkillDefinition skill = skillWithAllowedTools(List.of());
        ToolInput input = ToolInput.from(Map.of());

        SkillToolValidator.ValidationResult result = validator.validate(skill, "AnyTool", input);
        assertThat(result.allowed()).isTrue();
    }

    // ====================== validateArgs() tests ======================

    @Test
    void testSafeArgs_Passes() {
        var result = validator.validateArgs("test", Map.of("target", "src/Main.java", "action", "format"));
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testDangerousShellInjection_DollarParen_Rejected() {
        var result = validator.validateArgs("test", Map.of("cmd", "echo $(whoami)"));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("dangerous");
    }

    @Test
    void testDangerousShellInjection_Backtick_Rejected() {
        var result = validator.validateArgs("test", Map.of("cmd", "echo `id`"));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("dangerous");
    }

    @Test
    void testDangerousShellInjection_Semicolon_Rejected() {
        var result = validator.validateArgs("test", Map.of("cmd", "; rm -rf /"));
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void testArgsTooLong_Rejected() {
        String longArg = "x".repeat(2001);
        var result = validator.validateArgs("test", Map.of("data", longArg));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("exceeds max length");
    }

    @Test
    void testArgsExactly2000_Passes() {
        String exactArg = "x".repeat(2000);
        var result = validator.validateArgs("test", Map.of("data", exactArg));
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testNullArgs_Passes() {
        var result = validator.validateArgs("test", null);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testEmptyArgs_Passes() {
        var result = validator.validateArgs("test", Map.of());
        assertThat(result.allowed()).isTrue();
    }

    // ====================== validateForkPermission() tests ======================

    @Test
    void testForkNormalDepth_Passes() {
        SkillDefinition skill = skillWith(null, "fork");
        ToolUseContext ctx = ToolUseContext.of("/tmp", "session1").withNestingDepth(1);

        var result = validator.validateForkPermission(skill, ctx);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void testForkExceedsMaxDepth_Rejected() {
        SkillDefinition skill = skillWith(null, "fork");
        ToolUseContext ctx = ToolUseContext.of("/tmp", "session1").withNestingDepth(3);

        var result = validator.validateForkPermission(skill, ctx);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("nesting depth");
    }

    @Test
    void testForkAtMaxDepth_Rejected() {
        SkillDefinition skill = skillWith(null, "fork");
        ToolUseContext ctx = ToolUseContext.of("/tmp", "session1").withNestingDepth(5);

        var result = validator.validateForkPermission(skill, ctx);
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void testNonForkSkill_AlwaysPasses() {
        SkillDefinition skill = skillWith(null, "inline");
        ToolUseContext ctx = ToolUseContext.of("/tmp", "session1").withNestingDepth(100);

        var result = validator.validateForkPermission(skill, ctx);
        assertThat(result.allowed()).isTrue();
    }
}

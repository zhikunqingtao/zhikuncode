package com.aicodeassistant.skill;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Skill 安全拦截器 — 运行时验证技能的工具权限、参数安全性和 fork 嵌套深度。
 */
@Service
public class SkillToolValidator {
    private static final Logger log = LoggerFactory.getLogger(SkillToolValidator.class);

    private static final int MAX_ARG_LENGTH = 2000;
    private static final int MAX_FORK_NESTING_DEPTH = 3;

    // 危险参数模式: shell注入
    // 注意：仅由 validateArgs() 应用；validateText()（受信任的内置补充说明技能及内置 review）不应用这些模式。
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
        Pattern.compile("\\$\\(.*\\)"),      // $(command)
        Pattern.compile("`.*`"),              // `command`
        Pattern.compile(";\\s*\\w+"),         // ; command
        Pattern.compile("\\|\\s*\\w+"),       // | command (pipe to another)
        Pattern.compile("&&\\s*rm\\s"),       // && rm
        Pattern.compile("\\$\\{.*\\}")        // ${var}
    );

    /**
     * 验证Skill是否允许使用指定工具
     */
    public ValidationResult validate(SkillDefinition skill, String toolName, ToolInput input) {
        List<String> allowedTools = skill.frontmatter().allowedTools();
        if (allowedTools == null || allowedTools.isEmpty()) {
            return ValidationResult.allow(); // 未指定白名单=允许所有
        }
        if (allowedTools.stream().anyMatch(t -> t.equalsIgnoreCase(toolName))) {
            return ValidationResult.allow();
        }
        return ValidationResult.deny("Tool '" + toolName + "' is not in allowed-tools list for skill '"
            + skill.effectiveName() + "'. Allowed: " + allowedTools);
    }

    /**
     * 验证参数安全性（防注入攻击）
     */
    public ValidationResult validateArgs(String skillName, Map<String, String> args) {
        if (args == null || args.isEmpty()) return ValidationResult.allow();

        for (Map.Entry<String, String> entry : args.entrySet()) {
            String value = entry.getValue();
            if (value == null) continue;

            // 长度检查
            ValidationResult lengthValidation = validateText(skillName, entry.getKey(), value);
            if (!lengthValidation.allowed()) return lengthValidation;

            // 危险模式检查
            for (Pattern pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(value).find()) {
                    log.warn("[SKILL-SECURITY] Dangerous pattern detected in arg '{}' for skill '{}': {}",
                        entry.getKey(), skillName, pattern.pattern());
                    return ValidationResult.deny("Potentially dangerous content detected in argument '"
                        + entry.getKey() + "' for skill '" + skillName + "'");
                }
            }
        }
        return ValidationResult.allow();
    }

    /**
     * 验证提示文本（仅长度检查）— 受信任内置技能的参数验证契约。
     *
     * <p>适用范围：source 为 BUNDLED 且接受补充说明的技能
     * （见 {@link SkillDefinition#acceptsSupplementaryInstructions()}），以及内置 {@code review}。
     * 这些输入被视为追加到技能提示中的提示文本，本代码不会执行它们。
     *
     * <p>仅应用长度限制（{@code MAX_ARG_LENGTH}）；有意<b>不</b>应用 {@code DANGEROUS_PATTERNS}，
     * 以便 Markdown/代码示例（如反引号、管道、{@code $(...)}）按字面保留。这是相对
     * {@link #validateArgs(String, Map)} 的有意放宽，仅针对受信任的内置技能。
     *
     * <p>其他所有技能（project/user/plugin/MCP 来源，以及不在白名单中的内置技能，
     * 包括 publish-oss/publish-meoo）仍通过 {@link #validateArgs(String, Map)} 进行
     * 长度与 {@code DANGEROUS_PATTERNS} 检查。
     *
     * <p>本契约仅作用于 SkillExecutor（Skill 工具）路径；{@code /skill} 斜杠命令（SkillCommand）直接渲染提示，不经过本校验器。
     */
    public ValidationResult validateText(String skillName, String argumentName, String text) {
        if (text != null && text.length() > MAX_ARG_LENGTH) {
            return ValidationResult.deny("Argument '" + argumentName
                    + "' exceeds max length " + MAX_ARG_LENGTH + " for skill '" + skillName + "'");
        }
        return ValidationResult.allow();
    }

    /**
     * Fork模式额外权限检查
     */
    public ValidationResult validateForkPermission(SkillDefinition skill, ToolUseContext context) {
        if (!skill.frontmatter().isFork()) {
            return ValidationResult.allow(); // 非fork模式无需检查
        }
        if (context.nestingDepth() >= MAX_FORK_NESTING_DEPTH) {
            return ValidationResult.deny("Fork rejected: nesting depth " + context.nestingDepth()
                + " exceeds maximum " + MAX_FORK_NESTING_DEPTH);
        }
        return ValidationResult.allow();
    }

    // ValidationResult内嵌record
    public record ValidationResult(boolean allowed, String reason) {
        public static ValidationResult allow() { return new ValidationResult(true, null); }
        public static ValidationResult deny(String reason) { return new ValidationResult(false, reason); }
    }
}

package com.aicodeassistant.skill;

import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.skill.SkillTokenBudget.BudgetStatus;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 技能执行器 — Markdown 解析结果 → 参数替换 → 执行完成。
 * <p>
 * 完整执行流程:
 * <ol>
 *     <li>SkillTool.call() 接收 skill 名称和参数</li>
 *     <li>SkillRegistry.resolve(name) → SkillDefinition</li>
 *     <li>ArgumentSubstitution.substitute(content, args) → 渲染后的提示词</li>
 *     <li>根据 context='inline'|'fork' 决定执行模式</li>
 * </ol>
 *
 */
@Service
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final SkillRegistry skillRegistry;
    private final SkillToolValidator skillToolValidator;
    private final SkillTokenBudget skillTokenBudget;
    private final TokenCounter tokenCounter;

    /**
     * Skill execution timeout in seconds.
     * Timeout hierarchy: Tool(30s) ⊂ Skill(120s) ⊂ Agent(600s)
     * - Tool level: Managed by individual tool implementations (e.g., BashTool command timeout)
     * - Skill level: This constant (120s)
     * - Agent level: AgentTimeoutConfig (600s)
     */
    private static final long SKILL_TIMEOUT_SECONDS = 120;

    public SkillExecutor(SkillRegistry skillRegistry, SkillToolValidator skillToolValidator,
                         SkillTokenBudget skillTokenBudget, TokenCounter tokenCounter) {
        this.skillRegistry = skillRegistry;
        this.skillToolValidator = skillToolValidator;
        this.skillTokenBudget = skillTokenBudget;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 执行技能 — 核心入口。
     *
     * @param skillName    技能名称
     * @param args         用户参数字符串
     * @param context      工具使用上下文
     * @return 执行结果
     */
    public ToolResult execute(String skillName, String args, ToolUseContext context) {
        // 1. 查找技能定义
        SkillDefinition skill = skillRegistry.resolve(skillName);
        if (skill == null) {
            return ToolResult.error("Skill not found: " + skillName
                    + ". Available skills: " + skillRegistry.getAllSkills().stream()
                    .map(SkillDefinition::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none"));
        }

        log.info("Executing skill: {} (source={}, context={})",
                skill.name(), skill.source(), skill.frontmatter().context());

        // 2. 参数解析和替换
        Map<String, String> params = skill.parseArgs(args);

        // 2.5 参数安全验证
        SkillToolValidator.ValidationResult argsValidation = skillToolValidator.validateArgs(skill.effectiveName(), params);
        if (!argsValidation.allowed()) {
            log.warn("[SKILL] Args validation failed for '{}': {}", skill.effectiveName(), argsValidation.reason());
            return ToolResult.error("Skill argument validation failed: " + argsValidation.reason());
        }

        String renderedPrompt = skill.renderTemplate(params);

        // 2.7 Token预算检查
        int estimatedRequestTokens = tokenCounter.estimateTokens(renderedPrompt);
        if (!skillTokenBudget.canConsume(context.sessionId(), skillName, estimatedRequestTokens)) {
            BudgetStatus status = skillTokenBudget.getStatus(context.sessionId(), skillName);
            return ToolResult.error(String.format(
                    "Skill token budget exceeded. Skill '%s' used %d/%d tokens, session total %d/%d",
                    skillName, status.skillUsed(), SkillTokenBudget.SINGLE_SKILL_BUDGET,
                    status.sessionUsed(), SkillTokenBudget.TOTAL_SESSION_BUDGET));
        }

        // 3. Fork权限验证
        if (skill.frontmatter().isFork()) {
            SkillToolValidator.ValidationResult forkValidation = skillToolValidator.validateForkPermission(skill, context);
            if (!forkValidation.allowed()) {
                log.warn("[SKILL] Fork validation failed for '{}': {}", skill.effectiveName(), forkValidation.reason());
                return ToolResult.error("Skill fork permission denied: " + forkValidation.reason());
            }
        }

        // 4. 执行模式分发 (with timeout protection)
        ToolResult result;
        try {
            result = CompletableFuture.supplyAsync(() -> {
                if (skill.frontmatter().isFork()) {
                    return executeFork(skill, renderedPrompt, context);
                } else {
                    return executeInline(skill, renderedPrompt, context);
                }
            }).orTimeout(SKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof TimeoutException) {
                log.warn("[SKILL] Execution timed out after {}s for skill '{}'", SKILL_TIMEOUT_SECONDS, skillName);
                // Record estimated consumption on timeout
                skillTokenBudget.recordConsumption(context.sessionId(), skillName, estimatedRequestTokens);
                return ToolResult.error("Skill '" + skillName + "' execution timed out after " + SKILL_TIMEOUT_SECONDS + " seconds");
            }
            log.error("[SKILL] Unexpected error executing skill '{}': {}", skillName, ex.getMessage(), ex);
            return ToolResult.error("Skill execution failed: " + ex.getMessage());
        }

        // 5. 记录实际token消耗
        int actualTokens = tokenCounter.estimateTokens(result.content() != null ? result.content() : "");
        skillTokenBudget.recordConsumption(context.sessionId(), skillName, actualTokens);

        return result;
    }

    /**
     * Fork 模式 — 创建独立子代理执行。
     * <p>
     * 返回提示词和技能元信息，由外层 AgentService 负责实际子代理创建。
     */
    private ToolResult executeFork(SkillDefinition skill, String renderedPrompt,
                                    ToolUseContext context) {
        log.info("Skill '{}' executing in fork mode", skill.name());

        // 构建 fork 执行结果 — 包含子代理所需信息
        return ToolResult.success(
                "Skill '" + skill.name() + "' loaded in fork mode.\n\n"
                        + "Rendered prompt:\n" + renderedPrompt
        ).withMetadata("skillName", skill.name())
                .withMetadata("executionMode", "fork")
                .withMetadata("renderedPrompt", renderedPrompt)
                .withMetadata("agent", skill.frontmatter().agent())
                .withMetadata("model", skill.frontmatter().resolvedModel())
                .withMetadata("effort", skill.frontmatter().effort());
    }

    /**
     * Inline 模式 — 将渲染后的提示词作为用户消息注入当前对话。
     */
    private ToolResult executeInline(SkillDefinition skill, String renderedPrompt,
                                      ToolUseContext context) {
        log.info("Skill '{}' executing in inline mode", skill.name());

        return new ToolResult(
                "Skill '" + skill.name() + "' loaded. Prompt injected.",
                false,
                Map.of(
                        "injectedPrompt", renderedPrompt,
                        "commandName", skill.name(),
                        "executionMode", "inline",
                        "model", skill.frontmatter().resolvedModel() != null
                                ? skill.frontmatter().resolvedModel() : "inherit"
                )
        );
    }
}

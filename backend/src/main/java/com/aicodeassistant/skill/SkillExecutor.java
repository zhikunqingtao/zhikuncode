package com.aicodeassistant.skill;

import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

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
 * @see <a href="SPEC §4.7.3">SkillExecutor 执行桥接</a>
 */
@Service
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);

    private final SkillRegistry skillRegistry;

    /** 默认技能执行超时 */
    private static final Duration SKILL_TIMEOUT = Duration.ofMinutes(5);

    public SkillExecutor(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
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
        String renderedPrompt = skill.renderTemplate(params);

        // 3. 执行模式分发
        if (skill.frontmatter().isFork()) {
            return executeFork(skill, renderedPrompt, context);
        } else {
            return executeInline(skill, renderedPrompt, context);
        }
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

package com.aicodeassistant.skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能定义 — 解析后的完整技能对象。
 * <p>
 * 包含 frontmatter 元数据、Markdown 正文内容以及加载来源信息。
 *
 * @param name          技能名称（不含 / 前缀）
 * @param fileName      原始文件名
 * @param frontmatter   YAML 前置数据
 * @param content       Markdown 正文内容（用于模板渲染）
 * @param source        加载来源: bundled/managed/user/project/plugin/mcp
 * @param filePath      文件绝对路径（null 表示内置技能）
 */
public record SkillDefinition(
        String name,
        String fileName,
        FrontmatterData frontmatter,
        String content,
        SkillSource source,
        String filePath
) {

    private static final Set<String> SUPPLEMENTARY_INSTRUCTION_SKILLS = Set.of(
            "commit", "pr", "test", "verify", "debug", "remember",
            "software-architecture", "prompt-engineering", "csv-data-summarizer",
            "test-driven-development");

    /** Stable settings key, independent of a display name/frontmatter alias. */
    public String id() {
        return SkillStateService.canonicalId(name);
    }

    /**
     * 技能加载来源
     */
    public enum SkillSource {
        BUNDLED,    // 内置技能
        MANAGED,    // 企业管理技能
        USER,       // 用户全局技能 (~/.zhikun/skills/)
        PROJECT,    // 项目技能 (.zhikun/skills/)
        PLUGIN,     // 插件提供的技能
        MCP         // MCP 构建的技能
    }

    /**
     * 获取有效描述 — frontmatter 优先，fallback 到文件名。
     */
    public String effectiveDescription() {
        if (frontmatter.description() != null) {
            return frontmatter.description();
        }
        return "Skill: " + name;
    }

    /**
     * 获取有效名称 — frontmatter.name 优先，fallback 到文件名。
     */
    public String effectiveName() {
        if (frontmatter.name() != null) {
            return frontmatter.name();
        }
        return name;
    }

    /**
     * 是否允许用户直接调用。
     */
    public boolean isUserInvocable() {
        return frontmatter.userInvocable();
    }

    /**
     * 解析参数 — 将字符串参数解析为 key=value 或位置参数。
     */
    public Map<String, String> parseArgs(String args) {
        return ArgumentSubstitution.parseArgs(args, frontmatter.arguments());
    }

    /**
     * 渲染模板 — 替换 {{param_name}} 变量。
     */
    public String renderTemplate(Map<String, String> params) {
        return ArgumentSubstitution.substitute(content, params);
    }

    public boolean acceptsSupplementaryInstructions() {
        return source == SkillSource.BUNDLED && SUPPLEMENTARY_INSTRUCTION_SKILLS.contains(name);
    }

    /** Preserve prose beyond positional parameters without recursively substituting user text. */
    public String renderPrompt(Map<String, String> params, String rawArgs) {
        String rendered = renderTemplate(params);
        if (acceptsSupplementaryInstructions() && rawArgs != null && !rawArgs.isBlank()) {
            rendered += "\n\n## 本次用户补充说明\n\n"
                    + "请结合以下完整说明判断任务范围和限制。对于已声明参数，显式命名参数（key=value）优先于位置参数，同名命名参数重复时采用最后一个值。"
                    + "若模板参数因空白切分而不完整，应核对完整原文，不要将截断片段当作完整路径或范围；仍有歧义时先澄清。\n\n"
                    + rawArgs;
        }
        return rendered;
    }

    /**
     * 从 Markdown 文件内容创建 SkillDefinition。
     */
    public static SkillDefinition fromMarkdown(String fileName, String rawContent,
                                                SkillSource source, String filePath) {
        FrontmatterParser.ParsedMarkdown parsed = FrontmatterParser.parse(rawContent);
        String skillName = fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3)
                : fileName;
        return new SkillDefinition(
                skillName, fileName, parsed.frontmatter(),
                parsed.content(), source, filePath
        );
    }
}

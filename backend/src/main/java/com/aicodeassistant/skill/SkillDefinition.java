package com.aicodeassistant.skill;

import java.util.List;
import java.util.Map;

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
 * @see <a href="SPEC §4.7">Skill 系统</a>
 */
public record SkillDefinition(
        String name,
        String fileName,
        FrontmatterData frontmatter,
        String content,
        SkillSource source,
        String filePath
) {

    /**
     * 技能加载来源 — 对应源码 5 种 LoadedFrom。
     */
    public enum SkillSource {
        BUNDLED,    // 内置技能
        MANAGED,    // 企业管理技能
        USER,       // 用户全局技能 (~/.claude/skills/)
        PROJECT,    // 项目技能 (.claude/skills/)
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

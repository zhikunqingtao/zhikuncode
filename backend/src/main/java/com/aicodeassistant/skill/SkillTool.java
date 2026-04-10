package com.aicodeassistant.skill;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillTool — 执行预定义的技能工作流。
 * <p>
 * 输入 Schema:
 * <pre>
 * {
 *   "skill": string (必需) - 技能名称或路径
 *   "args":  string (可选) - 技能参数（字符串形式）
 * }
 * </pre>
 * <p>
 * 权限: NONE, isConcurrencySafe: true, shouldDefer: true
 *
 * @see <a href="SPEC §4.1.20">SkillTool 定义</a>
 * @see <a href="SPEC §4.7">Skill 系统</a>
 */
@Component
public class SkillTool implements Tool {

    private final SkillExecutor skillExecutor;
    private final SkillRegistry skillRegistry;

    public SkillTool(SkillExecutor skillExecutor, SkillRegistry skillRegistry) {
        this.skillExecutor = skillExecutor;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "Skill";
    }

    @Override
    public String getDescription() {
        return "Execute a predefined skill workflow. "
                + "Skills are Markdown files with YAML frontmatter that define reusable workflows.";
    }

    @Override
    public String prompt() {
        return """
                Execute a skill within the main conversation
                
                When users ask you to perform tasks, check if any of the available skills match. \
                Skills provide specialized capabilities and domain knowledge.
                
                When users reference a "slash command" or "/<something>" (e.g., "/commit", \
                "/review-pr"), they are referring to a skill. Use this tool to invoke it.
                
                How to invoke:
                - Use this tool with the skill name and optional arguments
                - Examples:
                  - `skill: "pdf"` - invoke the pdf skill
                  - `skill: "commit", args: "-m 'Fix bug'"` - invoke with arguments
                  - `skill: "review-pr", args: "123"` - invoke with arguments
                  - `skill: "ms-office-suite:pdf"` - invoke using fully qualified name
                
                Important:
                - Available skills are listed in system-reminder messages in the conversation
                - When a skill matches the user's request, invoke the Skill tool BEFORE \
                generating any other response
                - NEVER mention a skill without actually calling this tool
                - Do not invoke a skill that is already running
                - Do not use this tool for built-in CLI commands (like /help, /clear, etc.)
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skill", Map.of(
                "type", "string",
                "description", "The skill name or path to execute"
        ));
        properties.put("args", Map.of(
                "type", "string",
                "description", "Arguments for the skill (space-separated or key=value format)"
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("skill")
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String skillName = input.getString("skill")
                .replaceFirst("^/", ""); // 去除可能的 "/" 前缀
        String args = input.getString("args", "");

        return skillExecutor.execute(skillName, args, context);
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        // Skill 本身只做模板渲染，不直接修改文件
        return true;
    }

    @Override
    public String getGroup() {
        return "skill";
    }
}

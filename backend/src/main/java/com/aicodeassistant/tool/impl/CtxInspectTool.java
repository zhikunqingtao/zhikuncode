package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CtxInspectTool implements Tool {

    @Override public String getName() { return "CtxInspect"; }
    @Override public String getDescription() {
        return "检查当前会话上下文状态（消息数、Token 用量、工具调用统计）";
    }
    @Override public String getGroup() { return "system"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "detail_level", Map.of("type", "string", "description", "summary|detailed, 默认 summary")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String level = input.getString("detail_level", "summary");
        StringBuilder sb = new StringBuilder("## 上下文检查\n\n");
        sb.append("- 会话 ID: ").append(context.sessionId()).append("\n");
        sb.append("- 工作目录: ").append(context.workingDirectory()).append("\n");
        sb.append("- 嵌套深度: ").append(context.nestingDepth()).append("\n");
        return ToolResult.success(sb.toString());
    }

    @Override public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }
}

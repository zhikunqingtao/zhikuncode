package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class SnipTool implements Tool {

    @Override public String getName() { return "Snip"; }
    @Override public String getDescription() {
        return "提取文件中指定范围的代码片段，支持行号/符号定位";
    }
    @Override public String getGroup() { return "code_intelligence"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("file_path"),
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "文件路径"),
                "start_line", Map.of("type", "integer", "description", "开始行号"),
                "end_line", Map.of("type", "integer", "description", "结束行号"),
                "symbol", Map.of("type", "string", "description", "符号名称（函数/类/方法）"),
                "context_lines", Map.of("type", "integer", "description", "上下文行数，默认 3")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String filePath = input.getString("file_path");
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            int start, end;
            if (input.has("symbol")) {
                String symbol = input.getString("symbol");
                int ctx = input.getInt("context_lines", 3);
                int symLine = findSymbolLine(lines, symbol);
                if (symLine < 0) return ToolResult.error("未找到符号: " + symbol);
                start = Math.max(0, symLine - ctx);
                end = Math.min(lines.size() - 1, findSymbolEnd(lines, symLine) + ctx);
            } else {
                start = input.getInt("start_line", 1) - 1;
                end = input.getInt("end_line", lines.size()) - 1;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, start); i <= Math.min(lines.size() - 1, end); i++) {
                sb.append(String.format("%6d│ %s%n", i + 1, lines.get(i)));
            }
            return ToolResult.success(sb.toString());
        } catch (Exception e) {
            return ToolResult.error("代码片段提取失败: " + e.getMessage());
        }
    }

    private int findSymbolLine(List<String> lines, String symbol) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(symbol)) return i;
        }
        return -1;
    }

    private int findSymbolEnd(List<String> lines, int startLine) {
        int depth = 0;
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);
            depth += line.chars().filter(c -> c == '{').count();
            depth -= line.chars().filter(c -> c == '}').count();
            if (depth <= 0 && i > startLine) return i;
        }
        return Math.min(startLine + 30, lines.size() - 1);
    }
}

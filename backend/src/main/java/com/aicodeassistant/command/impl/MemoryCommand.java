package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.ProjectMemoryService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class MemoryCommand implements Command {

    private final ProjectMemoryService memoryService;

    public MemoryCommand(ProjectMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override public String getName() { return "memory"; }
    @Override public String getDescription() { return "Manage project memory (zhikun.md)"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置，无法加载记忆文件");
        }
        Path workingDir = Path.of(context.workingDir()).normalize();

        if (args == null || args.isBlank() || args.equals("show")) {
            String memory = memoryService.loadMemory(workingDir);
            boolean hasMemory = !memory.isBlank();
            return CommandResult.jsx(Map.of(
                "action", "showMemoryFiles",
                "workingDir", context.workingDir(),
                "hasMemory", hasMemory,
                "content", hasMemory ? memory : "",
                "files", List.of("zhikun.md", "zhikun.local.md")
            ));
        }

        if (args.equals("init")) {
            try {
                String template = """
                    # 项目记忆
                    
                    ## 技术栈
                    <!-- 在此填写项目技术栈信息 -->
                    
                    ## 编码规范
                    <!-- 在此填写编码规范 -->
                    
                    ## 注意事项
                    <!-- 在此填写项目注意事项 -->
                    """;
                memoryService.writeMemory(workingDir, template, false);
                return CommandResult.jsx(Map.of(
                    "action", "memoryCreated",
                    "fileName", "zhikun.md",
                    "workingDir", context.workingDir()
                ));
            } catch (IOException e) {
                return CommandResult.error("创建失败: " + e.getMessage());
            }
        }

        return CommandResult.error("未知子命令。用法: /memory [show|init]");
    }
}

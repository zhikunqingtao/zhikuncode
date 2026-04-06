package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /memory — 记忆管理，查看/编辑 CLAUDE.md 内存文件层级。
 *
 * @see <a href="SPEC §3.3.4a.7">/memory 命令</a>
 */
@Component
public class MemoryCommand implements Command {

    @Override public String getName() { return "memory"; }
    @Override public String getDescription() { return "View and manage memory files (CLAUDE.md)"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String workDir = context.workingDir();

        StringBuilder sb = new StringBuilder();
        sb.append("Memory Files:\n\n");
        sb.append("  ~/.claude/CLAUDE.md          (global)\n");

        if (workDir != null) {
            sb.append("  ").append(workDir).append("/CLAUDE.md       (project)\n");
            sb.append("  ").append(workDir).append("/CLAUDE.local.md (personal)\n");
        }

        sb.append("\nUse /memory to open and edit memory files.");

        return CommandResult.jsx(Map.of(
                "action", "showMemoryFiles",
                "workingDir", workDir != null ? workDir : "",
                "display", sb.toString()
        ));
    }
}

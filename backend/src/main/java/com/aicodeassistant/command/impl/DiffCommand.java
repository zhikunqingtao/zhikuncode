package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * /diff — 显示本次会话的文件改动。
 *
 * @see <a href="SPEC §3.3.2">/diff 命令</a>
 */
@Component
public class DiffCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(DiffCommand.class);

    @Override public String getName() { return "diff"; }
    @Override public String getDescription() { return "Show file changes in this session"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD")
                    .directory(context.workingDir() != null
                            ? new java.io.File(context.workingDir()) : null)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return CommandResult.error("git diff failed with exit code: " + exitCode);
            }

            if (output.isBlank()) {
                return CommandResult.text("No uncommitted changes.");
            }

            return CommandResult.text(output);

        } catch (Exception e) {
            log.error("Diff command failed: {}", e.getMessage(), e);
            return CommandResult.error("Failed to get diff: " + e.getMessage());
        }
    }
}

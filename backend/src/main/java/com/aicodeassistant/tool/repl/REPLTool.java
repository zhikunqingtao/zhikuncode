package com.aicodeassistant.tool.repl;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REPLTool — 交互式代码解释器工具。
 * <p>
 * 启动交互式 REPL 会话，支持在进程内执行代码并返回输出。
 * <p>
 * 数据流: LLM -> REPLTool -> ProcessBuilder(PTY) -> WebSocket -> xterm.js
 * <p>
 * 支持语言 (P1): python
 * 支持语言 (P2): node, ruby
 * <p>
 * 生命周期:
 * - 最大并发 REPL 会话数: 3
 * - 单次执行超时: 30秒
 * - 会话空闲超时: 10分钟 (自动 destroy)
 * - 会话总超时: 1小时 (强制销毁)
 *
 * @see <a href="SPEC §4.1.16">REPLTool</a>
 */
@Component
public class REPLTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(REPLTool.class);

    private final ReplManager replManager;

    public REPLTool(ReplManager replManager) {
        this.replManager = replManager;
    }

    @Override
    public String getName() {
        return "REPL";
    }

    @Override
    public String getDescription() {
        return "Start an interactive code interpreter session. " +
                "Execute code in a persistent REPL environment with " +
                "session support for maintaining state across calls.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "language", Map.of(
                                "type", "string",
                                "enum", List.of("python", "node", "ruby"),
                                "description", "Programming language for the REPL"
                        ),
                        "code", Map.of(
                                "type", "string",
                                "description", "Code to execute in the REPL"
                        ),
                        "sessionId", Map.of(
                                "type", "string",
                                "description", "Session ID to reuse an existing REPL session"
                        )
                ),
                "required", List.of("code")
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String language = input.getString("language", "python");
        String code = input.getString("code");
        String sessionId = input.getString("sessionId",
                UUID.randomUUID().toString());

        try {
            // 获取或创建会话
            ReplSession session = replManager.getOrCreate(
                    sessionId, language, context.workingDirectory());

            // 向进程 stdin 写入代码
            session.writeStdin(code);

            // 等待输出（带超时）
            String output = session.readAvailableOutput(
                    ReplManager.EXEC_TIMEOUT.toMillis());

            // 读取 stderr
            String stderr = session.readAvailableStderr();

            // 截断保护
            output = replManager.truncateOutput(output);

            // 构建结果
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("replSessionId", sessionId);
            metadata.put("language", language);
            if (!stderr.isEmpty()) {
                metadata.put("stderr", replManager.truncateOutput(stderr));
            }

            return new ToolResult(output, false, metadata);
        } catch (UnsupportedOperationException e) {
            return ToolResult.error(e.getMessage());
        } catch (ReplManager.ReplException e) {
            return ToolResult.error("REPL error: " + e.getMessage());
        } catch (Exception e) {
            log.error("REPL execution failed: {}", e.getMessage(), e);
            return ToolResult.error("REPL execution failed: " + e.getMessage());
        }
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public boolean isDestructive(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return false;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return false;
    }

    @Override
    public String getGroup() {
        return "bash";
    }

    @Override
    public String toAutoClassifierInput(ToolInput input) {
        String language = input.getString("language", "python");
        String code = input.getString("code", "");
        return String.format("REPL(%s): %s", language, code);
    }

    @PreDestroy
    public void cleanup() {
        replManager.destroyAll();
    }
}

package com.aicodeassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具编排层 — 批量执行工具调用，管理分区和并发。
 * <p>
 * 由 QueryEngine 在收到 API 响应后调用。
 * 将 tool_use 块列表分区为并发/串行批次，使用 StreamingToolExecutor 执行。
 *
 * @see <a href="SPEC §3.2.1b">工具执行管线</a>
 * @see <a href="SPEC §3.2.2">工具并发执行引擎</a>
 */
@Service
public class ToolOrchestration {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestration.class);

    private final ToolRegistry toolRegistry;
    private final StreamingToolExecutor executor;

    public ToolOrchestration(ToolRegistry toolRegistry, StreamingToolExecutor executor) {
        this.toolRegistry = toolRegistry;
        this.executor = executor;
    }

    /**
     * 工具调用块 — 从 LLM 响应中提取的工具调用信息。
     */
    public record ToolUseBlock(
            String id,
            String name,
            Map<String, Object> input
    ) {}

    /**
     * 工具结果块 — 发送回 LLM 的工具执行结果。
     */
    public record ToolResultBlock(
            String toolUseId,
            String content,
            boolean isError
    ) {}

    /**
     * 批量执行工具调用 — 按分区策略并发/串行执行。
     *
     * @param toolCalls LLM 返回的 tool_use 块列表
     * @param context   工具执行上下文
     * @return 按原始顺序排列的工具结果
     */
    public List<ToolResultBlock> runTools(List<ToolUseBlock> toolCalls, ToolUseContext context) {
        log.info("Running {} tool calls", toolCalls.size());

        StreamingToolExecutor.ExecutionSession session = executor.newSession();

        // 将所有工具加入执行器
        for (ToolUseBlock call : toolCalls) {
            Tool tool = toolRegistry.findByName(call.name());
            session.addTool(tool, ToolInput.from(call.input()), call.id(), context);
        }

        // 等待所有工具完成，按序收集结果
        List<ToolResultBlock> results = new ArrayList<>();
        while (results.size() < toolCalls.size()) {
            List<StreamingToolExecutor.TrackedTool> yielded = session.yieldCompleted();
            for (StreamingToolExecutor.TrackedTool t : yielded) {
                results.add(new ToolResultBlock(
                        t.getToolUseId(),
                        t.getResult().content(),
                        t.getResult().isError()
                ));
            }
            if (yielded.isEmpty()) {
                try {
                    Thread.sleep(10); // 避免忙等
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("All {} tool calls completed", results.size());
        return results;
    }
}

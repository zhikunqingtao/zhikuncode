package com.aicodeassistant.coordinator;

import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentRequest;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 进程内 Worker 后端 — 使用 Virtual Thread 池并发执行子代理任务。
 * <p>
 * 复用 SubAgentExecutor 执行每个 Worker，通过 Virtual Thread 实现高并发。
 * 默认并发度由 TeamManager 指定（workerCount）。
 *
 * @see <a href="SPEC §11">Team/Swarm 基础设施</a>
 */
@Component
public class InProcessBackend {

    private static final Logger log = LoggerFactory.getLogger(InProcessBackend.class);

    /** 单个 Worker 最大执行时间 */
    private static final long WORKER_TIMEOUT_SECONDS = 300; // 5 min

    private final SubAgentExecutor subAgentExecutor;

    public InProcessBackend(SubAgentExecutor subAgentExecutor) {
        this.subAgentExecutor = subAgentExecutor;
    }

    /**
     * 并行执行多个子代理请求 — 使用 Virtual Thread。
     *
     * @param requests      子代理请求列表
     * @param parentContext 父查询上下文
     * @param maxConcurrency 最大并发数（-1 = 不限制）
     * @return 所有请求的执行结果（顺序与输入对应）
     */
    public List<AgentResult> executeParallel(List<AgentRequest> requests,
                                              ToolUseContext parentContext,
                                              int maxConcurrency) {
        if (requests.isEmpty()) {
            return List.of();
        }

        log.info("InProcessBackend: executing {} tasks, concurrency={}", requests.size(), maxConcurrency);

        // 使用 Virtual Thread Executor
        try (ExecutorService executor = maxConcurrency > 0
                ? Executors.newFixedThreadPool(maxConcurrency, Thread.ofVirtual().factory())
                : Executors.newVirtualThreadPerTaskExecutor()) {

            List<Future<AgentResult>> futures = new ArrayList<>(requests.size());

            for (AgentRequest request : requests) {
                futures.add(executor.submit(() -> {
                    try {
                        log.debug("Worker starting: {}", request.agentId());
                        AgentResult result = subAgentExecutor.executeSync(request, parentContext);
                        log.debug("Worker completed: {} -> {}", request.agentId(), result.status());
                        return result;
                    } catch (Exception e) {
                        log.error("Worker failed: {} -> {}", request.agentId(), e.getMessage(), e);
                        return new AgentResult("completed",
                                "Worker execution failed: " + e.getMessage(),
                                request.prompt(), null);
                    }
                }));
            }

            // 收集结果
            List<AgentResult> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                Future<AgentResult> future = futures.get(i);
                AgentRequest request = requests.get(i);
                try {
                    AgentResult result = future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    results.add(result);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    log.warn("Worker timed out: {}", request.agentId());
                    results.add(new AgentResult("completed",
                            "Worker timed out after " + WORKER_TIMEOUT_SECONDS + " seconds",
                            request.prompt(), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(new AgentResult("completed",
                            "Worker interrupted", request.prompt(), null));
                } catch (ExecutionException e) {
                    log.error("Worker execution error: {}", request.agentId(), e.getCause());
                    results.add(new AgentResult("completed",
                            "Worker error: " + e.getCause().getMessage(),
                            request.prompt(), null));
                }
            }

            log.info("InProcessBackend: all {} tasks completed", results.size());
            return results;
        }
    }

    /**
     * 顺序执行多个子代理请求（测试用或低并发场景）。
     */
    public List<AgentResult> executeSequential(List<AgentRequest> requests,
                                                ToolUseContext parentContext) {
        List<AgentResult> results = new ArrayList<>();
        for (AgentRequest request : requests) {
            try {
                results.add(subAgentExecutor.executeSync(request, parentContext));
            } catch (Exception e) {
                log.error("Sequential worker failed: {}", request.agentId(), e);
                results.add(new AgentResult("completed",
                        "Worker failed: " + e.getMessage(),
                        request.prompt(), null));
            }
        }
        return results;
    }
}

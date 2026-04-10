package com.aicodeassistant.service;

import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 费用追踪服务 — 对齐 Claude Code CostTracker。
 * <p>
 * 基于模型费率表 (inputCostPer1k / outputCostPer1k) 实时累计会话和全局费用。
 * MVP 实现: 内存存储，重启清零。
 */
@Service
public class CostTrackerService {

    private static final Logger log = LoggerFactory.getLogger(CostTrackerService.class);

    private final ModelRegistry modelRegistry;

    /** 会话级费用 — sessionId → CostSummary */
    private final ConcurrentHashMap<String, CostSummary> sessionCosts = new ConcurrentHashMap<>();

    /** 全局累计费用 */
    private final AtomicReference<CostSummary> globalCost = new AtomicReference<>(CostSummary.ZERO);

    public CostTrackerService(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * 记录一次 API 调用的费用。
     *
     * @param sessionId 会话 ID
     * @param model     模型名称
     * @param usage     Token 使用量
     */
    public void recordUsage(String sessionId, String model, Usage usage) {
        ModelCapabilities caps = modelRegistry.getCapabilities(model);
        double inputCost = usage.inputTokens() * caps.costPer1kInput() / 1000.0;
        double outputCost = usage.outputTokens() * caps.costPer1kOutput() / 1000.0;
        double cacheDiscount = usage.cacheReadInputTokens() * caps.costPer1kInput() * 0.9 / 1000.0;
        double totalCost = inputCost + outputCost - cacheDiscount;

        CostSummary delta = new CostSummary(
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens(), totalCost, 1);

        // 会话级累加
        sessionCosts.merge(sessionId, delta, CostSummary::add);

        // 全局累加
        globalCost.updateAndGet(current -> current.add(delta));

        log.debug("Cost recorded: session={}, model={}, cost=${}, total=${}",
                sessionId, model, String.format("%.6f", totalCost),
                String.format("%.6f", globalCost.get().totalCost()));
    }

    /** 获取会话费用 */
    public CostSummary getSessionCost(String sessionId) {
        return sessionCosts.getOrDefault(sessionId, CostSummary.ZERO);
    }

    /** 获取全局费用 */
    public CostSummary getGlobalCost() {
        return globalCost.get();
    }

    /** 获取所有会话费用 */
    public Map<String, CostSummary> getAllSessionCosts() {
        return Map.copyOf(sessionCosts);
    }

    /** 清除会话费用 */
    public void clearSession(String sessionId) {
        sessionCosts.remove(sessionId);
    }

    /** 费用摘要 */
    public record CostSummary(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            double totalCost,
            int apiCalls
    ) {
        public static final CostSummary ZERO = new CostSummary(0, 0, 0, 0.0, 0);

        public CostSummary add(CostSummary other) {
            return new CostSummary(
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens,
                    totalCost + other.totalCost,
                    apiCalls + other.apiCalls
            );
        }
    }
}

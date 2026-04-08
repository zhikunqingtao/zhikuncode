package com.aicodeassistant.service;

import com.aicodeassistant.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * CostTracker — 费用追踪服务。
 * 对齐原版 cost-tracker.ts
 */
@Service
public class CostTrackerService {

    private static final Logger log = LoggerFactory.getLogger(CostTrackerService.class);

    /** 模型定价 (USD per 1M tokens): [input, output] */
    private static final Map<String, double[]> PRICING = Map.ofEntries(
            Map.entry("claude-sonnet-4-20250514", new double[]{3.0, 15.0}),
            Map.entry("claude-3-5-sonnet", new double[]{3.0, 15.0}),
            Map.entry("claude-3-5-sonnet-20241022", new double[]{3.0, 15.0}),
            Map.entry("claude-3-opus", new double[]{15.0, 75.0}),
            Map.entry("claude-3-haiku", new double[]{0.25, 1.25}),
            Map.entry("claude-3-5-haiku", new double[]{0.80, 4.0}),
            Map.entry("gpt-4o", new double[]{2.5, 10.0}),
            Map.entry("gpt-4o-mini", new double[]{0.15, 0.6}),
            Map.entry("gpt-4-turbo", new double[]{10.0, 30.0})
    );

    private static final double[] DEFAULT_PRICING = new double[]{3.0, 15.0};

    /** 会话ID → 累计费用 */
    private final Map<String, DoubleAdder> sessionCosts = new ConcurrentHashMap<>();

    /**
     * 计算单次调用费用。
     */
    public double calculateCost(String model, Usage usage) {
        double[] pricing = resolvePricing(model);
        return (pricing[0] * usage.inputTokens() / 1_000_000.0)
             + (pricing[1] * usage.outputTokens() / 1_000_000.0);
    }

    /**
     * 记录使用量并累计费用。
     */
    public void recordUsage(String sessionId, String model, Usage usage) {
        double cost = calculateCost(model, usage);
        sessionCosts.computeIfAbsent(sessionId, k -> new DoubleAdder()).add(cost);
        log.debug("Cost recorded: session={}, model={}, cost=${}, total=${}",
                sessionId, model, String.format("%.6f", cost),
                String.format("%.6f", getSessionCost(sessionId)));
    }

    /**
     * 获取会话累计费用。
     */
    public double getSessionCost(String sessionId) {
        var adder = sessionCosts.get(sessionId);
        return adder != null ? adder.sum() : 0.0;
    }

    /**
     * Budget 检查: 超过阈值返回 true。
     */
    public boolean isOverBudget(String sessionId, double budgetUsd) {
        return budgetUsd > 0 && getSessionCost(sessionId) >= budgetUsd;
    }

    /**
     * 清除会话费用记录。
     */
    public void clearSession(String sessionId) {
        sessionCosts.remove(sessionId);
    }

    private double[] resolvePricing(String model) {
        if (model == null) return DEFAULT_PRICING;
        // 精确匹配
        double[] pricing = PRICING.get(model);
        if (pricing != null) return pricing;
        // 前缀匹配
        for (var entry : PRICING.entrySet()) {
            if (model.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return DEFAULT_PRICING;
    }
}

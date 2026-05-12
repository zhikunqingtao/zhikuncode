package com.aicodeassistant.model.dto;

import java.util.List;

/**
 * 启发式分析结果 — Python /api/analysis/change-impact 响应映射
 */
public record HeuristicAnalysis(
    int affectedApiCount,
    int indirectImpactCount,
    int potentialImpactCount,
    boolean hasHighConfidenceImpact,
    boolean truncated,
    List<String> filesAffected
) {
    /** 不可用时的降级实例（Python 超时/故障） */
    public static HeuristicAnalysis unavailable() {
        return new HeuristicAnalysis(0, 0, 0, false, true, List.of());
    }
}

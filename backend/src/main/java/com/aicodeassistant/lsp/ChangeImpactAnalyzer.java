package com.aicodeassistant.lsp;

import com.aicodeassistant.lsp.model.CallLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 变更影响分析器 — 基于 LSP Call Hierarchy 评估代码修改的影响范围。
 * <p>
 * 核心能力:
 * <ul>
 *   <li>分析编辑范围内的符号被多少调用者引用</li>
 *   <li>根据调用者数量自动判定风险等级</li>
 *   <li>生成人类可读的影响报告</li>
 * </ul>
 * <p>
 * 风险等级阈值:
 * <ul>
 *   <li>HIGH: 调用者 &gt; 20</li>
 *   <li>MEDIUM: 调用者 5-20</li>
 *   <li>LOW: 调用者 &lt; 5</li>
 * </ul>
 */
@Component
public class ChangeImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ChangeImpactAnalyzer.class);

    /** 风险等级阈值 */
    private static final int HIGH_RISK_THRESHOLD = 20;
    private static final int MEDIUM_RISK_THRESHOLD = 5;

    private final LspCallHierarchyService callHierarchyService;

    public ChangeImpactAnalyzer(LspCallHierarchyService callHierarchyService) {
        this.callHierarchyService = callHierarchyService;
    }

    // ===== 公开类型定义 =====

    /**
     * 风险等级枚举。
     */
    public enum RiskLevel {
        HIGH("高风险"),
        MEDIUM("中风险"),
        LOW("低风险");

        private final String displayName;

        RiskLevel(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 影响报告 — 包含修改影响的完整分析结果。
     *
     * @param modifiedSymbolCount 受影响的符号数量
     * @param impactMap           符号 → 调用者列表的映射
     * @param riskLevel           综合风险等级
     * @param summary             人类可读的影响摘要
     */
    public record ImpactReport(
            int modifiedSymbolCount,
            Map<String, List<CallLocation>> impactMap,
            RiskLevel riskLevel,
            String summary
    ) {
        /**
         * 获取所有调用者的总数。
         */
        public int totalCallerCount() {
            return impactMap.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }
    }

    // ===== 核心分析方法 =====

    /**
     * 分析编辑范围的影响 — 在代码修改前调用，评估修改风险。
     * <p>
     * 分析流程:
     * <ol>
     *   <li>获取编辑范围内的文档符号</li>
     *   <li>对每个符号调用 incomingCalls 获取调用者</li>
     *   <li>聚合结果，计算风险等级</li>
     *   <li>生成影响报告</li>
     * </ol>
     *
     * @param filePath  待修改文件路径
     * @param startLine 修改起始行 (1-based)
     * @param endLine   修改结束行 (1-based)
     * @return 影响分析报告
     */
    public ImpactReport analyzeBeforeEdit(String filePath, int startLine, int endLine) {
        log.info("Analyzing impact for {}:{}-{}", filePath, startLine, endLine);

        Map<String, List<CallLocation>> impactMap = new LinkedHashMap<>();

        // 对编辑范围内的每一行尝试获取 incomingCalls
        // 优化: 实际实现中应先获取 documentSymbol，然后只对范围内的符号分析
        Set<String> analyzedPositions = new HashSet<>();

        for (int line = startLine; line <= endLine; line++) {
            // 简化策略: 对每行首列进行一次 prepareCallHierarchy
            // 完整实现应结合 documentSymbol 精确定位符号位置
            String posKey = filePath + ":" + line;
            if (analyzedPositions.contains(posKey)) {
                continue;
            }
            analyzedPositions.add(posKey);

            List<CallLocation> callers = callHierarchyService.getIncomingCalls(filePath, line, 1);
            if (!callers.isEmpty()) {
                String symbolKey = buildSymbolKey(filePath, line);
                impactMap.put(symbolKey, callers);
            }
        }

        int symbolCount = impactMap.size();
        int totalCallers = impactMap.values().stream()
                .mapToInt(List::size)
                .sum();

        RiskLevel riskLevel = determineRiskLevel(totalCallers);
        String summary = buildSummary(symbolCount, totalCallers, riskLevel, impactMap);

        log.info("Impact analysis complete: {} symbols, {} callers, risk={}", symbolCount, totalCallers, riskLevel);

        return new ImpactReport(symbolCount, impactMap, riskLevel, summary);
    }

    // ===== 风险等级判定 =====

    /**
     * 根据调用者总数判定风险等级。
     * <p>
     * 阈值:
     * - 调用者 &gt; 20 → HIGH
     * - 调用者 5-20 → MEDIUM
     * - 调用者 &lt; 5 → LOW
     */
    private RiskLevel determineRiskLevel(int totalCallers) {
        if (totalCallers > HIGH_RISK_THRESHOLD) {
            return RiskLevel.HIGH;
        } else if (totalCallers >= MEDIUM_RISK_THRESHOLD) {
            return RiskLevel.MEDIUM;
        } else {
            return RiskLevel.LOW;
        }
    }

    // ===== 报告生成 =====

    /**
     * 生成人类可读的影响摘要。
     */
    private String buildSummary(int symbolCount, int totalCallers,
                                 RiskLevel riskLevel, Map<String, List<CallLocation>> impactMap) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("修改了 %d 个符号，影响 %d 个调用方，风险等级: %s",
                symbolCount, totalCallers, riskLevel.name()));

        if (!impactMap.isEmpty()) {
            sb.append("\n\n受影响的符号:");
            impactMap.forEach((symbol, callers) -> {
                sb.append(String.format("\n  • %s — %d 个调用者", symbol, callers.size()));
                // 显示前 3 个调用者
                callers.stream()
                        .limit(3)
                        .forEach(loc -> sb.append("\n      ← ").append(loc.toDisplayString()));
                if (callers.size() > 3) {
                    sb.append(String.format("\n      ... 及其他 %d 个调用者", callers.size() - 3));
                }
            });
        }

        if (riskLevel == RiskLevel.HIGH) {
            sb.append("\n\n⚠️ 高风险修改: 建议先进行充分的回归测试覆盖。");
        }

        return sb.toString();
    }

    /**
     * 构建符号标识键。
     */
    private String buildSymbolKey(String filePath, int line) {
        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        return fileName + ":" + line;
    }
}

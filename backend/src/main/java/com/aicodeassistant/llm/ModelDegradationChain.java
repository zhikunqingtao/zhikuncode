package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型降级链管理 — 当主模型不可用时，提供降级备选方案。
 * <p>
 * 设计原则：
 * - 最多 3 级降级，避免无限回退
 * - 预定义降级链 + 配置覆盖
 * - 降级优先选择同族模型，再选跨厂商备选
 */
@Component
public class ModelDegradationChain {

    private static final Logger log = LoggerFactory.getLogger(ModelDegradationChain.class);

    /** 最多 3 级降级 */
    private static final int MAX_DEGRADATION_DEPTH = 3;

    /** 预定义降级链 — key: 主模型, value: 按优先级排列的降级模型列表 */
    private static final Map<String, List<String>> DEGRADATION_CHAINS = Map.of(
            "claude-sonnet-4-6", List.of("qwen3.7-max", "deepseek-v4-flash"),
            "qwen3.7-max", List.of("qwen3.7-plus", "deepseek-v4-flash"),
            "qwen3.7-plus", List.of("deepseek-v4-flash", "qwen3.7-max")
    );

    /**
     * 获取下一个降级模型。
     *
     * @param currentModelId   当前模型 ID
     * @param degradationLevel 当前降级层级（0-based，0 表示尚未降级）
     * @return 下一个降级模型 ID，或 empty 表示无可用降级
     */
    public Optional<String> getNextFallback(String currentModelId, int degradationLevel) {
        if (degradationLevel >= MAX_DEGRADATION_DEPTH) {
            log.warn("Model degradation depth exceeded for '{}' at level {}",
                    currentModelId, degradationLevel);
            return Optional.empty();
        }

        List<String> chain = DEGRADATION_CHAINS.get(currentModelId);
        if (chain == null || chain.isEmpty()) {
            log.debug("No degradation chain defined for model '{}'", currentModelId);
            return Optional.empty();
        }

        if (degradationLevel < chain.size()) {
            String fallback = chain.get(degradationLevel);
            log.info("Model degradation: '{}' level {} → '{}'",
                    currentModelId, degradationLevel, fallback);
            return Optional.of(fallback);
        }

        return Optional.empty();
    }

    /**
     * 判断是否还有可降级的模型。
     *
     * @param currentModelId 当前模型 ID
     * @param currentLevel   当前降级层级
     * @return true 表示还有可用的降级选项
     */
    public boolean hasFallback(String currentModelId, int currentLevel) {
        if (currentLevel >= MAX_DEGRADATION_DEPTH) return false;

        List<String> chain = DEGRADATION_CHAINS.get(currentModelId);
        return chain != null && currentLevel < chain.size();
    }

    /**
     * 获取模型的完整降级链。
     *
     * @param modelId 模型 ID
     * @return 降级模型列表（可能为空）
     */
    public List<String> getDegradationChain(String modelId) {
        return DEGRADATION_CHAINS.getOrDefault(modelId, List.of());
    }

    /**
     * 获取最大降级深度。
     */
    public int getMaxDegradationDepth() {
        return MAX_DEGRADATION_DEPTH;
    }
}

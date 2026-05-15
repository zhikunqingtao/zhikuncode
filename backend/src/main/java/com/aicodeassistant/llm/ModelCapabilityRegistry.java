package com.aicodeassistant.llm;

import com.aicodeassistant.config.ModelCapabilityConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型能力参数注册表 — 提供模型特定的参数查询。
 * <p>
 * 数据来源优先级：
 * 1. application.yml 配置（通过 ModelCapabilityConfig 注入）
 * 2. 硬编码预定义模型配置
 * 3. 默认值（DEFAULT）
 * <p>
 * 核心职责：
 * - 查询模型的 tokenCharRatio（用于 Token 估算）
 * - 动态计算压缩阈值（基于 contextWindow）
 * - 动态计算 Buffer Token 数
 */
@Component
public class ModelCapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilityRegistry.class);

    /**
     * 模型能力定义。
     */
    public record ModelCapability(
            String modelId,
            int contextWindow,
            int outputMaxTokens,
            double tokenCharRatio,
            boolean supportsToolUse,
            boolean supportsVision,
            boolean supportsStreaming,
            boolean supportsCache,
            int rateLimitRpm,
            int rateLimitTpm
    ) {}

    /** 默认模型能力（当模型未配置时使用） */
    private static final ModelCapability DEFAULT = new ModelCapability(
            "default", 128_000, 8_192, 3.5,
            true, false, true, false,
            60, 1_000_000
    );

    /** 硬编码预定义模型配置 */
    private static final Map<String, ModelCapability> BUILTIN_CAPABILITIES = Map.of(
            "claude-sonnet-4-20250514", new ModelCapability(
                    "claude-sonnet-4-20250514", 200_000, 64_000, 3.5,
                    true, true, true, true, 60, 1_000_000),
            "qwen-max", new ModelCapability(
                    "qwen-max", 128_000, 8_192, 2.5,
                    true, false, true, false, 60, 1_000_000),
            "qwen-plus", new ModelCapability(
                    "qwen-plus", 128_000, 8_192, 2.5,
                    true, false, true, false, 60, 1_000_000),
            "deepseek-chat", new ModelCapability(
                    "deepseek-chat", 64_000, 8_192, 2.8,
                    true, false, true, false, 60, 1_000_000)
    );

    /** 合并后的能力注册表（YAML 覆盖 > 硬编码） */
    private final Map<String, ModelCapability> registry = new ConcurrentHashMap<>();

    private final ModelCapabilityConfig config;

    public ModelCapabilityRegistry(ModelCapabilityConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        // 先加载硬编码预定义
        registry.putAll(BUILTIN_CAPABILITIES);

        // 再用 YAML 配置覆盖
        if (config.getCapabilities() != null) {
            config.getCapabilities().forEach((modelId, props) -> {
                ModelCapability cap = new ModelCapability(
                        modelId,
                        props.getContextWindow() > 0 ? props.getContextWindow()
                                : getBuiltinOrDefault(modelId).contextWindow(),
                        props.getOutputMaxTokens() > 0 ? props.getOutputMaxTokens()
                                : getBuiltinOrDefault(modelId).outputMaxTokens(),
                        props.getTokenCharRatio() > 0 ? props.getTokenCharRatio()
                                : getBuiltinOrDefault(modelId).tokenCharRatio(),
                        props.isSupportsToolUse(),
                        props.isSupportsVision(),
                        props.isSupportsStreaming(),
                        props.isSupportsCache(),
                        props.getRateLimitRpm(),
                        props.getRateLimitTpm()
                );
                registry.put(modelId, cap);
            });
        }

        log.info("ModelCapabilityRegistry initialized with {} models (builtin={}, yaml={})",
                registry.size(), BUILTIN_CAPABILITIES.size(),
                config.getCapabilities() != null ? config.getCapabilities().size() : 0);
    }

    /**
     * 获取模型能力，不存在时返回默认值。
     */
    public ModelCapability getCapability(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return DEFAULT;
        }
        ModelCapability cap = registry.get(modelId);
        if (cap == null) {
            log.debug("Model '{}' not registered, using DEFAULT capability (contextWindow={}, tokenCharRatio={})",
                    modelId, DEFAULT.contextWindow(), DEFAULT.tokenCharRatio());
            return DEFAULT;
        }
        return cap;
    }

    /**
     * 获取压缩阈值 — 根据 contextWindow 动态计算。
     * <ul>
     *   <li>>= 200K → 90%</li>
     *   <li>>= 128K → 85%</li>
     *   <li>>= 64K → 80%</li>
     *   <li>< 64K → 75%</li>
     * </ul>
     */
    public double getCompactThreshold(String modelId) {
        int window = getCapability(modelId).contextWindow();
        if (window >= 200_000) return 0.90;
        if (window >= 128_000) return 0.85;
        if (window >= 64_000) return 0.80;
        return 0.75;
    }

    /**
     * 获取 Buffer Token 数 — contextWindow * 10%。
     */
    public int getBufferTokens(String modelId) {
        return (int) (getCapability(modelId).contextWindow() * 0.10);
    }

    /**
     * 获取模型的 Token/字符比率。
     */
    public double getTokenCharRatio(String modelId) {
        return getCapability(modelId).tokenCharRatio();
    }

    /**
     * 判断模型是否已注册。
     */
    public boolean isRegistered(String modelId) {
        return registry.containsKey(modelId);
    }

    // ── 内部方法 ──

    private ModelCapability getBuiltinOrDefault(String modelId) {
        return BUILTIN_CAPABILITIES.getOrDefault(modelId, DEFAULT);
    }
}

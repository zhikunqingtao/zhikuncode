package com.aicodeassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型能力配置 — 通过 application.yml 的 model.capabilities 前缀注入。
 * <p>
 * 支持从配置文件加载模型特定参数（contextWindow, tokenCharRatio 等），
 * 覆盖 ModelCapabilityRegistry 的硬编码默认值。
 */
@Component
@ConfigurationProperties(prefix = "model")
public class ModelCapabilityConfig {

    private Map<String, ModelProperties> capabilities = new HashMap<>();

    public Map<String, ModelProperties> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Map<String, ModelProperties> capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * 单个模型的能力属性。
     */
    public static class ModelProperties {
        private int contextWindow;
        private int outputMaxTokens;
        private double tokenCharRatio;
        private boolean supportsToolUse = true;
        private boolean supportsVision = false;
        private boolean supportsStreaming = true;
        private boolean supportsCache = false;
        private int rateLimitRpm = 60;
        private int rateLimitTpm = 1_000_000;

        public int getContextWindow() { return contextWindow; }
        public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

        public int getOutputMaxTokens() { return outputMaxTokens; }
        public void setOutputMaxTokens(int outputMaxTokens) { this.outputMaxTokens = outputMaxTokens; }

        public double getTokenCharRatio() { return tokenCharRatio; }
        public void setTokenCharRatio(double tokenCharRatio) { this.tokenCharRatio = tokenCharRatio; }

        public boolean isSupportsToolUse() { return supportsToolUse; }
        public void setSupportsToolUse(boolean supportsToolUse) { this.supportsToolUse = supportsToolUse; }

        public boolean isSupportsVision() { return supportsVision; }
        public void setSupportsVision(boolean supportsVision) { this.supportsVision = supportsVision; }

        public boolean isSupportsStreaming() { return supportsStreaming; }
        public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }

        public boolean isSupportsCache() { return supportsCache; }
        public void setSupportsCache(boolean supportsCache) { this.supportsCache = supportsCache; }

        public int getRateLimitRpm() { return rateLimitRpm; }
        public void setRateLimitRpm(int rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

        public int getRateLimitTpm() { return rateLimitTpm; }
        public void setRateLimitTpm(int rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }
    }
}

package com.aicodeassistant.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** User configuration overrides for the single ModelRegistry authority. */
@Component
@ConfigurationProperties(prefix = "model")
public class ModelCapabilitiesProperties {
    private Map<String, Override> capabilities = new LinkedHashMap<>();
    public Map<String, Override> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, Override> capabilities) {
        this.capabilities = capabilities == null ? new LinkedHashMap<>() : capabilities;
    }

    public static class Override {
        private Integer contextWindow;
        private Double tokenCharRatio;
        private Integer outputMaxTokens;
        private Boolean supportsCache;
        private Boolean supportsToolUse;
        private Boolean supportsVision;
        private Boolean supportsStreaming;
        public Integer getContextWindow(){return contextWindow;} public void setContextWindow(Integer v){contextWindow=v;}
        public Double getTokenCharRatio(){return tokenCharRatio;} public void setTokenCharRatio(Double v){tokenCharRatio=v;}
        public Integer getOutputMaxTokens(){return outputMaxTokens;} public void setOutputMaxTokens(Integer v){outputMaxTokens=v;}
        public Boolean getSupportsCache(){return supportsCache;} public void setSupportsCache(Boolean v){supportsCache=v;}
        public Boolean getSupportsToolUse(){return supportsToolUse;} public void setSupportsToolUse(Boolean v){supportsToolUse=v;}
        public Boolean getSupportsVision(){return supportsVision;} public void setSupportsVision(Boolean v){supportsVision=v;}
        public Boolean getSupportsStreaming(){return supportsStreaming;} public void setSupportsStreaming(Boolean v){supportsStreaming=v;}
    }
}

package com.aicodeassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * 子代理超时配置 — 通过 application.yml 的 agent.timeout 前缀注入。
 * <p>
 * 配置项：
 * <ul>
 *   <li>default-seconds: 单个子代理默认超时（默认 600s = 10min）</li>
 *   <li>max-seconds: 超时上限（默认 1800s = 30min）</li>
 *   <li>graceful-shutdown-seconds: abort 信号后优雅关闭窗口（默认 30s）</li>
 *   <li>max-wait-minutes: 父 QueryEngine 等待所有后台代理的总超时（默认 15min）</li>
 *   <li>watchdog-multiplier: Watchdog 超时倍数（Watchdog = 最长工具超时 × 此值，默认 2.0）</li>
 *   <li>tool-consume-max-wait-minutes: 工具消费等待覆盖值（0 = 动态计算（推荐），>0 = 调试覆盖值）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "agent.timeout")
public class AgentTimeoutConfig {
    private int defaultSeconds = 1800;
    private int maxSeconds = 1800;
    private int gracefulShutdownSeconds = 30;
    private int maxWaitMinutes = 15;
    private double watchdogMultiplier = 2.0;
    private int toolConsumeMaxWaitMinutes = 0;

    @PostConstruct
    public void validate() {
        if (defaultSeconds <= 0 || defaultSeconds > maxSeconds) {
            throw new IllegalStateException(
                    "agent.timeout.default-seconds must be > 0 and <= max-seconds");
        }
        if (watchdogMultiplier < 1.5) {
            throw new IllegalStateException(
                    "agent.timeout.watchdog-multiplier must be >= 1.5");
        }
    }

    // Getters and Setters
    public int getDefaultSeconds() { return defaultSeconds; }
    public void setDefaultSeconds(int s) { this.defaultSeconds = s; }
    public int getMaxSeconds() { return maxSeconds; }
    public void setMaxSeconds(int s) { this.maxSeconds = s; }
    public int getGracefulShutdownSeconds() { return gracefulShutdownSeconds; }
    public void setGracefulShutdownSeconds(int s) { this.gracefulShutdownSeconds = s; }
    public int getMaxWaitMinutes() { return maxWaitMinutes; }
    public void setMaxWaitMinutes(int m) { this.maxWaitMinutes = m; }
    public double getWatchdogMultiplier() { return watchdogMultiplier; }
    public void setWatchdogMultiplier(double v) { this.watchdogMultiplier = v; }
    public int getToolConsumeMaxWaitMinutes() { return toolConsumeMaxWaitMinutes; }
    public void setToolConsumeMaxWaitMinutes(int m) { this.toolConsumeMaxWaitMinutes = m; }
}

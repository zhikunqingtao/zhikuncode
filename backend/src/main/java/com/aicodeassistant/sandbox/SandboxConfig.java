package com.aicodeassistant.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 沙箱配置 — Docker 容器隔离参数。
 *
 * @see <a href="SPEC section 9.3">SandboxManager</a>
 */
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxConfig {

    /** 是否启用沙箱 */
    private boolean enabled = false;

    /** Docker 镜像名称 */
    private String image = "ai-code-assistant-sandbox:latest";

    /** 命令执行超时（秒） */
    private int timeoutSeconds = 300;

    /** 内存限制 */
    private String memoryLimit = "512m";

    /** 是否启用网络 */
    private boolean networkEnabled = false;

    /** seccomp 配置文件路径（null 表示使用默认） */
    private String seccompProfile = null;

    /** 工作目录挂载模式: ro (只读) 或 rw (读写) */
    private String mountMode = "ro";

    // ===== Getters & Setters =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }

    public boolean isNetworkEnabled() { return networkEnabled; }
    public void setNetworkEnabled(boolean networkEnabled) { this.networkEnabled = networkEnabled; }

    public String getSeccompProfile() { return seccompProfile; }
    public void setSeccompProfile(String seccompProfile) { this.seccompProfile = seccompProfile; }

    public String getMountMode() { return mountMode; }
    public void setMountMode(String mountMode) { this.mountMode = mountMode; }
}

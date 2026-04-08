package com.aicodeassistant.tool.powershell;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Windows 操作系统条件 — 仅当运行环境为 Windows 时匹配。
 * <p>
 * 替代错误的 {@code @ConditionalOnProperty(name = "os.name")} —
 * 因为 {@code @ConditionalOnProperty} 仅读 Spring Environment，
 * 不读 JVM 系统属性 (System.getProperty)。
 *
 * @see <a href="SPEC §4.1.10">PowerShellTool</a>
 */
public class WindowsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("windows");
    }

    /** 静态工具方法 — 判断当前是否为 Windows 平台 */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}

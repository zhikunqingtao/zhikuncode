package com.aicodeassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 安全审计日志服务。
 * <p>
 * 使用独立的 {@code security-audit} Logger，与业务日志分离。
 * 日志内容包括：被拦截的命令、敏感路径访问、审计事件。
 */
@Service
@ConditionalOnProperty(
        name = "security.enhanced-blacklist.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SecurityAuditLogger {

    private static final Logger auditLog = LoggerFactory.getLogger("security-audit");
    private static final Logger log = LoggerFactory.getLogger(SecurityAuditLogger.class);

    /**
     * 记录被拦截的命令。
     *
     * @param command 原始命令
     * @param result  拦截结果
     */
    public void logBlocked(String command, CommandBlacklistService.BlockResult result) {
        auditLog.warn("[SECURITY-BLOCK] level={}, rule={}, reason={}, command={}",
                result.level(), result.rule(), result.reason(), sanitize(command));
    }

    /**
     * 记录敏感路径访问。
     *
     * @param path   被访问的路径
     * @param result 路径检查结果
     */
    public void logPathAccess(String path, SensitivePathRegistry.PathCheckResult result) {
        auditLog.warn("[SECURITY-PATH] level={}, path={}, reason={}",
                result.level(), sanitize(path), result.reason());
    }

    /**
     * 记录通用审计事件。
     *
     * @param event   事件类型
     * @param context 事件上下文
     */
    public void logAuditEvent(String event, Map<String, Object> context) {
        auditLog.info("[SECURITY-AUDIT] event={}, timestamp={}, context={}",
                event, Instant.now(), context);
    }

    /**
     * 清理日志内容，防止日志注入。
     */
    private String sanitize(String input) {
        if (input == null) return "null";
        return input.replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}

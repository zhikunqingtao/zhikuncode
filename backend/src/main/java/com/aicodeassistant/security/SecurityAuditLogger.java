package com.aicodeassistant.security;

import com.aicodeassistant.observability.SafeLogValue;

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

    /**
     * 记录被拦截的命令。
     *
     * @param command 原始命令
     * @param result  拦截结果
     */
    public void logBlocked(String command, CommandBlacklistService.BlockResult result) {
        try {
            auditLog.warn("[SECURITY-BLOCK] level={}, rule={}, reasonLength={}, reasonFingerprint={}, commandLength={}, commandFingerprint={}",
                    result.level(), result.rule(), SafeLogValue.length(result.reason()),
                    SafeLogValue.fingerprint(result.reason()), SafeLogValue.length(command),
                    SafeLogValue.fingerprint(command));
        } catch (Throwable ignored) { }
    }

    /**
     * 记录敏感路径访问。
     *
     * @param path   被访问的路径
     * @param result 路径检查结果
     */
    public void logPathAccess(String path, SensitivePathRegistry.PathCheckResult result) {
        try {
            auditLog.warn("[SECURITY-PATH] level={}, pathLength={}, pathFingerprint={}, reasonLength={}, reasonFingerprint={}",
                    result.level(), SafeLogValue.length(path), SafeLogValue.fingerprint(path),
                    SafeLogValue.length(result.reason()), SafeLogValue.fingerprint(result.reason()));
        } catch (Throwable ignored) { }
    }

    /**
     * 记录通用审计事件。
     *
     * @param event   事件类型
     * @param context 事件上下文
     */
    public void logAuditEvent(String event, Map<String, Object> context) {
        try {
            String rendered = context == null ? "" : String.valueOf(context);
            auditLog.info("[SECURITY-AUDIT] event={}, timestamp={}, contextKeys={}, contextLength={}, contextFingerprint={}",
                    event, Instant.now(), context == null ? java.util.Set.of() : context.keySet(),
                    SafeLogValue.length(rendered), SafeLogValue.fingerprint(rendered));
        } catch (Throwable ignored) { }
    }
}

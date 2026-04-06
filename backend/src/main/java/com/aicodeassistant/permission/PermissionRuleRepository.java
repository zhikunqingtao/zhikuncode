package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限规则仓库 — 管理 always_allow / always_deny / always_ask 规则的持久化。
 * <p>
 * P0 阶段使用内存存储，后续对接 global.db SQLite 持久化。
 * 规则按 toolName 分组存储，支持 SESSION / PROJECT / GLOBAL 三种作用域。
 *
 * @see <a href="SPEC §3.4.2">权限规则</a>
 */
@Repository
public class PermissionRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(PermissionRuleRepository.class);

    /** allow 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> allowRules = new ConcurrentHashMap<>();

    /** deny 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> denyRules = new ConcurrentHashMap<>();

    /** ask 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> askRules = new ConcurrentHashMap<>();

    /** 会话级规则（会话结束时清理） */
    private final Map<String, List<PermissionRule>> sessionAllowRules = new ConcurrentHashMap<>();
    private final Map<String, List<PermissionRule>> sessionDenyRules = new ConcurrentHashMap<>();

    // ==================== 规则添加 ====================

    /**
     * 添加 allow 规则。
     */
    public void addAllowRule(PermissionRule rule) {
        String key = rule.ruleValue().toolName();
        if (rule.source() == PermissionRuleSource.USER_SESSION) {
            sessionAllowRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        } else {
            allowRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        }
        log.info("Added allow rule: tool={}, content={}, source={}", 
                key, rule.ruleValue().ruleContent(), rule.source());
    }

    /**
     * 添加 deny 规则。
     */
    public void addDenyRule(PermissionRule rule) {
        String key = rule.ruleValue().toolName();
        if (rule.source() == PermissionRuleSource.USER_SESSION) {
            sessionDenyRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        } else {
            denyRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        }
        log.info("Added deny rule: tool={}, content={}, source={}", 
                key, rule.ruleValue().ruleContent(), rule.source());
    }

    /**
     * 添加 ask 规则。
     */
    public void addAskRule(PermissionRule rule) {
        String key = rule.ruleValue().toolName();
        askRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        log.info("Added ask rule: tool={}, content={}, source={}", 
                key, rule.ruleValue().ruleContent(), rule.source());
    }

    // ==================== 规则查询 ====================

    /**
     * 获取所有 allow 规则（合并持久化 + 会话级）。
     */
    public Map<String, List<PermissionRule>> getAllowRules() {
        return mergeRuleMaps(allowRules, sessionAllowRules);
    }

    /**
     * 获取所有 deny 规则（合并持久化 + 会话级）。
     */
    public Map<String, List<PermissionRule>> getDenyRules() {
        return mergeRuleMaps(denyRules, sessionDenyRules);
    }

    /**
     * 获取所有 ask 规则。
     */
    public Map<String, List<PermissionRule>> getAskRules() {
        return Collections.unmodifiableMap(askRules);
    }

    // ==================== 规则移除 ====================

    /**
     * 移除指定工具的所有 allow 规则。
     */
    public void removeAllowRules(String toolName) {
        allowRules.remove(toolName);
        sessionAllowRules.remove(toolName);
    }

    /**
     * 移除指定工具的所有 deny 规则。
     */
    public void removeDenyRules(String toolName) {
        denyRules.remove(toolName);
        sessionDenyRules.remove(toolName);
    }

    /**
     * 清除所有会话级规则。
     */
    public void clearSessionRules() {
        sessionAllowRules.clear();
        sessionDenyRules.clear();
        log.info("Cleared all session-level permission rules");
    }

    /**
     * 清除所有规则。
     */
    public void clearAll() {
        allowRules.clear();
        denyRules.clear();
        askRules.clear();
        sessionAllowRules.clear();
        sessionDenyRules.clear();
        log.info("Cleared all permission rules");
    }

    // ==================== 构建 PermissionContext ====================

    /**
     * 构建当前权限上下文。
     */
    public PermissionContext buildContext(PermissionMode mode,
                                          boolean isBypassAvailable,
                                          boolean isAutoAvailable) {
        return new PermissionContext(
                mode,
                Set.of(),
                getAllowRules(),
                getDenyRules(),
                getAskRules(),
                isBypassAvailable,
                isAutoAvailable
        );
    }

    // ==================== 内部方法 ====================

    private Map<String, List<PermissionRule>> mergeRuleMaps(
            Map<String, List<PermissionRule>> persistent,
            Map<String, List<PermissionRule>> session) {
        Map<String, List<PermissionRule>> merged = new HashMap<>(persistent);
        session.forEach((key, rules) ->
                merged.merge(key, rules, (existing, newRules) -> {
                    List<PermissionRule> combined = new ArrayList<>(existing);
                    combined.addAll(newRules);
                    return combined;
                }));
        return Collections.unmodifiableMap(merged);
    }
}

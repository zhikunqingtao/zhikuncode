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

    /** 企业策略规则源 */
    private final PolicySettingsSource policySettingsSource;

    /** 插件扩展点规则源 */
    private final PluginSettingsSource pluginSettingsSource;

    /** allow 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> allowRules = new ConcurrentHashMap<>();

    /** deny 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> denyRules = new ConcurrentHashMap<>();

    /** ask 规则: toolName → rules */
    private final Map<String, List<PermissionRule>> askRules = new ConcurrentHashMap<>();

    /** 会话级规则（会话结束时清理） */
    private final Map<String, List<PermissionRule>> sessionAllowRules = new ConcurrentHashMap<>();
    private final Map<String, List<PermissionRule>> sessionDenyRules = new ConcurrentHashMap<>();

    /** 规则 ID → 规则对象的反向映射，支持按 ID 查找和删除 */
    private final Map<String, PermissionRule> ruleById = new ConcurrentHashMap<>();

    public PermissionRuleRepository(PolicySettingsSource policySettingsSource,
                                     PluginSettingsSource pluginSettingsSource) {
        this.policySettingsSource = policySettingsSource;
        this.pluginSettingsSource = pluginSettingsSource;
    }

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

    /**
     * 添加规则并记录 ID 映射，支持后续按 ID 删除。
     * 根据规则的 ruleBehavior 自动分发到 allow/deny/ask 存储。
     */
    public void addRuleWithId(String ruleId, PermissionRule rule) {
        ruleById.put(ruleId, rule);
        switch (rule.ruleBehavior()) {
            case ALLOW -> addAllowRule(rule);
            case DENY  -> addDenyRule(rule);
            case ASK   -> addAskRule(rule);
            default    -> log.warn("Unsupported behavior for rule {}: {}", ruleId, rule.ruleBehavior());
        }
        log.info("Added rule with ID: {} -> tool={}, behavior={}",
                ruleId, rule.ruleValue().toolName(), rule.ruleBehavior());
    }

    /**
     * 按 ID 删除规则。
     * 同时从 ruleById 映射和对应的 allow/deny/ask 列表中移除。
     */
    public boolean removeRuleById(String ruleId) {
        PermissionRule rule = ruleById.remove(ruleId);
        if (rule == null) return false;

        String toolName = rule.ruleValue().toolName();
        boolean removed = switch (rule.ruleBehavior()) {
            case ALLOW -> removeFromMap(allowRules, toolName, rule)
                       || removeFromMap(sessionAllowRules, toolName, rule);
            case DENY  -> removeFromMap(denyRules, toolName, rule)
                       || removeFromMap(sessionDenyRules, toolName, rule);
            case ASK   -> removeFromMap(askRules, toolName, rule);
            default    -> false;
        };
        if (removed) {
            log.info("Removed rule by ID: {} -> tool={}", ruleId, toolName);
        }
        return removed;
    }

    private boolean removeFromMap(Map<String, List<PermissionRule>> map,
                                   String toolName, PermissionRule rule) {
        List<PermissionRule> rules = map.get(toolName);
        if (rules == null) return false;
        boolean removed = rules.remove(rule);
        if (rules.isEmpty()) map.remove(toolName);
        return removed;
    }

    // ==================== 规则查询 ====================

    /**
     * 获取所有 allow 规则（合并 企业策略 + 插件 + 持久化 + 会话级）。
     * 合并优先级: 企业策略 > 插件 > 用户持久化 > 会话级
     */
    public Map<String, List<PermissionRule>> getAllowRules() {
        Map<String, List<PermissionRule>> base = mergeRuleMaps(allowRules, sessionAllowRules);
        base = mergeExternalRules(base, PermissionBehavior.ALLOW);
        return base;
    }

    /**
     * 获取所有 deny 规则（合并 企业策略 + 插件 + 持久化 + 会话级）。
     */
    public Map<String, List<PermissionRule>> getDenyRules() {
        Map<String, List<PermissionRule>> base = mergeRuleMaps(denyRules, sessionDenyRules);
        base = mergeExternalRules(base, PermissionBehavior.DENY);
        return base;
    }

    /**
     * 获取所有 ask 规则（合并 企业策略 + 插件）。
     */
    public Map<String, List<PermissionRule>> getAskRules() {
        Map<String, List<PermissionRule>> base = new HashMap<>(askRules);
        base = mergeExternalRules(base, PermissionBehavior.ASK);
        return Collections.unmodifiableMap(base);
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

    /**
     * 合并外部规则源（企业策略 + 插件）到基础规则映射中。
     * 企业策略规则排在最前面（最高优先级），插件规则次之。
     */
    private Map<String, List<PermissionRule>> mergeExternalRules(
            Map<String, List<PermissionRule>> base, PermissionBehavior behavior) {
        Map<String, List<PermissionRule>> result = new HashMap<>(base);

        // 1. 加载企业策略规则（最高优先级，排在前面）
        List<PermissionRule> policyRules = policySettingsSource.loadRules(
                policySettingsSource.getDefaultPolicyPath());
        for (PermissionRule rule : policyRules) {
            if (rule.ruleBehavior() == behavior) {
                String key = rule.ruleValue().toolName();
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(0, rule);
            }
        }

        // 2. 加载插件规则
        List<PermissionRule> pluginRules = pluginSettingsSource.getRules();
        for (PermissionRule rule : pluginRules) {
            if (rule.ruleBehavior() == behavior) {
                String key = rule.ruleValue().toolName();
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
            }
        }

        return result;
    }
}

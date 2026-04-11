package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.model.PermissionRuleSource;
import com.aicodeassistant.model.PermissionRuleValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件扩展点权限规则源 — 从插件注册的额外权限规则。
 * <p>
 * 允许 MCP 服务器、Hook 插件、Classifier 等组件在运行时注册权限规则。
 * 这些规则作为补充层叠加在企业策略和用户设置之上。
 * <p>
 * 优先级: 企业策略 > 插件 > 用户 > 默认
 *
 * @see <a href="SPEC §4.9">权限规则来源</a>
 */
@Component
public class PluginSettingsSource {

    private static final Logger log = LoggerFactory.getLogger(PluginSettingsSource.class);

    /** 插件注册的规则（线程安全，支持运行时动态注册） */
    private final CopyOnWriteArrayList<PermissionRule> pluginRules = new CopyOnWriteArrayList<>();

    /**
     * 注册一条插件权限规则。
     *
     * @param toolName     工具名称
     * @param behavior     权限行为 (ALLOW/DENY/ASK)
     * @param ruleContent  匹配内容（可选，null 表示匹配整个工具）
     * @param source       规则来源（MCP/HOOK/CLASSIFIER/SANDBOX）
     */
    public void registerRule(String toolName, PermissionBehavior behavior,
                             String ruleContent, PermissionRuleSource source) {
        PermissionRule rule = new PermissionRule(
                source,
                behavior,
                new PermissionRuleValue(toolName, ruleContent)
        );
        pluginRules.add(rule);
        log.info("Plugin rule registered: tool={}, behavior={}, source={}, content={}",
                toolName, behavior, source, ruleContent);
    }

    /**
     * 注册一条 MCP 服务器权限规则。
     */
    public void registerMcpRule(String toolName, PermissionBehavior behavior, String ruleContent) {
        registerRule(toolName, behavior, ruleContent, PermissionRuleSource.MCP);
    }

    /**
     * 注册一条 Hook 权限规则。
     */
    public void registerHookRule(String toolName, PermissionBehavior behavior, String ruleContent) {
        registerRule(toolName, behavior, ruleContent, PermissionRuleSource.HOOK);
    }

    /**
     * 注册一条 Classifier 权限规则。
     */
    public void registerClassifierRule(String toolName, PermissionBehavior behavior, String ruleContent) {
        registerRule(toolName, behavior, ruleContent, PermissionRuleSource.CLASSIFIER);
    }

    /**
     * 注册一条 Sandbox 权限规则。
     */
    public void registerSandboxRule(String toolName, PermissionBehavior behavior, String ruleContent) {
        registerRule(toolName, behavior, ruleContent, PermissionRuleSource.SANDBOX);
    }

    /**
     * 获取所有已注册的插件规则。
     */
    public List<PermissionRule> getRules() {
        return Collections.unmodifiableList(new ArrayList<>(pluginRules));
    }

    /**
     * 获取指定来源的规则。
     */
    public List<PermissionRule> getRulesBySource(PermissionRuleSource source) {
        return pluginRules.stream()
                .filter(r -> r.source() == source)
                .toList();
    }

    /**
     * 移除指定来源的所有规则。
     */
    public void removeRulesBySource(PermissionRuleSource source) {
        pluginRules.removeIf(r -> r.source() == source);
        log.info("Removed all plugin rules from source: {}", source);
    }

    /**
     * 移除指定工具的所有插件规则。
     */
    public void removeRulesByTool(String toolName) {
        pluginRules.removeIf(r -> r.ruleValue().toolName().equals(toolName));
        log.info("Removed all plugin rules for tool: {}", toolName);
    }

    /**
     * 清除所有插件规则。
     */
    public void clearAll() {
        pluginRules.clear();
        log.info("Cleared all plugin permission rules");
    }
}

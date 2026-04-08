package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 危险规则剥离服务 — 防止 Auto 模式下通过 CLAUDE.md 允许规则绕过权限检查。
 * <p>
 * 对应源码: src/utils/permissions/permissionSetup.ts
 * <p>
 * 当进入 Auto 模式时，系统扫描所有 alwaysAllow 规则，
 * 将其中的"危险规则"剥离并记录到剥离清单中。
 * 退出 Auto 模式时，恢复被剥离的规则。
 *
 * @see <a href="SPEC §4.9.4">危险规则剥离</a>
 */
@Service
public class DangerousRuleStripper {

    private static final Logger log = LoggerFactory.getLogger(DangerousRuleStripper.class);

    /** 剥离清单 — 记录被剥离的规则，退出 Auto 模式时恢复 */
    private final List<PermissionRule> strippedRules = new ArrayList<>();

    // ============ 危险规则识别模式 ============

    /**
     * Bash 危险规则:
     * - 解释器前缀 (如 "python:*", "node:*", "ruby:*")
     * - 管道到解释器 (如 "| python", "| bash")
     */
    private static final List<Pattern> BASH_DANGEROUS_PATTERNS = List.of(
            Pattern.compile("^(python|node|ruby|perl|sh|bash|zsh):.*"),
            Pattern.compile(".*\\|\\s*(python|bash|sh|node).*")
    );

    /**
     * PowerShell 危险规则:
     * - 嵌套壳 (如 "powershell -Command")
     * - 脚本执行器 (如 "Invoke-Expression", "iex")
     * - 进程启动器 (如 "Start-Process -Verb RunAs")
     */
    private static final List<Pattern> POWERSHELL_DANGEROUS_PATTERNS = List.of(
            Pattern.compile("(?i).*(Invoke-Expression|iex|Start-Process).*"),
            Pattern.compile("(?i).*(-Command|-EncodedCommand).*")
    );

    /** Agent 危险规则: 任何 Agent 工具的允许规则（防止委托攻击） */
    private static final Set<String> ALWAYS_DANGEROUS_TOOLS = Set.of("Agent");

    /**
     * 进入 Auto 模式时调用 — 剥离危险规则。
     * <p>
     * 流程:
     * 1. 扫描所有 alwaysAllow 规则
     * 2. 识别危险规则（Bash 通配符/解释器、PowerShell 嵌套壳、Agent）
     * 3. 将危险规则从生效规则集中移除
     * 4. 记录到 strippedRules 清单
     *
     * @param allowRules 当前所有 allow 规则（可修改的列表）
     * @return 被剥离的危险规则列表
     */
    public List<PermissionRule> onEnterAutoMode(Map<String, List<PermissionRule>> allowRules) {
        strippedRules.clear();

        if (allowRules == null || allowRules.isEmpty()) {
            return List.of();
        }

        // 遍历所有工具的 allow 规则，识别并剥离危险规则
        for (Map.Entry<String, List<PermissionRule>> entry : allowRules.entrySet()) {
            List<PermissionRule> rules = entry.getValue();
            if (rules == null) continue;

            Iterator<PermissionRule> it = rules.iterator();
            while (it.hasNext()) {
                PermissionRule rule = it.next();
                if (isDangerous(rule)) {
                    strippedRules.add(rule);
                    it.remove();
                }
            }
        }

        if (!strippedRules.isEmpty()) {
            log.info("Auto mode: stripped {} dangerous allow rules", strippedRules.size());
            for (PermissionRule rule : strippedRules) {
                log.debug("  Stripped: tool={}, content={}",
                        rule.ruleValue().toolName(), rule.ruleValue().ruleContent());
            }
        }

        return List.copyOf(strippedRules);
    }

    /**
     * 退出 Auto 模式时调用 — 恢复被剥离的规则。
     *
     * @param allowRules 当前 allow 规则映射（规则将被恢复到此映射中）
     */
    public void onExitAutoMode(Map<String, List<PermissionRule>> allowRules) {
        if (strippedRules.isEmpty()) {
            return;
        }

        for (PermissionRule rule : strippedRules) {
            String toolName = rule.ruleValue().toolName();
            allowRules.computeIfAbsent(toolName, k -> new ArrayList<>()).add(rule);
        }

        log.info("Auto mode exit: restored {} stripped rules", strippedRules.size());
        strippedRules.clear();
    }

    /**
     * 获取当前剥离的规则数量。
     */
    public int getStrippedCount() {
        return strippedRules.size();
    }

    /**
     * 获取当前剥离的规则列表（只读视图）。
     */
    public List<PermissionRule> getStrippedRules() {
        return List.copyOf(strippedRules);
    }

    /**
     * 判断规则是否为危险规则。
     */
    boolean isDangerous(PermissionRule rule) {
        if (rule == null || rule.ruleValue() == null) return false;

        String toolName = rule.ruleValue().toolName();
        String content = rule.ruleValue().ruleContent();

        // Agent 工具的任何允许规则都视为危险
        if (ALWAYS_DANGEROUS_TOOLS.contains(toolName)) {
            return true;
        }

        // Bash 无内容限制的通配符规则
        if ("Bash".equals(toolName) && (content == null || content.isEmpty())) {
            return true;
        }

        // Bash 解释器前缀 / 管道模式
        if ("Bash".equals(toolName) && content != null) {
            for (Pattern p : BASH_DANGEROUS_PATTERNS) {
                if (p.matcher(content).matches()) return true;
            }
        }

        // PowerShell 危险模式
        if ("PowerShell".equals(toolName) && content != null) {
            for (Pattern p : POWERSHELL_DANGEROUS_PATTERNS) {
                if (p.matcher(content).matches()) return true;
            }
        }

        return false;
    }
}

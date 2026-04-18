package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 权限规则匹配器 — 实现 3 种匹配模式（精确/前缀/通配符）。
 * <p>
 * 对齐源码: shellRuleMatching.ts (229 行)
 *
 * @see <a href="SPEC §3.4.4">权限规则匹配算法</a>
 */
@Component
public class PermissionRuleMatcher {

    // ==================== 规则查找 ====================

    /**
     * 查找匹配的 deny 规则。
     */
    public PermissionRule findDenyRule(PermissionContext context, Tool tool) {
        return findMatchingRule(context.alwaysDenyRules(), tool, null);
    }

    /**
     * 查找匹配的 deny 规则（带子命令级匹配）。
     */
    public PermissionRule findDenyRule(PermissionContext context, Tool tool, ToolInput input) {
        return findMatchingRule(context.alwaysDenyRules(), tool, input);
    }

    /**
     * 查找匹配的 ask 规则。
     */
    public PermissionRule findAskRule(PermissionContext context, Tool tool) {
        return findMatchingRule(context.alwaysAskRules(), tool, null);
    }

    /**
     * 查找匹配的 ask 规则（带子命令级匹配）。
     */
    public PermissionRule findAskRule(PermissionContext context, Tool tool, ToolInput input) {
        return findMatchingRule(context.alwaysAskRules(), tool, input);
    }

    /**
     * 查找匹配的 allow 规则。
     */
    public PermissionRule findAllowRule(PermissionContext context, Tool tool) {
        return findMatchingRule(context.alwaysAllowRules(), tool, null);
    }

    /**
     * 查找匹配的 allow 规则（带子命令级匹配）。
     */
    public PermissionRule findAllowRule(PermissionContext context, Tool tool, ToolInput input) {
        return findMatchingRule(context.alwaysAllowRules(), tool, input);
    }

    // ==================== 核心匹配 ====================

    /**
     * 在规则映射中查找匹配的规则。
     *
     * @param ruleMap 规则映射 (toolName → rules)
     * @param tool    目标工具
     * @param input   工具输入（可选，用于内容级匹配）
     * @return 匹配的规则，或 null
     */
    private PermissionRule findMatchingRule(
            Map<String, List<PermissionRule>> ruleMap,
            Tool tool, ToolInput input) {

        if (ruleMap == null || ruleMap.isEmpty()) {
            return null;
        }

        String toolName = tool.getName();

        // 1. 精确工具名匹配
        List<PermissionRule> toolRules = ruleMap.get(toolName);
        if (toolRules != null) {
            for (PermissionRule rule : toolRules) {
                if (matchesRule(rule, tool, input)) {
                    return rule;
                }
            }
        }

        // 2. 通配符 "*" 匹配所有工具
        List<PermissionRule> wildcardRules = ruleMap.get("*");
        if (wildcardRules != null) {
            for (PermissionRule rule : wildcardRules) {
                if (matchesRule(rule, tool, input)) {
                    return rule;
                }
            }
        }

        // 3. MCP 工具匹配: mcp__<serverName> 匹配该服务器所有工具
        if (toolName.startsWith("mcp__")) {
            String[] parts = toolName.split("__");
            if (parts.length >= 3) {
                String serverKey = parts[0] + "__" + parts[1];
                List<PermissionRule> mcpRules = ruleMap.get(serverKey);
                if (mcpRules != null && !mcpRules.isEmpty()) {
                    return mcpRules.get(0);
                }
            }
        }

        return null;
    }

    /**
     * 检查单条规则是否匹配。
     */
    private boolean matchesRule(PermissionRule rule, Tool tool, ToolInput input) {
        PermissionRuleValue value = rule.ruleValue();

        // 无内容条件 → 匹配整个工具
        if (value.ruleContent() == null || value.ruleContent().isBlank()) {
            return true;
        }

        // 有内容条件 → 需要匹配命令内容（如 BashTool 的 command 参数）
        if (input == null) {
            return false;
        }

        String command = input.getOptionalString("command").orElse("");
        return matchContent(value.ruleContent(), command);
    }

    // ==================== 内容匹配（3 种模式） ====================

    /**
     * Shell 命令规则匹配 — 3 种模式 + 子命令级前缀匹配。
     *
     * @param ruleContent 规则内容（如 "npm:*", "git push *", "git status", "docker run")
     * @param command     实际命令
     * @return 是否匹配
     */
    public boolean matchContent(String ruleContent, String command) {
        // 1. 检查 :* 后缀 → 前缀规则
        if (ruleContent.endsWith(":*")) {
            String prefix = ruleContent.substring(0, ruleContent.length() - 2);
            return command.equals(prefix) || command.startsWith(prefix + " ");
        }

        // 2. 子命令级前缀匹配 — 支持 "git commit"、"docker run" 等多级命令
        //    规则 "git commit" 匹配 "git commit -m 'msg'" 和 "git commit"
        //    规则 "git *" 匹配所有 git 子命令
        if (ruleContent.contains(" ")) {
            // 多词规则：检查是否为子命令级匹配
            if (ruleContent.endsWith(" *")) {
                // "git *" 模式 → 匹配命令前缀
                String cmdPrefix = ruleContent.substring(0, ruleContent.length() - 2);
                return command.equals(cmdPrefix) || command.startsWith(cmdPrefix + " ");
            }
            // "git commit" 模式 → 精确子命令匹配
            if (command.equals(ruleContent) || command.startsWith(ruleContent + " ")) {
                return true;
            }
        }

        // 3. 检查含未转义 * → 通配符规则
        if (containsUnescapedWildcard(ruleContent)) {
            return matchWildcardPattern(ruleContent, command);
        }

        // 4. 精确匹配
        return command.equals(ruleContent);
    }

    /**
     * 通配符匹配算法 — 对齐源码 matchWildcardPattern。
     */
    private boolean matchWildcardPattern(String pattern, String command) {
        // 1. 转义处理: \* → 占位符, \\ → 占位符
        String ESCAPED_STAR = "\u0001";
        String ESCAPED_BACKSLASH = "\u0002";

        String processed = pattern
                .replace("\\*", ESCAPED_STAR)
                .replace("\\\\", ESCAPED_BACKSLASH);

        // 2. 正则特殊字符转义
        processed = escapeRegexChars(processed);

        // 3. 未转义 * → .*
        processed = processed.replace("*", ".*");

        // 4. 占位符还原
        processed = processed
                .replace(ESCAPED_STAR, "\\*")
                .replace(ESCAPED_BACKSLASH, "\\\\");

        // 5. 尾部优化: 仅含单个 * 且以 ' .*' 结尾
        long wildcardCount = pattern.chars().filter(c -> c == '*').count()
                - countOccurrences(pattern, "\\*");
        if (wildcardCount == 1 && processed.endsWith(" .*")) {
            processed = processed.substring(0, processed.length() - 3) + "( .*)?";
        }

        // 6/7. 完整匹配
        try {
            Pattern regex = Pattern.compile("^" + processed + "$", Pattern.DOTALL);
            return regex.matcher(command).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查是否包含未转义的通配符 */
    private boolean containsUnescapedWildcard(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '*') {
                if (i == 0 || s.charAt(i - 1) != '\\') {
                    return true;
                }
            }
        }
        return false;
    }

    /** 转义正则特殊字符 */
    private String escapeRegexChars(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (".+?^${}()|[]\\".indexOf(c) >= 0 && c != '*') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 计算子串出现次数 */
    private long countOccurrences(String s, String sub) {
        long count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}

package com.aicodeassistant.skill;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能模板参数替换 — 对应源码 argumentSubstitution.ts。
 * <p>
 * 替换算法:
 * <ol>
 *     <li>parseArgumentNames(content) → 提取所有 {{arg_name}} 模板变量</li>
 *     <li>parseArgs(argsString, argDefs) → 将字符串参数解析为 key=value Map</li>
 *     <li>substitute(content, params) → 替换 {{arg_name}} 为实际值</li>
 * </ol>
 *
 * @see <a href="SPEC §4.7.3">参数替换算法</a>
 */
public final class ArgumentSubstitution {

    private ArgumentSubstitution() {}

    /** 模板变量匹配: {{arg_name}} */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * 提取模板中所有参数名称。
     *
     * @param content Markdown 模板内容
     * @return 参数名列表（去重，保持顺序）
     */
    public static List<String> parseArgumentNames(String content) {
        if (content == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = TEMPLATE_VAR.matcher(content);
        while (matcher.find()) {
            seen.add(matcher.group(1));
        }
        return new ArrayList<>(seen);
    }

    /**
     * 解析参数字符串 — 支持位置参数和命名参数 (key=value)。
     *
     * @param argsString 用户提供的参数字符串
     * @param argDefs    frontmatter 中定义的参数名列表
     * @return 参数 Map (argName → value)
     */
    public static Map<String, String> parseArgs(String argsString, List<String> argDefs) {
        Map<String, String> result = new LinkedHashMap<>();
        if (argsString == null || argsString.isBlank()) {
            return result;
        }

        String[] parts = argsString.trim().split("\\s+");
        List<String> positionalValues = new ArrayList<>();

        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            if (eqIdx > 0) {
                // 命名参数: key=value
                String key = part.substring(0, eqIdx);
                String value = part.substring(eqIdx + 1);
                result.put(key, value);
            } else {
                positionalValues.add(part);
            }
        }

        // 位置参数按顺序匹配 argDefs
        if (argDefs != null) {
            for (int i = 0; i < Math.min(positionalValues.size(), argDefs.size()); i++) {
                String argName = argDefs.get(i);
                if (!result.containsKey(argName)) {
                    result.put(argName, positionalValues.get(i));
                }
            }
        }

        // 无 argDefs 时，第一个位置参数作为 "args"
        if ((argDefs == null || argDefs.isEmpty()) && !positionalValues.isEmpty()) {
            result.put("args", String.join(" ", positionalValues));
        }

        return result;
    }

    /**
     * 替换模板变量 — 将 {{arg_name}} 替换为实际值。
     * <p>
     * 未提供参数时保留 {{arg_name}} 占位符（由 LLM 自行理解）。
     *
     * @param content 模板内容
     * @param params  参数 Map
     * @return 替换后的内容
     */
    public static String substitute(String content, Map<String, String> params) {
        if (content == null) return "";
        if (params == null || params.isEmpty()) return content;

        Matcher matcher = TEMPLATE_VAR.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String argName = matcher.group(1);
            String replacement = params.get(argName);
            if (replacement != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            // 未找到参数时保留原始 {{arg_name}}
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

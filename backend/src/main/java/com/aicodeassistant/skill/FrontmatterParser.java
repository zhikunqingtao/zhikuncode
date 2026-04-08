package com.aicodeassistant.skill;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Frontmatter 解析器 — 解析 Markdown 文件的 YAML front matter。
 * <p>
 * 解析流程 (对照源码 frontmatterParser.ts L85-110):
 * <ol>
 *     <li>检测 '---' 开头分隔符（必须是文件第一行）</li>
 *     <li>查找结束 '---' 分隔符</li>
 *     <li>对中间 YAML 文本执行特殊字符预处理 (quoteProblematicValues)</li>
 *     <li>简化 YAML 解析（key: value 格式）</li>
 *     <li>返回 ParsedMarkdown(frontmatter, content)</li>
 * </ol>
 *
 * @see <a href="SPEC §4.7.3">Frontmatter 解析算法</a>
 */
public final class FrontmatterParser {

    private FrontmatterParser() {}

    /** 解析结果 */
    public record ParsedMarkdown(FrontmatterData frontmatter, String content) {}

    /** YAML 特殊字符检测模式 */
    private static final Pattern YAML_SPECIAL_CHARS = Pattern.compile("[{}\\[\\]*&#!|>%@`]|:\\s");

    /** YAML key: value 行匹配 */
    private static final Pattern YAML_LINE = Pattern.compile("^(\\w[\\w\\-]*):\\s*(.*)$");

    /** YAML 列表项匹配 */
    private static final Pattern YAML_LIST_ITEM = Pattern.compile("^\\s+-\\s+(.+)$");

    /**
     * 解析 Markdown 文件内容 — 提取 frontmatter 和正文。
     *
     * @param rawContent 完整的 Markdown 文件内容
     * @return 解析结果，包含 frontmatter 数据和正文内容
     */
    public static ParsedMarkdown parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new ParsedMarkdown(FrontmatterData.defaults(), "");
        }

        String content = rawContent.trim();

        // 检测 '---' 开头分隔符
        if (!content.startsWith("---")) {
            return new ParsedMarkdown(
                    fallbackDescription(content),
                    content);
        }

        // 查找结束 '---' 分隔符
        int endIndex = content.indexOf("\n---", 3);
        if (endIndex == -1) {
            return new ParsedMarkdown(
                    fallbackDescription(content),
                    content);
        }

        // 提取 YAML 部分和正文部分
        String yamlText = content.substring(3, endIndex).trim();
        String bodyContent = content.substring(endIndex + 4).trim();

        // 解析 YAML
        FrontmatterData frontmatter = parseYaml(yamlText);

        // description fallback: 从 markdown 第一段落提取
        if (frontmatter.description() == null || frontmatter.description().isBlank()) {
            String fallback = extractFirstParagraph(bodyContent);
            if (fallback != null) {
                frontmatter = new FrontmatterData(
                        fallback, frontmatter.name(), frontmatter.allowedTools(),
                        frontmatter.argumentHint(), frontmatter.arguments(),
                        frontmatter.whenToUse(), frontmatter.version(), frontmatter.model(),
                        frontmatter.disableModelInvocation(), frontmatter.userInvocable(),
                        frontmatter.hooks(), frontmatter.effort(), frontmatter.context(),
                        frontmatter.agent(), frontmatter.paths(), frontmatter.shell()
                );
            }
        }

        return new ParsedMarkdown(frontmatter, bodyContent);
    }

    /**
     * 解析简化 YAML — 支持 key: value 和 key: [list] 格式。
     */
    static FrontmatterData parseYaml(String yamlText) {
        Map<String, Object> map = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;

        for (String line : yamlText.split("\n")) {
            // 跳过注释行
            if (line.trim().startsWith("#")) continue;

            // 列表项
            Matcher listMatcher = YAML_LIST_ITEM.matcher(line);
            if (listMatcher.matches() && currentKey != null) {
                if (currentList == null) {
                    currentList = new ArrayList<>();
                    map.put(currentKey, currentList);
                }
                currentList.add(unquote(listMatcher.group(1).trim()));
                continue;
            }

            // key: value 行
            Matcher kvMatcher = YAML_LINE.matcher(line.trim());
            if (kvMatcher.matches()) {
                // 保存之前的列表
                currentKey = normalizeKey(kvMatcher.group(1));
                String value = kvMatcher.group(2).trim();
                currentList = null;

                if (value.isEmpty()) {
                    // 可能是列表头
                    map.put(currentKey, "");
                } else {
                    map.put(currentKey, unquote(value));
                }
            }
        }

        return buildFrontmatterData(map);
    }

    /**
     * 从解析后的 Map 构建 FrontmatterData。
     */
    @SuppressWarnings("unchecked")
    private static FrontmatterData buildFrontmatterData(Map<String, Object> map) {
        return new FrontmatterData(
                getStr(map, "description"),
                getStr(map, "name"),
                parseToolsList(map.get("allowed-tools")),
                getStr(map, "argument-hint"),
                parseStringList(map.get("arguments")),
                getStr(map, "when_to_use"),
                getStr(map, "version"),
                getStr(map, "model"),
                "true".equalsIgnoreCase(getStr(map, "disable_model_invocation")),
                !"false".equalsIgnoreCase(getStr(map, "user-invocable")),
                Map.of(), // hooks: 简化处理
                getStr(map, "effort"),
                getStr(map, "context") != null ? getStr(map, "context") : "inline",
                getStr(map, "agent"),
                parseStringList(map.get("paths")),
                getStr(map, "shell") != null ? getStr(map, "shell") : "bash"
        );
    }

    /** 标准化 YAML key — 支持 snake_case 和 kebab-case */
    private static String normalizeKey(String key) {
        return key.toLowerCase().replace('_', '-');
    }

    private static String getStr(Map<String, Object> map, String key) {
        // 尝试 kebab-case 和 snake_case
        Object val = map.get(key);
        if (val == null) {
            val = map.get(key.replace('-', '_'));
        }
        if (val == null) {
            val = map.get(key.replace('_', '-'));
        }
        return val != null && !val.toString().isEmpty() ? val.toString() : null;
    }

    /** 解析工具列表 — 支持逗号分隔字符串或 YAML 数组 */
    @SuppressWarnings("unchecked")
    private static List<String> parseToolsList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) return List.of();
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 解析字符串列表 */
    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        String str = value.toString().trim();
        if (str.isEmpty()) return List.of();
        return List.of(str);
    }

    /** 去除引号 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 从正文提取第一段落作为 description fallback */
    private static String extractFirstParagraph(String content) {
        if (content == null || content.isBlank()) return null;
        // 跳过标题行
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() && sb.length() > 0) break;
            if (trimmed.startsWith("#")) continue;
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** 无 frontmatter 时的 fallback */
    private static FrontmatterData fallbackDescription(String content) {
        String desc = extractFirstParagraph(content);
        return new FrontmatterData(
                desc, null, List.of(), null, List.of(),
                null, null, null, false, true,
                Map.of(), null, "inline", null, List.of(), "bash"
        );
    }
}

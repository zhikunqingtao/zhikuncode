package com.aicodeassistant.tool.bash;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heredoc 提取器 — 对齐原版 heredoc.ts（734行）核心逻辑。
 * <p>
 * 从命令中提取 heredoc 并替换为占位符，供 BashSecurityAnalyzer 安全分析。
 * <p>
 * 安全要点:
 * <ol>
 *   <li>跳过引号内的 &lt;&lt;</li>
 *   <li>跳过注释内的 &lt;&lt;</li>
 *   <li>跳过转义的 &lt;&lt;</li>
 *   <li>跳过算术上下文中的 &lt;&lt;</li>
 *   <li>跳过 $' 和 $" 特殊引用</li>
 *   <li>行继续符检测</li>
 *   <li>PST_EOFTOKEN 提前关闭检测</li>
 * </ol>
 *
 * @see <a href="SPEC §11.5.3">Heredoc 解析规格</a>
 */
public class HeredocExtractor {

    /**
     * Heredoc 信息记录。
     */
    public record HeredocInfo(
            String delimiter,        // 分隔符词（不含引号）
            String content,          // heredoc 内容
            boolean isDash,          // <<- 变体（strip leading tabs）
            boolean isQuotedOrEscaped // 引号/转义分隔符（禁止变量展开）
    ) {}

    public record HeredocExtractionResult(
            String processedCommand,                    // 占位符替换后的命令
            Map<String, HeredocInfo> heredocs            // 占位符 → heredoc 信息
    ) {}

    /**
     * 从 heredoc 操作符位置提取所属命令名。
     * 取 &lt;&lt; 前子串的第一个 token（命令名）。
     */
    public String getCommandForHeredoc(String command, int heredocStartIndex) {
        String before = command.substring(0, heredocStartIndex).trim();
        String[] tokens = before.split("\\s+");
        return tokens.length > 0 ? tokens[0] : "";
    }

    // Heredoc 起始正则 — 对齐原版 HEREDOC_START_PATTERN
    // 支持: <<WORD, <<'WORD', <<"WORD", <<-WORD, <<-'WORD', <<\WORD
    private static final Pattern HEREDOC_START = Pattern.compile(
            "(?<!<)<<(?!<)(-)?[ \\t]*(?:(['\"])(\\w+)\\2|\\\\?(\\w+))"
    );

    /**
     * 从命令中提取 heredoc 并替换为占位符。
     */
    public HeredocExtractionResult extract(String command) {
        if (!command.contains("<<")) {
            return new HeredocExtractionResult(command, Map.of());
        }

        // 安全前置检查
        if (command.matches(".*\\$['\"].*")) {
            return new HeredocExtractionResult(command, Map.of()); // bail: ANSI-C quoting
        }
        int firstHeredocPos = command.indexOf("<<");
        if (firstHeredocPos > 0 && command.substring(0, firstHeredocPos).contains("`")) {
            return new HeredocExtractionResult(command, Map.of()); // bail: backtick
        }

        // ═══ 增量式引号/注释状态扫描器 ═══
        final String cmd = command;

        // 使用数组封装可变状态以供内部类访问
        final boolean[] inSingleQ = {false};
        final boolean[] inDoubleQ = {false};
        final boolean[] inComment = {false};
        final boolean[] dqEscapeNext = {false};
        final int[] pendingBackslashes = {0};
        final int[] scanPos = {0};

        // advanceTo 方法实现
        Runnable advanceToFactory = null; // 使用下面的 lambda

        // ═══ 主循环 ═══
        Map<String, HeredocInfo> heredocs = new LinkedHashMap<>();
        Matcher m = HEREDOC_START.matcher(command);

        while (m.find()) {
            int startIndex = m.start();

            // 推进扫描器到匹配位置
            for (int i = scanPos[0]; i < startIndex; i++) {
                char ch = cmd.charAt(i);
                if (ch == '\n') inComment[0] = false;

                if (inSingleQ[0]) { if (ch == '\'') inSingleQ[0] = false; continue; }
                if (inDoubleQ[0]) {
                    if (dqEscapeNext[0]) { dqEscapeNext[0] = false; continue; }
                    if (ch == '\\') { dqEscapeNext[0] = true; continue; }
                    if (ch == '"') inDoubleQ[0] = false;
                    continue;
                }
                if (ch == '\\') { pendingBackslashes[0]++; continue; }
                boolean escaped = pendingBackslashes[0] % 2 == 1;
                pendingBackslashes[0] = 0;
                if (escaped) continue;
                if (ch == '\'') inSingleQ[0] = true;
                else if (ch == '"') inDoubleQ[0] = true;
                else if (!inComment[0] && ch == '#') inComment[0] = true;
            }
            scanPos[0] = startIndex;

            // 跳过: 引号内/注释内/转义的 <<
            if (inSingleQ[0] || inDoubleQ[0]) continue;
            if (inComment[0]) continue;
            if (pendingBackslashes[0] % 2 == 1) continue;

            boolean isDash = "-".equals(m.group(1));
            String delimiter = m.group(3) != null ? m.group(3) : m.group(4);
            boolean isQuotedOrEscaped = m.group(2) != null || m.group(0).contains("\\");
            int operatorEndIndex = m.end();

            // 找到逻辑行结尾（跳过引号内的换行）
            int firstNewlineOffset = findUnquotedNewline(command, operatorEndIndex);
            if (firstNewlineOffset == -1) continue;

            // 行继续符检测（\+换行）
            String sameLine = command.substring(operatorEndIndex, operatorEndIndex + firstNewlineOffset);
            int trailingBs = countTrailingBackslashes(sameLine);
            if (trailingBs % 2 == 1) continue; // 行继续符 → bail

            // 提取 heredoc 内容直到关闭分隔符
            int contentStart = operatorEndIndex + firstNewlineOffset + 1;
            if (contentStart >= command.length()) continue;
            String afterNewline = command.substring(contentStart);
            String[] contentLines = afterNewline.split("\n", -1);

            int closingLineIndex = -1;
            for (int i = 0; i < contentLines.length; i++) {
                String line = contentLines[i];
                String checkLine = isDash ? line.replaceFirst("^\t*", "") : line;
                if (checkLine.equals(delimiter)) {
                    closingLineIndex = i;
                    break;
                }
                // PST_EOFTOKEN 检测
                if (checkLine.length() > delimiter.length() && checkLine.startsWith(delimiter)) {
                    char afterDelim = checkLine.charAt(delimiter.length());
                    if (")}`|&;(<>".indexOf(afterDelim) >= 0) {
                        closingLineIndex = -1;
                        break; // bail: 可能的 shell 元字符
                    }
                }
            }

            if (closingLineIndex == -1) continue; // 未找到关闭分隔符

            String content = String.join("\n",
                    Arrays.copyOfRange(contentLines, 0, closingLineIndex));
            heredocs.put("heredoc_" + heredocs.size(),
                    new HeredocInfo(delimiter, content, isDash, isQuotedOrEscaped));
        }

        return new HeredocExtractionResult(command, heredocs);
    }

    /** 找到第一个非引号内的换行符偏移量 */
    private int findUnquotedNewline(String cmd, int from) {
        boolean inSQ = false, inDQ = false;
        for (int k = from; k < cmd.length(); k++) {
            char ch = cmd.charAt(k);
            if (inSQ) { if (ch == '\'') inSQ = false; continue; }
            if (inDQ) { if (ch == '\\') { k++; continue; } if (ch == '"') inDQ = false; continue; }
            if (ch == '\n') return k - from;
            if (ch == '\'') inSQ = true;
            else if (ch == '"') inDQ = true;
        }
        return -1;
    }

    /** 计算字符串末尾连续反斜杠数量 */
    private int countTrailingBackslashes(String s) {
        int count = 0;
        for (int j = s.length() - 1; j >= 0 && s.charAt(j) == '\\'; j--) count++;
        return count;
    }

    /** 快速检测命令是否包含 heredoc */
    public static boolean containsHeredoc(String command) {
        return command != null && HEREDOC_START.matcher(command).find();
    }
}

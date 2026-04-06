package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 降级 Fallback: BashCommandClassifier — 纯正则实现。
 * <p>
 * 对应源码 isSearchOrReadBashCommand() 的等价逻辑。
 * 仅在 BashSecurityAnalyzer.parseForSecurity() 返回 parse-unavailable 时使用。
 * <p>
 * 安全设计: fail-closed — 无法分类时返回 UNKNOWN (需要权限确认)。
 *
 * @see <a href="SPEC §3.2.3a">BashTool 实现算法</a>
 */
@Component
public class BashCommandClassifier {

    // 命令分类表 (对应源码 src/tools/BashTool/isSearchOrReadBashCommand.ts)
    private static final Set<String> SEARCH_CMDS = Set.of(
            "find", "grep", "rg", "ag", "ack", "locate", "which", "whereis");
    private static final Set<String> READ_CMDS = Set.of(
            "cat", "head", "tail", "less", "more", "wc", "stat", "file",
            "strings", "jq", "awk", "cut", "sort", "uniq", "tr");
    private static final Set<String> LIST_CMDS = Set.of("ls", "tree", "du");
    private static final Set<String> SILENT_CMDS = Set.of(
            "mv", "cp", "rm", "mkdir", "rmdir", "chmod", "chown", "chgrp",
            "touch", "ln", "cd", "export", "unset", "wait");
    private static final Set<String> NEUTRAL_CMDS = Set.of(
            "echo", "printf", "true", "false", ":");

    private static final Pattern FIRST_TOKEN_PATTERN = Pattern.compile("^([\\w./-]+)");

    /**
     * 命令分类结果。
     */
    public record Classification(boolean isSearch, boolean isRead, boolean isList) {
        /** 是否为只读命令 */
        public boolean isReadOnly() {
            return isSearch || isRead || isList;
        }
    }

    /**
     * 正则分割 + 首 token 分类。
     * 管道/复合命令: 所有子命令均为只读 → 整体只读。
     */
    public Classification classify(String command) {
        if (command == null || command.isBlank()) {
            return new Classification(false, false, false);
        }
        // 分割操作符: |, &&, ||, ;
        String[] parts = command.split("\\s*(?:\\|\\||&&|[|;])\\s*");
        boolean allSearch = true, allRead = true, allList = true;
        boolean hasNonNeutral = false;

        for (String part : parts) {
            String cmd = extractFirstToken(part.trim());
            if (cmd.isEmpty() || NEUTRAL_CMDS.contains(cmd)) continue;

            hasNonNeutral = true;

            if (!SEARCH_CMDS.contains(cmd)) allSearch = false;
            if (!READ_CMDS.contains(cmd) && !SEARCH_CMDS.contains(cmd)) allRead = false;
            if (!LIST_CMDS.contains(cmd)) allList = false;

            // 任一子命令不在已知分类中 → fail-closed
            if (!SEARCH_CMDS.contains(cmd) && !READ_CMDS.contains(cmd)
                    && !LIST_CMDS.contains(cmd) && !SILENT_CMDS.contains(cmd)
                    && !NEUTRAL_CMDS.contains(cmd)) {
                return new Classification(false, false, false);
            }
        }

        if (!hasNonNeutral) {
            return new Classification(false, false, false);
        }

        return new Classification(allSearch, allRead, allList);
    }

    /**
     * 判断命令是否为搜索或读取命令。
     */
    public boolean isSearchOrReadCommand(String argv0) {
        if (argv0 == null || argv0.isBlank()) return false;
        return SEARCH_CMDS.contains(argv0) || READ_CMDS.contains(argv0) || LIST_CMDS.contains(argv0);
    }

    /**
     * 提取首 token，跳过环境变量赋值 (KEY=VAL) 和 sudo/env 前缀。
     */
    private String extractFirstToken(String part) {
        // 跳过 VAR=value 前缀
        String s = part.replaceAll("^(\\w+=\\S*\\s+)+", "");
        // 跳过 sudo/env 等前缀命令 — SPEC 增强
        s = s.replaceAll("^(sudo|env|nice|nohup|time)\\s+", "");
        // 提取首个 word
        Matcher m = FIRST_TOKEN_PATTERN.matcher(s);
        return m.find() ? m.group(1) : "";
    }
}

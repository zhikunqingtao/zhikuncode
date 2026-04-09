package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

import java.util.*;
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

    // ──── 层 1: 纯只读命令 (~60个，无需参数检查) ────
    // 对齐原版 readOnlyValidation.ts READONLY_COMMANDS
    private static final Set<String> READONLY_COMMANDS = Set.of(
            // 系统信息
            "cal", "uptime", "id", "uname", "free", "df", "du",
            "locale", "groups", "nproc", "getconf",
            // 文件查看 (只读)
            "cat", "head", "tail", "wc", "stat", "strings",
            "hexdump", "od", "nl", "readlink",
            // 文本处理 (只读)
            "cut", "paste", "tr", "column", "tac", "rev", "fold",
            "expand", "unexpand", "fmt", "comm", "cmp", "numfmt",
            // 路径操作 (只读)
            "basename", "dirname", "realpath",
            // 其他只读
            "diff", "true", "false", "sleep", "which", "type",
            "expr", "test", "seq", "tsort", "pr"
    );

    // ──── 层 2: 正则匹配只读命令 (有参数的只读命令) ────
    // 对齐原版 READONLY_COMMAND_REGEXES
    private static final List<Pattern> READONLY_REGEXES = List.of(
            Pattern.compile("^echo\\s"),
            Pattern.compile("^uniq(\\s|$)"),
            Pattern.compile("^pwd(\\s|$)"),
            Pattern.compile("^whoami(\\s|$)"),
            Pattern.compile("^node\\s+(-v|--version)"),
            Pattern.compile("^python3?\\s+--version"),
            Pattern.compile("^java\\s+(-version|--version)"),
            Pattern.compile("^mvn\\s+--version"),
            Pattern.compile("^gradle\\s+--version")
    );

    // ──── 层 3: flag 级别验证 (带安全 flag 白名单) ────
    // 对齐原版 COMMAND_ALLOWLIST
    public enum FlagArgType { NONE, VALUE, NUMBER }
    public record FlagConfig(Map<String, FlagArgType> safeFlags) {}

    private static final Map<String, FlagConfig> COMMAND_ALLOWLIST = Map.ofEntries(
            Map.entry("xargs", new FlagConfig(Map.of(
                    "-I", FlagArgType.VALUE, "-n", FlagArgType.NUMBER,
                    "-P", FlagArgType.NUMBER, "-d", FlagArgType.VALUE,
                    "-0", FlagArgType.NONE, "--null", FlagArgType.NONE,
                    "-t", FlagArgType.NONE, "--verbose", FlagArgType.NONE))),
            Map.entry("sort", new FlagConfig(Map.of(
                    "-r", FlagArgType.NONE, "--reverse", FlagArgType.NONE,
                    "-n", FlagArgType.NONE, "-u", FlagArgType.NONE,
                    "-k", FlagArgType.VALUE, "-t", FlagArgType.VALUE,
                    "-f", FlagArgType.NONE))),
            Map.entry("man", new FlagConfig(Map.of(
                    "-a", FlagArgType.NONE, "-f", FlagArgType.NONE, "-k", FlagArgType.NONE))),
            Map.entry("ps", new FlagConfig(Map.of(
                    "-e", FlagArgType.NONE, "-A", FlagArgType.NONE,
                    "-f", FlagArgType.NONE, "-u", FlagArgType.VALUE))),
            Map.entry("netstat", new FlagConfig(Map.of(
                    "-a", FlagArgType.NONE, "-n", FlagArgType.NONE,
                    "-t", FlagArgType.NONE, "-l", FlagArgType.NONE)))
    );

    // ──── 外部只读命令前缀 (docker/kubectl/npm/yarn/pip) ────
    private static final Set<String> EXTERNAL_READONLY_PREFIXES = Set.of(
            "docker ps", "docker images", "docker logs", "docker inspect",
            "kubectl get", "kubectl describe", "kubectl logs",
            "npm list", "npm info", "npm outdated", "npm audit",
            "yarn list", "yarn info", "yarn outdated",
            "pip list", "pip show", "pip freeze"
    );

    // ──── Git 只读命令 (带 flag 验证) ────
    private static final Map<String, FlagConfig> GIT_READONLY_COMMANDS = Map.of(
            "git diff", new FlagConfig(Map.of(
                    "--cached", FlagArgType.NONE, "--staged", FlagArgType.NONE,
                    "--stat", FlagArgType.NONE, "--name-only", FlagArgType.NONE,
                    "--name-status", FlagArgType.NONE, "--no-color", FlagArgType.NONE)),
            "git log", new FlagConfig(Map.of(
                    "--oneline", FlagArgType.NONE, "-n", FlagArgType.NUMBER,
                    "--graph", FlagArgType.NONE, "--stat", FlagArgType.NONE,
                    "--format", FlagArgType.VALUE, "--author", FlagArgType.VALUE)),
            "git show", new FlagConfig(Map.of(
                    "--stat", FlagArgType.NONE, "--format", FlagArgType.VALUE)),
            "git status", new FlagConfig(Map.of(
                    "-s", FlagArgType.NONE, "--short", FlagArgType.NONE,
                    "--porcelain", FlagArgType.NONE)),
            "git branch", new FlagConfig(Map.of(
                    "-a", FlagArgType.NONE, "--all", FlagArgType.NONE,
                    "-v", FlagArgType.NONE, "--verbose", FlagArgType.NONE))
    );

    // ──── 原有分类表 (用于 classify 方法) ────
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
     * 三层只读验证 — 对齐原版 readOnlyValidation.ts 架构。
     *
     * @param command 完整命令字符串
     * @return true 如果命令被验证为只读
     */
    public boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String trimmed = command.trim();

        // 层 1: 纯只读命令 — 首 token 匹配
        String firstToken = extractFirstToken(trimmed);
        if (READONLY_COMMANDS.contains(firstToken)) return true;

        // 层 2: 正则匹配只读
        for (Pattern p : READONLY_REGEXES) {
            if (p.matcher(trimmed).find()) return true;
        }

        // 层 3: flag 级别验证
        FlagConfig config = COMMAND_ALLOWLIST.get(firstToken);
        if (config != null && validateFlags(trimmed, config)) return true;

        // 外部只读命令前缀
        for (String prefix : EXTERNAL_READONLY_PREFIXES) {
            if (trimmed.startsWith(prefix)) return true;
        }

        // Git 只读命令
        for (var entry : GIT_READONLY_COMMANDS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                if (validateFlags(trimmed.substring(entry.getKey().length()).trim(), entry.getValue())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 验证命令 flags 是否全部在白名单内。
     */
    private boolean validateFlags(String argsStr, FlagConfig config) {
        if (argsStr.isBlank()) return true;
        String[] parts = argsStr.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("-")) {
                FlagArgType type = config.safeFlags().get(part);
                if (type == null) return false; // unknown flag
                if (type == FlagArgType.VALUE || type == FlagArgType.NUMBER) {
                    i++; // skip next arg (the value)
                }
            }
            // non-flag args are allowed (file paths, etc.)
        }
        return true;
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

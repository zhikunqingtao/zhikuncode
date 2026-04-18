package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 降级 Fallback: BashCommandClassifier — 纯正则实现。
 * <p>
 * 对应源码 isSearchOrReadBashCommand() 的等价逻辑。
 * 仅在 BashSecurityAnalyzer.parseForSecurity() 返回 parse-unavailable 时使用。
 * <p>
 * 安全设计: fail-closed — 无法分类时返回 UNKNOWN (需要权限确认)。
 * <p>
 * 三层验证架构 (对齐原版 readOnlyValidation.ts):
 * <ol>
 *   <li>层1: READONLY_COMMANDS — 纯只读命令，无需参数检查</li>
 *   <li>层2: READONLY_REGEXES — 正则匹配只读</li>
 *   <li>层3: COMMAND_ALLOWLIST — flag级别白名单验证 (含 validateFlags 6项安全加固)</li>
 * </ol>
 *
 * @see <a href="SPEC §3.2.3a">BashTool 实现算法</a>
 */
@Component
public class BashCommandClassifier {

    // ══════════════════════════════════════════════════════════════
    // 层 1: 纯只读命令 (~60个，无需参数检查)
    // 对齐原版 readOnlyValidation.ts READONLY_COMMANDS
    // ★ 安全修正: env/printenv 已移除(可泄露敏感环境变量); tput 移至 COMMAND_ALLOWLIST
    // ══════════════════════════════════════════════════════════════
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
            "expr", "test", "seq", "tsort", "pr",
            // 对齐原版 (不含 env/printenv/tput)
            "getent", "ulimit", "umask",
            "stty", "tset", "infocmp", "toe",
            "ldd", "nm", "objdump", "readelf", "size",
            "openssl", "xxd", "md5sum", "sha1sum", "cksum",
            "look", "spell", "factor", "bc"
    );

    // ══════════════════════════════════════════════════════════════
    // 层 2: 正则匹配只读命令 (有参数的只读命令)
    // 对齐原版 READONLY_COMMAND_REGEXES
    // ══════════════════════════════════════════════════════════════
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

    // ══════════════════════════════════════════════════════════════
    // 层 3: flag 级别验证 (带安全 flag 白名单)
    // 对齐原版 COMMAND_ALLOWLIST + validateFlags 6项安全加固
    // ══════════════════════════════════════════════════════════════
    public enum FlagArgType { NONE, VALUE, NUMBER }

    /**
     * Flag 配置 record — 对齐原版 ExternalCommandConfig。
     * @param safeFlags 安全 flag 白名单
     * @param respectsDoubleDash 是否遵守 POSIX -- (默认 true)
     * @param additionalDangerousCheck 额外危险检查回调 (rawCommand, args) -> isDangerous
     */
    public record FlagConfig(
            Map<String, FlagArgType> safeFlags,
            boolean respectsDoubleDash,
            BiPredicate<String, String[]> additionalDangerousCheck
    ) {
        /** 向后兼容: 仅 safeFlags (respectsDoubleDash=true, 无回调) */
        public FlagConfig(Map<String, FlagArgType> safeFlags) {
            this(safeFlags, true, null);
        }
        /** 向后兼容: safeFlags + respectsDoubleDash (无回调) */
        public FlagConfig(Map<String, FlagArgType> safeFlags, boolean respectsDoubleDash) {
            this(safeFlags, respectsDoubleDash, null);
        }
    }

    // flag 正则：以 - 开头，后跟字母/数字/- — 对齐原版 FLAG_PATTERN
    private static final Pattern FLAG_PATTERN = Pattern.compile("^-[a-zA-Z0-9-]");

    // xargs 安全目标命令白名单 — 对齐原版 SAFE_TARGET_COMMANDS_FOR_XARGS (L1232-1239)
    // SECURITY: 仅允许以下纯只读命令作为 xargs 目标
    private static final Set<String> SAFE_TARGET_COMMANDS_FOR_XARGS = Set.of(
            "echo", "printf", "wc", "grep", "head", "tail"
    );

    // find 危险参数黑名单正则 — 对齐原版 readOnlyValidation.ts L1569
    private static final Pattern FIND_DANGEROUS_PATTERN = Pattern.compile(
            "-(?:delete|exec|execdir|ok|okdir|fprint0?|fls|fprintf)\\b"
    );

    // fd/fdfind 共享安全 flag 白名单 — 对齐原版 FD_SAFE_FLAGS (L55-123)
    private static final Map<String, FlagArgType> FD_SAFE_FLAGS = Map.ofEntries(
            Map.entry("-h", FlagArgType.NONE), Map.entry("--help", FlagArgType.NONE),
            Map.entry("-V", FlagArgType.NONE), Map.entry("--version", FlagArgType.NONE),
            Map.entry("-H", FlagArgType.NONE), Map.entry("--hidden", FlagArgType.NONE),
            Map.entry("-I", FlagArgType.NONE), Map.entry("--no-ignore", FlagArgType.NONE),
            Map.entry("--no-ignore-vcs", FlagArgType.NONE),
            Map.entry("--no-ignore-parent", FlagArgType.NONE),
            Map.entry("-s", FlagArgType.NONE), Map.entry("--case-sensitive", FlagArgType.NONE),
            Map.entry("-i", FlagArgType.NONE), Map.entry("--ignore-case", FlagArgType.NONE),
            Map.entry("-g", FlagArgType.NONE), Map.entry("--glob", FlagArgType.NONE),
            Map.entry("--regex", FlagArgType.NONE),
            Map.entry("-F", FlagArgType.NONE), Map.entry("--fixed-strings", FlagArgType.NONE),
            Map.entry("-a", FlagArgType.NONE), Map.entry("--absolute-path", FlagArgType.NONE),
            Map.entry("-L", FlagArgType.NONE), Map.entry("--follow", FlagArgType.NONE),
            Map.entry("-p", FlagArgType.NONE), Map.entry("--full-path", FlagArgType.NONE),
            Map.entry("-0", FlagArgType.NONE), Map.entry("--print0", FlagArgType.NONE),
            Map.entry("-d", FlagArgType.NUMBER), Map.entry("--max-depth", FlagArgType.NUMBER),
            Map.entry("--min-depth", FlagArgType.NUMBER), Map.entry("--exact-depth", FlagArgType.NUMBER),
            Map.entry("-t", FlagArgType.VALUE), Map.entry("--type", FlagArgType.VALUE),
            Map.entry("-e", FlagArgType.VALUE), Map.entry("--extension", FlagArgType.VALUE),
            Map.entry("-S", FlagArgType.VALUE), Map.entry("--size", FlagArgType.VALUE),
            Map.entry("--changed-within", FlagArgType.VALUE),
            Map.entry("--changed-before", FlagArgType.VALUE),
            Map.entry("-o", FlagArgType.VALUE), Map.entry("--owner", FlagArgType.VALUE),
            Map.entry("-E", FlagArgType.VALUE), Map.entry("--exclude", FlagArgType.VALUE),
            Map.entry("--ignore-file", FlagArgType.VALUE),
            Map.entry("-c", FlagArgType.VALUE), Map.entry("--color", FlagArgType.VALUE),
            Map.entry("-j", FlagArgType.NUMBER), Map.entry("--threads", FlagArgType.NUMBER),
            Map.entry("--max-buffer-time", FlagArgType.VALUE),
            Map.entry("--max-results", FlagArgType.NUMBER),
            Map.entry("-1", FlagArgType.NONE), Map.entry("-q", FlagArgType.NONE),
            Map.entry("--quiet", FlagArgType.NONE), Map.entry("--show-errors", FlagArgType.NONE),
            Map.entry("--strip-cwd-prefix", FlagArgType.NONE),
            Map.entry("--one-file-system", FlagArgType.NONE),
            Map.entry("--prune", FlagArgType.NONE),
            Map.entry("--search-path", FlagArgType.VALUE),
            Map.entry("--base-directory", FlagArgType.VALUE),
            Map.entry("--path-separator", FlagArgType.VALUE),
            Map.entry("--batch-size", FlagArgType.NUMBER),
            Map.entry("--no-require-git", FlagArgType.NONE),
            Map.entry("--hyperlink", FlagArgType.VALUE),
            Map.entry("--and", FlagArgType.VALUE), Map.entry("--format", FlagArgType.VALUE)
    );

    private static final Map<String, FlagConfig> COMMAND_ALLOWLIST = Map.ofEntries(
            // xargs: +xargs目标命令检测在validateFlags中
            Map.entry("xargs", new FlagConfig(Map.ofEntries(
                    Map.entry("-I", FlagArgType.VALUE), Map.entry("-n", FlagArgType.NUMBER),
                    Map.entry("-P", FlagArgType.NUMBER), Map.entry("-d", FlagArgType.VALUE),
                    Map.entry("-0", FlagArgType.NONE), Map.entry("--null", FlagArgType.NONE),
                    Map.entry("-t", FlagArgType.NONE), Map.entry("--verbose", FlagArgType.NONE),
                    Map.entry("-r", FlagArgType.NONE), Map.entry("--no-run-if-empty", FlagArgType.NONE),
                    Map.entry("-E", FlagArgType.VALUE), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("-s", FlagArgType.NUMBER), Map.entry("--max-chars", FlagArgType.NUMBER)))),
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
                    "-t", FlagArgType.NONE, "-l", FlagArgType.NONE))),
            Map.entry("file", new FlagConfig(Map.of(
                    "-b", FlagArgType.NONE, "--brief", FlagArgType.NONE,
                    "-i", FlagArgType.NONE, "--mime", FlagArgType.NONE,
                    "-L", FlagArgType.NONE, "--dereference", FlagArgType.NONE))),
            Map.entry("sed", new FlagConfig(Map.of(
                    "-n", FlagArgType.NONE, "-e", FlagArgType.VALUE,
                    "-E", FlagArgType.NONE, "--regexp-extended", FlagArgType.NONE))),
            Map.entry("grep", new FlagConfig(Map.ofEntries(
                    Map.entry("-r", FlagArgType.NONE), Map.entry("-R", FlagArgType.NONE),
                    Map.entry("-l", FlagArgType.NONE), Map.entry("-L", FlagArgType.NONE),
                    Map.entry("-c", FlagArgType.NONE), Map.entry("-n", FlagArgType.NONE),
                    Map.entry("-i", FlagArgType.NONE), Map.entry("-v", FlagArgType.NONE),
                    Map.entry("-w", FlagArgType.NONE), Map.entry("-x", FlagArgType.NONE),
                    Map.entry("-E", FlagArgType.NONE), Map.entry("-P", FlagArgType.NONE),
                    Map.entry("-F", FlagArgType.NONE), Map.entry("-o", FlagArgType.NONE),
                    Map.entry("-h", FlagArgType.NONE), Map.entry("-H", FlagArgType.NONE),
                    Map.entry("--include", FlagArgType.VALUE), Map.entry("--exclude", FlagArgType.VALUE),
                    Map.entry("--exclude-dir", FlagArgType.VALUE), Map.entry("-A", FlagArgType.NUMBER),
                    Map.entry("-B", FlagArgType.NUMBER), Map.entry("-C", FlagArgType.NUMBER),
                    Map.entry("-m", FlagArgType.NUMBER), Map.entry("--color", FlagArgType.VALUE)))),
            Map.entry("rg", new FlagConfig(Map.ofEntries(
                    Map.entry("-i", FlagArgType.NONE), Map.entry("--ignore-case", FlagArgType.NONE),
                    Map.entry("-S", FlagArgType.NONE), Map.entry("--smart-case", FlagArgType.NONE),
                    Map.entry("-l", FlagArgType.NONE), Map.entry("--files-with-matches", FlagArgType.NONE),
                    Map.entry("-c", FlagArgType.NONE), Map.entry("--count", FlagArgType.NONE),
                    Map.entry("-n", FlagArgType.NONE), Map.entry("--line-number", FlagArgType.NONE),
                    Map.entry("-w", FlagArgType.NONE), Map.entry("-v", FlagArgType.NONE),
                    Map.entry("--invert-match", FlagArgType.NONE),
                    Map.entry("-o", FlagArgType.NONE), Map.entry("--only-matching", FlagArgType.NONE),
                    Map.entry("-t", FlagArgType.VALUE), Map.entry("-T", FlagArgType.VALUE),
                    Map.entry("-g", FlagArgType.VALUE), Map.entry("--glob", FlagArgType.VALUE),
                    Map.entry("-A", FlagArgType.NUMBER), Map.entry("-B", FlagArgType.NUMBER),
                    Map.entry("-C", FlagArgType.NUMBER),
                    Map.entry("-m", FlagArgType.NUMBER), Map.entry("--max-count", FlagArgType.NUMBER),
                    Map.entry("--hidden", FlagArgType.NONE), Map.entry("--no-ignore", FlagArgType.NONE),
                    Map.entry("-F", FlagArgType.NONE), Map.entry("--fixed-strings", FlagArgType.NONE),
                    Map.entry("--heading", FlagArgType.NONE), Map.entry("--no-heading", FlagArgType.NONE),
                    Map.entry("--column", FlagArgType.NONE),
                    Map.entry("--type-list", FlagArgType.NONE),
                    Map.entry("-u", FlagArgType.NONE), Map.entry("-a", FlagArgType.NONE),
                    Map.entry("--text", FlagArgType.NONE),
                    Map.entry("-z", FlagArgType.NONE), Map.entry("--json", FlagArgType.NONE),
                    Map.entry("--stats", FlagArgType.NONE), Map.entry("--debug", FlagArgType.NONE),
                    Map.entry("--color", FlagArgType.VALUE), Map.entry("--colors", FlagArgType.VALUE)))),
            Map.entry("tree", new FlagConfig(Map.of(
                    "-L", FlagArgType.NUMBER, "-d", FlagArgType.NONE,
                    "-a", FlagArgType.NONE, "-I", FlagArgType.VALUE,
                    "--gitignore", FlagArgType.NONE, "-f", FlagArgType.NONE))),
            Map.entry("date", new FlagConfig(Map.of(
                    "-u", FlagArgType.NONE, "--utc", FlagArgType.NONE,
                    "-d", FlagArgType.VALUE, "--date", FlagArgType.VALUE))),
            Map.entry("hostname", new FlagConfig(Map.of(
                    "-f", FlagArgType.NONE, "-i", FlagArgType.NONE))),
            Map.entry("lsof", new FlagConfig(Map.of(
                    "-i", FlagArgType.VALUE, "-p", FlagArgType.VALUE,
                    "-n", FlagArgType.NONE, "-P", FlagArgType.NONE))),
            Map.entry("pgrep", new FlagConfig(Map.of(
                    "-l", FlagArgType.NONE, "-a", FlagArgType.NONE,
                    "-f", FlagArgType.NONE, "-u", FlagArgType.VALUE))),
            Map.entry("ss", new FlagConfig(Map.of(
                    "-t", FlagArgType.NONE, "-u", FlagArgType.NONE,
                    "-l", FlagArgType.NONE, "-n", FlagArgType.NONE,
                    "-a", FlagArgType.NONE, "-p", FlagArgType.NONE))),
            Map.entry("base64", new FlagConfig(Map.of(
                    "-d", FlagArgType.NONE, "--decode", FlagArgType.NONE,
                    "-w", FlagArgType.NUMBER))),
            Map.entry("sha256sum", new FlagConfig(Map.of(
                    "-c", FlagArgType.NONE, "--check", FlagArgType.NONE))),
            // ★ fd/fdfind 白名单 — 对齐原版 FD_SAFE_FLAGS
            // SECURITY: -x/--exec, -X/--exec-batch, -l/--list-details 排除
            Map.entry("fd", new FlagConfig(FD_SAFE_FLAGS)),
            Map.entry("fdfind", new FlagConfig(FD_SAFE_FLAGS)),
            // ★ tput — 从READONLY_COMMANDS移至此处，带危险capability回调
            Map.entry("tput", new FlagConfig(Map.of(), true, (cmd, args) -> {
                for (String arg : args) {
                    if (Set.of("init", "reset", "rmacs", "smacs").contains(arg)) return true;
                }
                return false;
            }))
    );

    // ══════════════════════════════════════════════════════════════
    // 外部只读命令前缀 (docker/kubectl/npm/yarn/pip)
    // ══════════════════════════════════════════════════════════════
    private static final Set<String> EXTERNAL_READONLY_PREFIXES = Set.of(
            "docker ps", "docker images",
            "kubectl get", "kubectl describe", "kubectl logs",
            "npm list", "npm info", "npm outdated", "npm audit",
            "yarn list", "yarn info", "yarn outdated",
            "pip list", "pip show", "pip freeze"
    );

    // ══════════════════════════════════════════════════════════════
    // Git 只读命令 (带 flag 验证 + additionalDangerousCheck)
    // ══════════════════════════════════════════════════════════════
    private static final Map<String, FlagConfig> GIT_READONLY_COMMANDS = Map.ofEntries(
            Map.entry("git diff", new FlagConfig(Map.of(
                    "--cached", FlagArgType.NONE, "--staged", FlagArgType.NONE,
                    "--stat", FlagArgType.NONE, "--name-only", FlagArgType.NONE,
                    "--name-status", FlagArgType.NONE, "--no-color", FlagArgType.NONE))),
            Map.entry("git log", new FlagConfig(Map.of(
                    "--oneline", FlagArgType.NONE, "-n", FlagArgType.NUMBER,
                    "--graph", FlagArgType.NONE, "--stat", FlagArgType.NONE,
                    "--format", FlagArgType.VALUE, "--author", FlagArgType.VALUE))),
            Map.entry("git show", new FlagConfig(Map.of(
                    "--stat", FlagArgType.NONE, "--format", FlagArgType.VALUE))),
            Map.entry("git status", new FlagConfig(Map.of(
                    "-s", FlagArgType.NONE, "--short", FlagArgType.NONE,
                    "--porcelain", FlagArgType.NONE))),
            // ★ git tag — 对齐原版 L739-805 复杂回调
            Map.entry("git tag", new FlagConfig(
                    Map.ofEntries(
                            Map.entry("-l", FlagArgType.NONE), Map.entry("--list", FlagArgType.NONE),
                            Map.entry("--sort", FlagArgType.VALUE), Map.entry("-n", FlagArgType.NUMBER),
                            Map.entry("--contains", FlagArgType.VALUE), Map.entry("--no-contains", FlagArgType.VALUE),
                            Map.entry("--points-at", FlagArgType.VALUE), Map.entry("--merged", FlagArgType.VALUE),
                            Map.entry("--no-merged", FlagArgType.VALUE), Map.entry("--format", FlagArgType.VALUE),
                            Map.entry("--column", FlagArgType.NONE), Map.entry("--no-column", FlagArgType.NONE),
                            Map.entry("-i", FlagArgType.NONE), Map.entry("--ignore-case", FlagArgType.NONE)),
                    true,
                    (rawCmd, args) -> {
                        // SECURITY: `git tag v1.0` 创建标签 → not readOnly
                        Set<String> flagsWithArgs = Set.of(
                                "--contains", "--no-contains", "--merged", "--no-merged",
                                "--points-at", "--sort", "--format", "-n");
                        int idx = 0;
                        boolean seenListFlag = false;
                        boolean seenDashDash = false;
                        while (idx < args.length) {
                            String t = args[idx];
                            if (t == null || t.isEmpty()) { idx++; continue; }
                            if ("--".equals(t) && !seenDashDash) { seenDashDash = true; idx++; continue; }
                            if (!seenDashDash && t.startsWith("-")) {
                                if ("--list".equals(t) || "-l".equals(t)) {
                                    seenListFlag = true;
                                } else if (t.startsWith("-") && !t.startsWith("--")
                                        && t.length() > 2 && !t.contains("=")
                                        && t.substring(1).contains("l")) {
                                    seenListFlag = true; // bundle: -li, -il
                                }
                                if (t.contains("=")) { idx++; }
                                else if (flagsWithArgs.contains(t)) { idx += 2; }
                                else { idx++; }
                            } else {
                                if (!seenListFlag) return true; // positional arg + no --list = create tag
                                idx++;
                            }
                        }
                        return false;
                    }
            )),
            // ★ git branch — 对齐原版 L851-921 含 flagsWithOptionalArgs
            Map.entry("git branch", new FlagConfig(
                    Map.ofEntries(
                            Map.entry("-a", FlagArgType.NONE), Map.entry("--all", FlagArgType.NONE),
                            Map.entry("-v", FlagArgType.NONE), Map.entry("--verbose", FlagArgType.NONE),
                            Map.entry("-vv", FlagArgType.NONE),
                            Map.entry("-r", FlagArgType.NONE), Map.entry("--remotes", FlagArgType.NONE),
                            Map.entry("-l", FlagArgType.NONE), Map.entry("--list", FlagArgType.NONE),
                            Map.entry("--color", FlagArgType.NONE), Map.entry("--no-color", FlagArgType.NONE),
                            Map.entry("--column", FlagArgType.NONE), Map.entry("--no-column", FlagArgType.NONE),
                            Map.entry("--abbrev", FlagArgType.NUMBER), Map.entry("--no-abbrev", FlagArgType.NONE),
                            Map.entry("--contains", FlagArgType.VALUE), Map.entry("--no-contains", FlagArgType.VALUE),
                            Map.entry("--merged", FlagArgType.NONE), Map.entry("--no-merged", FlagArgType.NONE),
                            Map.entry("--points-at", FlagArgType.VALUE), Map.entry("--sort", FlagArgType.VALUE),
                            Map.entry("--show-current", FlagArgType.NONE),
                            Map.entry("-i", FlagArgType.NONE), Map.entry("--ignore-case", FlagArgType.NONE)),
                    true,
                    (rawCmd, args) -> {
                        Set<String> flagsWithArgs = Set.of(
                                "--contains", "--no-contains", "--points-at", "--sort");
                        Set<String> flagsWithOptionalArgs = Set.of("--merged", "--no-merged");
                        int idx = 0;
                        String lastFlag = "";
                        boolean seenListFlag = false;
                        boolean seenDashDash = false;
                        while (idx < args.length) {
                            String t = args[idx];
                            if (t == null || t.isEmpty()) { idx++; continue; }
                            if ("--".equals(t) && !seenDashDash) { seenDashDash = true; lastFlag = ""; idx++; continue; }
                            if (!seenDashDash && t.startsWith("-")) {
                                if ("--list".equals(t) || "-l".equals(t)) {
                                    seenListFlag = true;
                                } else if (t.startsWith("-") && !t.startsWith("--")
                                        && t.length() > 2 && !t.contains("=")
                                        && t.substring(1).contains("l")) {
                                    seenListFlag = true;
                                }
                                if (t.contains("=")) { lastFlag = t.split("=")[0]; idx++; }
                                else if (flagsWithArgs.contains(t)) { lastFlag = t; idx += 2; }
                                else { lastFlag = t; idx++; }
                            } else {
                                boolean lastFlagHasOptionalArg = flagsWithOptionalArgs.contains(lastFlag);
                                if (!seenListFlag && !lastFlagHasOptionalArg) {
                                    return true; // positional arg + no list/optional-arg = create branch
                                }
                                idx++;
                            }
                        }
                        return false;
                    }
            )),
            Map.entry("git remote", new FlagConfig(Map.of("-v", FlagArgType.NONE))),
            Map.entry("git stash", new FlagConfig(Map.of("list", FlagArgType.NONE))),
            Map.entry("git ls-files", new FlagConfig(Map.of())),
            Map.entry("git ls-tree", new FlagConfig(Map.of("-r", FlagArgType.NONE))),
            Map.entry("git rev-parse", new FlagConfig(Map.of())),
            Map.entry("git config", new FlagConfig(Map.of(
                    "--get", FlagArgType.VALUE, "-l", FlagArgType.NONE, "--list", FlagArgType.NONE))),
            Map.entry("git blame", new FlagConfig(Map.of("-L", FlagArgType.VALUE)))
    );

    // ══════════════════════════════════════════════════════════════
    // GH CLI 只读命令白名单 — 对齐原版 GH_READ_ONLY_COMMANDS (L984-1380)
    // SECURITY: 每个子命令都排除了 --web/-w 和 --show-token/-t
    // ══════════════════════════════════════════════════════════════
    private static final Map<String, FlagConfig> GH_READONLY_COMMANDS = Map.ofEntries(
            Map.entry("gh pr view", new FlagConfig(Map.of(
                    "--json", FlagArgType.VALUE, "--comments", FlagArgType.NONE,
                    "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh pr list", new FlagConfig(Map.ofEntries(
                    Map.entry("--state", FlagArgType.VALUE), Map.entry("-s", FlagArgType.VALUE),
                    Map.entry("--author", FlagArgType.VALUE), Map.entry("--assignee", FlagArgType.VALUE),
                    Map.entry("--label", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--base", FlagArgType.VALUE),
                    Map.entry("--head", FlagArgType.VALUE), Map.entry("--search", FlagArgType.VALUE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--draft", FlagArgType.NONE),
                    Map.entry("--app", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh pr diff", new FlagConfig(Map.of(
                    "--color", FlagArgType.VALUE, "--name-only", FlagArgType.NONE,
                    "--patch", FlagArgType.NONE, "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh pr checks", new FlagConfig(Map.ofEntries(
                    Map.entry("--watch", FlagArgType.NONE), Map.entry("--required", FlagArgType.NONE),
                    Map.entry("--fail-fast", FlagArgType.NONE), Map.entry("--json", FlagArgType.VALUE),
                    Map.entry("--interval", FlagArgType.NUMBER), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh pr status", new FlagConfig(Map.of(
                    "--conflict-status", FlagArgType.NONE, "-c", FlagArgType.NONE,
                    "--json", FlagArgType.VALUE, "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh issue view", new FlagConfig(Map.of(
                    "--json", FlagArgType.VALUE, "--comments", FlagArgType.NONE,
                    "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh issue list", new FlagConfig(Map.ofEntries(
                    Map.entry("--state", FlagArgType.VALUE), Map.entry("-s", FlagArgType.VALUE),
                    Map.entry("--assignee", FlagArgType.VALUE), Map.entry("--author", FlagArgType.VALUE),
                    Map.entry("--label", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--milestone", FlagArgType.VALUE),
                    Map.entry("--search", FlagArgType.VALUE), Map.entry("--json", FlagArgType.VALUE),
                    Map.entry("--app", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh issue status", new FlagConfig(Map.of(
                    "--json", FlagArgType.VALUE, "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh repo view", new FlagConfig(Map.of("--json", FlagArgType.VALUE))),
            Map.entry("gh run list", new FlagConfig(Map.ofEntries(
                    Map.entry("--branch", FlagArgType.VALUE), Map.entry("-b", FlagArgType.VALUE),
                    Map.entry("--status", FlagArgType.VALUE), Map.entry("-s", FlagArgType.VALUE),
                    Map.entry("--workflow", FlagArgType.VALUE), Map.entry("-w", FlagArgType.VALUE),
                    Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE), Map.entry("--event", FlagArgType.VALUE),
                    Map.entry("-e", FlagArgType.VALUE), Map.entry("--user", FlagArgType.VALUE),
                    Map.entry("-u", FlagArgType.VALUE), Map.entry("--created", FlagArgType.VALUE),
                    Map.entry("--commit", FlagArgType.VALUE), Map.entry("-c", FlagArgType.VALUE)))),
            Map.entry("gh run view", new FlagConfig(Map.ofEntries(
                    Map.entry("--log", FlagArgType.NONE), Map.entry("--log-failed", FlagArgType.NONE),
                    Map.entry("--exit-status", FlagArgType.NONE), Map.entry("--verbose", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE), Map.entry("--json", FlagArgType.VALUE),
                    Map.entry("--repo", FlagArgType.VALUE), Map.entry("-R", FlagArgType.VALUE),
                    Map.entry("--job", FlagArgType.VALUE), Map.entry("-j", FlagArgType.VALUE),
                    Map.entry("--attempt", FlagArgType.NUMBER), Map.entry("-a", FlagArgType.NUMBER)))),
            Map.entry("gh auth status", new FlagConfig(Map.of(
                    "--active", FlagArgType.NONE, "-a", FlagArgType.NONE,
                    "--hostname", FlagArgType.VALUE, "-h", FlagArgType.VALUE, "--json", FlagArgType.VALUE))),
            Map.entry("gh release list", new FlagConfig(Map.ofEntries(
                    Map.entry("--exclude-drafts", FlagArgType.NONE),
                    Map.entry("--exclude-pre-releases", FlagArgType.NONE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--order", FlagArgType.VALUE),
                    Map.entry("-O", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh release view", new FlagConfig(Map.of(
                    "--json", FlagArgType.VALUE, "--repo", FlagArgType.VALUE, "-R", FlagArgType.VALUE))),
            Map.entry("gh workflow list", new FlagConfig(Map.ofEntries(
                    Map.entry("--all", FlagArgType.NONE), Map.entry("-a", FlagArgType.NONE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh workflow view", new FlagConfig(Map.ofEntries(
                    Map.entry("--ref", FlagArgType.VALUE), Map.entry("-r", FlagArgType.VALUE),
                    Map.entry("--yaml", FlagArgType.NONE), Map.entry("-y", FlagArgType.NONE),
                    Map.entry("--repo", FlagArgType.VALUE), Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh label list", new FlagConfig(Map.ofEntries(
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--order", FlagArgType.VALUE),
                    Map.entry("--search", FlagArgType.VALUE), Map.entry("-S", FlagArgType.VALUE),
                    Map.entry("--sort", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh search repos", new FlagConfig(Map.ofEntries(
                    Map.entry("--archived", FlagArgType.NONE), Map.entry("--created", FlagArgType.VALUE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--language", FlagArgType.VALUE),
                    Map.entry("--license", FlagArgType.VALUE), Map.entry("--limit", FlagArgType.NUMBER),
                    Map.entry("-L", FlagArgType.NUMBER), Map.entry("--owner", FlagArgType.VALUE),
                    Map.entry("--sort", FlagArgType.VALUE), Map.entry("--visibility", FlagArgType.VALUE)))),
            Map.entry("gh search issues", new FlagConfig(Map.ofEntries(
                    Map.entry("--assignee", FlagArgType.VALUE), Map.entry("--author", FlagArgType.VALUE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--label", FlagArgType.VALUE),
                    Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("--state", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE)))),
            Map.entry("gh search prs", new FlagConfig(Map.ofEntries(
                    Map.entry("--assignee", FlagArgType.VALUE), Map.entry("--author", FlagArgType.VALUE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--label", FlagArgType.VALUE),
                    Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("--state", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE), Map.entry("--draft", FlagArgType.NONE)))),
            Map.entry("gh search commits", new FlagConfig(Map.ofEntries(
                    Map.entry("--author", FlagArgType.VALUE), Map.entry("--json", FlagArgType.VALUE),
                    Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("--owner", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE), Map.entry("--sort", FlagArgType.VALUE)))),
            Map.entry("gh search code", new FlagConfig(Map.ofEntries(
                    Map.entry("--extension", FlagArgType.VALUE), Map.entry("--filename", FlagArgType.VALUE),
                    Map.entry("--json", FlagArgType.VALUE), Map.entry("--language", FlagArgType.VALUE),
                    Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                    Map.entry("--owner", FlagArgType.VALUE), Map.entry("--repo", FlagArgType.VALUE),
                    Map.entry("-R", FlagArgType.VALUE), Map.entry("--size", FlagArgType.VALUE))))
    );

    // ══════════════════════════════════════════════════════════════
    // Docker 只读命令 — 对齐原版 DOCKER_READ_ONLY_COMMANDS (L1386-1410)
    // ══════════════════════════════════════════════════════════════
    private static final Map<String, FlagConfig> DOCKER_READONLY_COMMANDS = Map.of(
            "docker logs", new FlagConfig(Map.ofEntries(
                    Map.entry("--follow", FlagArgType.NONE), Map.entry("-f", FlagArgType.NONE),
                    Map.entry("--tail", FlagArgType.VALUE), Map.entry("-n", FlagArgType.VALUE),
                    Map.entry("--timestamps", FlagArgType.NONE), Map.entry("-t", FlagArgType.NONE),
                    Map.entry("--since", FlagArgType.VALUE), Map.entry("--until", FlagArgType.VALUE),
                    Map.entry("--details", FlagArgType.NONE))),
            "docker inspect", new FlagConfig(Map.of(
                    "--format", FlagArgType.VALUE, "-f", FlagArgType.VALUE,
                    "--type", FlagArgType.VALUE, "--size", FlagArgType.NONE, "-s", FlagArgType.NONE))
    );

    // ══════════════════════════════════════════════════════════════
    // Pyright 只读命令 — 对齐原版 PYRIGHT_READ_ONLY_COMMANDS (L1504-1531)
    // SECURITY: --watch/-w 和 --createstub 通过回调检测; respectsDoubleDash=false
    // ══════════════════════════════════════════════════════════════
    private static final Map<String, FlagConfig> PYRIGHT_READONLY_COMMANDS = Map.of(
            "pyright", new FlagConfig(
                    Map.ofEntries(
                            Map.entry("--outputjson", FlagArgType.NONE),
                            Map.entry("--project", FlagArgType.VALUE), Map.entry("-p", FlagArgType.VALUE),
                            Map.entry("--pythonversion", FlagArgType.VALUE),
                            Map.entry("--pythonplatform", FlagArgType.VALUE),
                            Map.entry("--typeshedpath", FlagArgType.VALUE),
                            Map.entry("--venvpath", FlagArgType.VALUE),
                            Map.entry("--level", FlagArgType.VALUE), Map.entry("--stats", FlagArgType.NONE),
                            Map.entry("--verbose", FlagArgType.NONE), Map.entry("--version", FlagArgType.NONE),
                            Map.entry("--dependencies", FlagArgType.NONE),
                            Map.entry("--warnings", FlagArgType.NONE)),
                    false, // pyright 不遵守 POSIX --
                    (cmd, args) -> {
                        for (String a : args) {
                            if ("--watch".equals(a) || "-w".equals(a)) return true;
                            if (a.startsWith("--createstub")) return true;
                        }
                        return false;
                    }
            )
    );

    // ══════════════════════════════════════════════════════════════
    // GH 危险回调 — 对齐原版 ghIsDangerousCallback (L944-982)
    // 防止通过 --repo=HOST/OWNER/REPO 或 URL 参数进行 DNS exfiltration
    // ══════════════════════════════════════════════════════════════
    private static boolean ghIsDangerousCallback(String rawCommand, String[] args) {
        for (String token : args) {
            if (token == null || token.isEmpty()) continue;
            String value = token;
            if (token.startsWith("-")) {
                int eq = token.indexOf('=');
                if (eq == -1) continue;
                value = token.substring(eq + 1);
                if (value.isEmpty()) continue;
            }
            if (!value.contains("/") && !value.contains("://") && !value.contains("@")) continue;
            if (value.contains("://")) return true;
            if (value.contains("@")) return true;
            long slashCount = value.chars().filter(c -> c == '/').count();
            if (slashCount >= 2) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 命令风险分级体系 (SAFE / MODERATE / DANGEROUS / BLOCKED)
    // ══════════════════════════════════════════════════════════════

    /**
     * 命令风险级别。
     * <ul>
     *   <li>SAFE: 只读命令，无副作用，直接放行</li>
     *   <li>MODERATE: 可能有副作用但风险可控，需权限确认</li>
     *   <li>DANGEROUS: 破坏性操作，需严格权限确认</li>
     *   <li>BLOCKED: 绝对禁止，直接拒绝执行</li>
     * </ul>
     */
    public enum RiskLevel {
        SAFE,       // 只读命令，无副作用
        MODERATE,   // 可能有副作用但风险可控
        DANGEROUS,  // 破坏性操作
        BLOCKED     // 绝对禁止
    }

    /**
     * 命令风险评估结果。
     */
    public record RiskAssessment(
            RiskLevel level,
            String reason,
            String command
    ) {
        public boolean isBlocked() { return level == RiskLevel.BLOCKED; }
        public boolean isSafe() { return level == RiskLevel.SAFE; }
    }

    // 绝对禁止命令 — 100% 拒绝执行
    private static final Set<String> BLOCKED_COMMAND_SET = Set.of(
            "sudo", "su", "doas");

    // 破坏性命令 — 需严格权限确认
    private static final Set<String> DANGEROUS_COMMAND_SET = Set.of(
            "rm", "rmdir", "chmod", "chown", "mkfs", "dd",
            "shred", "truncate", "wipefs", "fdisk", "parted",
            "kill", "killall", "pkill", "reboot", "shutdown",
            "halt", "poweroff", "init", "systemctl");

    // 中等风险命令 — 有副作用但风险可控
    private static final Set<String> MODERATE_COMMAND_SET = Set.of(
            "mv", "cp", "mkdir", "touch", "ln", "cd",
            "export", "unset", "tee", "install",
            "npm", "yarn", "pip", "brew", "apt", "apt-get",
            "git", "docker", "make", "cmake",
            "wget", "curl", "ssh", "scp");

    // 敏感信息泄露命令 — 需权限确认
    private static final Set<String> SENSITIVE_INFO_COMMANDS = Set.of(
            "env", "printenv", "set");

    /**
     * 评估命令的风险级别。
     * <p>
     * 支持管道/链式命令，取所有子命令中的最高风险级别。
     *
     * @param command 完整命令字符串
     * @return 风险评估结果
     */
    public RiskAssessment assessRisk(String command) {
        if (command == null || command.isBlank()) {
            return new RiskAssessment(RiskLevel.SAFE, "Empty command", command);
        }
        String trimmed = command.trim();

        // 拆分管道/链式命令
        String[] segments = trimmed.split("\\s*(?:\\|\\||&&|[|;])\\s*");
        RiskLevel maxLevel = RiskLevel.SAFE;
        String maxReason = "Read-only command";

        for (String segment : segments) {
            String seg = segment.trim();
            if (seg.isEmpty()) continue;

            // 剥离环境变量赋值前缀
            String stripped = seg.replaceAll("^(\\w+=\\S*\\s+)+", "");
            String firstToken = extractFirstToken(stripped);
            if (firstToken.isEmpty()) continue;

            RiskLevel segLevel;
            String segReason;

            if (BLOCKED_COMMAND_SET.contains(firstToken)) {
                segLevel = RiskLevel.BLOCKED;
                segReason = "Privilege escalation command: " + firstToken;
            } else if (DANGEROUS_COMMAND_SET.contains(firstToken)) {
                segLevel = RiskLevel.DANGEROUS;
                segReason = "Destructive command: " + firstToken;
            } else if (SENSITIVE_INFO_COMMANDS.contains(firstToken)
                    && (stripped.equals(firstToken) || stripped.startsWith(firstToken + " "))) {
                // env 无参数或 printenv → 信息泄露风险
                // 但 env <cmd> 包装命令不算
                if (stripped.equals(firstToken) || "printenv".equals(firstToken) || "set".equals(firstToken)) {
                    segLevel = RiskLevel.MODERATE;
                    segReason = "Sensitive info disclosure risk: " + firstToken;
                } else {
                    segLevel = RiskLevel.MODERATE;
                    segReason = "Command wrapper: " + firstToken;
                }
            } else if (MODERATE_COMMAND_SET.contains(firstToken)) {
                segLevel = RiskLevel.MODERATE;
                segReason = "Side-effect command: " + firstToken;
            } else if (isSearchOrReadCommand(firstToken) || READONLY_COMMANDS.contains(firstToken)) {
                segLevel = RiskLevel.SAFE;
                segReason = "Read-only command: " + firstToken;
            } else {
                segLevel = RiskLevel.MODERATE;
                segReason = "Unknown command: " + firstToken;
            }

            if (segLevel.ordinal() > maxLevel.ordinal()) {
                maxLevel = segLevel;
                maxReason = segReason;
            }
        }

        return new RiskAssessment(maxLevel, maxReason, command);
    }

    // ══════════════════════════════════════════════════════════════
    // 原有分类表 (用于 classify 方法)
    // ══════════════════════════════════════════════════════════════
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

    // ══════════════════════════════════════════════════════════════
    // isReadOnlyCommand — 三层只读验证 + 管道拆分 + 安全加固
    // 对齐原版 readOnlyValidation.ts 完整架构
    // ══════════════════════════════════════════════════════════════

    /**
     * 三层只读验证 — 对齐原版 readOnlyValidation.ts 架构。
     * <p>
     * 安全加固 (§11.5.6D + §11.5.7):
     * <ol>
     *   <li>管道拆分: 对每段递归调用 isReadOnlyCommand()</li>
     *   <li>containsUnquotedExpansion 前置检查: 防止 $变量 和 glob 绕过</li>
     *   <li>$token 拒绝 + 花括号展开检测: 在 ALLOWLIST 匹配前检查</li>
     * </ol>
     *
     * @param command 完整命令字符串
     * @return true 如果命令被验证为只读
     */
    public boolean isReadOnlyCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String trimmed = command.trim();

        // ★ 管道拆分 — 对齐原版 readOnlyValidation.ts L1969
        if (trimmed.contains("|") || trimmed.contains("&&")
                || trimmed.contains("||") || trimmed.contains(";")) {
            String[] segments = trimmed.split("\\s*(?:\\|\\||&&|[|;])\\s*");
            if (segments.length > 1) {
                for (String segment : segments) {
                    if (!segment.isBlank() && !isReadOnlyCommand(segment.trim())) {
                        return false;
                    }
                }
                return true;
            }
        }

        // ★ containsUnquotedExpansion 前置检查 — 对齐原版 L1968
        if (containsUnquotedExpansion(trimmed)) return false;

        // 层 1: 纯只读命令 — 首 token 匹配
        String firstToken = extractFirstToken(trimmed);
        if (READONLY_COMMANDS.contains(firstToken)) return true;

        // 层 2: 正则匹配只读
        for (Pattern p : READONLY_REGEXES) {
            if (p.matcher(trimmed).find()) return true;
        }

        // ★ find 命令特殊处理: 正则黑名单而非 flag 白名单 — 对齐原版 L1569
        if ("find".equals(firstToken)) {
            if (trimmed.matches(".*(?<!\\\\)[<>$`|{}&].*")) return false;
            if (trimmed.matches(".*(?<!\\\\)[()].*")) return false;
            return !FIND_DANGEROUS_PATTERN.matcher(trimmed).find();
        }

        // 层 3: flag 级别验证
        FlagConfig config = COMMAND_ALLOWLIST.get(firstToken);
        if (config != null) {
            String argsStr = trimmed.substring(firstToken.length()).trim();
            String[] argTokens = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");

            // ★ $token 拒绝 + 花括号展开检测 — 对齐原版 isCommandSafeViaFlagParsing L1351-1369
            for (String t : argTokens) {
                if (t != null && t.contains("$")) return false;
                if (t != null && t.contains("{") && (t.contains(",") || t.contains("..")))
                    return false;
            }

            if (validateFlags(argTokens, 0, config, firstToken)) {
                if (config.additionalDangerousCheck() != null
                        && config.additionalDangerousCheck().test(trimmed, argTokens)) {
                    return false;
                }
                return true;
            }
        }

        // 外部只读命令前缀
        for (String prefix : EXTERNAL_READONLY_PREFIXES) {
            if (trimmed.startsWith(prefix)) return true;
        }

        // Git 只读命令
        for (var entry : GIT_READONLY_COMMANDS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                String argsStr = trimmed.substring(entry.getKey().length()).trim();
                String[] argTokens = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");
                // $token 检查
                for (String t : argTokens) {
                    if (t != null && t.contains("$")) return false;
                }
                FlagConfig gitConfig = entry.getValue();
                if (validateFlags(argTokens, 0, gitConfig, entry.getKey().split(" ")[0])) {
                    if (gitConfig.additionalDangerousCheck() != null
                            && gitConfig.additionalDangerousCheck().test(trimmed, argTokens)) {
                        return false;
                    }
                    return true;
                }
            }
        }

        // GH CLI 只读命令
        for (var entry : GH_READONLY_COMMANDS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                String argsStr = trimmed.substring(entry.getKey().length()).trim();
                String[] argTokens = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");
                if (validateFlags(argTokens, 0, entry.getValue(), "gh")) {
                    if (!ghIsDangerousCallback(trimmed, argTokens)) return true;
                }
            }
        }

        // Docker 只读命令
        for (var entry : DOCKER_READONLY_COMMANDS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                String argsStr = trimmed.substring(entry.getKey().length()).trim();
                String[] argTokens = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");
                if (validateFlags(argTokens, 0, entry.getValue(), "docker")) return true;
            }
        }

        // Pyright 只读命令
        for (var entry : PYRIGHT_READONLY_COMMANDS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                String argsStr = trimmed.substring(entry.getKey().length()).trim();
                String[] argTokens = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");
                FlagConfig pyrightConfig = entry.getValue();
                if (validateFlags(argTokens, 0, pyrightConfig, entry.getKey())) {
                    if (pyrightConfig.additionalDangerousCheck() != null
                            && pyrightConfig.additionalDangerousCheck().test(trimmed, argTokens)) {
                        return false;
                    }
                    return true;
                }
            }
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // containsUnquotedExpansion — 引号状态机检测 $变量/glob
    // ══════════════════════════════════════════════════════════════

    /**
     * 检测未引用的变量展开和 glob — 对齐原版 containsUnquotedExpansion。
     * 使用字符级状态机精确跟踪引号状态。
     */
    public boolean containsUnquotedExpansion(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && !inSingleQuote) { escaped = true; continue; }
            if (c == '\'' && !inDoubleQuote) { inSingleQuote = !inSingleQuote; continue; }
            if (c == '"' && !inSingleQuote) { inDoubleQuote = !inDoubleQuote; continue; }
            if (inSingleQuote) continue;
            if (c == '$' && i + 1 < command.length()) {
                char next = command.charAt(i + 1);
                if (Character.isLetter(next) || next == '_' || next == '{' || next == '(') {
                    return true;
                }
            }
            if (!inDoubleQuote) {
                if (c == '*' || c == '?') return true;
                if (c == '{' && command.indexOf(',', i) > i
                        && command.indexOf('}', i) > command.indexOf(',', i)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // validateFlags — 安全加固版 (6项修复)
    // 对齐原版 readOnlyCommandValidation.ts validateFlags() (L1684-1893)
    // ══════════════════════════════════════════════════════════════

    /**
     * 安全加固版 flag 验证。
     * <p>修复项:
     * <ol>
     *   <li>--flag=value 内联值解析 (hasEquals 安全语义)</li>
     *   <li>组合短 flag 安全 (要求所有 bundled flag 为 none 类型)</li>
     *   <li>respectsDoubleDash 支持</li>
     *   <li>git -&lt;number&gt; 简写支持</li>
     *   <li>grep/rg -A20 附着数字参数支持</li>
     *   <li>xargs 安全目标命令检测</li>
     * </ol>
     */
    private boolean validateFlags(String[] tokens, int startIndex, FlagConfig config,
                                   String commandName) {
        int i = startIndex;
        while (i < tokens.length) {
            String token = tokens[i];
            if (token == null || token.isEmpty()) { i++; continue; }

            // -- 双横杠处理
            if ("--".equals(token)) {
                if (config.respectsDoubleDash()) {
                    break; // -- 后面都是位置参数，停止验证
                }
                i++; continue;
            }

            if (token.startsWith("-") && token.length() > 1 && FLAG_PATTERN.matcher(token).find()) {
                // ★ 修复1: --flag=value 格式解析
                boolean hasEquals = token.contains("=");
                String flag = hasEquals ? token.substring(0, token.indexOf('=')) : token;
                String inlineValue = hasEquals ? token.substring(token.indexOf('=') + 1) : null;

                if (flag == null || flag.isEmpty()) return false;

                FlagArgType flagArgType = config.safeFlags().get(flag);

                if (flagArgType == null) {
                    // git -<number> 简写
                    if ("git".equals(commandName) && flag.matches("^-\\d+$")) {
                        i++; continue;
                    }
                    // grep/rg -A20 附着数字参数
                    if (("grep".equals(commandName) || "rg".equals(commandName))
                            && flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        String potentialFlag = flag.substring(0, 2);
                        String potentialValue = flag.substring(2);
                        if (config.safeFlags().containsKey(potentialFlag)
                                && potentialValue.matches("^\\d+$")) {
                            i++; continue;
                        }
                    }
                    // ★ 修复2: 组合短 flag 安全检查 — 对齐原版 L1812-1830
                    if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        boolean allNone = true;
                        for (int j = 1; j < flag.length(); j++) {
                            String singleFlag = "-" + flag.charAt(j);
                            FlagArgType type = config.safeFlags().get(singleFlag);
                            if (type == null) return false;
                            if (type != FlagArgType.NONE) { allNone = false; break; }
                        }
                        if (!allNone) return false;
                        i++; continue;
                    }
                    return false; // 未知 flag
                }

                // 验证 flag 参数
                if (flagArgType == FlagArgType.NONE) {
                    if (hasEquals) return false;
                    i++;
                } else {
                    if (hasEquals) {
                        if (flagArgType == FlagArgType.NUMBER && inlineValue != null
                                && !inlineValue.isEmpty() && !inlineValue.matches("^\\d+$")) {
                            return false;
                        }
                        i++;
                    } else {
                        if (i + 1 >= tokens.length) return false;
                        String argValue = tokens[i + 1];
                        if (flagArgType == FlagArgType.NUMBER && argValue != null
                                && !argValue.matches("^\\d+$")) {
                            return false;
                        }
                        i += 2;
                    }
                }
            } else {
                // 非 flag（位置参数）— xargs 特殊处理
                if ("xargs".equals(commandName)) {
                    if ("--".equals(token) && i + 1 < tokens.length) {
                        i++;
                        token = tokens[i];
                    }
                    if (token != null && SAFE_TARGET_COMMANDS_FOR_XARGS.contains(token)) {
                        break; // 安全目标命令 → 停止验证
                    }
                    return false; // 未知目标命令 → 拒绝
                }
                i++;
            }
        }
        return true;
    }

    /** 兼容旧签名的适配方法 */
    private boolean validateFlags(String argsStr, FlagConfig config) {
        if (argsStr.isBlank()) return true;
        String[] tokens = argsStr.split("\\s+");
        return validateFlags(tokens, 0, config, null);
    }

    // ══════════════════════════════════════════════════════════════
    // 其他方法 (保留原有逻辑)
    // ══════════════════════════════════════════════════════════════

    /**
     * 检查复合命令中是否包含写操作。
     * 对齐原版 checkReadOnlyConstraints。
     */
    public boolean isCompoundCommandReadOnly(String command) {
        String[] segments = command.split("\\s*(?:\\|\\||&&|[|;])\\s*");
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.matches(".*(?<![>2&])>(?!>).*") || trimmed.contains(">>")) {
                String redirectTarget = trimmed.replaceAll(".*>>?\\s*", "").trim().split("\\s")[0];
                if (!redirectTarget.startsWith("/dev/")) return false;
            }
            String firstToken = extractFirstToken(trimmed);
            if (!isReadOnlyCommand(trimmed) && !READONLY_COMMANDS.contains(firstToken)
                    && !firstToken.isEmpty()) {
                if (Set.of("rm", "mv", "cp", "mkdir", "rmdir", "chmod", "chown",
                        "touch", "ln", "tee", "dd", "mkfs").contains(firstToken)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Git 命令特有安全检查 — 对齐原版 git -c/--exec-path/--config-env 阻断。
     */
    public boolean isGitCommandSafe(String command) {
        if (!command.startsWith("git ")) return true;
        if (command.matches("git\\s+-c\\s+.*")) return false;
        if (command.contains("--exec-path=")) return false;
        if (command.contains("--config-env")) return false;
        if (command.matches(".*\\\\\\\\[^\\s]+\\\\.*")) return false;
        return true;
    }

    /**
     * 提取首 token，跳过环境变量赋值 (KEY=VAL) 和 sudo/env 前缀。
     */
    private String extractFirstToken(String part) {
        String s = part.replaceAll("^(\\w+=\\S*\\s+)+", "");
        s = s.replaceAll("^(sudo|env|nice|nohup|time)\\s+", "");
        Matcher m = FIRST_TOKEN_PATTERN.matcher(s);
        return m.find() ? m.group(1) : "";
    }
}

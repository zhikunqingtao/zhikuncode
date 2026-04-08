package com.aicodeassistant.tool.bash;

import com.aicodeassistant.tool.bash.ast.BashAstNode;
import com.aicodeassistant.tool.bash.ast.BashAstNode.*;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.tool.bash.parser.BashLexer;
import com.aicodeassistant.tool.bash.parser.BashParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Bash AST 安全分析器 — 对齐源码 ast.ts parseForSecurity。
 * <p>
 * 核心问题: "能否为此命令字符串中的每个简单命令生成可信的 argv[]？"
 * <ul>
 *   <li>YES → Simple(commands) — 下游匹配 argv[0] 与权限规则</li>
 *   <li>NO  → TooComplex(reason) — 需用户确认</li>
 *   <li>解析失败 → ParseUnavailable — 回退遗留路径</li>
 * </ul>
 * <p>
 * 这不是沙箱，不阻止危险命令执行。
 *
 * @see <a href="SPEC §3.2.3c.2">AST 安全分析</a>
 */
@Component
public class BashSecurityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BashSecurityAnalyzer.class);

    // ──── 安全限制常量 ────

    /** 命令最大长度 — 超过直接返回 parse-unavailable */
    private static final int MAX_COMMAND_LENGTH = 10_000;

    // ──── 预检查正则 ────

    /** 控制字符 (ASCII 0-31 除 \t \n \r) */
    private static final Pattern CONTROL_CHAR_RE = Pattern.compile(
            "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** Unicode 空白 (非 ASCII 空白字符) */
    private static final Pattern UNICODE_WHITESPACE_RE = Pattern.compile(
            "[\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]");

    /** 反斜杠 + 空白 (仅匹配引号外的裸 \t 或 \\ 空格，排除行继续和单词内转义) — 预留位，当前禁用以避免误报 */
    // private static final Pattern BACKSLASH_WHITESPACE_RE = Pattern.compile("\\\\[\\t ]");

    /** Zsh ~[...] 动态目录 */
    private static final Pattern ZSH_TILDE_BRACKET_RE = Pattern.compile(
            "~\\[");

    /** Zsh =cmd 扩展 — 仅匹配行首/空白后的 =word，排除 VAR=value 赋值 */
    private static final Pattern ZSH_EQUALS_EXPANSION_RE = Pattern.compile(
            "(?:^|[\\s;&|])=[a-zA-Z]");

    /** 大括号展开混淆 — 仅匹配 {xx,yy} “大括号展开”含引号的模式，排除普通大括号分组 { cmd; } */
    private static final Pattern BRACE_WITH_QUOTE_RE = Pattern.compile(
            "\\{[^}]*,[^}]*['\"]\\}");

    /** argv 中隐藏换行 # 模式 */
    private static final Pattern NEWLINE_HASH_RE = Pattern.compile(
            "\\n#");

    // ──── 安全环境变量 ────

    /** SAFE_ENV_VARS — 解析时可安全替换为占位符 */
    private static final Set<String> SAFE_ENV_VARS = Set.of(
            // 路径
            "HOME", "PWD", "OLDPWD", "TMPDIR", "PATH",
            // 用户
            "USER", "LOGNAME", "UID", "EUID", "HOSTNAME",
            // Shell
            "SHELL", "BASH_VERSION", "BASHPID", "SHLVL", "HISTFILE", "IFS",
            // 系统
            "PPID", "RANDOM", "SECONDS", "LINENO"
    );

    /** 安全分析级特殊变量 (不含 @ 和 *) */
    private static final Set<String> SPECIAL_VAR_NAMES = Set.of(
            "?", "$", "!", "#", "0", "-"
    );

    /** 占位符 */
    private static final String VAR_PLACEHOLDER = "__VAR_PLACEHOLDER__";
    private static final String CMDSUB_PLACEHOLDER = "__CMDSUB_OUTPUT__";

    // ──── DANGEROUS_TYPES — 直接触发 too-complex 的 AST 节点类型 ────

    /**
     * 源码 ast.ts DANGEROUS_TYPES 的 Java 对应。
     * 这些节点类型在 AST 遍历中直接返回 too-complex。
     */
    private static final Set<String> DANGEROUS_TYPES = Set.of(
            "arithmetic_expansion",     // $(( expr ))
            "process_substitution",     // <() / >()
            "brace_expression",         // {a,b,c}
            "translated_string",        // $"..."
            "c_style_for_statement",    // for((i=0;i<10;i++))
            "ternary_expression"        // ((a?b:c))
    );

    // ──── Eval 类内置命令 (直接拒绝) ────

    /**
     * 对齐源码 ast.ts EVAL_LIKE_BUILTINS — 执行参数为 shell 代码的内置命令。
     * 安全关键: 遗漏任一项都可能导致 RCE 漏洞。
     */
    private static final Set<String> EVAL_LIKE_BUILTINS = Set.of(
            "eval", "source", ".", "exec",
            "fc",                       // fc -e/-s 执行编辑器/命令
            "coproc",                   // 以协进程方式执行任意命令
            "noglob", "nocorrect",      // zsh precommand modifiers
            "trap",                     // trap 'code' EXIT
            "enable", "hash",
            "mapfile", "readarray",     // -C callback 回调执行
            "bind",                     // bind -x 执行 shell 命令
            "complete", "compgen",      // -C/-F/-W 交互式回调
            "alias",                    // expand_aliases 风险
            "let"                       // 算术求值 = $(()) 等价
    );

    /** Zsh 危险内置命令 */
    private static final Set<String> ZSH_DANGEROUS_BUILTINS = Set.of(
            "zmodload", "autoload", "functions", "zle",
            "zstyle", "zformat", "zparseopts",
            "sched", "ztcp", "zsocket"
    );

    /** 包装命令 (需递归剥离) — 对齐源码 checkSemantics 完整列表 */
    private static final Set<String> WRAPPER_COMMANDS = Set.of(
            "command", "builtin", "sudo", "nohup", "nice", "env",
            "stdbuf", "timeout", "xargs"
    );

    /** [[ 算术比较操作符 */
    private static final Set<String> ARITHMETIC_COMPARE_OPS = Set.of(
            "-eq", "-ne", "-lt", "-gt", "-le", "-ge"
    );

    /** 裸变量展开不安全正则 (空格/tab/换行/*?[) */
    private static final Pattern BARE_VAR_UNSAFE_RE = Pattern.compile(
            "[\\s*?\\[]");

    /** 数组下标含 [ */
    private static final Pattern SUBSCRIPT_BRACKET_RE = Pattern.compile(
            "\\[");

    // ──── 解析器实例 ────

    private final BashParser parser = new BashParser();

    // ──── 公共入口 ────

    /**
     * 安全解析入口 — 返回三态结果。
     *
     * @param cmd Bash 命令字符串
     * @return ParseForSecurityResult
     */
    public ParseForSecurityResult parseForSecurity(String cmd) {
        // 空字符串 → simple([])
        if (cmd == null || cmd.isBlank()) {
            return new ParseForSecurityResult.Simple(List.of());
        }

        // 命令过长 → parse-unavailable
        if (cmd.length() > MAX_COMMAND_LENGTH) {
            return new ParseForSecurityResult.ParseUnavailable();
        }

        // ── 预检查链 ──
        String preCheckReason = runPreChecks(cmd);
        if (preCheckReason != null) {
            return new ParseForSecurityResult.TooComplex(preCheckReason, "pre-check");
        }

        // ── 解析 ──
        ProgramNode root = parser.parse(cmd);
        if (root == null) {
            // 解析器超时/预算耗尽/命令过长
            return new ParseForSecurityResult.ParseUnavailable();
        }

        // ── AST 遍历 ──
        return walkProgram(cmd, root);
    }

    // ──── 预检查 ────

    /**
     * 预检查链 — 返回拒绝原因或 null (通过)。
     */
    private String runPreChecks(String cmd) {
        if (CONTROL_CHAR_RE.matcher(cmd).find()) {
            return "Command contains control characters";
        }
        if (UNICODE_WHITESPACE_RE.matcher(cmd).find()) {
            return "Command contains Unicode whitespace";
        }
        if (ZSH_TILDE_BRACKET_RE.matcher(cmd).find()) {
            return "Command contains zsh ~[...] dynamic directory";
        }
        if (ZSH_EQUALS_EXPANSION_RE.matcher(cmd).find()) {
            return "Command contains zsh =cmd expansion";
        }
        if (BRACE_WITH_QUOTE_RE.matcher(cmd).find()) {
            return "Command contains brace-quote confusion";
        }
        return null;
    }

    // ──── AST 遍历 ────

    /**
     * walkProgram — 从根节点开始遍历 AST，提取 SimpleCommand 列表。
     */
    private ParseForSecurityResult walkProgram(String cmd, ProgramNode root) {
        List<SimpleCommandNode> commands = new ArrayList<>();
        Map<String, String> varScope = new HashMap<>();

        try {
            collectCommands(root, commands, varScope);
        } catch (TooComplexException e) {
            return new ParseForSecurityResult.TooComplex(e.reason, e.nodeType);
        }

        // ── 语义检查 ──
        for (SimpleCommandNode sc : commands) {
            String semanticReason = checkSemantics(sc);
            if (semanticReason != null) {
                return new ParseForSecurityResult.TooComplex(semanticReason, "semantic-check");
            }
        }

        return new ParseForSecurityResult.Simple(commands);
    }

    /**
     * 递归收集 SimpleCommandNode — 对齐源码 collectCommands()。
     */
    private void collectCommands(BashAstNode node,
                                 List<SimpleCommandNode> commands,
                                 Map<String, String> varScope) {
        if (node == null) return;

        switch (node) {
            case ProgramNode prog -> {
                for (StatementNode stmt : prog.statements()) {
                    collectCommands(stmt, commands, varScope);
                }
            }

            case StatementNode stmt ->
                    collectCommands(stmt.body(), commands, varScope);

            case SimpleCommandNode cmd ->
                    commands.add(cmd);

            case PipelineNode pipeline -> {
                // 管道各阶段: scope 副本 (子 shell 语义)
                for (BashAstNode pipeCmd : pipeline.commands()) {
                    Map<String, String> pipeScope = new HashMap<>(varScope);
                    collectCommands(pipeCmd, commands, pipeScope);
                }
            }

            case AndOrNode andOr -> {
                // && → scope 线性传递; || → scope 重置
                collectCommands(andOr.left(), commands, varScope);
                if ("&&".equals(andOr.operator())) {
                    collectCommands(andOr.right(), commands, varScope);
                } else {
                    // || 后 scope 重置为入口快照
                    Map<String, String> resetScope = new HashMap<>(varScope);
                    collectCommands(andOr.right(), commands, resetScope);
                }
            }

            case RedirectedStatementNode redir ->
                    collectCommands(redir.body(), commands, varScope);

            case SubshellNode subshell -> {
                // 子 shell → scope 副本
                Map<String, String> subScope = new HashMap<>(varScope);
                collectCommands(subshell.body(), commands, subScope);
            }

            case BraceGroupNode braceGroup ->
                    collectCommands(braceGroup.body(), commands, varScope);

            case IfNode ifNode -> {
                // 条件用真实 scope, 分支用 scope 副本
                collectCommands(ifNode.condition(), commands, varScope);
                Map<String, String> thenScope = new HashMap<>(varScope);
                collectCommands(ifNode.thenBody(), commands, thenScope);
                if (ifNode.elseBody() != null) {
                    Map<String, String> elseScope = new HashMap<>(varScope);
                    collectCommands(ifNode.elseBody(), commands, elseScope);
                }
            }

            case ForNode forNode -> {
                // 循环变量 → VAR_PLACEHOLDER, body 用 scope 副本
                Map<String, String> forScope = new HashMap<>(varScope);
                forScope.put(forNode.varName(), VAR_PLACEHOLDER);
                collectCommands(forNode.body(), commands, forScope);
            }

            case WhileNode whileNode -> {
                collectCommands(whileNode.condition(), commands, varScope);
                Map<String, String> bodyScope = new HashMap<>(varScope);
                collectCommands(whileNode.body(), commands, bodyScope);
            }

            case CaseNode caseNode -> {
                for (CaseItem item : caseNode.items()) {
                    Map<String, String> itemScope = new HashMap<>(varScope);
                    collectCommands(item.body(), commands, itemScope);
                }
            }

            case FunctionDefNode funcDef ->
                    collectCommands(funcDef.body(), commands, varScope);

            case NegatedCommandNode negated ->
                    collectCommands(negated.body(), commands, varScope);

            case DeclarationCommandNode decl ->
                    // 声明命令提取 argv
                    commands.add(new SimpleCommandNode(
                            decl.argv(), decl.assignments(), List.of(),
                            decl.startByte(), decl.endByte(), decl.rawText()));

            case TestCommandNode test -> {
                // 测试命令提取 argv，前缀补充命令名 [ 或 [[
                String cmd = test.rawText().startsWith("[[") ? "[[" : "[";
                List<String> testArgv = new ArrayList<>();
                testArgv.add(cmd);
                testArgv.addAll(test.argv());
                commands.add(new SimpleCommandNode(
                        testArgv, List.of(), List.of(),
                        test.startByte(), test.endByte(), test.rawText()));
            }

            case VariableAssignmentNode va -> {
                // 变量赋值追踪到 scope
                if (!"PS4".equals(va.name()) && !"IFS".equals(va.name())) {
                    varScope.put(va.name(), va.value() != null ? va.value() : "");
                } else {
                    throw new TooComplexException(
                            "Dangerous variable assignment: " + va.name(),
                            "variable_assignment");
                }
            }

            case TooComplexNode complex ->
                    throw new TooComplexException(complex.reason(), "too-complex");
        }
    }

    // ──── 语义检查 (checkSemantics) ────

    /**
     * 对每个 SimpleCommand 执行后置语义验证。
     *
     * @return 拒绝原因，或 null (通过)
     */
    private String checkSemantics(SimpleCommandNode cmd) {
        List<String> argv = cmd.argv();
        if (argv.isEmpty()) return null;

        // 1. 包装命令剥离
        argv = stripWrappers(argv);
        if (argv.isEmpty()) return null;

        String argv0 = argv.getFirst();

        // 2. argv[0] 基本验证
        if (argv0.isEmpty()) {
            return "Empty command name";
        }
        if (argv0.contains(VAR_PLACEHOLDER) || argv0.contains(CMDSUB_PLACEHOLDER)) {
            return "Command name contains placeholder";
        }
        if (argv0.startsWith("-") || argv0.startsWith("|") || argv0.startsWith("&")) {
            return "Command name starts with operator character: " + argv0;
        }

        // 3. Shell 关键字作为命令名 → 拒绝 (tree-sitter 误解析)
        if (BashLexer.SHELL_KEYWORDS.contains(argv0)) {
            return "Shell keyword as command name: " + argv0;
        }

        // 4. Eval 类内置命令检查
        if (EVAL_LIKE_BUILTINS.contains(argv0)) {
            return "eval-like builtin: " + argv0;
        }

        // 5. Zsh 危险内置命令
        if (ZSH_DANGEROUS_BUILTINS.contains(argv0)) {
            return "zsh dangerous builtin: " + argv0;
        }

        // 6. 数组下标评估防护 (SUBSCRIPT_EVAL_FLAGS)
        String subscriptCheck = checkSubscriptEval(argv);
        if (subscriptCheck != null) return subscriptCheck;

        // 7. [[ 算术比较防护: -eq/-ne/-lt 等两侧含 [ → 拒绝
        String arithCheck = checkArithmeticCompare(argv);
        if (arithCheck != null) return arithCheck;

        // 8. /proc/*/environ 访问检查 (泄露密钥)
        for (String arg : argv) {
            if (arg.contains("/proc/") && arg.contains("/environ")) {
                return "/proc/*/environ access detected";
            }
        }

        // 9. jq system() 和 -f/-L/--from-file 检查
        if ("jq".equals(argv0)) {
            for (String arg : argv) {
                if (arg.contains("system(") || arg.contains("system (")) {
                    return "jq system() call detected";
                }
            }
            for (String arg : argv) {
                if ("-f".equals(arg) || "-L".equals(arg) || "--from-file".equals(arg)) {
                    return "jq " + arg + " detected";
                }
            }
        }

        // 10. argv 中 \n# 模式检查 (隐藏参数绕过路径验证)
        for (String arg : argv) {
            if (NEWLINE_HASH_RE.matcher(arg).find()) {
                return "Hidden argument after newline-hash pattern";
            }
        }

        // 11. 翻译字符串检测: $"..." 是 locale-dependent 翻译，可能被滥用
        for (String arg : argv) {
            if (arg.startsWith("$\"") || arg.contains("$\"")) {
                return "translated_string detected in argument";
            }
        }

        // 12. 大括号展开检测: {a,b,c} 模式 — shell 会展开为多个参数
        for (String arg : argv) {
            if (arg.startsWith("{") && arg.endsWith("}") && arg.contains(",")) {
                return "brace_expression detected in argument";
            }
        }

        return null;
    }

    /**
     * [[ 算术比较防护。
     * -eq/-ne/-lt/-gt/-le/-ge 两侧操作数含 [ → 拒绝。
     * bash 在算术比较中执行 $(...) 嵌套在下标中的命令。
     */
    private String checkArithmeticCompare(List<String> argv) {
        for (int i = 0; i < argv.size(); i++) {
            if (ARITHMETIC_COMPARE_OPS.contains(argv.get(i))) {
                // 检查左操作数
                if (i > 0 && SUBSCRIPT_BRACKET_RE.matcher(argv.get(i - 1)).find()) {
                    return "Arithmetic comparison with array subscript on left: " + argv.get(i - 1);
                }
                // 检查右操作数
                if (i + 1 < argv.size() && SUBSCRIPT_BRACKET_RE.matcher(argv.get(i + 1)).find()) {
                    return "Arithmetic comparison with array subscript on right: " + argv.get(i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 递归剥离包装命令 — 对齐源码 checkSemantics 完整包装列表。
     * <p>
     * 包装命令:
     * <ul>
     *   <li>command [-pvV] → 递归 (-v/-V 仅查询不执行, 保留)</li>
     *   <li>builtin [-p] → 递归 (-p 查询不执行, 保留)</li>
     *   <li>sudo [-niuU...] → 递归</li>
     *   <li>nohup → 递归</li>
     *   <li>xargs -I → 递归 (仅 -I 形式)</li>
     *   <li>nice [-n N] → 递归</li>
     *   <li>env [VAR=val] [-i] → 递归 (拒绝 -S/-C/-P)</li>
     *   <li>stdbuf [-oOeE MODE] → 递归</li>
     *   <li>timeout [flags] duration → 递归</li>
     * </ul>
     */
    private List<String> stripWrappers(List<String> argv) {
        if (argv.isEmpty()) return argv;

        String cmd = argv.getFirst();

        // command -v/-V 仅查询不执行 → 保留
        if ("command".equals(cmd) && argv.size() > 1) {
            String flag = argv.get(1);
            if ("-v".equals(flag) || "-V".equals(flag)) {
                return argv;
            }
        }

        // builtin -p 查询不执行 → 保留
        if ("builtin".equals(cmd) && argv.size() > 1) {
            if ("-p".equals(argv.get(1))) {
                return argv;
            }
        }

        if (!WRAPPER_COMMANDS.contains(cmd)) {
            return argv;
        }

        // 特殊处理: env -S/-C/-P → 拒绝 (允许注入)
        if ("env".equals(cmd)) {
            for (int i = 1; i < argv.size(); i++) {
                String arg = argv.get(i);
                if ("-S".equals(arg) || "-C".equals(arg) || "-P".equals(arg)) {
                    return argv; // 不剥离, 让 eval-like 检查处理
                }
            }
        }

        // 特殊处理: timeout 的 duration 参数不能含 $()
        if ("timeout".equals(cmd) && argv.size() > 1) {
            for (int i = 1; i < argv.size(); i++) {
                String arg = argv.get(i);
                if (!arg.startsWith("-") && (arg.contains("$(") || arg.contains("`"))) {
                    return argv; // duration 含命令替换 → 不剥离
                }
            }
        }

        // 剥离第一层: 跳过标志和 VAR=val, 找到实际命令
        List<String> remaining = new ArrayList<>();
        boolean foundCmd = false;
        for (int i = 1; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (!foundCmd && arg.startsWith("-")) {
                // nice -n N: 跳过 -n 和下一个参数
                if ("nice".equals(cmd) && "-n".equals(arg) && i + 1 < argv.size()) {
                    i++; // 跳过 N
                }
                continue;
            }
            if (!foundCmd && arg.contains("=")) {
                continue; // 跳过 env VAR=val
            }
            // timeout: 第一个非标志参数是 duration, 跳过它
            if (!foundCmd && "timeout".equals(cmd)) {
                foundCmd = false;
                // duration 已跳过, 标记为找到内部命令位置
                remaining.clear();
                for (int j = i + 1; j < argv.size(); j++) {
                    remaining.add(argv.get(j));
                }
                break;
            }
            foundCmd = true;
            remaining.add(arg);
        }

        if (remaining.isEmpty()) return argv;

        // 递归剥离下一层
        return stripWrappers(remaining);
    }

    /**
     * 数组下标评估防护。
     * printf -v / read -a / declare -n 等的 NAME 参数含 [ 则拒绝。
     */
    private String checkSubscriptEval(List<String> argv) {
        if (argv.isEmpty()) return null;
        String cmd = argv.getFirst();

        // printf -v NAME
        if ("printf".equals(cmd)) {
            for (int i = 1; i < argv.size() - 1; i++) {
                if ("-v".equals(argv.get(i))) {
                    String name = (i + 1 < argv.size()) ? argv.get(i + 1) : "";
                    if (SUBSCRIPT_BRACKET_RE.matcher(name).find()) {
                        return "printf -v with array subscript: " + name;
                    }
                }
            }
        }

        // read -a NAME
        if ("read".equals(cmd)) {
            for (int i = 1; i < argv.size(); i++) {
                if ("-a".equals(argv.get(i)) && i + 1 < argv.size()) {
                    String name = argv.get(i + 1);
                    if (SUBSCRIPT_BRACKET_RE.matcher(name).find()) {
                        return "read -a with array subscript: " + name;
                    }
                }
            }
        }

        // declare -n NAME
        if ("declare".equals(cmd) || "typeset".equals(cmd)) {
            for (int i = 1; i < argv.size(); i++) {
                if ("-n".equals(argv.get(i)) && i + 1 < argv.size()) {
                    String name = argv.get(i + 1);
                    if (SUBSCRIPT_BRACKET_RE.matcher(name).find()) {
                        return "declare -n with array subscript: " + name;
                    }
                }
            }
        }

        // unset / read 裸名称含 [
        if ("unset".equals(cmd) || "read".equals(cmd)) {
            for (int i = 1; i < argv.size(); i++) {
                String arg = argv.get(i);
                if (!arg.startsWith("-") && SUBSCRIPT_BRACKET_RE.matcher(arg).find()) {
                    return cmd + " with array subscript: " + arg;
                }
            }
        }

        return null;
    }

    // ──── 内部异常 ────

    /** 遍历中抛出的 too-complex 信号 */
    private static class TooComplexException extends RuntimeException {
        final String reason;
        final String nodeType;

        TooComplexException(String reason, String nodeType) {
            super(reason);
            this.reason = reason;
            this.nodeType = nodeType;
        }
    }
}

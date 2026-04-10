package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sed 命令安全验证器 — 对齐原版 sedValidation.ts。
 * <p>
 * 两种模式:
 * <ol>
 *   <li>Pattern 1: sed -n 'Np' — 只读打印</li>
 *   <li>Pattern 2: sed 's/old/new/flags' — 替换命令</li>
 * </ol>
 *
 * @see <a href="SPEC §4.6">BashTool 安全规则</a>
 */
@Component
public class SedValidator {

    /** 允许的只读 flags (对齐原版 sedValidation.ts allowedFlags) */
    private static final Set<String> READONLY_FLAGS = Set.of(
            "-n", "--quiet", "--silent", "-E", "--regexp-extended", "-r",
            "-z", "--zero-terminated", "--posix");

    /** 打印命令正则 (对齐原版 isPrintCommand) */
    private static final Pattern PRINT_COMMAND_PATTERN =
            Pattern.compile("^(?:\\d+|\\d+,\\d+)?p$");

    /** 替换命令 flags 白名单 (对齐原版 substitution flags) */
    private static final Pattern SAFE_SUB_FLAGS = Pattern.compile("^[gpiImM1-9]*$");

    /** in-place 编辑 flags */
    private static final Set<String> INPLACE_FLAGS = Set.of("-i", "--in-place");

    /**
     * Pattern 1: sed -n 'Np' — 只读打印。
     *
     * @param args        sed 命令的 flag 参数列表 (不含 'sed' 本身)
     * @param expressions sed 表达式列表
     * @return true 如果是只读打印命令
     */
    public boolean isReadOnlyPrint(List<String> args, List<String> expressions) {
        if (!hasFlag(args, Set.of("-n", "--quiet", "--silent"))) return false;
        if (!validateFlagsAgainst(args, READONLY_FLAGS)) return false;
        return expressions.stream().allMatch(expr ->
                Arrays.stream(expr.split(";"))
                        .map(String::trim)
                        .allMatch(cmd -> PRINT_COMMAND_PATTERN.matcher(cmd).matches()));
    }

    /**
     * Pattern 2: sed 's/old/new/flags' — 替换命令分类。
     *
     * @param args            sed 命令的 flag 参数列表
     * @param expressions     sed 表达式列表
     * @param allowFileWrites 是否允许 -i (in-place 编辑)
     * @return 分类结果
     */
    public SedClassification classifySubstitution(List<String> args,
                                                   List<String> expressions,
                                                   boolean allowFileWrites) {
        // 不允许写入时，禁止 -i flag
        if (!allowFileWrites && hasFlag(args, INPLACE_FLAGS)) {
            return SedClassification.NEEDS_PERMISSION;
        }
        // ★ 组合 flag 验证 (Task 2.2)
        String[] flagTokens = args.stream().filter(a -> a.startsWith("-")).toArray(String[]::new);
        if (!validateSedFlags(flagTokens, READONLY_FLAGS)) {
            return SedClassification.NEEDS_PERMISSION;
        }
        if (expressions.size() != 1) return SedClassification.NEEDS_PERMISSION;

        String expr = expressions.getFirst().trim();
        // ★ 支持任意分隔符: s/ s| s# 等 (Task 2.1)
        if (expr.length() < 2 || expr.charAt(0) != 's') return SedClassification.NEEDS_PERMISSION;

        // ★ 危险操作检测 (Task 2.3)
        if (containsDangerousOperations(expr)) return SedClassification.NEEDS_PERMISSION;

        // 提取 flags 部分并验证
        String flags = extractSubstitutionFlags(expr);
        if (flags != null && SAFE_SUB_FLAGS.matcher(flags).matches()) {
            return allowFileWrites
                    ? SedClassification.WRITE_WITH_PERMISSION
                    : SedClassification.READONLY_STDOUT;
        }
        return SedClassification.NEEDS_PERMISSION;
    }

    /**
     * 从 s<delim>pattern<delim>replacement<delim>flags 中提取 flags。
     * 支持任意非字母数字非反斜杠字符作为分隔符（对齐 POSIX sed 规范）。
     *
     * @return flags 字符串，或 null 如果格式无效
     */
    String extractSubstitutionFlags(String expr) {
        if (expr.length() < 2 || expr.charAt(0) != 's') return null;

        char delim = expr.charAt(1); // s 后面的第一个字符就是分隔符

        // POSIX: 分隔符不能是反斜杠、换行符、字母、数字
        if (delim == '\\' || delim == '\n' || Character.isLetterOrDigit(delim)) return null;

        int delimCount = 0;
        int lastDelimPos = -1;
        for (int i = 2; i < expr.length(); i++) { // 从位置2开始（跳过 s 和首个分隔符）
            if (expr.charAt(i) == '\\' && i + 1 < expr.length()) {
                i++; // 跳过转义字符
                continue;
            }
            if (expr.charAt(i) == delim) {
                delimCount++;
                lastDelimPos = i;
                if (delimCount == 2) break; // 找到 pattern/replacement 的两个分隔符
            }
        }
        if (delimCount < 1) return null; // 无效格式
        if (delimCount == 2) {
            return expr.substring(lastDelimPos + 1);
        }
        return ""; // 只有 1 个分隔符 → 没有 flags
    }

    /**
     * 检查参数列表是否包含指定 flag 之一。
     */
    private boolean hasFlag(List<String> args, Set<String> flags) {
        return args.stream().anyMatch(flags::contains);
    }

    /**
     * 验证所有 flag 参数是否在允许列表内。
     */
    private boolean validateFlagsAgainst(List<String> args, Set<String> allowedFlags) {
        return args.stream()
                .filter(a -> a.startsWith("-"))
                .allMatch(allowedFlags::contains);
    }

    /**
     * 拆分组合 flag: "-nE" → ['-n', '-E'] 逐个验证。
     * 对齐原版 sedValidation.ts validateFlagsAgainstAllowlist()。
     */
    private boolean validateSedFlags(String[] tokens, Set<String> allowedFlags) {
        for (String token : tokens) {
            if (!token.startsWith("-") || token.startsWith("--")) {
                // 长 flag 直接检查
                if (token.startsWith("--") && !allowedFlags.contains(token)) return false;
                continue;
            }
            // 短 flag 组合: "-nE" → 检查 "-n" 和 "-E"
            for (int i = 1; i < token.length(); i++) {
                String singleFlag = "-" + token.charAt(i);
                if (!allowedFlags.contains(singleFlag)) return false;
            }
        }
        return true;
    }

    /**
     * 检测危险 sed 操作 — 对齐原版 containsDangerousOperations()（L473-629）。
     * 即使通过了白名单检查，也要拒绝包含危险操作的表达式。
     */
    private boolean containsDangerousOperations(String expr) {
        String cmd = expr.trim();
        if (cmd.isEmpty()) return false;

        // 1. 拒绝非 ASCII 字符（Unicode 同形字攻击）
        if (!cmd.matches("[\\x01-\\x7F]+")) return true;
        // 2. 拒绝花括号块（太复杂无法安全解析）
        if (cmd.contains("{") || cmd.contains("}")) return true;
        // 3. 拒绝换行符（多行命令）
        if (cmd.contains("\n")) return true;
        // 4. 拒绝 w/W 写文件命令（各种地址格式）
        if (Pattern.matches("^[wW]\\s*\\S+.*", cmd)) return true;
        if (Pattern.matches("^\\d+\\s*[wW]\\s*\\S+.*", cmd)) return true;
        if (Pattern.matches("^/[^/]*/[IMim]*\\s*[wW]\\s*\\S+.*", cmd)) return true;
        // 5. 拒绝 e/E 执行命令
        if (Pattern.matches("^e.*", cmd)) return true;
        if (Pattern.matches("^\\d+\\s*e.*", cmd)) return true;
        // 6. 拒绝替换命令中的 w/W/e/E flags
        Matcher subst = Pattern.compile("s([^\\\\\\n]).*?\\1.*?\\1(.*?)$").matcher(cmd);
        if (subst.find()) {
            String flags = subst.group(2);
            if (flags != null && flags.matches(".*[wWeE].*")) return true;
        }
        return false;
    }

    /**
     * sed 命令安全分类结果。
     */
    public enum SedClassification {
        /** 只读输出到 stdout */
        READONLY_STDOUT,
        /** 写入文件但需要权限确认 */
        WRITE_WITH_PERMISSION,
        /** 需要用户权限确认 */
        NEEDS_PERMISSION
    }
}

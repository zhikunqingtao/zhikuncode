package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
        if (expressions.size() != 1) return SedClassification.NEEDS_PERMISSION;

        String expr = expressions.getFirst().trim();
        if (!expr.startsWith("s/")) return SedClassification.NEEDS_PERMISSION;

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
     * 从 s/pattern/replacement/flags 中提取末尾的 flags。
     *
     * @return flags 字符串，或 null 如果格式无效
     */
    String extractSubstitutionFlags(String expr) {
        if (!expr.startsWith("s/")) return null;
        // 找到第三个未转义的 / — 分隔符
        int delimCount = 0;
        int lastDelimPos = -1;
        for (int i = 1; i < expr.length(); i++) {
            if (expr.charAt(i) == '/' && (i == 0 || expr.charAt(i - 1) != '\\')) {
                delimCount++;
                lastDelimPos = i;
                if (delimCount == 3) break;
            }
        }
        if (delimCount < 2) return null; // 无效格式
        if (delimCount == 3) {
            return expr.substring(lastDelimPos + 1);
        }
        // 只有 2 个分隔符 → 没有 flags
        return "";
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

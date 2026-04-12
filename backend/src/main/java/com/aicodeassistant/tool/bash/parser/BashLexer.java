package com.aicodeassistant.tool.bash.parser;

import com.aicodeassistant.tool.bash.ast.BashTokenType;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Bash 词法分析器 — 手写实现，对齐源码 bashParser.ts Lexer。
 * <p>
 * 核心设计:
 * <ul>
 *   <li>双索引追踪: i (Java char 索引) + b (UTF-8 字节偏移)</li>
 *   <li>最长匹配: 多字符操作符优先匹配 (3字符 → 2字符 → 1字符)</li>
 *   <li>上下文敏感: [[ [ { } ! 仅在命令位置识别为操作符</li>
 *   <li>引号状态机: 单引号/双引号/ANSI-C 引号/$() 嵌套</li>
 * </ul>
 *
 * @see <a href="SPEC §3.2.3c.1">词法分析器规范</a>
 */
public class BashLexer {

    // ──── 关键字集合 ────

    /** Shell 关键字 */
    public static final Set<String> SHELL_KEYWORDS = Set.of(
            "if", "then", "elif", "else", "fi",
            "while", "until", "for", "in", "do", "done",
            "case", "esac", "function", "select"
    );

    /** 声明关键字 */
    public static final Set<String> DECL_KEYWORDS = Set.of(
            "export", "declare", "typeset", "readonly", "local"
    );

    /**
     * 命令起始关键字 — 这些关键字后面跟的是新命令位置，
     * 影响 [ { [[ ! 等上下文敏感符号的识别。
     */
    private static final Set<String> CMD_START_KEYWORDS = Set.of(
            "if", "elif", "then", "else",
            "while", "until", "do",
            "fi", "done", "esac"
    );

    /** Lexer 级特殊变量 — 包含 @ 和 * (用于识别 $@ $* token 边界) */
    public static final Set<Character> SPECIAL_VARS = Set.of(
            '?', '$', '@', '*', '#', '-', '!', '_'
    );

    // ──── 源码与状态 ────

    private final String source;
    private final int length;

    /** Java 字符索引 (用于 source.charAt(i) 访问) */
    private int i;

    /** UTF-8 字节偏移 (用于 AST startByte/endByte) */
    private int b;

    /** 是否在命令起始位置 (影响 [[ [ { } ! 的识别) */
    private boolean atCmdStart = true;

    /** 上一次 nextToken 调用时，是否在该 Token 前跳过了空白 */
    private boolean hadWhitespaceBefore = true;

    /** 节点计数 (预算控制) */
    private int nodeCount;

    /** 节点预算上限 */
    private final int maxNodes;

    /**
     * 构造 Lexer。
     *
     * @param source   待分析的 Bash 源码
     * @param maxNodes 节点预算上限 (默认 50000)
     */
    public BashLexer(String source, int maxNodes) {
        this.source = source;
        this.length = source.length();
        this.i = 0;
        this.b = 0;
        this.nodeCount = 0;
        this.maxNodes = maxNodes;
    }

    public BashLexer(String source) {
        this(source, 50_000);
    }

    // ──── 状态保存/恢复 (回溯机制) ────

    /**
     * 保存当前 Lexer 状态 — 打包为 long (防溢出)。
     * <p>
     * 源码使用 (b << 16) | i 单个整数，但 Java 中用 long 更安全。
     */
    public long saveLex() {
        return ((long) b << 32) | (i & 0xFFFFFFFFL);
    }

    /** 恢复 Lexer 状态。 */
    public void restoreLex(long state) {
        this.b = (int) (state >>> 32);
        this.i = (int) (state & 0xFFFFFFFFL);
    }

    // ──── 核心方法 ────

    /**
     * 获取下一个 Token。
     *
     * @return 下一个 BashToken，到达末尾返回 EOF Token
     * @throws LexerBudgetExceededException 超出节点预算
     */
    public BashToken nextToken() {
        int beforeI = i;
        skipBlanks();
        hadWhitespaceBefore = (i > beforeI);

        if (i >= length) {
            return makeToken(BashTokenType.EOF, "", i, i, b, b);
        }

        nodeCount++;
        if (nodeCount > maxNodes) {
            throw new LexerBudgetExceededException("Node budget exceeded: " + maxNodes);
        }

        int startI = i;
        int startB = b;
        char ch = source.charAt(i);

        // ──── 换行 ────
        if (ch == '\n') {
            advance();
            atCmdStart = true;
            return makeToken(BashTokenType.NEWLINE, "\n", startI, i, startB, b);
        }

        // ──── 注释 ────
        if (ch == '#') {
            return scanComment(startI, startB);
        }

        // ──── 单引号 ────
        if (ch == '\'') {
            return scanSingleQuote(startI, startB);
        }

        // ──── 双引号 ────
        if (ch == '"') {
            return scanDoubleQuote(startI, startB);
        }

        // ──── ANSI-C 引号 $'...' ────
        if (ch == '$' && peek(1) == '\'') {
            return scanAnsiCQuote(startI, startB);
        }

        // ──── Dollar 展开 ────
        if (ch == '$') {
            return scanDollar(startI, startB);
        }

        // ──── 反引号 ────
        if (ch == '`') {
            advance();
            atCmdStart = false;
            return makeToken(BashTokenType.BACKTICK, "`", startI, i, startB, b);
        }

        // ──── 操作符 (最长匹配) ────
        BashToken opToken = tryScanOperator(startI, startB);
        if (opToken != null) {
            return opToken;
        }

        // ──── 普通单词 ────
        return scanWord(startI, startB);
    }

    /**
     * 查看当前位置后 offset 个字符，不移动指针。
     */
    public char peek(int offset) {
        int idx = i + offset;
        return idx < length ? source.charAt(idx) : '\0';
    }

    /** 当前字符 */
    public char current() {
        return i < length ? source.charAt(i) : '\0';
    }

    /** 是否已到末尾 */
    public boolean isAtEnd() {
        return i >= length;
    }

    /** 获取当前 Java 字符索引 */
    public int getIndex() {
        return i;
    }

    /** 获取当前 UTF-8 字节偏移 */
    public int getByteOffset() {
        return b;
    }

    /** 上一次 nextToken 返回的 Token 之前是否存在空白分隔 */
    public boolean hadWhitespaceBefore() {
        return hadWhitespaceBefore;
    }

    /** 获取源码 */
    public String getSource() {
        return source;
    }

    /** 获取子串 */
    public String substring(int start, int end) {
        return source.substring(start, end);
    }

    // ──── 前进方法 (UTF-8 双索引) ────

    /**
     * 前进一个字符，同时更新 Java 索引和 UTF-8 字节偏移。
     * <p>
     * advance() 规则 (对齐源码):
     * <ul>
     *   <li>charCode &lt; 0x80 → b += 1 (ASCII)</li>
     *   <li>charCode &lt; 0x800 → b += 2 (2字节 UTF-8)</li>
     *   <li>0xD800-0xDBFF → b += 4, i += 2 (代理对 = 4字节 UTF-8)</li>
     *   <li>其他 → b += 3 (3字节 UTF-8)</li>
     * </ul>
     */
    private void advance() {
        if (i >= length) return;
        char ch = source.charAt(i);
        if (ch < 0x80) {
            b += 1;
            i += 1;
        } else if (ch < 0x800) {
            b += 2;
            i += 1;
        } else if (Character.isHighSurrogate(ch)) {
            b += 4;
            i += 2; // 跳过代理对的两个 char
        } else {
            b += 3;
            i += 1;
        }
    }

    /** 前进 n 个字符 */
    private void advanceN(int n) {
        for (int k = 0; k < n; k++) {
            advance();
        }
    }

    // ──── 跳过空白 ────

    /**
     * 跳过空格、制表符、\r、行继续(\&lt;newline&gt;)。
     */
    private void skipBlanks() {
        while (i < length) {
            char ch = source.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\r') {
                advance();
            } else if (ch == '\\' && peek(1) == '\n') {
                // 行继续: \<newline> → 跳过两个字符
                advance(); // '\'
                advance(); // '\n'
            } else {
                break;
            }
        }
    }

    // ──── 注释扫描 ────

    private BashToken scanComment(int startI, int startB) {
        while (i < length && source.charAt(i) != '\n') {
            advance();
        }
        String text = source.substring(startI, i);
        atCmdStart = true;
        return makeToken(BashTokenType.COMMENT, text, startI, i, startB, b);
    }

    // ──── 单引号扫描 ────

    /**
     * 单引号 '...' — 内部无任何展开。
     */
    private BashToken scanSingleQuote(int startI, int startB) {
        advance(); // 跳过开引号 '
        while (i < length && source.charAt(i) != '\'') {
            advance();
        }
        if (i < length) {
            advance(); // 跳过闭引号 '
        }
        String text = source.substring(startI, i);
        atCmdStart = false;
        return makeToken(BashTokenType.SQUOTE, text, startI, i, startB, b);
    }

    // ──── 双引号扫描 ────

    /**
     * 双引号 "..." — 内部允许 $展开、命令替换、反引号。
     * <p>
     * 此处仅扫描到匹配的闭引号，不递归解析内部结构 (由 Parser 层处理)。
     */
    private BashToken scanDoubleQuote(int startI, int startB) {
        advance(); // 跳过开引号 "
        int depth = 0; // 嵌套 $() 深度
        while (i < length) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                advance(); // 跳过反斜杠
                if (i < length) advance(); // 跳过被转义的字符
                continue;
            }
            if (ch == '$' && peek(1) == '(') {
                depth++;
                advance();
                advance();
                continue;
            }
            if (ch == ')' && depth > 0) {
                depth--;
                advance();
                continue;
            }
            if (ch == '"' && depth == 0) {
                advance(); // 跳过闭引号 "
                break;
            }
            advance();
        }
        String text = source.substring(startI, i);
        atCmdStart = false;
        return makeToken(BashTokenType.DQUOTE, text, startI, i, startB, b);
    }

    // ──── ANSI-C 引号扫描 ────

    /**
     * ANSI-C 引号 $'...' — 支持转义序列 \n \t \\ \' \xHH &#92;uHHHH 等。
     */
    private BashToken scanAnsiCQuote(int startI, int startB) {
        advance(); // 跳过 $
        advance(); // 跳过 '
        while (i < length) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                advance(); // 跳过反斜杠
                if (i < length) advance(); // 跳过被转义的字符
                continue;
            }
            if (ch == '\'') {
                advance(); // 跳过闭引号
                break;
            }
            advance();
        }
        String text = source.substring(startI, i);
        atCmdStart = false;
        return makeToken(BashTokenType.ANSI_C, text, startI, i, startB, b);
    }

    // ──── Dollar 展开扫描 ────

    /**
     * $ 开头的展开:
     * <ul>
     *   <li>$(( → DOLLAR_DPAREN (算术展开)</li>
     *   <li>$( → DOLLAR_PAREN (命令替换)</li>
     *   <li>${ → DOLLAR_BRACE (参数展开)</li>
     *   <li>$' → ANSI_C (已在上层处理)</li>
     *   <li>$VAR / $? / $$ 等 → DOLLAR (简单变量)</li>
     * </ul>
     */
    private BashToken scanDollar(int startI, int startB) {
        advance(); // 跳过 $

        if (i >= length) {
            atCmdStart = false;
            return makeToken(BashTokenType.DOLLAR, "$", startI, i, startB, b);
        }

        char next = source.charAt(i);

        // $(( — 算术扩展 (完整内容，必须在 $( 之前检测！)
        if (next == '(' && peek(1) == '(') {
            return lexArithmeticExpansion(startI, startB);
        }

        // $( — 命令替换
        if (next == '(') {
            advance(); // (
            atCmdStart = true;
            return makeToken(BashTokenType.DOLLAR_PAREN, "$(", startI, i, startB, b);
        }

        // ${ — 参数扩展变体 (完整内容)
        if (next == '{') {
            return lexParameterExpansion(startI, startB);
        }

        // $SPECIAL_VAR — 特殊变量 ($? $$ $! $# $@ $* $- $_)
        if (SPECIAL_VARS.contains(next)) {
            advance();
            String text = source.substring(startI, i);
            atCmdStart = false;
            return makeToken(BashTokenType.DOLLAR, text, startI, i, startB, b);
        }

        // $NAME — 普通变量名
        if (isNameStart(next)) {
            while (i < length && isNameChar(source.charAt(i))) {
                advance();
            }
            String text = source.substring(startI, i);
            atCmdStart = false;
            return makeToken(BashTokenType.DOLLAR, text, startI, i, startB, b);
        }

        // 孤立 $ (不跟变量名)
        atCmdStart = false;
        return makeToken(BashTokenType.DOLLAR, "$", startI, i, startB, b);
    }

    // ──── 操作符扫描 (最长匹配优先) ────

    /**
     * 尝试匹配操作符 — 3字符 → 2字符 → 1字符 → 上下文敏感。
     *
     * @return 匹配的 Token，或 null 表示不是操作符
     */
    private BashToken tryScanOperator(int startI, int startB) {
        if (i >= length) return null;
        char c1 = source.charAt(i);
        char c2 = peek(1);
        char c3 = peek(2);

        // 3字符操作符
        String op3 = (c2 != '\0' && c3 != '\0') ? "" + c1 + c2 + c3 : "";
        if (!op3.isEmpty() && is3CharOp(op3)) {
            advanceN(3);
            updateCmdStartAfterOp(op3);
            return makeToken(BashTokenType.OP, op3, startI, i, startB, b);
        }

        // 2字符操作符
        String op2 = (c2 != '\0') ? "" + c1 + c2 : "";
        if (!op2.isEmpty() && is2CharOp(op2)) {
            // 上下文敏感: [[ 仅在命令位置
            if (op2.equals("[[") && !atCmdStart) {
                // 不作为操作符处理
            } else {
                advanceN(2);
                updateCmdStartAfterOp(op2);
                // <( 和 >( 是进程替换 — 使用完整内容词法分析
                if (op2.equals("<(") || op2.equals(">(")) {
                    // 回退 advanceN(2)，由 lexProcessSubstitution 统一处理
                    this.i = startI;
                    this.b = startB;
                    return lexProcessSubstitution(startI, startB);
                }
                return makeToken(BashTokenType.OP, op2, startI, i, startB, b);
            }
        }

        // 1字符操作符
        if (is1CharOp(c1)) {
            // 上下文敏感: [ { } ! 仅在命令位置
            if ((c1 == '[' || c1 == '{' || c1 == '}' || c1 == '!') && !atCmdStart) {
                return null; // 作为 WORD 处理
            }
            advance();
            String op1 = String.valueOf(c1);
            updateCmdStartAfterOp(op1);
            return makeToken(BashTokenType.OP, op1, startI, i, startB, b);
        }

        return null;
    }

    /** 3字符操作符集合 */
    private boolean is3CharOp(String op) {
        return switch (op) {
            case ";;&", "<<-", "<<<", ">&-", "<&-", "&>>", "$((" -> true;
            default -> false;
        };
    }

    /** 2字符操作符集合 */
    private boolean is2CharOp(String op) {
        return switch (op) {
            case "&&", "||", "|&", ";;", ";&",
                 ">>", ">&", ">|", "&>", "<<", "<&",
                 "<(", ">(", "((", "))",
                 "$(", "${", "[[" -> true;
            default -> false;
        };
    }

    /** 1字符操作符集合 */
    private boolean is1CharOp(char ch) {
        return switch (ch) {
            case '|', '&', ';', '>', '<', '(', ')', '[', '{', '}', '!' -> true;
            default -> false;
        };
    }

    /** 操作符后更新命令起始状态 */
    private void updateCmdStartAfterOp(String op) {
        atCmdStart = switch (op) {
            // 这些操作符后面开始新命令
            case "|", "&&", "||", ";", ";&", ";;&", "|&",
                 "(", ")", "))", "{", "!", "[[", ";;" -> true;
            // 这些操作符后面不是新命令
            default -> false;
        };
    }

    // ──── 普通单词扫描 ────

    /**
     * 扫描普通单词 (命令名、参数、文件路径等)。
     * <p>
     * 单词在遇到空白、操作符字符、引号时结束。
     * 反斜杠转义: \x 将 x 纳入当前单词。
     */
    private BashToken scanWord(int startI, int startB) {
        while (i < length) {
            char ch = source.charAt(i);

            // 反斜杠转义
            if (ch == '\\') {
                advance(); // 跳过 '\'
                if (i < length && source.charAt(i) != '\n') {
                    advance(); // 跳过被转义的字符
                }
                continue;
            }

            // 遇到空白/换行 → 单词结束
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                break;
            }

            // 遇到引号/Dollar/反引号 → 单词结束 (拼接由 Parser 层处理)
            if (ch == '\'' || ch == '"' || ch == '$' || ch == '`') {
                break;
            }

            // 遇到操作符字符 → 单词结束
            if (isOperatorChar(ch)) {
                break;
            }

            advance();
        }

        String text = source.substring(startI, i);

        // 判断是否为纯数字 (文件描述符)
        BashTokenType type = isAllDigits(text) ? BashTokenType.NUMBER : BashTokenType.WORD;

        // Shell 命令起始关键字后面跟的是新命令位置
        atCmdStart = CMD_START_KEYWORDS.contains(text);
        return makeToken(type, text, startI, i, startB, b);
    }

    // ──── 辅助方法 ────

    private boolean isOperatorChar(char ch) {
        return switch (ch) {
            // 注意: [, ], {, } 已移除 — 它们在非命令位置时应作为 word 的一部分
            // (如 glob: file[0-9].log, 大括号展开: {a,b,c})
            // 在命令位置时由 tryScanOperator 的 is1CharOp + atCmdStart 检查处理
            case '|', '&', ';', '>', '<', '(', ')' -> true;
            default -> false;
        };
    }

    private boolean isNameStart(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private boolean isNameChar(char ch) {
        return isNameStart(ch) || (ch >= '0' && ch <= '9');
    }

    private boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) < '0' || s.charAt(k) > '9') return false;
        }
        return true;
    }

    private BashToken makeToken(BashTokenType type, String text,
                                int startI, int endI, int startB, int endB) {
        return new BashToken(type, text, startB, endB, startI, endI);
    }

    /**
     * 计算字符串的 UTF-8 字节长度。
     */
    public static int utf8ByteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ──── 扩展语法词法分析方法 ────

    /**
     * 解析算术扩展 $((...))。
     * <p>
     * 匹配模式: $(( expression ))
     * 支持嵌套括号: $((1 + (2 * 3)))
     * 使用括号深度计数匹配到对应的 ))。
     *
     * @param startI 起始 Java 字符索引 (指向 $)
     * @param startB 起始 UTF-8 字节偏移
     * @return ARITHMETIC_EXPANSION Token，文本为完整的 $((expression))
     */
    private BashToken lexArithmeticExpansion(int startI, int startB) {
        advance(); // 跳过 (
        advance(); // 跳过 (
        // 现在 i 在 $(( 之后

        int depth = 1; // )) 深度计数

        while (i < length && depth > 0) {
            char ch = source.charAt(i);
            if (ch == ')' && peek(1) == ')') {
                depth--;
                if (depth == 0) {
                    advance(); // 第一个 )
                    advance(); // 第二个 )
                    break;
                }
                advance();
                advance();
            } else if (ch == '(' && peek(1) == '(') {
                depth++;
                advance();
                advance();
            } else {
                advance();
            }
        }

        String text = source.substring(startI, i);
        atCmdStart = false;
        return makeToken(BashTokenType.ARITHMETIC_EXPANSION, text, startI, i, startB, b);
    }

    /**
     * 解析参数扩展变体 ${...}。
     * <p>
     * 在现有 ${var} 解析的基础上扩展，支持 12 种变体：
     * <ul>
     *   <li>${var:-default}   使用默认值</li>
     *   <li>${var:=default}   赋值默认值</li>
     *   <li>${var:+alternate} 替代值</li>
     *   <li>${var:?error}     错误消息</li>
     *   <li>${var#pattern}    最短前缀删除</li>
     *   <li>${var##pattern}   最长前缀删除</li>
     *   <li>${var%pattern}    最短后缀删除</li>
     *   <li>${var%%pattern}   最长后缀删除</li>
     *   <li>${var/pat/repl}   首次替换</li>
     *   <li>${var//pat/repl}  全局替换</li>
     *   <li>${#var}           字符串长度</li>
     *   <li>${var:off:len}    子串提取</li>
     * </ul>
     *
     * @param startI 起始 Java 字符索引 (指向 $)
     * @param startB 起始 UTF-8 字节偏移
     * @return PARAMETER_EXPANSION Token，文本为完整的 ${...}
     */
    private BashToken lexParameterExpansion(int startI, int startB) {
        advance(); // 跳过 {
        // 现在 i 在 ${ 之后

        int braceDepth = 1;

        while (i < length && braceDepth > 0) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                advance(); // 跳过反斜杠
                if (i < length) advance(); // 跳过被转义字符
                continue;
            }
            if (ch == '{') {
                braceDepth++;
            } else if (ch == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    advance(); // 跳过闭合 }
                    break;
                }
            }
            advance();
        }

        String text = source.substring(startI, i);
        atCmdStart = false;
        return makeToken(BashTokenType.PARAMETER_EXPANSION, text, startI, i, startB, b);
    }

    /**
     * 解析进程替换 <(...) 和 >(...)。
     * <p>
     * 匹配 &lt;(command) 返回 PROCESS_SUBSTITUTION_IN，
     * 匹配 &gt;(command) 返回 PROCESS_SUBSTITUTION_OUT。
     * 使用括号深度计数匹配到对应的 )。
     *
     * @param startI 起始 Java 字符索引 (指向 &lt; 或 &gt;)
     * @param startB 起始 UTF-8 字节偏移
     * @return PROCESS_SUBSTITUTION_IN 或 PROCESS_SUBSTITUTION_OUT Token
     */
    private BashToken lexProcessSubstitution(int startI, int startB) {
        char prefix = source.charAt(i); // < 或 >
        advance(); // 跳过 < 或 >
        advance(); // 跳过 (

        int parenDepth = 1;

        while (i < length && parenDepth > 0) {
            char ch = source.charAt(i);
            if (ch == '(') {
                parenDepth++;
            } else if (ch == ')') {
                parenDepth--;
                if (parenDepth == 0) {
                    advance(); // 跳过闭合 )
                    break;
                }
            }
            advance();
        }

        String text = source.substring(startI, i);
        BashTokenType type = (prefix == '<')
                ? BashTokenType.PROCESS_SUBSTITUTION_IN
                : BashTokenType.PROCESS_SUBSTITUTION_OUT;
        atCmdStart = false;
        return makeToken(type, text, startI, i, startB, b);
    }

    /**
     * 参数扩展变体类型 — 用于识别 ${...} 内的操作符类型。
     */
    public enum ParameterExpansionType {
        /** 简单变量引用 ${var} */
        SIMPLE,
        /** 字符串长度 ${#var} */
        LENGTH,
        /** 默认值 ${var:-default} */
        DEFAULT_VALUE,
        /** 赋值默认值 ${var:=default} */
        ASSIGN_DEFAULT,
        /** 替代值 ${var:+alternate} */
        ALTERNATE,
        /** 错误消息 ${var:?error} */
        ERROR,
        /** 最短前缀删除 ${var#pattern} */
        PREFIX_SHORT,
        /** 最长前缀删除 ${var##pattern} */
        PREFIX_LONG,
        /** 最短后缀删除 ${var%pattern} */
        SUFFIX_SHORT,
        /** 最长后缀删除 ${var%%pattern} */
        SUFFIX_LONG,
        /** 首次替换 ${var/pat/repl} */
        REPLACE_FIRST,
        /** 全局替换 ${var//pat/repl} */
        REPLACE_ALL,
        /** 子串提取 ${var:off:len} */
        SUBSTRING
    }

    /**
     * 分类参数扩展类型。
     *
     * @param content ${...} 内部内容 (不含 ${ 和 })
     * @return 对应的 ParameterExpansionType
     */
    public static ParameterExpansionType classifyParameterExpansion(String content) {
        if (content.startsWith("#")) return ParameterExpansionType.LENGTH;
        if (content.contains(":-")) return ParameterExpansionType.DEFAULT_VALUE;
        if (content.contains(":=")) return ParameterExpansionType.ASSIGN_DEFAULT;
        if (content.contains(":+")) return ParameterExpansionType.ALTERNATE;
        if (content.contains(":?")) return ParameterExpansionType.ERROR;
        if (content.contains("##")) return ParameterExpansionType.PREFIX_LONG;
        if (content.contains("#")) return ParameterExpansionType.PREFIX_SHORT;
        if (content.contains("%%")) return ParameterExpansionType.SUFFIX_LONG;
        if (content.contains("%")) return ParameterExpansionType.SUFFIX_SHORT;
        if (content.contains("//")) return ParameterExpansionType.REPLACE_ALL;
        if (content.contains("/")) return ParameterExpansionType.REPLACE_FIRST;
        return ParameterExpansionType.SIMPLE;
    }

    // ──── 异常 ────

    /** Lexer 节点预算超出异常 */
    public static class LexerBudgetExceededException extends RuntimeException {
        public LexerBudgetExceededException(String message) {
            super(message);
        }
    }
}

package com.aicodeassistant.tool.bash.ast;

/**
 * Bash 词法分析器 Token 类型枚举。
 * <p>
 * 对应源码 bashParser.ts Lexer 的 Token 类型。
 * Java 实现: TS const enum → Java enum BashTokenType。
 *
 * @see <a href="SPEC §3.2.3c.1">词法分析器 Token 类型</a>
 */
public enum BashTokenType {

    // ──── 基础 Token ────

    /** 普通单词 (命令名、参数、文件路径等) */
    WORD,

    /** 数字 (文件描述符号等) */
    NUMBER,

    /** 操作符 (|, &, ;, >, <, 及其组合) */
    OP,

    /** 换行符 */
    NEWLINE,

    /** 注释 (# 到行尾) */
    COMMENT,

    // ──── 引号 Token ────

    /** 双引号字符串 "..." */
    DQUOTE,

    /** 单引号字符串 '...' */
    SQUOTE,

    /** ANSI-C 引号字符串 $'...' */
    ANSI_C,

    // ──── Dollar 展开 Token ────

    /** $ 简单变量引用 */
    DOLLAR,

    /** $( 命令替换开始 */
    DOLLAR_PAREN,

    /** ${ 参数展开开始 */
    DOLLAR_BRACE,

    /** $(( 算术展开开始 */
    DOLLAR_DPAREN,

    /** ` 反引号命令替换 */
    BACKTICK,

    // ──── 进程替换 Token ────

    /** <( 进程替换 (输入) */
    LT_PAREN,

    /** >( 进程替换 (输出) */
    GT_PAREN,

    // ──── 扩展语法 Token ────

    /** $((expression)) 算术扩展 (完整内容) */
    ARITHMETIC_EXPANSION,

    /** ${var#pattern} 等参数扩展变体 (完整内容) */
    PARAMETER_EXPANSION,

    /** <(command) 输入进程替换 (完整内容) */
    PROCESS_SUBSTITUTION_IN,

    /** >(command) 输出进程替换 (完整内容) */
    PROCESS_SUBSTITUTION_OUT,

    // ──── 结束标记 ────

    /** 输入结束 */
    EOF
}

package com.aicodeassistant.tool.bash.ast;

import java.util.List;

/**
 * Bash AST 节点 sealed interface 层级 — 唯一权威定义。
 * <p>
 * 与 SPEC §3.2.3c.1 EBNF 语法定义一一对应。
 * <p>
 * 设计原则:
 * <ol>
 *   <li>sealed interface 确保 switch 穷举性 (Java 21 pattern matching)</li>
 *   <li>所有节点携带源码位置 (startByte, endByte) 用于错误报告</li>
 *   <li>叶子节点 (SimpleCommandNode) 提供 argv[] 用于权限检查</li>
 *   <li>未识别/过于复杂的结构用 TooComplexNode 兜底 → 触发权限询问</li>
 * </ol>
 *
 * @see <a href="SPEC §3.2.3c.1">BashParser AST 定义</a>
 */
public sealed interface BashAstNode {

    /** UTF-8 字节起始偏移 */
    int startByte();

    /** UTF-8 字节结束偏移 (不含) */
    int endByte();

    /** 原始文本片段 */
    String rawText();

    // ──── 顶层结构 ────

    /** 程序根节点 — 对应 EBNF: program = statement_list */
    record ProgramNode(
            List<StatementNode> statements,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** 语句节点 — 对应 EBNF: statement = and_or_list [ '&' ] */
    record StatementNode(
            BashAstNode body,
            boolean isBackground,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 组合结构 ────

    /** 管道节点 — 对应 EBNF: pipeline = ['!'] command { ('|' | '|&') command } */
    record PipelineNode(
            List<BashAstNode> commands,
            boolean negated,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** 逻辑与/或节点 — 对应 EBNF: and_or_list = pipeline { ('&&' | '||') pipeline } */
    record AndOrNode(
            BashAstNode left,
            String operator,  // "&&" | "||"
            BashAstNode right,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** 子 shell — 对应 EBNF: subshell = '(' statement_list ')' */
    record SubshellNode(
            ProgramNode body,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** 大括号分组 — 对应 EBNF: brace_group = '{' statement_list '}' */
    record BraceGroupNode(
            ProgramNode body,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 简单命令 (权限检查的基础单元) ────

    /** 简单命令节点 — 对应 EBNF: simple_command */
    record SimpleCommandNode(
            List<String> argv,                 // argv[0]=命令名, 后续为参数 (引号已解析)
            List<VarAssignment> envVars,       // 前置 VAR=val 赋值
            List<RedirectNode> redirects,      // 重定向列表
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** 变量赋值 — VAR=value */
    record VarAssignment(String name, String value) {}

    /** 重定向 — 对应 EBNF: redirect */
    record RedirectNode(String operator, String target, int fd) {}

    // ──── 控制流 ────

    /** if 语句 — 对应 EBNF: if_clause */
    record IfNode(
            ProgramNode condition,
            ProgramNode thenBody,
            ProgramNode elseBody,  // nullable: 包括 elif 链和 else
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** for 循环 — 对应 EBNF: for_clause */
    record ForNode(
            String varName,
            List<String> words,
            ProgramNode body,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** while/until 循环 — 对应 EBNF: while_clause */
    record WhileNode(
            ProgramNode condition,
            ProgramNode body,
            boolean isUntil,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** case 语句 — 对应 EBNF: case_clause */
    record CaseNode(
            String word,
            List<CaseItem> items,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    /** case 分支项 — 对应 EBNF: case_item */
    record CaseItem(List<String> patterns, ProgramNode body) {}

    /** 函数定义 — 对应 EBNF: function_def = NAME '()' compound_command */
    record FunctionDefNode(
            String name,
            BashAstNode body,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 声明命令 ────

    /** 声明命令 — export/declare/local/readonly/typeset */
    record DeclarationCommandNode(
            String keyword,                    // "export" | "declare" | "local" | "readonly" | "typeset"
            List<String> argv,                 // 包含标志和参数
            List<VarAssignment> assignments,   // 变量赋值
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 重定向包装 ────

    /** 重定向语句 — 包裹内部命令 + redirect 列表 */
    record RedirectedStatementNode(
            BashAstNode body,
            List<RedirectNode> redirects,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 否定命令 ────

    /** 否定命令 — ! command */
    record NegatedCommandNode(
            BashAstNode body,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 条件测试 ────

    /** 条件测试 — [[ expr ]] 或 [ expr ] */
    record TestCommandNode(
            List<String> argv,                 // 测试表达式的 argv
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 变量赋值 (独立) ────

    /** 独立变量赋值 — VAR=value (无命令) */
    record VariableAssignmentNode(
            String name,
            String value,
            boolean isAppend,                  // += 运算符
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}

    // ──── 兜底: 过于复杂的结构 → 触发权限询问 ────

    /**
     * 兜底节点 — 解析器识别但安全分析无法处理的结构。
     * <p>
     * 包括: arithmetic_expansion, process_substitution, brace_expression,
     * translated_string, c_style_for_statement, ternary_expression 等。
     */
    record TooComplexNode(
            String reason,
            int startByte, int endByte, String rawText
    ) implements BashAstNode {}
}

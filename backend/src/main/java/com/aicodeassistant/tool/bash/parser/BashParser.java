package com.aicodeassistant.tool.bash.parser;

import com.aicodeassistant.tool.bash.ast.BashAstNode;

/**
 * Bash 解析器入口 — 带超时和节点预算控制。
 * <p>
 * 手写递归下降实现 (非 ANTLR)，对齐源码 bashParser.ts。
 * 安全限制: 50ms 解析超时 + 50000 节点预算。
 *
 * @see <a href="SPEC §3.2.3c">Bash 解析器规范</a>
 */
public class BashParser {

    /** 解析超时 (毫秒) — 防止对抗性输入 DoS */
    private static final int PARSE_TIMEOUT_MS = 50;

    /** AST 节点预算上限 — 防止内存溢出 */
    private static final int MAX_NODES = 50_000;

    /** 命令最大长度 (字符) — 超过直接返回 null */
    private static final int MAX_COMMAND_LENGTH = 10_000;

    /**
     * 解析 Bash 命令字符串，返回 AST 根节点。
     *
     * @param source Bash 命令字符串
     * @return ProgramNode 根节点；超时/预算耗尽/命令过长返回 null
     */
    public BashAstNode.ProgramNode parse(String source) {
        if (source == null || source.isEmpty()) {
            return new BashAstNode.ProgramNode(
                    java.util.List.of(), 0, 0, "");
        }

        if (source.length() > MAX_COMMAND_LENGTH) {
            return null; // 命令过长 → parse-unavailable
        }

        long deadline = System.nanoTime() + PARSE_TIMEOUT_MS * 1_000_000L;

        try {
            var lexer = new BashLexer(source, MAX_NODES);
            var core = new BashParserCore(lexer, deadline, MAX_NODES);
            return core.parseProgram();
        } catch (BashLexer.LexerBudgetExceededException
                 | BashParserCore.ParserTimeoutException
                 | BashParserCore.ParserBudgetExceededException e) {
            return null; // 超时/预算耗尽 → PARSE_ABORTED
        }
    }
}

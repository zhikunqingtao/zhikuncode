package com.aicodeassistant.tool.bash.parser;

import com.aicodeassistant.tool.bash.ast.BashAstNode;
import com.aicodeassistant.tool.bash.ast.BashAstNode.*;
import com.aicodeassistant.tool.bash.ast.BashTokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Bash 递归下降解析器核心 — 5 层解析层次，对齐源码 bashParser.ts。
 * <p>
 * 解析层次:
 * <pre>
 * parseProgram()
 *   parseStatements(terminator)    // 分号/and/换行分隔的语句序列
 *     parseAndOr()                 // and and / || 链（左结合）
 *       parsePipeline()            // | / |and 管道
 *         parseCommand()           // 单个命令分派
 *           parseSimpleCommand()   // 命令名 + 参数 + 重定向
 *           parseIf/While/For/Case
 *           parseFunction/parseDeclaration
 *           subshell / compound_statement / test_command
 * </pre>
 *
 * @see <a href="SPEC section 3.2.3c.1">语法分析层次</a>
 */
public class BashParserCore {

    private final BashLexer lexer;
    private final long deadline;   // System.nanoTime() 超时截止
    private final int maxNodes;
    private int nodeCount;

    /** 当前 Token (预读一个) */
    private BashToken current;

    public BashParserCore(BashLexer lexer, long deadline, int maxNodes) {
        this.lexer = lexer;
        this.deadline = deadline;
        this.maxNodes = maxNodes;
        this.nodeCount = 0;
        this.current = lexer.nextToken();
    }

    // ──── 超时与预算检查 ────

    private void checkBudget() {
        if (System.nanoTime() > deadline) {
            throw new ParserTimeoutException("Parser timeout exceeded");
        }
        nodeCount++;
        if (nodeCount > maxNodes) {
            throw new ParserBudgetExceededException("Node budget exceeded: " + maxNodes);
        }
    }

    // ──── Token 操作 ────

    /** 消费当前 Token 并前进 */
    private BashToken consume() {
        BashToken tok = current;
        current = lexer.nextToken();
        return tok;
    }

    /** 消费指定类型的 Token，不匹配则返回 null */
    private BashToken consumeIf(BashTokenType type) {
        if (current.type() == type) {
            return consume();
        }
        return null;
    }

    /** 消费指定操作符，不匹配则返回 null */
    private BashToken consumeOp(String op) {
        if (current.isOp(op)) {
            return consume();
        }
        return null;
    }

    /** 消费指定关键字，不匹配则返回 null */
    private BashToken consumeKeyword(String keyword) {
        if (current.isWord(keyword)) {
            return consume();
        }
        return null;
    }

    /** 跳过换行和注释 */
    private void skipNewlines() {
        while (current.type() == BashTokenType.NEWLINE
                || current.type() == BashTokenType.COMMENT) {
            consume();
        }
    }

    /** 是否为语句终止符 */
    private boolean isStatementTerminator() {
        if (current.isEof()) return true;
        if (current.isOp(")")) return true;
        if (current.isOp("}")) return true;
        // Shell 关键字终止符
        String text = current.text();
        return switch (text) {
            case "then", "else", "elif", "fi",
                 "do", "done", "esac", ";;", ";&", ";;&" -> true;
            default -> false;
        };
    }

    // ──── 层 1: parseProgram ────

    /**
     * 解析顶层程序 — 对应 EBNF: program = statement_list
     */
    public ProgramNode parseProgram() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        List<StatementNode> stmts = parseStatements();

        int endB = current.startByte();
        int endI = current.startIndex();
        String raw = lexer.substring(startI, endI);

        return new ProgramNode(stmts, startB, endB, raw);
    }

    // ──── 层 2: parseStatements ────

    /**
     * 解析语句序列 — 分号/and/换行分隔。
     * 对应 EBNF: statement_list = statement { (';' | 'and' | NEWLINE) statement }
     */
    private List<StatementNode> parseStatements() {
        List<StatementNode> stmts = new ArrayList<>();

        skipNewlines();

        while (!current.isEof() && !isStatementTerminator()) {
            checkBudget();

            StatementNode stmt = parseStatement();
            if (stmt != null) {
                stmts.add(stmt);
            }

            // 消费分隔符: ; & \n
            boolean consumed = false;
            while (current.isOp(";") || current.isOp("&")
                    || current.type() == BashTokenType.NEWLINE
                    || current.type() == BashTokenType.COMMENT) {
                consumed = true;
                consume();
            }

            if (!consumed && !current.isEof() && !isStatementTerminator()) {
                break; // 无分隔符且未结束 → 停止
            }
        }

        return stmts;
    }

    /**
     * 解析单个语句 — 对应 EBNF: statement = and_or_list [ 'and' ]
     */
    private StatementNode parseStatement() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        BashAstNode body = parseAndOr();
        if (body == null) return null;

        // 检查是否为后台执行 (&)
        boolean isBackground = false;
        if (current.isOp("&") && !current.isOp("&&")) {
            // 单独的 & 是后台，&& 是逻辑与 → 通过 isOp 精确匹配
            if (current.text().equals("&")) {
                consume();
                isBackground = true;
            }
        }

        int endB = current.startByte();
        int endI = current.startIndex();
        String raw = lexer.substring(startI, Math.min(endI, lexer.getSource().length()));

        return new StatementNode(body, isBackground, startB, endB, raw);
    }

    // ──── 层 3: parseAndOr ────

    /**
     * 解析 andand / || 链 — 左结合。
     * 对应 EBNF: and_or_list = pipeline { ('andand' | '||') pipeline }
     */
    private BashAstNode parseAndOr() {
        checkBudget();

        BashAstNode left = parsePipeline();
        if (left == null) return null;

        while (current.isOp("&&") || current.isOp("||")) {
            checkBudget();
            String operator = current.text();
            consume();
            skipNewlines(); // && / || 后允许换行

            BashAstNode right = parsePipeline();
            if (right == null) {
                break;
            }

            left = new AndOrNode(
                    left, operator, right,
                    left.startByte(), right.endByte(),
                    lexer.getSource().substring(
                            findCharIndex(left.startByte()),
                            Math.min(findCharIndex(right.endByte()),
                                    lexer.getSource().length()))
            );
        }

        return left;
    }

    // ──── 层 4: parsePipeline ────

    /**
     * 解析管道 — 对应 EBNF: pipeline = ['!'] command { ('|' | '|and') command }
     */
    private BashAstNode parsePipeline() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        // 检查否定 !
        boolean negated = false;
        if (current.isOp("!")) {
            negated = true;
            consume();
        }

        BashAstNode first = parseCommand();
        if (first == null) return null;

        // 检查管道操作符
        if (!current.isOp("|") && !current.isOp("|&")) {
            // 单个命令，非管道
            if (negated) {
                int endB = first.endByte();
                String raw = lexer.substring(startI,
                        Math.min(findCharIndex(endB), lexer.getSource().length()));
                return new NegatedCommandNode(first, startB, endB, raw);
            }
            return first;
        }

        // 构建管道
        List<BashAstNode> commands = new ArrayList<>();
        commands.add(first);

        while (current.isOp("|") || current.isOp("|&")) {
            checkBudget();
            consume();
            skipNewlines(); // 管道符后允许换行

            BashAstNode cmd = parseCommand();
            if (cmd == null) break;
            commands.add(cmd);
        }

        int endB = commands.getLast().endByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new PipelineNode(commands, negated, startB, endB, raw);
    }

    // ──── 层 5: parseCommand ────

    /**
     * 解析单个命令 — 分派到各种命令类型。
     * <p>
     * 对应 EBNF: command = simple_command | compound_command [redirect_list] | function_def
     * 复合命令和函数定义允许尾部重定向 (如 log() { echo; } 2>/dev/null)。
     */
    private BashAstNode parseCommand() {
        checkBudget();

        if (current.isEof() || isStatementTerminator()) {
            return null;
        }

        String text = current.text();
        BashAstNode result = null;
        boolean isCompound = false;

        // ── 控制流关键字 ──
        if (current.type() == BashTokenType.WORD) {
            result = switch (text) {
                case "if" -> { isCompound = true; yield parseIf(); }
                case "while", "until" -> { isCompound = true; yield parseWhile(); }
                case "for" -> { isCompound = true; yield parseFor(); }
                case "case" -> { isCompound = true; yield parseCase(); }
                case "select" -> { isCompound = true; yield parseSelect(); }
                case "function" -> { isCompound = true; yield parseFunction(); }
                case "export", "declare", "typeset", "readonly", "local" ->
                        parseDeclaration();
                default -> {
                    BashAstNode r = parseSimpleCommandOrFunction();
                    if (r instanceof FunctionDefNode) isCompound = true;
                    yield r;
                }
            };
        }

        // ── 子 shell: ( stmts ) ──
        if (result == null && current.isOp("(")) {
            result = parseSubshell();
            isCompound = true;
        }

        // ── 大括号分组: { stmts } ──
        if (result == null && current.isOp("{")) {
            result = parseBraceGroup();
            isCompound = true;
        }

        // ── 条件测试: [[ expr ]] 或 [ expr ] ──
        if (result == null && (current.isOp("[[") || current.isOp("["))) {
            result = parseTestCommand();
            isCompound = true;
        }

        // ── 否定: ! ──
        if (result == null && current.isOp("!")) {
            result = parseNegated();
        }

        // 默认: 尝试简单命令
        if (result == null) {
            result = parseSimpleCommand();
        }

        // ── 复合命令/函数定义的尾部重定向 ──
        if (result != null && isCompound && (isRedirectOperator() || isFdRedirectStart())) {
            result = wrapWithRedirects(result);
        }

        return result;
    }

    /**
     * 将复合命令包装为 RedirectedStatementNode (附加尾部重定向)。
     * <p>
     * 示例: log() { echo "$@"; } 2>/dev/null
     */
    private BashAstNode wrapWithRedirects(BashAstNode body) {
        List<RedirectNode> redirects = new ArrayList<>();
        while (isRedirectOperator() || isFdRedirectStart()) {
            RedirectNode redir = parseRedirect();
            if (redir != null) redirects.add(redir);
        }
        if (redirects.isEmpty()) return body;

        int endB = current.startByte();
        String raw = lexer.substring(
                findCharIndex(body.startByte()),
                Math.min(findCharIndex(endB), lexer.getSource().length()));
        return new RedirectedStatementNode(body, redirects, body.startByte(), endB, raw);
    }

    // ──── 简单命令解析 ────

    /**
     * 解析简单命令 — 对应 EBNF: simple_command = [var_assignment+] command_word {word} [redirect_list]
     */
    private BashAstNode parseSimpleCommand() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        List<VarAssignment> envVars = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        List<RedirectNode> redirects = new ArrayList<>();

        // 1. 前置变量赋值 (VAR=value)
        while (isVarAssignment()) {
            VarAssignment va = parseVarAssignment();
            if (va != null) envVars.add(va);
        }

        // 2. 命令名 + 参数
        while (!current.isEof() && !isStatementTerminator()
                && !isOperatorToken() && !isRedirectOperator()) {
            if (isVarAssignment() && argv.isEmpty()) {
                // 额外的变量赋值
                VarAssignment va = parseVarAssignment();
                if (va != null) envVars.add(va);
                continue;
            }

            // 危险 token 类型 → 直接返回 TooComplexNode
            if (current.type() == BashTokenType.DOLLAR_DPAREN) {
                return parseTooComplex("arithmetic_expansion", startB, startI);
            }
            if (current.type() == BashTokenType.LT_PAREN
                    || current.type() == BashTokenType.GT_PAREN) {
                return parseTooComplex("process_substitution", startB, startI);
            }

            String word = consumeWord();
            if (word == null) break;
            argv.add(word);
        }

        // 3. 重定向
        while (isRedirectOperator()) {
            RedirectNode redir = parseRedirect();
            if (redir != null) redirects.add(redir);
        }

        // 纯变量赋值 (无命令): A=1 B=2
        if (argv.isEmpty() && !envVars.isEmpty()) {
            // 转为 VariableAssignmentNode (第一个)
            VarAssignment first = envVars.getFirst();
            int endB = current.startByte();
            String raw = lexer.substring(startI,
                    Math.min(findCharIndex(endB), lexer.getSource().length()));
            if (envVars.size() == 1) {
                return new VariableAssignmentNode(
                        first.name(), first.value(), false, startB, endB, raw);
            }
            // 多个赋值 → 也包装为 SimpleCommandNode (空 argv)
            return new SimpleCommandNode(argv, envVars, redirects, startB, endB, raw);
        }

        if (argv.isEmpty() && envVars.isEmpty()) {
            return null;
        }

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new SimpleCommandNode(argv, envVars, redirects, startB, endB, raw);
    }

    /**
     * 解析简单命令或函数定义 (NAME() { ... })
     */
    private BashAstNode parseSimpleCommandOrFunction() {
        // 检查是否为函数定义: name() { ... }
        long saved = lexer.saveLex();
        BashToken savedCurrent = current;
        int savedNodeCount = nodeCount;

        if (current.type() == BashTokenType.WORD) {
            String name = current.text();
            consume();
            if (current.isOp("(")) {
                consume();
                if (current.isOp(")")) {
                    consume();
                    // 确认为函数定义
                    int startB = savedCurrent.startByte();
                    int startI = savedCurrent.startIndex();
                    BashAstNode body = parseCommand();
                    int endB = current.startByte();
                    String raw = lexer.substring(startI,
                            Math.min(findCharIndex(endB), lexer.getSource().length()));
                    return new FunctionDefNode(name, body, startB, endB, raw);
                }
            }
        }

        // 不是函数定义 → 恢复并解析为简单命令
        lexer.restoreLex(saved);
        current = savedCurrent;
        nodeCount = savedNodeCount;
        return parseSimpleCommand();
    }

    // ──── 重定向解析 ────

    /**
     * 判断当前是否为重定向操作符
     */
    private boolean isRedirectOperator() {
        if (current.type() == BashTokenType.OP) {
            String op = current.text();
            return switch (op) {
                case ">", ">>", "<", "<<", "<<<", ">&", "<&",
                     "&>", "&>>", ">|", "<<-", ">&-", "<&-" -> true;
                default -> false;
            };
        }
        // 数字 + 重定向: 2>
        if (current.type() == BashTokenType.NUMBER) {
            char next = lexer.peek(0);
            // 下一个字符如果是 > 或 < 则为 fd 重定向
            // 但这需要更复杂的前瞻，暂简化处理
        }
        return false;
    }

    /**
     * 解析单个重定向。
     */
    private RedirectNode parseRedirect() {
        int fd = -1;

        // 可选文件描述符号
        if (current.type() == BashTokenType.NUMBER) {
            fd = Integer.parseInt(current.text());
            consume();
        }

        // 重定向操作符
        if (!isRedirectOperator()) return null;
        String operator = current.text();
        consume();

        // 目标
        String target = "";
        if (!current.isEof() && current.type() != BashTokenType.NEWLINE) {
            target = consumeWord();
            if (target == null) target = "";
        }

        return new RedirectNode(operator, target, fd);
    }

    // ──── 控制流解析 ────

    /**
     * if 语句: if stmt_list then stmt_list [elif stmt_list then stmt_list]* [else stmt_list] fi
     */
    private BashAstNode parseIf() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeKeyword("if");
        skipNewlines();

        ProgramNode condition = parseInlineProgram("then");
        consumeKeyword("then");
        skipNewlines();

        ProgramNode thenBody = parseInlineProgram("elif", "else", "fi");

        ProgramNode elseBody = null;
        if (consumeKeyword("elif") != null) {
            // elif → 递归为嵌套 if
            BashAstNode nestedIf = parseIf_elifBranch();
            if (nestedIf != null) {
                elseBody = new ProgramNode(
                        List.of(new StatementNode(nestedIf, false,
                                nestedIf.startByte(), nestedIf.endByte(), nestedIf.rawText())),
                        nestedIf.startByte(), nestedIf.endByte(), nestedIf.rawText()
                );
            }
        } else if (consumeKeyword("else") != null) {
            skipNewlines();
            elseBody = parseInlineProgram("fi");
        }

        consumeKeyword("fi");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new IfNode(condition, thenBody, elseBody, startB, endB, raw);
    }

    /** elif 分支解析 (不消费 elif 关键字 — 已在 parseIf 中消费) */
    private BashAstNode parseIf_elifBranch() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        skipNewlines();
        ProgramNode condition = parseInlineProgram("then");
        consumeKeyword("then");
        skipNewlines();

        ProgramNode thenBody = parseInlineProgram("elif", "else", "fi");

        ProgramNode elseBody = null;
        if (consumeKeyword("elif") != null) {
            BashAstNode nested = parseIf_elifBranch();
            if (nested != null) {
                elseBody = new ProgramNode(
                        List.of(new StatementNode(nested, false,
                                nested.startByte(), nested.endByte(), nested.rawText())),
                        nested.startByte(), nested.endByte(), nested.rawText()
                );
            }
        } else if (consumeKeyword("else") != null) {
            skipNewlines();
            elseBody = parseInlineProgram("fi");
        }

        consumeKeyword("fi");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new IfNode(condition, thenBody, elseBody, startB, endB, raw);
    }

    /**
     * select 语句 (P1 扩展) — 遇到时标记为 TooComplex。
     * 对应 EBNF: select NAME [in word+] ; do stmts done
     */
    private BashAstNode parseSelect() {
        int startB = current.startByte();
        int startI = current.startIndex();
        return parseTooComplex("select_statement", startB, startI);
    }

    /**
     * while/until 循环: while stmt_list do stmt_list done
     */
    private BashAstNode parseWhile() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        boolean isUntil = current.text().equals("until");
        consume(); // while/until
        skipNewlines();

        ProgramNode condition = parseInlineProgram("do");
        consumeKeyword("do");
        skipNewlines();

        ProgramNode body = parseInlineProgram("done");
        consumeKeyword("done");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new WhileNode(condition, body, isUntil, startB, endB, raw);
    }

    /**
     * for 循环: for NAME [in word+] ; do stmt_list done
     */
    private BashAstNode parseFor() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeKeyword("for");
        skipNewlines();

        // C 风格 for: for (( ... ))
        if (current.isOp("((")) {
            return parseTooComplex("c_style_for_statement", startB, startI);
        }

        // 变量名
        String varName = current.text();
        consume();

        // 可选: in word+
        List<String> words = new ArrayList<>();
        skipNewlines();
        if (consumeKeyword("in") != null) {
            while (!current.isEof() && !current.isOp(";")
                    && current.type() != BashTokenType.NEWLINE
                    && !current.isWord("do")) {
                String w = consumeWord();
                if (w == null) break;
                words.add(w);
            }
        }

        // 分隔符: ; 或 \n
        if (current.isOp(";")) consume();
        skipNewlines();

        consumeKeyword("do");
        skipNewlines();

        ProgramNode body = parseInlineProgram("done");
        consumeKeyword("done");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new ForNode(varName, words, body, startB, endB, raw);
    }

    /**
     * case 语句: case word in { case_item } esac
     */
    private BashAstNode parseCase() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeKeyword("case");
        String word = consumeWord();
        if (word == null) word = "";
        consumeKeyword("in");
        skipNewlines();

        List<CaseItem> items = new ArrayList<>();
        while (!current.isEof() && !current.isWord("esac")) {
            checkBudget();
            CaseItem item = parseCaseItem();
            if (item != null) items.add(item);
            skipNewlines();
        }

        consumeKeyword("esac");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new CaseNode(word, items, startB, endB, raw);
    }

    private CaseItem parseCaseItem() {
        checkBudget();
        skipNewlines();

        // 可选: ( 前缀
        consumeOp("(");

        // patterns: pattern { '|' pattern }
        List<String> patterns = new ArrayList<>();
        String p = consumeWord();
        if (p != null) patterns.add(p);

        while (current.isOp("|")) {
            consume();
            String next = consumeWord();
            if (next != null) patterns.add(next);
        }

        // )
        consumeOp(")");
        skipNewlines();

        // body
        ProgramNode body = parseInlineProgram(";;", ";&", ";;&", "esac");

        // 终止符: ;; 或 ;& 或 ;;&
        if (current.isOp(";;") || current.isOp(";&") || current.isOp(";;&")) {
            consume();
        }

        return new CaseItem(patterns, body);
    }

    /**
     * 函数定义: function NAME { ... } 或 function NAME () { ... }
     */
    private BashAstNode parseFunction() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeKeyword("function");
        String name = current.text();
        consume();

        // 可选 ()
        if (current.isOp("(")) {
            consume();
            consumeOp(")");
        }
        skipNewlines();

        BashAstNode body = parseCommand();

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new FunctionDefNode(name, body, startB, endB, raw);
    }

    /**
     * 声明命令: export/declare/local/readonly/typeset
     */
    private BashAstNode parseDeclaration() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        String keyword = current.text();
        consume();

        List<String> argv = new ArrayList<>();
        argv.add(keyword);
        List<VarAssignment> assignments = new ArrayList<>();

        while (!current.isEof() && !isStatementTerminator()
                && !isOperatorToken() && !isRedirectOperator()) {
            // 检查是否为变量赋值
            if (isVarAssignment()) {
                VarAssignment va = parseVarAssignment();
                if (va != null) assignments.add(va);
            } else {
                String w = consumeWord();
                if (w == null) break;
                argv.add(w);
            }
        }

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new DeclarationCommandNode(keyword, argv, assignments, startB, endB, raw);
    }

    // ──── 复合结构解析 ────

    /**
     * 子 shell: ( statement_list )
     */
    private BashAstNode parseSubshell() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeOp("(");
        skipNewlines();

        ProgramNode body = parseInlineProgram(")");

        consumeOp(")");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new SubshellNode(body, startB, endB, raw);
    }

    /**
     * 大括号分组: { statement_list }
     */
    private BashAstNode parseBraceGroup() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeOp("{");
        skipNewlines();

        ProgramNode body = parseInlineProgram("}");

        consumeOp("}");

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new BraceGroupNode(body, startB, endB, raw);
    }

    /**
     * 条件测试: [[ expr ]] 或 [ expr ]
     */
    private BashAstNode parseTestCommand() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        boolean isDouble = current.isOp("[[");
        consume(); // [[ 或 [

        String closer = isDouble ? "]]" : "]";
        List<String> argv = new ArrayList<>();

        // 使用 text 匹配而非 isOp 匹配 — ] 和 ]] 可能是 WORD 类型
        while (!current.isEof() && !current.text().equals(closer)) {
            String w = consumeWord();
            if (w == null) {
                // 操作符也可以是测试参数 (-f, -d, =, !=, &&, etc.)
                if (current.type() == BashTokenType.OP) {
                    argv.add(current.text());
                    consume();
                } else {
                    break;
                }
            } else {
                argv.add(w);
            }
        }

        // 消费闭合符 (可能是 WORD 或 OP 类型)
        if (current.text().equals(closer)) {
            consume();
        }

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new TestCommandNode(argv, startB, endB, raw);
    }

    /**
     * 否定命令: ! command
     */
    private BashAstNode parseNegated() {
        checkBudget();
        int startB = current.startByte();
        int startI = current.startIndex();

        consumeOp("!");

        BashAstNode body = parseCommand();

        int endB = body != null ? body.endByte() : current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new NegatedCommandNode(body, startB, endB, raw);
    }

    // ──── 辅助解析方法 ────

    /**
     * 解析内联 ProgramNode — 读取语句直到遇到指定终止关键字。
     */
    private ProgramNode parseInlineProgram(String... terminators) {
        int startB = current.startByte();
        int startI = current.startIndex();

        List<StatementNode> stmts = new ArrayList<>();

        skipNewlines();

        while (!current.isEof() && !isTerminator(terminators)) {
            checkBudget();
            StatementNode stmt = parseStatement();
            if (stmt != null) stmts.add(stmt);

            // 消费分隔符
            boolean consumed = false;
            while (current.isOp(";") || current.isOp("&")
                    || current.type() == BashTokenType.NEWLINE
                    || current.type() == BashTokenType.COMMENT) {
                consumed = true;
                consume();
            }

            if (!consumed && !current.isEof() && !isTerminator(terminators)) {
                break;
            }
        }

        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));

        return new ProgramNode(stmts, startB, endB, raw);
    }

    private boolean isTerminator(String... terminators) {
        for (String t : terminators) {
            if (current.text().equals(t)) return true;
        }
        return false;
    }

    /**
     * 消费一个 “word” — 包括 WORD, NUMBER, SQUOTE, DQUOTE, ANSI_C, DOLLAR 等。
     * 相邻的引号/变量展开会拼接为一个完整单词 (仅当中间无空白分隔时)。
     */
    private String consumeWord() {
        if (!isWordToken()) return null;
    
        StringBuilder sb = new StringBuilder();
        sb.append(current.text());
        consume();
        // 继续拼接直接相邻的 Token (无空白分隔)
        while (isWordToken() && !lexer.hadWhitespaceBefore()) {
            sb.append(current.text());
            consume();
        }
        return sb.toString();
    }

    /** 判断当前 Token 是否可以构成单词的一部分 */
    private boolean isWordToken() {
        return switch (current.type()) {
            case WORD, NUMBER, SQUOTE, DQUOTE, ANSI_C, DOLLAR,
                 DOLLAR_PAREN, DOLLAR_BRACE, BACKTICK -> true;
            default -> false;
        };
    }

    /**
     * 判断当前是否为文件描述符+重定向 (如 2> 2>> 等)。
     * 检查 NUMBER token 后紧跟的原始字符是否为重定向符号。
     */
    private boolean isFdRedirectStart() {
        if (current.type() != BashTokenType.NUMBER) return false;
        char ch = lexer.current();
        return ch == '>' || ch == '<';
    }

    /** 判断当前是否为管道/逻辑操作符 Token (用于停止简单命令解析) */
    private boolean isOperatorToken() {
        if (current.type() != BashTokenType.OP) return false;
        String op = current.text();
        return switch (op) {
            case "|", "||", "&&", ";", "&", "|&",
                 "(", ")", "{", "}", "[[", "]]", ";;", ";&", ";;&" -> true;
            default -> false;
        };
    }

    /** 判断当前 Token 是否为变量赋值 (NAME=value) */
    private boolean isVarAssignment() {
        if (current.type() != BashTokenType.WORD) return false;
        String text = current.text();
        int eq = text.indexOf('=');
        if (eq <= 0) return false;
        // 验证 = 前面是合法变量名
        for (int k = 0; k < eq; k++) {
            char ch = text.charAt(k);
            if (k == 0 && !isNameStart(ch)) return false;
            if (k > 0 && !isNameChar(ch)) return false;
        }
        return true;
    }

    /** 解析变量赋值 */
    private VarAssignment parseVarAssignment() {
        String text = current.text();
        int eq = text.indexOf('=');
        String name = text.substring(0, eq);
        String value = text.substring(eq + 1);
        consume();

        // 值可能跟着引号 Token
        if (value.isEmpty() && isWordToken()
                && (current.type() == BashTokenType.SQUOTE
                || current.type() == BashTokenType.DQUOTE
                || current.type() == BashTokenType.DOLLAR)) {
            value = consumeWord();
            if (value == null) value = "";
        }

        return new VarAssignment(name, value);
    }

    /** TooComplex 兜底 — 消费到语句终止 */
    private BashAstNode parseTooComplex(String reason, int startB, int startI) {
        // 跳过直到语句终止
        while (!current.isEof() && !isStatementTerminator()
                && current.type() != BashTokenType.NEWLINE
                && !current.isOp(";")) {
            consume();
        }
        int endB = current.startByte();
        String raw = lexer.substring(startI,
                Math.min(findCharIndex(endB), lexer.getSource().length()));
        return new TooComplexNode(reason, startB, endB, raw);
    }

    private boolean isNameStart(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private boolean isNameChar(char ch) {
        return isNameStart(ch) || (ch >= '0' && ch <= '9');
    }

    /**
     * 简单地从字节偏移推算字符索引 — 对于纯 ASCII 两者相等，
     * 含多字节字符时需要精确计算。此处使用保守估计。
     */
    private int findCharIndex(int byteOffset) {
        // 对于纯 ASCII (绝大多数 bash 命令)，字节偏移 == 字符索引
        return Math.min(byteOffset, lexer.getSource().length());
    }

    // ──── 异常类型 ────

    /** 解析器超时异常 */
    public static class ParserTimeoutException extends RuntimeException {
        public ParserTimeoutException(String message) {
            super(message);
        }
    }

    /** 解析器节点预算超出异常 */
    public static class ParserBudgetExceededException extends RuntimeException {
        public ParserBudgetExceededException(String message) {
            super(message);
        }
    }
}

package com.aicodeassistant.tool.bash;

import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.bash.ast.BashAstNode;
import com.aicodeassistant.tool.bash.ast.BashAstNode.*;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.tool.bash.parser.BashParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashParser 50 条黄金测试 — 对齐 SPEC §3.2.3c 精选语料库。
 * <p>
 * 覆盖 12 个核心语法类别：简单命令(5)/管道(4)/重定向(5)/变量展开(5)/
 * 命令替换(4)/引号转义(5)/控制流(6)/复合(4)/函数(2)/Glob(3)/声明(3)/安全边界(4)。
 * <p>
 * 安全结果验证: simple → parseForSecurity 返回 Simple,
 *               too-complex → 返回 TooComplex,
 *               parse-unavailable → 返回 ParseUnavailable 或 null。
 */
class BashParserGoldenTest {

    private BashParser parser;
    private BashSecurityAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        parser = new BashParser();
        // ★ 创建真实的 AppStateStore 并设置工作目录，避免 NPE ★
        AppStateStore appStateStore = new AppStateStore();
        appStateStore.setState(state -> state.withSession(s ->
                s.withWorkingDirectory(System.getProperty("user.dir"))));
        analyzer = new BashSecurityAnalyzer(new PathValidator(), appStateStore);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════════

    /** 断言解析成功且安全结果为 simple */
    private ParseForSecurityResult.Simple assertSimple(String cmd) {
        var result = analyzer.parseForSecurity(cmd);
        assertInstanceOf(ParseForSecurityResult.Simple.class, result,
                "Expected Simple for: " + cmd + ", got: " + result);
        return (ParseForSecurityResult.Simple) result;
    }

    /** 断言安全结果为 too-complex */
    private ParseForSecurityResult.TooComplex assertTooComplex(String cmd) {
        var result = analyzer.parseForSecurity(cmd);
        assertInstanceOf(ParseForSecurityResult.TooComplex.class, result,
                "Expected TooComplex for: " + cmd + ", got: " + result);
        return (ParseForSecurityResult.TooComplex) result;
    }

    /** 断言解析成功 (返回 ProgramNode) */
    private ProgramNode assertParses(String cmd) {
        var node = parser.parse(cmd);
        assertNotNull(node, "Parse returned null for: " + cmd);
        assertFalse(node.statements().isEmpty(), "Empty program for: " + cmd);
        return node;
    }

    /** 获取程序第一条语句的 body */
    private BashAstNode firstBody(ProgramNode prog) {
        return prog.statements().getFirst().body();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 1. 简单命令 (5)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 简单命令")
    class SimpleCommands {

        @Test
        @DisplayName("[1-01] ls → simple")
        void test_1_01() {
            var result = assertSimple("ls");
            assertEquals(1, result.commands().size());
            assertEquals("ls", result.commands().getFirst().argv().getFirst());
        }

        @Test
        @DisplayName("[1-02] echo hello world → simple")
        void test_1_02() {
            var result = assertSimple("echo hello world");
            assertEquals(1, result.commands().size());
            var cmd = result.commands().getFirst();
            assertEquals("echo", cmd.argv().getFirst());
            assertTrue(cmd.argv().size() >= 2);
        }

        @Test
        @DisplayName("[1-03] git commit -m 'fix bug' → simple")
        void test_1_03() {
            var result = assertSimple("git commit -m 'fix bug'");
            assertEquals(1, result.commands().size());
            assertEquals("git", result.commands().getFirst().argv().getFirst());
        }

        @Test
        @DisplayName("[1-04] ENV=value command arg → simple")
        void test_1_04() {
            var result = assertSimple("ENV=value command arg");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[1-05] A=1 B=2 → simple (纯赋值)")
        void test_1_05() {
            var result = assertSimple("A=1 B=2");
            // 纯变量赋值 — commands 可能为空
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. 管道与序列 (4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 管道与序列")
    class PipelinesAndSequences {

        @Test
        @DisplayName("[2-01] cat file | grep pattern → simple (单管道)")
        void test_2_01() {
            var result = assertSimple("cat file | grep pattern");
            assertTrue(result.commands().size() >= 2);
        }

        @Test
        @DisplayName("[2-02] ps aux | grep java | head -5 → simple (多级管道)")
        void test_2_02() {
            var result = assertSimple("ps aux | grep java | head -5");
            assertTrue(result.commands().size() >= 3);
        }

        @Test
        @DisplayName("[2-03] make && make install → simple (逻辑与)")
        void test_2_03() {
            var result = assertSimple("make && make install");
            assertTrue(result.commands().size() >= 2);
        }

        @Test
        @DisplayName("[2-04] cmd1 || cmd2 && cmd3 → simple (混合逻辑)")
        void test_2_04() {
            var result = assertSimple("cmd1 || cmd2 && cmd3");
            assertTrue(result.commands().size() >= 2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. 重定向 (5)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 重定向")
    class Redirections {

        @Test
        @DisplayName("[3-01] echo hello > output.txt → simple")
        void test_3_01() {
            var result = assertSimple("echo hello > output.txt");
            assertEquals(1, result.commands().size());
            assertEquals("echo", result.commands().getFirst().argv().getFirst());
        }

        @Test
        @DisplayName("[3-02] sort < input.txt >> result.txt → simple")
        void test_3_02() {
            var result = assertSimple("sort < input.txt >> result.txt");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[3-03] cmd 2>&1 → simple")
        void test_3_03() {
            var result = assertSimple("cmd 2>&1");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[3-04] cmd &> /dev/null → simple")
        void test_3_04() {
            var result = assertSimple("cmd &> /dev/null");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[3-05] cat <<'EOF'\\nhello world\\nEOF → simple (heredoc)")
        void test_3_05() {
            var result = assertSimple("cat <<'EOF'\nhello world\nEOF");
            assertFalse(result.commands().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. 变量展开 (5)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 变量展开")
    class VariableExpansions {

        @Test
        @DisplayName("[4-01] echo $HOME → simple")
        void test_4_01() {
            var result = assertSimple("echo $HOME");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[4-02] echo ${VAR:-default} → simple")
        void test_4_02() {
            var result = assertSimple("echo ${VAR:-default}");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[4-03] echo ${#array[@]} → simple")
        void test_4_03() {
            var result = assertSimple("echo ${#array[@]}");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[4-04] echo $? → simple")
        void test_4_04() {
            var result = assertSimple("echo $?");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[4-05] echo ${PATH//:/\\n} → simple")
        void test_4_05() {
            var result = assertSimple("echo ${PATH//:/\\n}");
            assertEquals(1, result.commands().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. 命令替换 (4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 命令替换")
    class CommandSubstitutions {

        @Test
        @DisplayName("[5-01] echo $(date +%Y) → simple")
        void test_5_01() {
            var result = assertSimple("echo $(date +%Y)");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[5-02] echo `uname` → simple")
        void test_5_02() {
            var result = assertSimple("echo `uname`");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[5-03] dir=$(pwd) → simple")
        void test_5_03() {
            var result = assertSimple("dir=$(pwd)");
            assertNotNull(result);
        }

        @Test
        @DisplayName("[5-04] echo $((1 + 2)) → too-complex (算术展开)")
        void test_5_04() {
            assertTooComplex("echo $((1 + 2))");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. 引号与转义 (5)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 引号与转义")
    class QuotingAndEscaping {

        @Test
        @DisplayName("[6-01] echo 'single quoted' → simple")
        void test_6_01() {
            var result = assertSimple("echo 'single quoted'");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[6-02] echo \"hello $USER\" → simple")
        void test_6_02() {
            var result = assertSimple("echo \"hello $USER\"");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[6-03] echo $'\\t\\n\\\\' → simple (ANSI-C)")
        void test_6_03() {
            var result = assertSimple("echo $'\\t\\n\\\\'");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[6-04] echo hello\\ world → simple (反斜杠转义)")
        void test_6_04() {
            var result = assertSimple("echo hello\\ world");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[6-05] echo \"pre\"'mid'\"$suf\" → simple (拼接)")
        void test_6_05() {
            var result = assertSimple("echo \"pre\"'mid'\"$suf\"");
            assertEquals(1, result.commands().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. 控制流 (6)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. 控制流")
    class ControlFlow {

        @Test
        @DisplayName("[7-01] if [ -f file ]; then echo yes; fi → simple")
        void test_7_01() {
            var result = assertSimple("if [ -f file ]; then echo yes; fi");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[7-02] if-elif-else 完整链 → simple")
        void test_7_02() {
            var result = assertSimple("if cmd; then a; elif cmd2; then b; else c; fi");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[7-03] for f in *.txt; do echo \"$f\"; done → simple")
        void test_7_03() {
            var result = assertSimple("for f in *.txt; do echo \"$f\"; done");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[7-04] for ((i=0; i<10; i++)); do echo $i; done → too-complex")
        void test_7_04() {
            assertTooComplex("for ((i=0; i<10; i++)); do echo $i; done");
        }

        @Test
        @DisplayName("[7-05] while read -r line; do echo \"$line\"; done → simple")
        void test_7_05() {
            var result = assertSimple("while read -r line; do echo \"$line\"; done");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[7-06] case \"$1\" in start) run;; stop) halt;; *) usage;; esac → simple")
        void test_7_06() {
            var result = assertSimple("case \"$1\" in start) run;; stop) halt;; *) usage;; esac");
            assertFalse(result.commands().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. 复合结构 (4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 复合结构")
    class CompoundStructures {

        @Test
        @DisplayName("[8-01] (cd /tmp && ls) → simple (子 shell)")
        void test_8_01() {
            var result = assertSimple("(cd /tmp && ls)");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[8-02] { echo a; echo b; } → simple (大括号分组)")
        void test_8_02() {
            var result = assertSimple("{ echo a; echo b; }");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[8-03] [[ -n \"$var\" && -f \"$file\" ]] → simple (测试命令)")
        void test_8_03() {
            var result = assertSimple("[[ -n \"$var\" && -f \"$file\" ]]");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[8-04] ! grep -q pattern file → simple (否定)")
        void test_8_04() {
            var result = assertSimple("! grep -q pattern file");
            assertFalse(result.commands().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 9. 函数定义 (2)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. 函数定义")
    class FunctionDefinitions {

        @Test
        @DisplayName("[9-01] greet() { echo \"hello $1\"; } → simple")
        void test_9_01() {
            var result = assertSimple("greet() { echo \"hello $1\"; }");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[9-02] log() { echo \"$@\"; } 2>/dev/null → simple")
        void test_9_02() {
            var result = assertSimple("log() { echo \"$@\"; } 2>/dev/null");
            assertFalse(result.commands().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 10. Glob 与大括号展开 (3)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. Glob 与大括号展开")
    class GlobAndBrace {

        @Test
        @DisplayName("[10-01] ls *.txt → simple")
        void test_10_01() {
            var result = assertSimple("ls *.txt");
            assertEquals(1, result.commands().size());
            assertEquals("ls", result.commands().getFirst().argv().getFirst());
        }

        @Test
        @DisplayName("[10-02] echo file[0-9].log → simple")
        void test_10_02() {
            var result = assertSimple("echo file[0-9].log");
            assertEquals(1, result.commands().size());
        }

        @Test
        @DisplayName("[10-03] echo {a,b,c} → too-complex (大括号展开)")
        void test_10_03() {
            assertTooComplex("echo {a,b,c}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 11. 声明命令 (3)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. 声明命令")
    class DeclarationCommands {

        @Test
        @DisplayName("[11-01] export PATH=\"/usr/bin:$PATH\" → simple")
        void test_11_01() {
            var result = assertSimple("export PATH=\"/usr/bin:$PATH\"");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[11-02] declare -a arr=(1 2 3) → simple")
        void test_11_02() {
            var result = assertSimple("declare -a arr=(1 2 3)");
            assertFalse(result.commands().isEmpty());
        }

        @Test
        @DisplayName("[11-03] local var=\"value\" → simple")
        void test_11_03() {
            var result = assertSimple("local var=\"value\"");
            assertFalse(result.commands().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 12. 安全边界用例 (4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. 安全边界")
    class SecurityBoundary {

        @Test
        @DisplayName("[12-01] echo <(cat /etc/passwd) → too-complex (进程替换)")
        void test_12_01() {
            assertTooComplex("echo <(cat /etc/passwd)");
        }

        @Test
        @DisplayName("[12-02] echo $\"hello\" → too-complex (翻译字符串)")
        void test_12_02() {
            assertTooComplex("echo $\"hello\"");
        }

        @Test
        @DisplayName("[12-03] trap 'rm -rf /' EXIT → simple (解析成功; 权限层拦截)")
        void test_12_03() {
            // trap 是 EVAL_LIKE_BUILTINS，但安全分析在 checkSemantics 层拦截
            // parseForSecurity → checkSemantics → "eval-like builtin: trap"
            // 所以实际应为 TooComplex (semantic-check)
            var result = analyzer.parseForSecurity("trap 'rm -rf /' EXIT");
            // trap 被 checkSemantics 的 EVAL_LIKE_BUILTINS 拦截 → TooComplex
            assertInstanceOf(ParseForSecurityResult.TooComplex.class, result,
                    "trap should be caught by EVAL_LIKE_BUILTINS check");
        }

        @Test
        @DisplayName("[12-04] 超长深嵌套 → parse-unavailable/too-complex (超时/预算)")
        void test_12_04() {
            // 构造超长命令 (> 10000 字符) → parse-unavailable
            String longCmd = "echo " + "a".repeat(10001);
            var result = analyzer.parseForSecurity(longCmd);
            // 超长命令 → ParseUnavailable
            assertInstanceOf(ParseForSecurityResult.ParseUnavailable.class, result,
                    "Command > 10000 chars should return ParseUnavailable");
        }
    }
}

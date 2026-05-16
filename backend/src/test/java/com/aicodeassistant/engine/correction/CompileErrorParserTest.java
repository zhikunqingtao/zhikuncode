package com.aicodeassistant.engine.correction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * CompileErrorParser 单元测试。
 * 覆盖 Java / TypeScript / Python 编译错误解析及边界场景。
 */
@DisplayName("CompileErrorParser 单元测试")
class CompileErrorParserTest {

    private CompileErrorParser parser;

    @BeforeEach
    void setUp() {
        parser = new CompileErrorParser();
    }

    @Test
    @DisplayName("解析 Java 编译错误 - Maven 格式")
    void testParseJavaCompileError() {
        String output = """
            [ERROR] /src/main/java/com/example/Main.java:15: error: cannot find symbol
              symbol:   variable foo
              location: class Main
            [ERROR] /src/main/java/com/example/Main.java:23: error: incompatible types
            """;
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(result.get().get(0).language()).isEqualTo("java");
        assertThat(result.get().get(0).fileName()).contains("Main.java");
        assertThat(result.get().get(0).lineNumber()).isEqualTo(15);
        assertThat(result.get().get(0).errorMessage()).contains("cannot find symbol");
    }

    @Test
    @DisplayName("解析多个 Java 编译错误")
    void testParseMultipleJavaErrors() {
        String output = """
            src/Foo.java:10: error: ';' expected
            src/Foo.java:20: error: variable x already defined
            src/Bar.java:5: error: package does not exist
            """;
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(3);
        assertThat(result.get()).allMatch(e -> e.language().equals("java"));
    }

    @Test
    @DisplayName("解析 TypeScript 编译错误")
    void testParseTypeScriptError() {
        String output = """
            src/app.ts(23,5): error TS2304: Cannot find name 'xyz'
            src/utils.tsx(10,1): error TS2322: Type 'string' is not assignable to type 'number'
            """;
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).language()).isEqualTo("typescript");
        assertThat(result.get().get(0).fileName()).isEqualTo("src/app.ts");
        assertThat(result.get().get(0).lineNumber()).isEqualTo(23);
        assertThat(result.get().get(1).fileName()).isEqualTo("src/utils.tsx");
    }

    @Test
    @DisplayName("解析 Python 语法错误")
    void testParsePythonError() {
        String output = """
            Traceback (most recent call last):
              File "main.py", line 10
                x = 1 +
                      ^
            SyntaxError: invalid syntax
            """;
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get().get(0).language()).isEqualTo("python");
        assertThat(result.get().get(0).fileName()).isEqualTo("main.py");
        assertThat(result.get().get(0).lineNumber()).isEqualTo(10);
        assertThat(result.get().get(0).errorMessage()).contains("SyntaxError");
    }

    @Test
    @DisplayName("无错误输出返回 empty")
    void testNoErrorReturnsEmpty() {
        String output = "BUILD SUCCESS\nTests run: 5, Failures: 0, Errors: 0";
        assertThat(parser.parse(output)).isEmpty();
    }

    @Test
    @DisplayName("空输出或null返回 empty")
    void testEmptyOutputReturnsEmpty() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("最多返回5个错误")
    void testMaxFiveErrors() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("src/Main.java:").append(i).append(": error: some error ").append(i).append("\n");
        }
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(sb.toString());
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(5);
    }

    @Test
    @DisplayName("混合语言错误均能解析")
    void testMixedLanguageErrors() {
        String output = """
            src/App.java:5: error: method not found
            src/index.ts(12,3): error TS2345: Argument type mismatch
            """;
        Optional<List<CorrectionInstruction.ParsedError>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).language()).isEqualTo("java");
        assertThat(result.get().get(1).language()).isEqualTo("typescript");
    }
}

package com.aicodeassistant.engine.correction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * TestFailureParser 单元测试。
 * 覆盖 JUnit / Jest / pytest 测试失败解析及边界场景。
 */
@DisplayName("TestFailureParser 单元测试")
class TestFailureParserTest {

    private TestFailureParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestFailureParser();
    }

    @Test
    @DisplayName("解析 JUnit 测试失败 - Maven Surefire 格式")
    void testParseJUnitFailure() {
        String output = """
            Tests run: 5, Failures: 2, Errors: 0, Skipped: 0

            Failed tests:
              testSomething(com.example.MyTest): expected:<true> but was:<false>
              testAnother(com.example.MyTest): expected:<42> but was:<0>
            """;
        Optional<List<CorrectionInstruction.ParsedTestFailure>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSizeGreaterThanOrEqualTo(1);

        CorrectionInstruction.ParsedTestFailure first = result.get().get(0);
        assertThat(first.framework()).isEqualTo("junit");
        assertThat(first.expected()).isEqualTo("true");
        assertThat(first.actual()).isEqualTo("false");
    }

    @Test
    @DisplayName("解析 Jest 测试失败")
    void testParseJestFailure() {
        String output = """
            FAIL src/components/App.test.tsx
              ● App component › renders correctly
                expect(received).toBe(expected)
                Expected: "Hello"
                Received: "World"

                at Object.<anonymous> (src/components/App.test.tsx:15:10)
            """;
        Optional<List<CorrectionInstruction.ParsedTestFailure>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSizeGreaterThanOrEqualTo(1);

        CorrectionInstruction.ParsedTestFailure first = result.get().get(0);
        assertThat(first.framework()).isEqualTo("jest");
        assertThat(first.expected()).contains("Hello");
        assertThat(first.actual()).contains("World");
    }

    @Test
    @DisplayName("解析 pytest 测试失败")
    void testParsePytestFailure() {
        String output = """
            FAILED tests/test_main.py::test_addition - AssertionError: assert 3 == 4
            FAILED tests/test_main.py::test_subtraction - assert 1 == 0
            """;
        Optional<List<CorrectionInstruction.ParsedTestFailure>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSizeGreaterThanOrEqualTo(1);

        CorrectionInstruction.ParsedTestFailure first = result.get().get(0);
        assertThat(first.framework()).isEqualTo("pytest");
        assertThat(first.testName()).contains("test_addition");
    }

    @Test
    @DisplayName("全部通过的测试输出返回 empty")
    void testPassingTestsReturnEmpty() {
        String output = """
            Tests run: 10, Failures: 0, Errors: 0, Skipped: 0

            BUILD SUCCESS
            """;
        assertThat(parser.parse(output)).isEmpty();
    }

    @Test
    @DisplayName("无测试相关输出返回 empty")
    void testNoTestOutputReturnsEmpty() {
        String output = "Some random build output with no test results";
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
    @DisplayName("最多返回5个失败")
    void testMaxFiveFailures() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("FAILED tests/test_calc.py::test_case_").append(i).append(" - AssertionError: assert 0 == ").append(i).append("\n");
        }
        Optional<List<CorrectionInstruction.ParsedTestFailure>> result = parser.parse(sb.toString());
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(5);
    }

    @Test
    @DisplayName("Jest 多个失败场景")
    void testParseMultipleJestFailures() {
        String output = """
            FAIL src/utils.test.ts
              ● utils › add function
                Expected: 5
                Received: 3

              ● utils › multiply function
                Expected: 20
                Received: 10
            """;
        Optional<List<CorrectionInstruction.ParsedTestFailure>> result = parser.parse(output);
        assertThat(result).isPresent();
        assertThat(result.get()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.get()).allMatch(f -> f.framework().equals("jest"));
    }
}

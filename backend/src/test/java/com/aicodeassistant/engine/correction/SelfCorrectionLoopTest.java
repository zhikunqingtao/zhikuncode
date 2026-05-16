package com.aicodeassistant.engine.correction;

import com.aicodeassistant.engine.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SelfCorrectionLoop 单元测试 — 使用 Mockito 模拟依赖。
 * 覆盖主控逻辑核心路径：错误检测、指令生成、中止判断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SelfCorrectionLoop 单元测试")
class SelfCorrectionLoopTest {

    @Mock
    private CompileErrorParser compileErrorParser;

    @Mock
    private TestFailureParser testFailureParser;

    @Mock
    private TokenCounter tokenCounter;

    private SelfCorrectionLoop loop;

    @BeforeEach
    void setUp() {
        loop = new SelfCorrectionLoop(compileErrorParser, testFailureParser, tokenCounter);
    }

    // ===== detectAndPrepareCorrection 测试 =====

    @Test
    @DisplayName("检测到编译错误 → 返回 COMPILE_ERROR 类型指令")
    void testDetectCompileError_ReturnsCorrection() {
        String toolOutput = "src/Main.java:10: error: cannot find symbol";
        List<CorrectionInstruction.ParsedError> errors = List.of(
            new CorrectionInstruction.ParsedError("src/Main.java", 10, "cannot find symbol", "java")
        );
        when(compileErrorParser.parse(toolOutput)).thenReturn(Optional.of(errors));
        when(tokenCounter.estimateTokens(anyString())).thenReturn(100);

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.COMPILE_ERROR);
        assertThat(result.get().attemptNumber()).isEqualTo(1);
        assertThat(result.get().instruction()).isNotBlank();
        assertThat(result.get().errorContext().fileName()).isEqualTo("src/Main.java");
    }

    @Test
    @DisplayName("检测到测试失败 → 返回 TEST_FAILURE 类型指令")
    void testDetectTestFailure_ReturnsCorrection() {
        String toolOutput = "FAILED tests/test_main.py::test_add - assert 3 == 4";
        when(compileErrorParser.parse(toolOutput)).thenReturn(Optional.empty());

        List<CorrectionInstruction.ParsedTestFailure> failures = List.of(
            new CorrectionInstruction.ParsedTestFailure(
                "tests/test_main.py::test_add", "4", "3", "", "pytest")
        );
        when(testFailureParser.parse(toolOutput)).thenReturn(Optional.of(failures));
        when(tokenCounter.estimateTokens(anyString())).thenReturn(100);

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.TEST_FAILURE);
        assertThat(result.get().attemptNumber()).isEqualTo(1);
        assertThat(result.get().errorContext().errorMessage()).contains("Expected");
    }

    @Test
    @DisplayName("超过最大尝试次数 → 返回 empty")
    void testMaxAttemptsExceeded_ReturnsEmpty() {
        String toolOutput = "src/Main.java:10: error: cannot find symbol";

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 3);

        assertThat(result).isEmpty();
        verifyNoInteractions(compileErrorParser);
        verifyNoInteractions(testFailureParser);
    }

    @Test
    @DisplayName("指令超 token 限制 → 截断")
    void testInstructionTokenLimit_Truncated() {
        String toolOutput = "src/Main.java:10: error: cannot find symbol";
        List<CorrectionInstruction.ParsedError> errors = List.of(
            new CorrectionInstruction.ParsedError("src/Main.java", 10, "cannot find symbol", "java")
        );
        when(compileErrorParser.parse(toolOutput)).thenReturn(Optional.of(errors));
        // 返回一个很大的 token 数以触发截断
        when(tokenCounter.estimateTokens(anyString())).thenReturn(2000);

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 0);

        assertThat(result).isPresent();
        assertThat(result.get().instruction()).contains("[truncated due to token limit]");
    }

    @Test
    @DisplayName("无错误也无测试失败 → 返回 empty")
    void testNoError_ReturnsEmpty() {
        String toolOutput = "BUILD SUCCESS";
        when(compileErrorParser.parse(toolOutput)).thenReturn(Optional.empty());
        when(testFailureParser.parse(toolOutput)).thenReturn(Optional.empty());

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 0);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null 工具输出 → 返回 empty")
    void testNullToolOutput_ReturnsEmpty() {
        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(null, 0);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("编译错误优先于测试失败")
    void testCompileErrorPriority() {
        String toolOutput = "src/Main.java:10: error: missing ; FAILED test::test1";
        List<CorrectionInstruction.ParsedError> errors = List.of(
            new CorrectionInstruction.ParsedError("src/Main.java", 10, "missing ;", "java")
        );
        when(compileErrorParser.parse(toolOutput)).thenReturn(Optional.of(errors));
        when(tokenCounter.estimateTokens(anyString())).thenReturn(100);

        Optional<CorrectionInstruction> result = loop.detectAndPrepareCorrection(toolOutput, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.COMPILE_ERROR);
        // testFailureParser 不应被调用
        verifyNoInteractions(testFailureParser);
    }

    // ===== shouldAbort 测试 =====

    @Test
    @DisplayName("新错误数量增加 → 应中止")
    void testShouldAbort_NewErrorsIntroduced() {
        String previousOutput = "src/A.java:1: error: foo";
        String newOutput = "src/A.java:1: error: foo\nsrc/B.java:2: error: bar";

        // previous: 1 error
        when(compileErrorParser.parse(previousOutput)).thenReturn(Optional.of(List.of(
            new CorrectionInstruction.ParsedError("src/A.java", 1, "foo", "java")
        )));
        when(testFailureParser.parse(previousOutput)).thenReturn(Optional.empty());

        // new: 2 errors
        when(compileErrorParser.parse(newOutput)).thenReturn(Optional.of(List.of(
            new CorrectionInstruction.ParsedError("src/A.java", 1, "foo", "java"),
            new CorrectionInstruction.ParsedError("src/B.java", 2, "bar", "java")
        )));
        when(testFailureParser.parse(newOutput)).thenReturn(Optional.empty());

        boolean result = loop.shouldAbort(newOutput, previousOutput);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("错误数量减少或相同 → 不中止")
    void testShouldAbort_SameOrFewerErrors_ContinuesFix() {
        String previousOutput = "src/A.java:1: error: foo\nsrc/B.java:2: error: bar";
        String newOutput = "src/A.java:1: error: foo";

        // previous: 2 errors
        List<CorrectionInstruction.ParsedError> prevErrors = List.of(
            new CorrectionInstruction.ParsedError("src/A.java", 1, "foo", "java"),
            new CorrectionInstruction.ParsedError("src/B.java", 2, "bar", "java")
        );
        when(compileErrorParser.parse(previousOutput)).thenReturn(Optional.of(prevErrors));
        when(testFailureParser.parse(previousOutput)).thenReturn(Optional.empty());

        // new: 1 error (same file, same type)
        List<CorrectionInstruction.ParsedError> newErrors = List.of(
            new CorrectionInstruction.ParsedError("src/A.java", 1, "foo", "java")
        );
        when(compileErrorParser.parse(newOutput)).thenReturn(Optional.of(newErrors));
        when(testFailureParser.parse(newOutput)).thenReturn(Optional.empty());

        boolean result = loop.shouldAbort(newOutput, previousOutput);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("引入新错误文件 → 应中止")
    void testShouldAbort_NewFileIntroduced() {
        String previousOutput = "src/A.java:1: error: foo";
        String newOutput = "src/C.java:5: error: new error";

        // previous: file A
        when(compileErrorParser.parse(previousOutput)).thenReturn(Optional.of(List.of(
            new CorrectionInstruction.ParsedError("src/A.java", 1, "foo", "java")
        )));
        when(testFailureParser.parse(previousOutput)).thenReturn(Optional.empty());

        // new: same count but different file (C instead of A)
        when(compileErrorParser.parse(newOutput)).thenReturn(Optional.of(List.of(
            new CorrectionInstruction.ParsedError("src/C.java", 5, "new error", "java")
        )));
        when(testFailureParser.parse(newOutput)).thenReturn(Optional.empty());

        boolean result = loop.shouldAbort(newOutput, previousOutput);
        assertThat(result).isTrue();
    }
}

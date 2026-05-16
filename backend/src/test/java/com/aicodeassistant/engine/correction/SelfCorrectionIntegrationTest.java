package com.aicodeassistant.engine.correction;

import com.aicodeassistant.config.ModelCapabilityConfig;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.llm.ModelCapabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * SelfCorrectionLoop 集成测试 — 使用真实组件，不 mock。
 * 验证端到端错误检测与修复指令生成。
 */
@DisplayName("SelfCorrectionLoop 集成测试")
class SelfCorrectionIntegrationTest {

    private SelfCorrectionLoop selfCorrectionLoop;

    @BeforeEach
    void setUp() {
        // 手动组装真实依赖（不依赖 Spring Context 以避免启动慢）
        CompileErrorParser compileErrorParser = new CompileErrorParser();
        TestFailureParser testFailureParser = new TestFailureParser();

        ModelCapabilityConfig config = new ModelCapabilityConfig();
        ModelCapabilityRegistry registry = new ModelCapabilityRegistry(config);
        registry.init();
        TokenCounter tokenCounter = new TokenCounter(registry, null, null);

        selfCorrectionLoop = new SelfCorrectionLoop(compileErrorParser, testFailureParser, tokenCounter);
    }

    @Test
    @DisplayName("真实 Java 编译错误 → 生成 COMPILE_ERROR 指令")
    void testRealCompileErrorDetection() {
        String javaError = "src/Main.java:15: error: cannot find symbol\n  symbol: variable x";
        Optional<CorrectionInstruction> result = selfCorrectionLoop.detectAndPrepareCorrection(javaError, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.COMPILE_ERROR);
        assertThat(result.get().instruction()).isNotBlank();
        assertThat(result.get().instruction()).contains("Main.java");
        assertThat(result.get().attemptNumber()).isEqualTo(1);
        assertThat(result.get().errorContext().fileName()).isEqualTo("src/Main.java");
        assertThat(result.get().errorContext().lineNumber()).isEqualTo(15);
    }

    @Test
    @DisplayName("真实 TypeScript 编译错误 → 生成修复指令")
    void testRealTypeScriptErrorDetection() {
        String tsError = "src/app.ts(10,5): error TS2304: Cannot find name 'myVar'";
        Optional<CorrectionInstruction> result = selfCorrectionLoop.detectAndPrepareCorrection(tsError, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.COMPILE_ERROR);
        assertThat(result.get().instruction()).contains("app.ts");
    }

    @Test
    @DisplayName("真实 pytest 失败 → 生成 TEST_FAILURE 指令")
    void testRealPytestFailureDetection() {
        String pytestOutput = "FAILED tests/test_calc.py::test_add - AssertionError: assert 3 == 4";
        Optional<CorrectionInstruction> result = selfCorrectionLoop.detectAndPrepareCorrection(pytestOutput, 0);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(CorrectionInstruction.CorrectionType.TEST_FAILURE);
        assertThat(result.get().instruction()).contains("test_add");
    }

    @Test
    @DisplayName("最大尝试次数到达 → 返回 empty")
    void testMaxAttemptsReached() {
        String error = "src/Main.java:15: error: cannot find symbol";
        assertThat(selfCorrectionLoop.detectAndPrepareCorrection(error, 3)).isEmpty();
    }

    @Test
    @DisplayName("成功构建输出 → 无修复指令")
    void testSuccessfulBuildNoCorrection() {
        String success = "BUILD SUCCESS\nTotal time: 2.5s";
        assertThat(selfCorrectionLoop.detectAndPrepareCorrection(success, 0)).isEmpty();
    }

    @Test
    @DisplayName("shouldAbort - 新增错误时中止")
    void testShouldAbortWhenNewErrorsIntroduced() {
        String previous = "src/A.java:10: error: missing return";
        String newOutput = "src/A.java:10: error: missing return\nsrc/B.java:5: error: type mismatch";

        boolean shouldAbort = selfCorrectionLoop.shouldAbort(newOutput, previous);
        assertThat(shouldAbort).isTrue();
    }

    @Test
    @DisplayName("shouldAbort - 错误减少时继续")
    void testShouldNotAbortWhenErrorsDecrease() {
        String previous = "src/A.java:10: error: missing return\nsrc/A.java:20: error: type mismatch";
        String newOutput = "src/A.java:10: error: missing return";

        boolean shouldAbort = selfCorrectionLoop.shouldAbort(newOutput, previous);
        assertThat(shouldAbort).isFalse();
    }

    @Test
    @DisplayName("第二次尝试 attemptNumber 正确递增")
    void testAttemptNumberIncrement() {
        String error = "src/Foo.java:1: error: bad type";
        Optional<CorrectionInstruction> result = selfCorrectionLoop.detectAndPrepareCorrection(error, 2);

        assertThat(result).isPresent();
        assertThat(result.get().attemptNumber()).isEqualTo(3);
    }
}

package com.aicodeassistant.engine.correction;

import java.util.List;

/**
 * SelfCorrectionLoop 核心数据结构。
 * 封装修复指令、错误上下文及解析结果。
 */
public record CorrectionInstruction(
    String instruction,          // 修复指令文本(≤800 tokens)
    ErrorContext errorContext,   // 错误上下文
    CorrectionType type,        // COMPILE_ERROR / TEST_FAILURE
    int attemptNumber           // 当前尝试次数(1-3)
) {

    /**
     * 修正类型枚举
     */
    public enum CorrectionType {
        COMPILE_ERROR,
        TEST_FAILURE
    }

    /**
     * 错误上下文，包含文件名、行号、错误消息及相关代码片段
     */
    public record ErrorContext(
        String fileName,
        int lineNumber,
        String errorMessage,
        String relevantCode
    ) {}

    /**
     * 解析后的编译错误
     */
    public record ParsedError(
        String fileName,
        int lineNumber,
        String errorMessage,
        String language  // "java", "typescript", "python"
    ) {}

    /**
     * 解析后的测试失败
     */
    public record ParsedTestFailure(
        String testName,
        String expected,
        String actual,
        String stackTrace,
        String framework  // "junit", "jest", "pytest"
    ) {}

    /**
     * 从解析后的编译错误列表构建 CorrectionInstruction
     */
    public static CorrectionInstruction fromCompileErrors(
            List<ParsedError> errors, int attemptNumber) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("errors must not be empty");
        }
        ParsedError first = errors.getFirst();
        ErrorContext ctx = new ErrorContext(
            first.fileName(),
            first.lineNumber(),
            first.errorMessage(),
            ""
        );
        String instruction = buildCompileInstruction(errors);
        return new CorrectionInstruction(instruction, ctx, CorrectionType.COMPILE_ERROR, attemptNumber);
    }

    /**
     * 从解析后的测试失败列表构建 CorrectionInstruction
     */
    public static CorrectionInstruction fromTestFailures(
            List<ParsedTestFailure> failures, int attemptNumber) {
        if (failures == null || failures.isEmpty()) {
            throw new IllegalArgumentException("failures must not be empty");
        }
        ParsedTestFailure first = failures.getFirst();
        ErrorContext ctx = new ErrorContext(
            first.testName(),
            0,
            first.actual() != null
                ? "Expected: " + first.expected() + ", Actual: " + first.actual()
                : first.stackTrace(),
            ""
        );
        String instruction = buildTestInstruction(failures);
        return new CorrectionInstruction(instruction, ctx, CorrectionType.TEST_FAILURE, attemptNumber);
    }

    private static String buildCompileInstruction(List<ParsedError> errors) {
        StringBuilder sb = new StringBuilder("Fix the following compile errors:\n");
        for (ParsedError e : errors) {
            sb.append(String.format("- %s:%d: %s%n", e.fileName(), e.lineNumber(), e.errorMessage()));
        }
        return sb.toString();
    }

    private static String buildTestInstruction(List<ParsedTestFailure> failures) {
        StringBuilder sb = new StringBuilder("Fix the following test failures:\n");
        for (ParsedTestFailure f : failures) {
            sb.append(String.format("- %s: expected=%s, actual=%s%n",
                f.testName(), f.expected(), f.actual()));
        }
        return sb.toString();
    }
}

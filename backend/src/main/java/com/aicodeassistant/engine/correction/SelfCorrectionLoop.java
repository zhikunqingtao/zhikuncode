package com.aicodeassistant.engine.correction;

import com.aicodeassistant.engine.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自修正循环主控逻辑。
 * <p>
 * 负责检测工具输出中的编译错误和测试失败，生成结构化修复指令，
 * 并判断修复是否引入了新错误以决定是否中止修复循环。
 * <p>
 * 修复流程:
 * <ol>
 *   <li>检测错误 → 优先处理编译错误，其次测试失败</li>
 *   <li>生成修复指令 → 结构化模板，受 token 限制约束</li>
 *   <li>中止判断 → 若修复引入新错误则中止</li>
 * </ol>
 */
@Service
@Slf4j
public class SelfCorrectionLoop {

    /** 最大修复尝试次数 */
    private static final int MAX_ATTEMPTS = 3;

    /** 修复指令最大 token 数 */
    private static final int MAX_INSTRUCTION_TOKENS = 800;

    /** 堆栈摘要最大行数 */
    private static final int MAX_STACK_TRACE_LINES = 3;

    private final CompileErrorParser compileErrorParser;
    private final TestFailureParser testFailureParser;
    private final TokenCounter tokenCounter;

    /**
     * 构造函数注入依赖。
     *
     * @param compileErrorParser 编译错误解析器
     * @param testFailureParser  测试失败解析器
     * @param tokenCounter       Token 计数器
     */
    public SelfCorrectionLoop(CompileErrorParser compileErrorParser,
                              TestFailureParser testFailureParser,
                              TokenCounter tokenCounter) {
        this.compileErrorParser = compileErrorParser;
        this.testFailureParser = testFailureParser;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 主入口：检测工具输出中的错误并生成修复指令。
     * <p>
     * 优先级: 编译错误 > 测试失败。若同时存在编译错误和测试失败，
     * 仅处理编译错误（编译错误修复后测试失败可能自动消除）。
     *
     * @param toolOutput      Bash 工具的输出（stdout + stderr）
     * @param currentAttempts 当前已尝试的修复次数（从 0 开始）
     * @return 修复指令，或 empty（无可修复错误 / 已超限）
     */
    public Optional<CorrectionInstruction> detectAndPrepareCorrection(
            String toolOutput, int currentAttempts) {

        // 1. 超限检查
        if (currentAttempts >= MAX_ATTEMPTS) {
            log.info("Self-correction attempt limit reached ({}/{}), stopping",
                    currentAttempts, MAX_ATTEMPTS);
            return Optional.empty();
        }

        if (toolOutput == null || toolOutput.isBlank()) {
            log.debug("Tool output is null or blank, no correction needed");
            return Optional.empty();
        }

        int attemptNumber = currentAttempts + 1;

        // 2. 先尝试编译错误解析
        Optional<List<CorrectionInstruction.ParsedError>> compileErrors =
                compileErrorParser.parse(toolOutput);
        if (compileErrors.isPresent()) {
            List<CorrectionInstruction.ParsedError> errors = compileErrors.get();
            log.info("Detected {} compile error(s), generating correction (attempt {}/{})",
                    errors.size(), attemptNumber, MAX_ATTEMPTS);

            String instruction = generateCompileInstruction(errors);
            instruction = truncateToTokenLimit(instruction, MAX_INSTRUCTION_TOKENS);

            CorrectionInstruction.ParsedError first = errors.getFirst();
            CorrectionInstruction.ErrorContext ctx = new CorrectionInstruction.ErrorContext(
                    first.fileName(), first.lineNumber(), first.errorMessage(), "");

            return Optional.of(new CorrectionInstruction(
                    instruction, ctx, CorrectionInstruction.CorrectionType.COMPILE_ERROR, attemptNumber));
        }

        // 3. 若无编译错误，尝试测试失败解析
        Optional<List<CorrectionInstruction.ParsedTestFailure>> testFailures =
                testFailureParser.parse(toolOutput);
        if (testFailures.isPresent()) {
            List<CorrectionInstruction.ParsedTestFailure> failures = testFailures.get();
            log.info("Detected {} test failure(s), generating correction (attempt {}/{})",
                    failures.size(), attemptNumber, MAX_ATTEMPTS);

            String instruction = generateTestInstruction(failures);
            instruction = truncateToTokenLimit(instruction, MAX_INSTRUCTION_TOKENS);

            CorrectionInstruction.ParsedTestFailure first = failures.getFirst();
            String errorMsg = first.actual() != null
                    ? "Expected: " + first.expected() + ", Actual: " + first.actual()
                    : first.stackTrace() != null ? first.stackTrace() : "Test failed";
            CorrectionInstruction.ErrorContext ctx = new CorrectionInstruction.ErrorContext(
                    first.testName(), 0, errorMsg, "");

            return Optional.of(new CorrectionInstruction(
                    instruction, ctx, CorrectionInstruction.CorrectionType.TEST_FAILURE, attemptNumber));
        }

        // 4. 无可修复错误
        log.debug("No compile errors or test failures detected in tool output");
        return Optional.empty();
    }

    /**
     * 中止检查：判断修复是否引入了新错误。
     * <p>
     * 中止条件（满足任一即中止）:
     * <ul>
     *   <li>新输出的错误总数 > 旧输出的错误总数</li>
     *   <li>新输出中出现了旧输出中不存在的错误文件</li>
     *   <li>新输出中出现了旧输出中不存在的错误类型</li>
     * </ul>
     *
     * @param newToolOutput      修复后的工具输出
     * @param previousToolOutput 修复前的工具输出
     * @return true 表示应中止（引入了新错误）
     */
    public boolean shouldAbort(String newToolOutput, String previousToolOutput) {
        int newErrorCount = countTotalErrors(newToolOutput);
        int previousErrorCount = countTotalErrors(previousToolOutput);

        // 错误数量增加 → 中止
        if (newErrorCount > previousErrorCount) {
            log.warn("Error count increased from {} to {}, aborting correction",
                    previousErrorCount, newErrorCount);
            return true;
        }

        // 检查是否有新的错误文件
        Set<String> previousFiles = extractErrorFiles(previousToolOutput);
        Set<String> newFiles = extractErrorFiles(newToolOutput);
        Set<String> newlyIntroducedFiles = new HashSet<>(newFiles);
        newlyIntroducedFiles.removeAll(previousFiles);
        if (!newlyIntroducedFiles.isEmpty()) {
            log.warn("New error files introduced: {}, aborting correction", newlyIntroducedFiles);
            return true;
        }

        // 检查是否有新的错误类型（语言/框架）
        Set<String> previousTypes = extractErrorTypes(previousToolOutput);
        Set<String> newTypes = extractErrorTypes(newToolOutput);
        Set<String> newlyIntroducedTypes = new HashSet<>(newTypes);
        newlyIntroducedTypes.removeAll(previousTypes);
        if (!newlyIntroducedTypes.isEmpty()) {
            log.warn("New error types introduced: {}, aborting correction", newlyIntroducedTypes);
            return true;
        }

        log.debug("No new errors introduced, continuing correction (errors: {} → {})",
                previousErrorCount, newErrorCount);
        return false;
    }

    /**
     * 生成结构化编译错误修复指令。
     * <p>
     * 模板: [ERROR_CONTEXT] + [FILE_LOCATION] + [SUGGESTED_FIX_DIRECTION]
     *
     * @param errors 解析后的编译错误列表
     * @return 结构化修复指令文本
     */
    private String generateCompileInstruction(List<CorrectionInstruction.ParsedError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following compilation error(s) need to be fixed:\n\n");

        for (CorrectionInstruction.ParsedError error : errors) {
            sb.append("File: ").append(error.fileName())
              .append(", Line: ").append(error.lineNumber()).append('\n');
            sb.append("Error: ").append(error.errorMessage()).append('\n');
            sb.append("Language: ").append(error.language()).append("\n\n");
        }

        sb.append("Please fix these errors. Do not introduce new errors.");
        return sb.toString();
    }

    /**
     * 生成结构化测试失败修复指令。
     * <p>
     * 模板: [TEST_NAME] + [EXPECTED/ACTUAL] + [STACK_TRACE_SUMMARY]
     *
     * @param failures 解析后的测试失败列表
     * @return 结构化修复指令文本
     */
    private String generateTestInstruction(List<CorrectionInstruction.ParsedTestFailure> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following test(s) are failing:\n\n");

        for (CorrectionInstruction.ParsedTestFailure failure : failures) {
            sb.append("Test: ").append(failure.testName())
              .append(" (").append(failure.framework()).append(")\n");

            if (failure.expected() != null && failure.actual() != null) {
                sb.append("Expected: ").append(failure.expected()).append('\n');
                sb.append("Actual: ").append(failure.actual()).append('\n');
            }

            if (failure.stackTrace() != null && !failure.stackTrace().isBlank()) {
                String stackSummary = summarizeStackTrace(failure.stackTrace());
                sb.append("Stack: ").append(stackSummary).append('\n');
            }

            sb.append('\n');
        }

        sb.append("Please fix the code to make these tests pass. Do not break other tests.");
        return sb.toString();
    }

    /**
     * 截断文本到指定 token 数限制。
     * <p>
     * 使用 TokenCounter 估算 token 数，若超限则按比例截断并附加截断标记。
     *
     * @param text      原始文本
     * @param maxTokens 最大 token 数
     * @return 截断后的文本（若未超限则原样返回）
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        int estimated = tokenCounter.estimateTokens(text);
        if (estimated <= maxTokens) {
            return text;
        }

        log.debug("Instruction exceeds token limit ({} > {}), truncating", estimated, maxTokens);
        // 按比例截断，留 10% 安全余量
        double ratio = (double) maxTokens / estimated;
        int charLimit = (int) (text.length() * ratio * 0.9);
        return text.substring(0, charLimit) + "\n... [truncated due to token limit]";
    }

    /**
     * 提取堆栈摘要（前 N 行）。
     *
     * @param stackTrace 完整堆栈信息
     * @return 摘要（最多 {@value #MAX_STACK_TRACE_LINES} 行）
     */
    private String summarizeStackTrace(String stackTrace) {
        String[] lines = stackTrace.split("\n");
        if (lines.length <= MAX_STACK_TRACE_LINES) {
            return stackTrace;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_STACK_TRACE_LINES; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        sb.append("\n... (").append(lines.length - MAX_STACK_TRACE_LINES).append(" more lines)");
        return sb.toString();
    }

    /**
     * 统计工具输出中的总错误数量（编译错误 + 测试失败）。
     *
     * @param toolOutput 工具输出文本
     * @return 错误总数
     */
    private int countTotalErrors(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) return 0;

        int count = 0;
        Optional<List<CorrectionInstruction.ParsedError>> compileErrors =
                compileErrorParser.parse(toolOutput);
        if (compileErrors.isPresent()) {
            count += compileErrors.get().size();
        }

        Optional<List<CorrectionInstruction.ParsedTestFailure>> testFailures =
                testFailureParser.parse(toolOutput);
        if (testFailures.isPresent()) {
            count += testFailures.get().size();
        }

        return count;
    }

    /**
     * 从工具输出中提取所有出现错误的文件名集合。
     *
     * @param toolOutput 工具输出文本
     * @return 错误文件名集合
     */
    private Set<String> extractErrorFiles(String toolOutput) {
        Set<String> files = new HashSet<>();
        if (toolOutput == null || toolOutput.isBlank()) return files;

        compileErrorParser.parse(toolOutput).ifPresent(errors ->
                files.addAll(errors.stream()
                        .map(CorrectionInstruction.ParsedError::fileName)
                        .collect(Collectors.toSet())));

        testFailureParser.parse(toolOutput).ifPresent(failures ->
                files.addAll(failures.stream()
                        .map(CorrectionInstruction.ParsedTestFailure::testName)
                        .collect(Collectors.toSet())));

        return files;
    }

    /**
     * 从工具输出中提取所有错误类型（语言/框架）。
     *
     * @param toolOutput 工具输出文本
     * @return 错误类型集合（如 "java", "junit", "jest" 等）
     */
    private Set<String> extractErrorTypes(String toolOutput) {
        Set<String> types = new HashSet<>();
        if (toolOutput == null || toolOutput.isBlank()) return types;

        compileErrorParser.parse(toolOutput).ifPresent(errors ->
                types.addAll(errors.stream()
                        .map(CorrectionInstruction.ParsedError::language)
                        .collect(Collectors.toSet())));

        testFailureParser.parse(toolOutput).ifPresent(failures ->
                types.addAll(failures.stream()
                        .map(CorrectionInstruction.ParsedTestFailure::framework)
                        .collect(Collectors.toSet())));

        return types;
    }
}

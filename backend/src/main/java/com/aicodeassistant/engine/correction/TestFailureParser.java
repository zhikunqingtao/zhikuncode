package com.aicodeassistant.engine.correction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 测试失败解析器。
 * 支持解析 JUnit、Jest、pytest 测试失败输出。
 */
@Slf4j
@Component
public class TestFailureParser {

    /** 最大返回失败数量 */
    private static final int MAX_FAILURES = 5;

    // ===== JUnit 模式 =====
    /** JUnit 测试总结行: Tests run: N, Failures: N */
    private static final Pattern JUNIT_SUMMARY_PATTERN =
        Pattern.compile("Tests run: (\\d+), Failures: (\\d+)");

    /** JUnit 失败方法名: methodName(com.package.ClassName) */
    private static final Pattern JUNIT_FAILURE_METHOD_PATTERN =
        Pattern.compile("(\\w+)\\([\\w.]+\\)");

    /** JUnit 断言失败: expected:<xxx> but was:<yyy> */
    private static final Pattern JUNIT_ASSERTION_PATTERN =
        Pattern.compile("expected:<(.+?)> but was:<(.+?)>");

    // ===== Jest 模式 =====
    /** Jest 失败标记: FAIL path/to/file.ts */
    private static final Pattern JEST_FAIL_PATTERN =
        Pattern.compile("FAIL (.+\\.tsx?)");

    /** Jest 测试名: ● Test Suite > test name */
    private static final Pattern JEST_TEST_NAME_PATTERN =
        Pattern.compile("● (.+)");

    /** Jest 期望值: Expected: xxx */
    private static final Pattern JEST_EXPECTED_PATTERN =
        Pattern.compile("Expected: (.+)");

    /** Jest 实际值: Received: xxx */
    private static final Pattern JEST_RECEIVED_PATTERN =
        Pattern.compile("Received: (.+)");

    // ===== pytest 模式 =====
    /**
     * pytest 失败（主正则）: ^FAILED path/to/file.py::test_name[param-1] - reason
     * 使用 ^...$ + MULTILINE 锚定整行，避免参数化测试 [param] 中的 :: 触发错位匹配（方案 D2）。
     * group(1) 捕获完整 "file::test" 部分，需通过 parsePytestLine 二次拆分。
     */
    private static final Pattern PYTEST_FAILED_PATTERN =
        Pattern.compile("^FAILED (.+?) - ", Pattern.MULTILINE);

    /**
     * pytest 失败（兼容 fallback 正则，方案 D2 §9 风险回滚项）：
     * 当主正则因换行符差异等环境因素未命中时，退回到原始双 group 正则。
     */
    private static final Pattern PYTEST_FAILED_PATTERN_FALLBACK =
        Pattern.compile("FAILED (.+?)::(.+?) -");

    /** pytest 断言: AssertionError: message */
    private static final Pattern PYTEST_ASSERTION_PATTERN =
        Pattern.compile("AssertionError: (.+)");

    /** pytest assert 语句: assert xxx == yyy */
    private static final Pattern PYTEST_ASSERT_EQ_PATTERN =
        Pattern.compile("assert (.+) == (.+)");

    /**
     * 解析工具输出中的测试失败信息。
     *
     * @param toolOutput 测试运行的原始输出文本
     * @return 解析到的测试失败列表，无匹配时返回 Optional.empty()
     */
    public Optional<List<CorrectionInstruction.ParsedTestFailure>> parse(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            log.debug("Tool output is null or blank, skipping parse");
            return Optional.empty();
        }

        List<CorrectionInstruction.ParsedTestFailure> failures = new ArrayList<>();

        parseJUnitFailures(toolOutput, failures);
        parseJestFailures(toolOutput, failures);
        parsePytestFailures(toolOutput, failures);

        if (failures.isEmpty()) {
            log.debug("No test failures detected in tool output");
            return Optional.empty();
        }

        // 限制最多返回 MAX_FAILURES 个失败
        if (failures.size() > MAX_FAILURES) {
            log.info("Found {} test failures, truncating to {}", failures.size(), MAX_FAILURES);
            failures = failures.subList(0, MAX_FAILURES);
        }

        log.info("Parsed {} test failure(s) from tool output", failures.size());
        return Optional.of(failures);
    }

    /**
     * 解析 JUnit 测试失败
     */
    private void parseJUnitFailures(String output, List<CorrectionInstruction.ParsedTestFailure> failures) {
        // 首先检查是否有 JUnit 输出
        Matcher summaryMatcher = JUNIT_SUMMARY_PATTERN.matcher(output);
        if (!summaryMatcher.find()) {
            return;
        }
        int failureCount = Integer.parseInt(summaryMatcher.group(2));
        if (failureCount == 0) {
            return;
        }

        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && failures.size() < MAX_FAILURES; i++) {
            Matcher methodMatcher = JUNIT_FAILURE_METHOD_PATTERN.matcher(lines[i]);
            if (methodMatcher.find()) {
                String testName = methodMatcher.group(1);
                String expected = null;
                String actual = null;
                StringBuilder stackTrace = new StringBuilder();

                // 查找断言信息和堆栈（从当前行开始，因为断言可能在同一行）
                for (int j = i; j < lines.length && j <= i + 10; j++) {
                    if (expected == null) {
                        Matcher assertMatcher = JUNIT_ASSERTION_PATTERN.matcher(lines[j]);
                        if (assertMatcher.find()) {
                            expected = assertMatcher.group(1);
                            actual = assertMatcher.group(2);
                        }
                    }
                    if (lines[j].trim().startsWith("at ")) {
                        stackTrace.append(lines[j].trim()).append("\n");
                    }
                }

                if (expected != null || !stackTrace.isEmpty()) {
                    failures.add(new CorrectionInstruction.ParsedTestFailure(
                        testName, expected, actual,
                        stackTrace.toString().trim(), "junit"
                    ));
                    log.debug("JUnit failure: {} - expected={}, actual={}", testName, expected, actual);
                }
            }
        }
    }

    /**
     * 解析 Jest 测试失败
     */
    private void parseJestFailures(String output, List<CorrectionInstruction.ParsedTestFailure> failures) {
        // 首先检查是否有 Jest 输出
        Matcher failMatcher = JEST_FAIL_PATTERN.matcher(output);
        if (!failMatcher.find()) {
            return;
        }

        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && failures.size() < MAX_FAILURES; i++) {
            Matcher testNameMatcher = JEST_TEST_NAME_PATTERN.matcher(lines[i]);
            if (testNameMatcher.find()) {
                String testName = testNameMatcher.group(1).trim();
                String expected = null;
                String actual = null;
                StringBuilder stackTrace = new StringBuilder();

                // 向后查找 Expected/Received
                for (int j = i + 1; j < lines.length && j <= i + 15; j++) {
                    Matcher expectedMatcher = JEST_EXPECTED_PATTERN.matcher(lines[j]);
                    if (expectedMatcher.find()) {
                        expected = expectedMatcher.group(1).trim();
                    }
                    Matcher receivedMatcher = JEST_RECEIVED_PATTERN.matcher(lines[j]);
                    if (receivedMatcher.find()) {
                        actual = receivedMatcher.group(1).trim();
                    }
                    if (lines[j].trim().startsWith("at ")) {
                        stackTrace.append(lines[j].trim()).append("\n");
                    }
                }

                if (expected != null || actual != null) {
                    failures.add(new CorrectionInstruction.ParsedTestFailure(
                        testName, expected, actual,
                        stackTrace.toString().trim(), "jest"
                    ));
                    log.debug("Jest failure: {} - expected={}, received={}", testName, expected, actual);
                }
            }
        }
    }

    /**
     * 解析 pytest 测试失败（方案 D2）。
     * 主正则使用 ^FAILED ... -  + MULTILINE 锚定整行，再由 {@link #parsePytestLine} 二次拆分 file/test。
     * 若主正则未命中（例如换行符差异），自动回退到原正则（双正则 OR 匹配）。
     */
    private void parsePytestFailures(String output, List<CorrectionInstruction.ParsedTestFailure> failures) {
        int beforeSize = failures.size();

        Matcher failedMatcher = PYTEST_FAILED_PATTERN.matcher(output);
        while (failedMatcher.find() && failures.size() < MAX_FAILURES) {
            TestFailure parsed = parsePytestLine(failedMatcher.group(1));
            addPytestFailure(output, parsed.file, parsed.test, failures);
        }

        // Fallback：主正则无任何新增匹配时，退回到原正则避免漏匹配（§9 风险回滚项）
        if (failures.size() == beforeSize) {
            Matcher fallbackMatcher = PYTEST_FAILED_PATTERN_FALLBACK.matcher(output);
            while (fallbackMatcher.find() && failures.size() < MAX_FAILURES) {
                addPytestFailure(output, fallbackMatcher.group(1), fallbackMatcher.group(2), failures);
            }
        }
    }

    /**
     * 二次拆分主正则捕获的 "file::test" 字符串。
     * 使用 indexOf("::") 定位首个分隔符，将参数化测试名 [param-1] 中的 :: 留在 test 部分。
     *
     * @param matched 主正则 group(1) 捕获的完整字符串，例如 "tests/test_a.py::test_x[param-1]"
     * @return 拆分后的 file 与 test
     */
    private TestFailure parsePytestLine(String matched) {
        int idx = matched.indexOf("::");
        String file = idx > 0 ? matched.substring(0, idx) : matched;
        String test = idx > 0 ? matched.substring(idx + 2) : "";
        return new TestFailure(file, test);
    }

    /**
     * 在输出中查找该 pytest 失败对应的断言信息，并追加到 failures。
     */
    private void addPytestFailure(String output, String fileName, String testName,
                                  List<CorrectionInstruction.ParsedTestFailure> failures) {
        String expected = null;
        String actual = null;
        String assertionMessage = null;

        String[] lines = output.split("\n");
        for (String line : lines) {
            Matcher assertEqMatcher = PYTEST_ASSERT_EQ_PATTERN.matcher(line);
            if (assertEqMatcher.find()) {
                expected = assertEqMatcher.group(2).trim();
                actual = assertEqMatcher.group(1).trim();
                break;
            }
            Matcher assertionMatcher = PYTEST_ASSERTION_PATTERN.matcher(line);
            if (assertionMatcher.find()) {
                assertionMessage = assertionMatcher.group(1).trim();
            }
        }

        String stackInfo = assertionMessage != null ? assertionMessage : "";
        failures.add(new CorrectionInstruction.ParsedTestFailure(
            fileName + "::" + testName,
            expected,
            actual,
            stackInfo,
            "pytest"
        ));
        log.debug("pytest failure: {}::{} - expected={}, actual={}", fileName, testName, expected, actual);
    }

    /** 仅用于 pytest 主正则二次拆分的 file/test 容器。 */
    private static final class TestFailure {
        final String file;
        final String test;

        TestFailure(String file, String test) {
            this.file = file;
            this.test = test;
        }
    }
}

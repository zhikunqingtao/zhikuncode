package com.aicodeassistant.engine.correction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 编译错误解析器。
 * 支持解析 Java、TypeScript、Python 编译错误输出。
 */
@Slf4j
@Component
public class CompileErrorParser {

    /** 最大返回错误数量 */
    private static final int MAX_ERRORS = 5;

    /** Java 编译错误模式: file.java:line: error: message */
    private static final Pattern JAVA_ERROR_PATTERN =
        Pattern.compile("(.+\\.java):(\\d+): error: (.+)");

    /** TypeScript 编译错误模式: file.ts(line,col): error TSxxxx: message */
    private static final Pattern TYPESCRIPT_ERROR_PATTERN =
        Pattern.compile("(.+\\.tsx?)\\((\\d+),\\d+\\): error TS\\d+: (.+)");

    /** Python 语法错误模式: File "file.py", line N */
    private static final Pattern PYTHON_ERROR_PATTERN =
        Pattern.compile("File \"(.+\\.py)\", line (\\d+)");

    /**
     * 解析工具输出中的编译错误。
     *
     * @param toolOutput 编译工具的原始输出文本
     * @return 解析到的错误列表，无匹配时返回 Optional.empty()
     */
    public Optional<List<CorrectionInstruction.ParsedError>> parse(String toolOutput) {
        if (toolOutput == null || toolOutput.isBlank()) {
            log.debug("Tool output is null or blank, skipping parse");
            return Optional.empty();
        }

        List<CorrectionInstruction.ParsedError> errors = new ArrayList<>();

        parseJavaErrors(toolOutput, errors);
        parseTypeScriptErrors(toolOutput, errors);
        parsePythonErrors(toolOutput, errors);

        if (errors.isEmpty()) {
            log.debug("No compile errors detected in tool output");
            return Optional.empty();
        }

        // 限制最多返回 MAX_ERRORS 个错误
        if (errors.size() > MAX_ERRORS) {
            log.info("Found {} compile errors, truncating to {}", errors.size(), MAX_ERRORS);
            errors = errors.subList(0, MAX_ERRORS);
        }

        log.info("Parsed {} compile error(s) from tool output", errors.size());
        return Optional.of(errors);
    }

    /**
     * 解析 Java 编译错误
     */
    private void parseJavaErrors(String output, List<CorrectionInstruction.ParsedError> errors) {
        Matcher matcher = JAVA_ERROR_PATTERN.matcher(output);
        while (matcher.find() && errors.size() < MAX_ERRORS) {
            String fileName = matcher.group(1);
            int lineNumber = Integer.parseInt(matcher.group(2));
            String errorMessage = matcher.group(3);
            errors.add(new CorrectionInstruction.ParsedError(
                fileName, lineNumber, errorMessage, "java"
            ));
            log.debug("Java error: {}:{} - {}", fileName, lineNumber, errorMessage);
        }
    }

    /**
     * 解析 TypeScript 编译错误
     */
    private void parseTypeScriptErrors(String output, List<CorrectionInstruction.ParsedError> errors) {
        Matcher matcher = TYPESCRIPT_ERROR_PATTERN.matcher(output);
        while (matcher.find() && errors.size() < MAX_ERRORS) {
            String fileName = matcher.group(1);
            int lineNumber = Integer.parseInt(matcher.group(2));
            String errorMessage = matcher.group(3);
            errors.add(new CorrectionInstruction.ParsedError(
                fileName, lineNumber, errorMessage, "typescript"
            ));
            log.debug("TypeScript error: {}:{} - {}", fileName, lineNumber, errorMessage);
        }
    }

    /**
     * 解析 Python 编译/语法错误。
     * Python 错误格式为多行，第一行包含文件名和行号，后续行包含错误信息。
     */
    private void parsePythonErrors(String output, List<CorrectionInstruction.ParsedError> errors) {
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && errors.size() < MAX_ERRORS; i++) {
            Matcher matcher = PYTHON_ERROR_PATTERN.matcher(lines[i]);
            if (matcher.find()) {
                String fileName = matcher.group(1);
                int lineNumber = Integer.parseInt(matcher.group(2));
                // 错误信息通常在后续行
                String errorMessage = extractPythonErrorMessage(lines, i);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    errors.add(new CorrectionInstruction.ParsedError(
                        fileName, lineNumber, errorMessage, "python"
                    ));
                    log.debug("Python error: {}:{} - {}", fileName, lineNumber, errorMessage);
                }
            }
        }
    }

    /**
     * 从 Python 错误输出的后续行中提取错误消息。
     * 通常格式为:
     *   File "xxx.py", line N
     *       some_code
     *   ErrorType: message
     */
    private String extractPythonErrorMessage(String[] lines, int fileLineIndex) {
        // 向后查找以大写字母开头且包含冒号的行（如 SyntaxError: xxx）
        for (int j = fileLineIndex + 1; j < lines.length && j <= fileLineIndex + 4; j++) {
            String line = lines[j].trim();
            if (line.matches("^[A-Z]\\w*Error:.*") || line.matches("^[A-Z]\\w*Exception:.*")) {
                return line;
            }
        }
        // 退而求其次，返回紧随的非空行
        if (fileLineIndex + 1 < lines.length) {
            String next = lines[fileLineIndex + 1].trim();
            if (!next.isBlank()) {
                return next;
            }
        }
        return null;
    }
}

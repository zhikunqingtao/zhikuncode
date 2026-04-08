package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文附件解析器 — 解析用户输入中的 @file 引用。
 * <p>
 * 支持的引用格式:
 * <ul>
 *     <li>{@code @path/to/file} — 引用整个文件</li>
 *     <li>{@code @path/to/file:L10-L20} — 引用文件的第 10-20 行</li>
 *     <li>{@code @path/to/file:L10} — 引用文件的第 10 行</li>
 * </ul>
 * <p>
 * 安全检查:
 * <ul>
 *     <li>路径遍历防护 (拒绝 ../ 路径)</li>
 *     <li>文件大小限制 (默认最大 1MB)</li>
 *     <li>仅允许工作目录内的文件</li>
 * </ul>
 *
 * @see <a href="SPEC section 3.1.0">UserInputProcessor — 附件解析</a>
 */
@Service
public class ContextAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(ContextAttachmentResolver.class);

    /** @file 引用正则: @path/to/file 或 @path/to/file:L10-L20 */
    private static final Pattern FILE_REF_PATTERN =
            Pattern.compile("@([\\w./_-]+(?:\\.[\\w]+))(?::L(\\d+)(?:-(\\d+))?)?");

    /** 最大文件大小 (1MB) */
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    /** 最大附件数量 */
    private static final int MAX_ATTACHMENTS = 20;

    /**
     * 解析用户输入中的所有 @file 引用。
     *
     * @param rawInput       原始用户输入
     * @param workingDirectory 当前工作目录
     * @return 解析后的附件列表
     */
    public List<ProcessedInput.ContextAttachment> resolve(String rawInput, Path workingDirectory) {
        if (rawInput == null || rawInput.isEmpty()) {
            return List.of();
        }

        List<ProcessedInput.ContextAttachment> attachments = new ArrayList<>();
        Matcher matcher = FILE_REF_PATTERN.matcher(rawInput);

        while (matcher.find() && attachments.size() < MAX_ATTACHMENTS) {
            String filePath = matcher.group(1);
            String startLineStr = matcher.group(2);
            String endLineStr = matcher.group(3);

            try {
                ProcessedInput.ContextAttachment attachment = resolveFile(
                        filePath, startLineStr, endLineStr, workingDirectory);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve @file reference: @{} — {}", filePath, e.getMessage());
            }
        }

        return attachments;
    }

    /**
     * 从用户输入中移除 @file 引用，返回清理后的文本。
     */
    public String stripFileReferences(String rawInput) {
        if (rawInput == null) return null;
        return FILE_REF_PATTERN.matcher(rawInput).replaceAll("").trim().replaceAll("\\s+", " ");
    }

    /**
     * 解析单个文件引用。
     */
    private ProcessedInput.ContextAttachment resolveFile(
            String filePath, String startLineStr, String endLineStr,
            Path workingDirectory) throws IOException {

        // 安全检查: 路径遍历防护
        if (filePath.contains("..")) {
            log.warn("Path traversal detected in @file reference: {}", filePath);
            return null;
        }

        Path resolvedPath = workingDirectory.resolve(filePath).normalize();

        // 安全检查: 确保在工作目录内
        if (!resolvedPath.startsWith(workingDirectory.normalize())) {
            log.warn("@file reference escapes working directory: {}", filePath);
            return null;
        }

        // 检查文件存在性
        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            log.debug("@file reference not found: {}", filePath);
            return null;
        }

        // 文件大小检查
        long fileSize = Files.size(resolvedPath);
        if (fileSize > MAX_FILE_SIZE) {
            log.warn("@file reference too large: {} ({} bytes, max {})",
                    filePath, fileSize, MAX_FILE_SIZE);
            return null;
        }

        // 读取文件内容
        if (startLineStr != null) {
            // 行范围引用
            int startLine = Integer.parseInt(startLineStr);
            int endLine = endLineStr != null ? Integer.parseInt(endLineStr) : startLine;
            return readLineRange(resolvedPath, filePath, startLine, endLine);
        } else {
            // 整个文件
            String content = Files.readString(resolvedPath);
            return ProcessedInput.ContextAttachment.ofFile(filePath, content);
        }
    }

    /**
     * 读取文件的指定行范围。
     */
    private ProcessedInput.ContextAttachment readLineRange(
            Path path, String filePath, int startLine, int endLine) throws IOException {

        List<String> allLines = Files.readAllLines(path);
        int start = Math.max(1, startLine);
        int end = Math.min(allLines.size(), endLine);

        if (start > allLines.size()) {
            return null;
        }

        List<String> selectedLines = allLines.subList(start - 1, end);
        String content = String.join("\n", selectedLines);

        return ProcessedInput.ContextAttachment.ofRange(filePath, content, start, end);
    }
}

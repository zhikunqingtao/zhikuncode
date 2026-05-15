package com.aicodeassistant.lsp.model;

/**
 * 调用位置信息 — 表示代码中的一个调用关系位置。
 * <p>
 * 用于 callHierarchy/incomingCalls、callHierarchy/outgoingCalls、textDocument/implementation
 * 等 LSP 操作的返回结果。
 *
 * @param filePath       调用所在文件的绝对路径
 * @param startLine      起始行 (1-based)
 * @param startCharacter 起始列 (1-based)
 * @param endLine        结束行 (1-based)
 * @param endCharacter   结束列 (1-based)
 * @param symbolName     符号名称 (如方法名、函数名)
 * @param containerName  所在容器名称 (如类名、模块名)
 */
public record CallLocation(
        String filePath,
        int startLine,
        int startCharacter,
        int endLine,
        int endCharacter,
        String symbolName,
        String containerName
) {

    /**
     * 格式化为人类可读的位置描述。
     */
    public String toDisplayString() {
        String file = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        String container = containerName != null && !containerName.isEmpty()
                ? containerName + "."
                : "";
        return String.format("%s%s (%s:%d:%d)", container, symbolName, file, startLine, startCharacter);
    }
}

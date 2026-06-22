package com.aicodeassistant.mcp.roots;

/**
 * MCP Roots 描述符，表示一个允许 MCP 服务器访问的文件系统根。
 *
 * @param uri  文件系统 URI，格式如 "file:///Users/user/project"
 * @param name 根目录的可读名称（通常为项目名）
 */
public record RootDescriptor(String uri, String name) {

    /**
     * 从绝对文件路径创建 RootDescriptor。
     *
     * @param absolutePath 绝对路径，例如 "/Users/foo/project"
     * @param projectName  项目可读名称
     */
    public static RootDescriptor fromPath(String absolutePath, String projectName) {
        String normalized = absolutePath == null ? "" : absolutePath;
        // 兼容 Windows / Unix 路径，统一为 file:// + 绝对路径
        String uri = normalized.startsWith("/")
                ? "file://" + normalized
                : "file:///" + normalized.replace('\\', '/');
        return new RootDescriptor(uri, projectName);
    }
}

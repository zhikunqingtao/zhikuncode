package com.aicodeassistant.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * LSP 服务器配置 — 每种语言的启动参数。
 * <p>
 * 启动策略: 按需启动（首次打开对应文件时初始化）。
 *
 * @param name            服务器名称 (如 "tsserver")
 * @param command         启动命令
 * @param args            命令参数
 * @param initOptions     LSP initialize 请求的 initializationOptions
 * @param settings        workspace/configuration 返回的设置
 * @param fileExtensions  支持的文件扩展名
 * @param startupTimeoutMs 启动超时 (默认 30000ms)
 * @param requestTimeoutMs 请求超时 (默认 30000ms)
 * @param idleShutdownMs  空闲关闭时间 (默认 300000ms = 5min)
 * @see <a href="SPEC §4.1.4">LSPTool</a>
 */
public record LSPServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, Object> initOptions,
        Map<String, Object> settings,
        List<String> fileExtensions,
        int startupTimeoutMs,
        int requestTimeoutMs,
        int idleShutdownMs
) {

    /** TypeScript 服务器 */
    public static LSPServerConfig typescript() {
        return new LSPServerConfig(
                "tsserver",
                "npx", List.of("typescript-language-server", "--stdio"),
                Map.of("typescript", Map.of("tsdk", "./node_modules/typescript/lib")),
                Map.of(),
                List.of(".ts", ".tsx", ".js", ".jsx"),
                30000, 30000, 300000
        );
    }

    /** Python 服务器 (pyright) */
    public static LSPServerConfig python() {
        return new LSPServerConfig(
                "pyright",
                "npx", List.of("pyright-langserver", "--stdio"),
                Map.of("python", Map.of("analysis",
                        Map.of("autoSearchPaths", true, "useLibraryCodeForTypes", true))),
                Map.of(),
                List.of(".py", ".pyi"),
                30000, 30000, 300000
        );
    }

    /** Go 服务器 */
    public static LSPServerConfig go() {
        return new LSPServerConfig(
                "gopls",
                "gopls", List.of("serve"),
                Map.of("gopls", Map.of("staticcheck", true, "usePlaceholders", true)),
                Map.of(),
                List.of(".go"),
                30000, 30000, 300000
        );
    }

    /** Rust 服务器 */
    public static LSPServerConfig rust() {
        return new LSPServerConfig(
                "rust-analyzer",
                "rust-analyzer", List.of(),
                Map.of("rust-analyzer", Map.of("checkOnSave", Map.of("command", "clippy"))),
                Map.of(),
                List.of(".rs"),
                30000, 30000, 300000
        );
    }

    /** Java 服务器 */
    public static LSPServerConfig java(Path workspace) {
        return new LSPServerConfig(
                "jdtls",
                "jdtls", List.of("-data", workspace.resolve(".jdtls-data").toString()),
                Map.of("java", Map.of("home", System.getenv().getOrDefault("JAVA_HOME", ""))),
                Map.of(),
                List.of(".java"),
                60000, 30000, 300000  // Java 启动较慢
        );
    }
}

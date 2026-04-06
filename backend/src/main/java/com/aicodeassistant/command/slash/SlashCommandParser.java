package com.aicodeassistant.command.slash;

/**
 * 斜杠命令解析器 — 解析用户输入的 /command 格式。
 * <p>
 * 解析规则 (对照源码 slashCommandParsing.ts):
 * <ol>
 *     <li>去除前导 /</li>
 *     <li>按空格分割</li>
 *     <li>第二个词为 "(MCP)" → isMcp=true</li>
 *     <li>剩余词拼接为 args</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3.4a.2">斜杠命令解析</a>
 */
public final class SlashCommandParser {

    private SlashCommandParser() {}

    /**
     * 解析结果。
     */
    public record ParsedSlashCommand(
            String commandName,
            String args,
            boolean isMcp
    ) {}

    /**
     * 解析斜杠命令输入。
     *
     * @param input 用户输入，如 "/help" 或 "/compact focus on API design"
     * @return 解析结果，如果输入不是有效的斜杠命令则返回 null
     */
    public static ParsedSlashCommand parse(String input) {
        if (input == null || input.isBlank()) return null;

        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) return null;

        // 去除前导 /
        String withoutSlash = trimmed.substring(1);
        if (withoutSlash.isBlank()) return null;

        // 按空格分割
        String[] parts = withoutSlash.split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String rawArgs = parts.length > 1 ? parts[1] : "";

        // 检查是否为 MCP 命令
        boolean isMcp = false;
        if (rawArgs.startsWith("(MCP)")) {
            isMcp = true;
            rawArgs = rawArgs.substring(5).trim();
        }

        return new ParsedSlashCommand(commandName, rawArgs.trim(), isMcp);
    }

    /**
     * 检查输入是否为斜杠命令。
     */
    public static boolean isSlashCommand(String input) {
        if (input == null || input.isBlank()) return false;
        String trimmed = input.trim();
        return trimmed.startsWith("/") && trimmed.length() > 1
                && Character.isLetterOrDigit(trimmed.charAt(1));
    }
}

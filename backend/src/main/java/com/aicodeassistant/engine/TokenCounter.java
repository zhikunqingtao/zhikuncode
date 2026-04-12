package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 计数器 — 多层精度估算。
 * <p>
 * 三层精度策略（对齐 Claude Code）：
 * 1. 粗略估算: 基于字符数的快速估算（默认）
 * 2. 文件类型调整: 根据内容类型使用不同系数
 * 3. 精确计算: 通过 Python tiktoken 服务（可选，关键路径使用）
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法</a>
 */
@Component
public class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);

    /** 默认每 token 字符数（英文约4，中文约2，混合取3.5） */
    private static final double DEFAULT_CHARS_PER_TOKEN = 3.5;

    /** JSON 内容每 token 字符数（JSON 结构化更紧凑） */
    private static final double JSON_CHARS_PER_TOKEN = 2.0;

    /** 代码内容每 token 字符数 */
    private static final double CODE_CHARS_PER_TOKEN = 3.5;

    /** 自然语言每 token 字符数 */
    private static final double NATURAL_LANGUAGE_CHARS_PER_TOKEN = 4.0;

    /** 中文内容每 token 字符数 */
    private static final double CHINESE_CHARS_PER_TOKEN = 2.0;

    // ===== 公开 API =====

    /**
     * 估算消息列表的总 token 数。
     */
    public int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        int totalChars = 0;
        for (Message msg : messages) {
            totalChars += estimateMessageChars(msg);
        }
        // 消息边界开销: 每条消息约 4 token
        return (int) (totalChars / DEFAULT_CHARS_PER_TOKEN) + messages.size() * 4;
    }

    /**
     * 估算单条文本的 token 数（自动检测内容类型）。
     * <p>
     * 向后兼容: 原有调用点无需修改，内部已升级为自动检测逻辑。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) (text.length() / detectCharsPerToken(text));
    }

    /**
     * 带内容类型提示的 token 估算。
     *
     * @param text        文本内容
     * @param contentType 内容类型提示（"json", "code", "java", "python",
     *                    "javascript", "typescript", "text", "markdown"）
     * @return 估算的 token 数
     */
    public int estimateTokens(String text, String contentType) {
        if (text == null || text.isEmpty()) return 0;
        double charsPerToken = switch (contentType != null ? contentType.toLowerCase() : "") {
            case "json" -> JSON_CHARS_PER_TOKEN;
            case "code", "java", "python", "javascript", "typescript" -> CODE_CHARS_PER_TOKEN;
            case "text", "markdown" -> NATURAL_LANGUAGE_CHARS_PER_TOKEN;
            default -> detectCharsPerToken(text);
        };
        return (int) (text.length() / charsPerToken);
    }

    /**
     * 估算图片 token 数 — 基于实际尺寸计算。
     * <p>
     * 公式: ceil(width * height / 750)
     * 参考 Claude 图片 token 计算规则。
     *
     * @param width  图片宽度(像素)
     * @param height 图片高度(像素)
     * @return 估算的 token 数
     */
    public int estimateImageTokens(int width, int height) {
        if (width <= 0 || height <= 0) return 85; // 回退到默认值
        return (int) Math.ceil((double) (width * height) / 750.0);
    }

    /**
     * 检测文本内容类型。
     *
     * @param text 待检测文本
     * @return 内容类型标识: "json", "code", "chinese", "text"
     */
    public String detectContentType(String text) {
        if (text == null || text.length() < 10) return "text";

        String trimmed = text.trim();

        // 检测 JSON
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return "json";
        }

        // 检测中文占比
        long chineseChars = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        double chineseRatio = (double) chineseChars / text.length();
        if (chineseRatio > 0.3) {
            return "chinese";
        }

        // 检测代码特征
        if (looksLikeCode(trimmed)) {
            return "code";
        }

        return "text";
    }

    // ===== 内部方法 =====

    /**
     * 自动检测内容类型并返回合适的字符/token比率。
     */
    private double detectCharsPerToken(String text) {
        if (text.length() < 10) return DEFAULT_CHARS_PER_TOKEN;

        // 检测是否为 JSON
        String trimmed = text.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return JSON_CHARS_PER_TOKEN;
        }

        // 检测中文占比
        long chineseChars = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        double chineseRatio = (double) chineseChars / text.length();
        if (chineseRatio > 0.3) {
            // 混合内容：按中文比例加权
            return CHINESE_CHARS_PER_TOKEN * chineseRatio
                    + DEFAULT_CHARS_PER_TOKEN * (1 - chineseRatio);
        }

        // 检测代码特征
        if (looksLikeCode(trimmed)) {
            return CODE_CHARS_PER_TOKEN;
        }

        return DEFAULT_CHARS_PER_TOKEN;
    }

    /**
     * 启发式检测文本是否看起来像代码。
     * 检查常见代码特征: 花括号、分号、import/function/class 关键字等。
     */
    private boolean looksLikeCode(String text) {
        // 取前 500 字符进行检测，避免大文本性能问题
        String sample = text.length() > 500 ? text.substring(0, 500) : text;

        int codeIndicators = 0;

        // 花括号和分号密度
        long braces = sample.chars().filter(c -> c == '{' || c == '}').count();
        long semicolons = sample.chars().filter(c -> c == ';').count();
        if (braces > 2 || semicolons > 3) codeIndicators++;

        // 常见代码关键字
        if (sample.contains("import ") || sample.contains("function ")
                || sample.contains("class ") || sample.contains("def ")
                || sample.contains("public ") || sample.contains("private ")
                || sample.contains("const ") || sample.contains("let ")
                || sample.contains("var ") || sample.contains("return ")) {
            codeIndicators++;
        }

        // 缩进模式（连续空格开头行）
        long indentedLines = sample.lines()
                .filter(line -> line.startsWith("    ") || line.startsWith("\t"))
                .count();
        if (indentedLines > 3) codeIndicators++;

        return codeIndicators >= 2;
    }

    /**
     * 估算单条消息的字符数。
     */
    private int estimateMessageChars(Message message) {
        return switch (message) {
            case Message.UserMessage user -> {
                int chars = 0;
                if (user.content() != null) {
                    for (ContentBlock block : user.content()) {
                        chars += estimateBlockChars(block);
                    }
                }
                if (user.toolUseResult() != null) {
                    chars += user.toolUseResult().length();
                }
                yield chars;
            }
            case Message.AssistantMessage assistant -> {
                int chars = 0;
                if (assistant.content() != null) {
                    for (ContentBlock block : assistant.content()) {
                        chars += estimateBlockChars(block);
                    }
                }
                yield chars;
            }
            case Message.SystemMessage system -> {
                yield system.content() != null ? system.content().length() : 0;
            }
        };
    }

    /**
     * 估算内容块的字符数（增强版）。
     */
    private int estimateBlockChars(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text ->
                    text.text() != null ? text.text().length() : 0;
            case ContentBlock.ToolUseBlock toolUse ->
                    (toolUse.name() != null ? toolUse.name().length() : 0)
                    + (toolUse.input() != null ? toolUse.input().toString().length() : 0)
                    + 20; // JSON 结构开销
            case ContentBlock.ToolResultBlock result ->
                    (result.content() != null ? result.content().length() : 0) + 10;
            case ContentBlock.ImageBlock image -> {
                // 使用精确的图片 token 计算（如果有尺寸信息）
                // image block 的 chars 等效 = token数 * 默认系数
                int tokens = estimateImageTokens(image.width(), image.height());
                yield (int) (tokens * DEFAULT_CHARS_PER_TOKEN);
            }
            case ContentBlock.ThinkingBlock thinking ->
                    thinking.thinking() != null ? thinking.thinking().length() : 0;
            case ContentBlock.RedactedThinkingBlock redacted -> 10;
        };
    }
}

package com.aicodeassistant.tool.bash;

import org.springframework.stereotype.Component;

/**
 * BashOutputProcessor — 智能截断命令输出，保留错误上下文。
 * <p>
 * 当输出超过 MAX_OUTPUT_CHARS 时，根据是否有错误决定截断策略：
 * <ul>
 *   <li>有错误: 优先保留尾部（错误信息通常在尾部）</li>
 *   <li>无错误: 保留头部和尾部，中间用省略标记</li>
 * </ul>
 */
@Component
public class BashOutputProcessor {

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int HEAD_CHARS = 10_000;
    private static final int TAIL_CHARS = 15_000;

    /**
     * 智能截断输出。
     * <p>
     * 保留头部和尾部（错误通常在尾部），中间用省略标记。
     *
     * @param rawOutput 原始输出
     * @param hasError  是否包含错误（exitCode != 0）
     * @return 处理后的输出
     */
    public String processOutput(String rawOutput, boolean hasError) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        // 未超过限制，直接返回
        if (rawOutput.length() <= MAX_OUTPUT_CHARS) {
            return rawOutput;
        }

        int totalLength = rawOutput.length();
        int truncatedChars = totalLength - HEAD_CHARS - TAIL_CHARS;

        if (hasError) {
            // 有错误时，优先保留尾部（错误信息在尾部）
            // 尾部保留更多: HEAD_CHARS (10k) + TAIL_CHARS (15k)
            String head = rawOutput.substring(0, HEAD_CHARS);
            String tail = rawOutput.substring(totalLength - TAIL_CHARS);
            return head
                    + "\n\n...[truncated " + truncatedChars + " chars, showing head + tail (error context preserved)]...\n\n"
                    + tail;
        } else {
            // 无错误时，均匀截断 — 头尾各保留一半
            int halfChars = MAX_OUTPUT_CHARS / 2;
            String head = rawOutput.substring(0, halfChars);
            String tail = rawOutput.substring(totalLength - halfChars);
            int uniformTruncated = totalLength - halfChars * 2;
            return head
                    + "\n\n...[truncated " + uniformTruncated + " chars]...\n\n"
                    + tail;
        }
    }
}

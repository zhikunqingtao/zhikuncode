package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snip 服务 — 对工具结果进行截断，保留首尾、省略中间。
 * <p>
 * 对标原版 toolLimits.ts + toolResultStorage.ts 四层截断策略。
 *
 * @see <a href="SPEC §3.1.6">压缩级联</a>
 */
@Service
public class SnipService {

    private static final Logger log = LoggerFactory.getLogger(SnipService.class);

    // 常量 (对齐原版 toolLimits.ts)
    private static final int DEFAULT_MAX_RESULT_SIZE_CHARS = 50_000;
    private static final int MAX_TOOL_RESULT_TOKENS = 100_000;
    private static final int BYTES_PER_TOKEN = 4;
    private static final int MAX_TOOL_RESULT_BYTES = MAX_TOOL_RESULT_TOKENS * BYTES_PER_TOKEN;
    private static final int MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000;

    private static final String SNIP_MARKER = "\n[... snipped %d characters ...]\n";
    private static final String TRUNCATION_MARKER = "\n\n[... content truncated (%d/%d chars) ...]\n\n";
    private static final String PERSISTED_OUTPUT_TAG = "<persisted-output>";

    /**
     * 策略 1: 单工具结果截断（保留头尾）。
     *
     * @param content     原始内容
     * @param budgetChars 字符预算
     * @return 截断后的内容 (≤ budgetChars)
     */
    public String snipIfNeeded(String content, int budgetChars) {
        if (content == null || content.length() <= budgetChars) return content;
        if (budgetChars <= 0) return "";

        int headSize = (int) (budgetChars * 0.5);
        int tailSize = budgetChars - headSize - 80; // 预留标记空间
        if (tailSize <= 0) {
            return content.substring(0, budgetChars);
        }

        String head = content.substring(0, headSize);
        String tail = content.substring(content.length() - tailSize);

        return head
                + String.format(TRUNCATION_MARKER, content.length(), budgetChars)
                + tail;
    }

    /**
     * 策略 2: 单消息内多工具结果总和限制。
     * 当总字符超过 MAX_TOOL_RESULTS_PER_MESSAGE_CHARS 时，
     * 按大小降序对最大的结果依次截断。
     */
    public List<ContentBlock> budgetToolResults(List<ContentBlock> blocks) {
        int totalChars = blocks.stream()
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .mapToInt(b -> ((ContentBlock.ToolResultBlock) b).content() != null
                        ? ((ContentBlock.ToolResultBlock) b).content().length() : 0)
                .sum();
        if (totalChars <= MAX_TOOL_RESULTS_PER_MESSAGE_CHARS) return blocks;

        // 按大小降序，对最大的结果依次截断
        List<ContentBlock.ToolResultBlock> sorted = blocks.stream()
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> (ContentBlock.ToolResultBlock) b)
                .sorted(Comparator.comparingInt(
                        b -> -(b.content() != null ? b.content().length() : 0)))
                .toList();

        Set<String> truncatedIds = new HashSet<>();
        int running = totalChars;
        for (ContentBlock.ToolResultBlock trb : sorted) {
            if (running <= MAX_TOOL_RESULTS_PER_MESSAGE_CHARS) break;
            int len = trb.content() != null ? trb.content().length() : 0;
            truncatedIds.add(trb.toolUseId());
            running -= len;
            // 持久化到磁盘
            persistToolResult(trb.toolUseId(), trb.content());
        }

        // 替换已截断的结果为预览 + 文件路径
        return blocks.stream().map(b -> {
            if (b instanceof ContentBlock.ToolResultBlock trb
                    && truncatedIds.contains(trb.toolUseId())) {
                String preview = snipIfNeeded(trb.content(), 200);
                String replacement = preview + "\n" + PERSISTED_OUTPUT_TAG
                        + getPersistedPath(trb.toolUseId());
                return (ContentBlock) new ContentBlock.ToolResultBlock(
                        trb.toolUseId(), replacement, trb.isError());
            }
            return b;
        }).toList();
    }

    /**
     * 遍历消息列表，对所有工具结果应用 Snip。
     *
     * @param messages    消息列表
     * @param budgetChars 每条工具结果的字符预算
     * @return 截断后的消息列表 (新 List，不修改原列表)
     */
    public List<Message> snipToolResults(List<Message> messages, int budgetChars) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage user
                    && user.toolUseResult() != null
                    && user.toolUseResult().length() > budgetChars) {
                String snipped = snipIfNeeded(user.toolUseResult(), budgetChars);
                result.add(new Message.UserMessage(
                        user.uuid(), user.timestamp(), user.content(),
                        snipped, user.sourceToolAssistantUUID()));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    // ==================== 持久化辅助 ====================

    private void persistToolResult(String toolUseId, String content) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "zhikun-tool-results");
            Files.createDirectories(dir);
            Path file = dir.resolve(toolUseId + ".txt");
            Files.writeString(file, content != null ? content : "");
            log.debug("Persisted tool result: {} ({} chars)", file, 
                    content != null ? content.length() : 0);
        } catch (IOException e) {
            log.warn("Failed to persist tool result {}: {}", toolUseId, e.getMessage());
        }
    }

    private String getPersistedPath(String toolUseId) {
        return Path.of(System.getProperty("java.io.tmpdir"), 
                "zhikun-tool-results", toolUseId + ".txt").toString();
    }
}

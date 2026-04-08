package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Snip 服务 — 对工具结果进行截断，保留首尾、省略中间。
 * <p>
 * 对标原版 query.ts applyToolResultBudget()。
 * 单条工具结果超出 budgetChars 时截断中间，插入 snip 标记。
 *
 * @see <a href="SPEC §3.1.6">压缩级联</a>
 */
@Service
public class SnipService {

    private static final String SNIP_MARKER = "\n[... snipped %d characters ...]\n";

    /**
     * 对单条文本进行 Snip 截断。
     * 保留首尾各 ~50%，中间插入 snip 标记。
     *
     * @param content     原始内容
     * @param budgetChars 字符预算
     * @return 截断后的内容 (≤ budgetChars)
     */
    public String snipIfNeeded(String content, int budgetChars) {
        if (content == null || content.length() <= budgetChars) return content;
        if (budgetChars <= 0) return "";

        // 先预估 marker 开销
        int snippedCount = content.length() - budgetChars;
        String marker = String.format(SNIP_MARKER, snippedCount);
        int markerOverhead = marker.length();
        int available = budgetChars - markerOverhead;

        if (available <= 0) {
            // 预算太小，直接截断尾部
            return content.substring(0, budgetChars);
        }

        int headSize = available / 2;
        int tailSize = available - headSize;
        String head = content.substring(0, headSize);
        String tail = content.substring(content.length() - tailSize);
        int actualSnipped = content.length() - headSize - tailSize;

        return head + String.format(SNIP_MARKER, actualSnipped) + tail;
    }

    /**
     * 遍历消息列表，对所有工具结果应用 Snip。
     * <p>
     * 注意: Message sealed interface 中工具结果存储在 UserMessage.toolUseResult()。
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
}

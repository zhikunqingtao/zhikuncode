package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;

/**
 * 两阶段 Token 预算守卫。
 * <p>
 * Phase1: 清理历史消息中的 Base64 图片内容，降低上下文 token 消耗。
 * Phase2: 最终 payload 校验，在超限时执行梯度降级策略。
 */
@Slf4j
@Component
public class TokenBudgetGuard {

    private static final double TEXT_TOKEN_RATIO = 3.5;
    private static final int BASE64_LENGTH_THRESHOLD = 40_000;
    private static final int PROTECT_TAIL_COUNT = 2;
    private static final List<String> BASE64_PREFIXES = List.of(
            "/9j/", "iVBOR", "R0lGOD", "UklGR", "PHN2"
    );

    // ==================== 返回类型 Records ====================

    public record GuardResult(
            List<Message> messages,
            boolean trimmed,
            int tokensBefore,
            int tokensAfter
    ) {}

    public record FinalBudgetResult(
            List<Map<String, Object>> apiMessages,
            Set<String> retainedImageHashes,
            int estimatedTokens,
            int inputBudget,
            boolean fitsBudget,
            String reductionSummary
    ) {}

    // ==================== Phase 1: 历史 Base64 清理 ====================

    /**
     * Phase1 — 深拷贝消息列表，清理历史消息中的 Base64 内容。
     * <p>
     * 第一遍保护尾部2条消息，第二遍（若仍超限）清理全部。
     */
    public GuardResult enforcePhase1(List<Message> messages, int inputBudget) {
        int tokensBefore = estimateTokens(messages);
        if (tokensBefore <= inputBudget) {
            return new GuardResult(new ArrayList<>(messages), false, tokensBefore, tokensBefore);
        }

        // 第一遍: protectTail=true, 跳过最后2条
        List<Message> cleaned = deepCleanBase64(messages, true);
        int tokensAfterFirstPass = estimateTokens(cleaned);

        if (tokensAfterFirstPass <= inputBudget) {
            log.info("Phase1 first pass sufficient: {} -> {} tokens (budget={})",
                    tokensBefore, tokensAfterFirstPass, inputBudget);
            return new GuardResult(cleaned, true, tokensBefore, tokensAfterFirstPass);
        }

        // 第二遍: protectTail=false, 清理所有
        cleaned = deepCleanBase64(messages, false);
        int tokensAfterSecondPass = estimateTokens(cleaned);
        log.info("Phase1 second pass: {} -> {} tokens (budget={})",
                tokensBefore, tokensAfterSecondPass, inputBudget);
        return new GuardResult(cleaned, true, tokensBefore, tokensAfterSecondPass);
    }

    // ==================== Phase 2: 最终 Payload 校验与梯度降级 ====================

    /**
     * Phase2 — 对已转换的 API 格式消息执行最终 token 预算校验。
     * 超限时按梯度降级策略依次尝试。
     */
    public FinalBudgetResult enforcePhase2(List<Map<String, Object>> apiMessages, int inputBudget) {
        int estimated = estimateApiTokens(apiMessages);

        if (estimated <= inputBudget) {
            Set<String> hashes = collectImageHashes(apiMessages);
            return new FinalBudgetResult(apiMessages, hashes, estimated, inputBudget, true, "无需降级");
        }

        log.warn("Phase2 超限: estimated={} budget={}, 开始梯度降级", estimated, inputBudget);
        List<Map<String, Object>> current = deepCopyApiMessages(apiMessages);
        StringBuilder summary = new StringBuilder();

        // 策略1: 缩略图替换 (640px, quality=0.6)
        current = replaceImagesWithThumbnails(current, 640, 0.6f);
        estimated = estimateApiTokens(current);
        summary.append("策略1-缩略图替换;");
        if (estimated <= inputBudget) {
            Set<String> hashes = collectImageHashes(current);
            return new FinalBudgetResult(current, hashes, estimated, inputBudget, true, summary.toString());
        }

        // 策略2: 替换图片为文本摘要
        current = replaceImagesWithTextSummary(current);
        estimated = estimateApiTokens(current);
        summary.append("策略2-图片替换为文本;");
        if (estimated <= inputBudget) {
            Set<String> hashes = collectImageHashes(current);
            return new FinalBudgetResult(current, hashes, estimated, inputBudget, true, summary.toString());
        }

        // 策略3: 移除所有注入的图片
        current = removeAllInjectedImages(current);
        estimated = estimateApiTokens(current);
        summary.append("策略3-移除所有图片;");
        Set<String> hashes = collectImageHashes(current);
        boolean fits = estimated <= inputBudget;
        if (!fits) {
            log.error("Phase2 所有降级策略执行后仍超限: estimated={} budget={}", estimated, inputBudget);
        }
        return new FinalBudgetResult(current, hashes, estimated, inputBudget, fits, summary.toString());
    }

    // ==================== Phase1 辅助方法 ====================

    private List<Message> deepCleanBase64(List<Message> messages, boolean protectTail) {
        List<Message> result = new ArrayList<>(messages.size());
        int protectFrom = protectTail ? Math.max(0, messages.size() - PROTECT_TAIL_COUNT) : messages.size();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i >= protectFrom) {
                // 受保护的尾部消息，直接保留
                result.add(msg);
                continue;
            }
            result.add(cleanMessageBase64(msg));
        }
        return result;
    }

    private Message cleanMessageBase64(Message msg) {
        if (msg instanceof Message.UserMessage user) {
            List<ContentBlock> cleanedBlocks = cleanContentBlocks(user.content());
            String cleanedToolResult = cleanToolUseResult(user.toolUseResult());
            return new Message.UserMessage(
                    user.uuid(), user.timestamp(), cleanedBlocks,
                    cleanedToolResult, user.sourceToolAssistantUUID());
        } else if (msg instanceof Message.AssistantMessage assistant) {
            List<ContentBlock> cleanedBlocks = cleanContentBlocks(assistant.content());
            return new Message.AssistantMessage(
                    assistant.uuid(), assistant.timestamp(), cleanedBlocks,
                    assistant.stopReason(), assistant.usage());
        }
        // SystemMessage 只有 String content，通常不含 Base64
        return msg;
    }

    private List<ContentBlock> cleanContentBlocks(List<ContentBlock> blocks) {
        if (blocks == null) return null;
        List<ContentBlock> result = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.ImageBlock img) {
                // ImageBlock 的 base64Data 替换为占位文本
                String placeholder = String.format("[image content removed - was %d chars]",
                        img.base64Data() != null ? img.base64Data().length() : 0);
                result.add(new ContentBlock.TextBlock(placeholder));
            } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                if (trb.content() != null && isBase64Content(trb.content())) {
                    String placeholder = String.format("[image content removed - was %d chars]",
                            trb.content().length());
                    result.add(new ContentBlock.ToolResultBlock(trb.toolUseId(), placeholder, trb.isError()));
                } else {
                    result.add(trb);
                }
            } else if (block instanceof ContentBlock.TextBlock tb) {
                if (tb.text() != null && isBase64Content(tb.text())) {
                    String placeholder = String.format("[image content removed - was %d chars]",
                            tb.text().length());
                    result.add(new ContentBlock.TextBlock(placeholder));
                } else {
                    result.add(tb);
                }
            } else {
                result.add(block);
            }
        }
        return result;
    }

    private String cleanToolUseResult(String toolUseResult) {
        if (toolUseResult == null) return null;
        if (isBase64Content(toolUseResult)) {
            return String.format("[image content removed - was %d chars]", toolUseResult.length());
        }
        return toolUseResult;
    }

    // ==================== Phase2 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> replaceImagesWithThumbnails(
            List<Map<String, Object>> apiMessages, int maxDim, float quality) {
        List<Map<String, Object>> result = new ArrayList<>(apiMessages.size());
        for (Map<String, Object> msg : apiMessages) {
            Object contentObj = msg.get("content");
            if (contentObj instanceof List<?> contentList) {
                List<Map<String, Object>> newContent = new ArrayList<>();
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        Map<String, Object> blockMap = (Map<String, Object>) block;
                        if ("image".equals(blockMap.get("type"))) {
                            Map<String, Object> resized = resizeImageBlock(blockMap, maxDim, quality);
                            newContent.add(resized);
                        } else {
                            newContent.add(new LinkedHashMap<>(blockMap));
                        }
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            } else {
                result.add(new LinkedHashMap<>(msg));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resizeImageBlock(Map<String, Object> block, int maxDim, float quality) {
        try {
            Object source = block.get("source");
            if (!(source instanceof Map<?, ?>)) return new LinkedHashMap<>(block);
            Map<String, Object> sourceMap = (Map<String, Object>) source;
            String data = (String) sourceMap.get("data");
            if (data == null || data.isEmpty()) return new LinkedHashMap<>(block);

            byte[] imageBytes = Base64.getDecoder().decode(data);
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) return new LinkedHashMap<>(block);

            // 计算缩放尺寸
            int w = original.getWidth();
            int h = original.getHeight();
            if (w <= maxDim && h <= maxDim) {
                return new LinkedHashMap<>(block); // 已经够小
            }
            double scale = Math.min((double) maxDim / w, (double) maxDim / h);
            int newW = Math.max(1, (int) (w * scale));
            int newH = Math.max(1, (int) (h * scale));

            // 缩放
            BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, newW, newH, null);
            g.dispose();

            // JPEG 编码
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(resized, null, null), param);
            writer.dispose();
            ios.close();

            String newData = Base64.getEncoder().encodeToString(baos.toByteArray());

            Map<String, Object> newSource = new LinkedHashMap<>(sourceMap);
            newSource.put("data", newData);
            newSource.put("media_type", "image/jpeg");

            Map<String, Object> newBlock = new LinkedHashMap<>(block);
            newBlock.put("source", newSource);
            return newBlock;
        } catch (Exception e) {
            log.warn("缩略图生成失败，保留原始: {}", e.getMessage());
            return new LinkedHashMap<>(block);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> replaceImagesWithTextSummary(List<Map<String, Object>> apiMessages) {
        List<Map<String, Object>> result = new ArrayList<>(apiMessages.size());
        for (Map<String, Object> msg : apiMessages) {
            Object contentObj = msg.get("content");
            if (contentObj instanceof List<?> contentList) {
                List<Map<String, Object>> newContent = new ArrayList<>();
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        Map<String, Object> blockMap = (Map<String, Object>) block;
                        if ("image".equals(blockMap.get("type"))) {
                            Map<String, Object> textBlock = new LinkedHashMap<>();
                            textBlock.put("type", "text");
                            textBlock.put("text", "[图片已移除以减少上下文]");
                            newContent.add(textBlock);
                        } else {
                            newContent.add(new LinkedHashMap<>(blockMap));
                        }
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            } else {
                result.add(new LinkedHashMap<>(msg));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> removeAllInjectedImages(List<Map<String, Object>> apiMessages) {
        List<Map<String, Object>> result = new ArrayList<>(apiMessages.size());
        for (Map<String, Object> msg : apiMessages) {
            Object contentObj = msg.get("content");
            if (contentObj instanceof List<?> contentList) {
                List<Map<String, Object>> newContent = new ArrayList<>();
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        Map<String, Object> blockMap = (Map<String, Object>) block;
                        if (!"image".equals(blockMap.get("type"))) {
                            newContent.add(new LinkedHashMap<>(blockMap));
                        }
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            } else {
                result.add(new LinkedHashMap<>(msg));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<String> collectImageHashes(List<Map<String, Object>> apiMessages) {
        Set<String> hashes = new HashSet<>();
        for (Map<String, Object> msg : apiMessages) {
            Object contentObj = msg.get("content");
            if (contentObj instanceof List<?> contentList) {
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        Map<String, Object> blockMap = (Map<String, Object>) block;
                        if ("image".equals(blockMap.get("type"))) {
                            Object source = blockMap.get("source");
                            if (source instanceof Map<?, ?> sourceMap) {
                                String data = (String) ((Map<String, Object>) sourceMap).get("data");
                                if (data != null && !data.isEmpty()) {
                                    String prefix = data.substring(0, Math.min(100, data.length()));
                                    hashes.add(String.valueOf(prefix.hashCode()));
                                }
                            }
                        }
                    }
                }
            }
        }
        return hashes;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deepCopyApiMessages(List<Map<String, Object>> apiMessages) {
        List<Map<String, Object>> result = new ArrayList<>(apiMessages.size());
        for (Map<String, Object> msg : apiMessages) {
            Map<String, Object> copy = new LinkedHashMap<>(msg);
            Object contentObj = copy.get("content");
            if (contentObj instanceof List<?> contentList) {
                List<Map<String, Object>> newContent = new ArrayList<>();
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        newContent.add(new LinkedHashMap<>((Map<String, Object>) block));
                    }
                }
                copy.put("content", newContent);
            }
            result.add(copy);
        }
        return result;
    }

    // ==================== Token 估算 ====================

    /**
     * 估算消息列表的 token 数。
     * 文本: length / 3.5, Base64: 1:1
     */
    int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage user) {
                total += estimateContentBlocksTokens(user.content());
                if (user.toolUseResult() != null) {
                    total += estimateStringTokens(user.toolUseResult());
                }
            } else if (msg instanceof Message.AssistantMessage assistant) {
                total += estimateContentBlocksTokens(assistant.content());
            } else if (msg instanceof Message.SystemMessage system) {
                if (system.content() != null) {
                    total += (int) (system.content().length() / TEXT_TOKEN_RATIO);
                }
            }
        }
        return total;
    }

    private int estimateContentBlocksTokens(List<ContentBlock> blocks) {
        if (blocks == null) return 0;
        int total = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock tb) {
                total += tb.text() != null ? (int) (tb.text().length() / TEXT_TOKEN_RATIO) : 0;
            } else if (block instanceof ContentBlock.ImageBlock img) {
                total += img.base64Data() != null ? img.base64Data().length() : 0;
            } else if (block instanceof ContentBlock.ToolResultBlock trb) {
                total += trb.content() != null ? estimateStringTokens(trb.content()) : 0;
            } else if (block instanceof ContentBlock.ToolUseBlock tub) {
                total += tub.input() != null ? (int) (tub.input().toString().length() / TEXT_TOKEN_RATIO) : 0;
            } else if (block instanceof ContentBlock.ThinkingBlock thk) {
                total += thk.thinking() != null ? (int) (thk.thinking().length() / TEXT_TOKEN_RATIO) : 0;
            }
        }
        return total;
    }

    private int estimateStringTokens(String content) {
        if (content == null) return 0;
        if (isBase64Content(content)) {
            return content.length(); // 1:1
        }
        return (int) (content.length() / TEXT_TOKEN_RATIO);
    }

    /**
     * 估算 API 格式消息的 token 数。
     */
    @SuppressWarnings("unchecked")
    int estimateApiTokens(List<Map<String, Object>> apiMessages) {
        int total = 0;
        for (Map<String, Object> msg : apiMessages) {
            Object contentObj = msg.get("content");
            if (contentObj instanceof String text) {
                total += (int) (text.length() / TEXT_TOKEN_RATIO);
            } else if (contentObj instanceof List<?> contentList) {
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> block) {
                        Map<String, Object> blockMap = (Map<String, Object>) block;
                        String type = (String) blockMap.get("type");
                        if ("text".equals(type)) {
                            String text = (String) blockMap.get("text");
                            if (text != null) {
                                total += (int) (text.length() / TEXT_TOKEN_RATIO);
                            }
                        } else if ("image".equals(type)) {
                            Object source = blockMap.get("source");
                            if (source instanceof Map<?, ?> sourceMap) {
                                String data = (String) ((Map<String, Object>) sourceMap).get("data");
                                if (data != null) {
                                    total += data.length(); // 1:1
                                }
                            }
                        } else if ("tool_result".equals(type)) {
                            Object trContent = blockMap.get("content");
                            if (trContent instanceof String s) {
                                if (isBase64Content(s)) {
                                    total += s.length(); // 1:1
                                } else {
                                    total += (int) (s.length() / TEXT_TOKEN_RATIO);
                                }
                            }
                        } else {
                            // tool_use, thinking 等其他类型按文本估算
                            String text = blockMap.toString();
                            total += (int) (text.length() / TEXT_TOKEN_RATIO);
                        }
                    }
                }
            }
        }
        return total;
    }

    // ==================== 通用辅助 ====================

    /**
     * 检查内容是否为 Base64 编码的图片数据。
     * 匹配常见 Base64 前缀，或长度超过阈值时采样验证字符集。
     */
    private boolean isBase64Content(String content) {
        if (content == null || content.isEmpty()) return false;

        // 1. 先检查已知图片 Base64 前缀（高置信度，直接返回）
        for (String prefix : BASE64_PREFIXES) {
            if (content.startsWith(prefix)) return true;
        }

        // 2. 长度超过阈值时，进一步验证是否为 Base64 字符集
        if (content.length() > BASE64_LENGTH_THRESHOLD) {
            return isLikelyBase64(content);
        }

        return false;
    }

    /**
     * 采样验证内容是否符合 Base64 字符集。
     * 取前 1000 个字符，如果 >95% 都是合法 Base64 字符则判定为 Base64。
     */
    private boolean isLikelyBase64(String content) {
        int sampleSize = Math.min(1000, content.length());
        int validChars = 0;
        for (int i = 0; i < sampleSize; i++) {
            char c = content.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                validChars++;
            } else if (c == '\n' || c == '\r') {
                // Base64 可能包含换行，不计入采样总数
                sampleSize = Math.min(sampleSize + 1, content.length());
            } else {
                // 非 Base64 字符
            }
        }
        // 有效 Base64 字符占比超过 95% 才判定为 Base64
        return sampleSize > 0 && (double) validChars / sampleSize > 0.95;
    }
}

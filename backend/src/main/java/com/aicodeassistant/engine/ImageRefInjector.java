package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.ContentBlock.ImageBlock;
import com.aicodeassistant.model.ContentBlock.ToolResultBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Message.AssistantMessage;
import com.aicodeassistant.model.Message.SystemMessage;
import com.aicodeassistant.model.Message.UserMessage;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.impl.ImageResultExternalizer.ImageToolRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * ImageRefInjector — 解析消息中的 [image_ref]...[/image_ref] 引用标记，
 * 在 API 调用前按需从磁盘读取图片并临时注入为 ImageBlock。
 */
@Slf4j
@Component
public class ImageRefInjector {

    private static final Set<String> SUPPORTED_MIMES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10MB
    private static final int MAX_IMAGES_PER_CALL = 5;
    private static final long MAX_SINGLE_IMAGE_BYTES = 1_500_000L; // 单张图片最多 1.5MB Base64
    private static final long MAX_TOTAL_INJECT_BYTES = 2_000_000L; // 总量最多 2MB Base64
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private final ObjectMapper objectMapper;
    private final PathSecurityService pathSecurityService;

    public ImageRefInjector(ObjectMapper objectMapper, PathSecurityService pathSecurityService) {
        this.objectMapper = objectMapper;
        this.pathSecurityService = pathSecurityService;
    }

    /**
     * 注入结果记录。
     *
     * @param messages      注入后的消息列表（深拷贝）
     * @param pendingHashes 本次尝试注入但尚未确认的哈希集合
     */
    public record InjectResult(List<Message> messages, Set<String> pendingHashes) {}

    /**
     * 候选引用记录，记录每个待注入图片的位置信息。
     */
    private record CandidateRef(int msgIndex, int blockIndex, ImageToolRef ref) {}

    /**
     * 核心方法：扫描消息中的图片引用并注入 ImageBlock。
     * 采用三阶段策略：收集 → 选取（优先最新） → 注入。
     *
     * @param messages         原始消息列表
     * @param runStartIndex    仅扫描 index >= runStartIndex 的消息
     * @param remainingBudget  剩余 token 预算（用于 base64 长度限制）
     * @param confirmedHashes  已确认的图片哈希集合
     * @param workingDirectory 工作目录（路径安全校验用）
     * @return InjectResult 包含深拷贝后的消息列表和 pending 哈希集合
     */
    public InjectResult injectForApiCall(List<Message> messages, int runStartIndex,
                                         int remainingBudget, Set<String> confirmedHashes,
                                         String workingDirectory) {
        return injectForApiCall(messages,runStartIndex,remainingBudget,confirmedHashes,
                new HashMap<>(),workingDirectory);
    }

    public InjectResult injectForApiCall(List<Message> messages, int runStartIndex,
                                         int remainingBudget, Set<String> confirmedHashes,
                                         Map<String,Integer> rejectedBudgetByHash,
                                         String workingDirectory) {
        List<Message> result = deepCopyMessages(messages);
        Set<String> pendingHashes = new HashSet<>();

        // ========== 阶段1：正向扫描，收集所有候选引用 ==========
        List<CandidateRef> candidates = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>(); // 用于候选去重
        for (int i = runStartIndex; i < result.size(); i++) {
            Message msg = result.get(i);
            List<ContentBlock> content = getContent(msg);
            if (content == null) continue;

            for (int blockIdx = 0; blockIdx < content.size(); blockIdx++) {
                ContentBlock block = content.get(blockIdx);
                if (block instanceof ToolResultBlock toolResult) {
                    String toolContent = toolResult.content();
                    if (toolContent != null && toolContent.contains("[image_ref]")) {
                        Optional<ImageToolRef> refOpt = parseRef(toolContent);
                        if (refOpt.isPresent()) {
                            ImageToolRef ref = refOpt.get();
                            // 去重：已确认的或本轮候选中已出现的，跳过
                            if (confirmedHashes.contains(ref.sha256()) || seenHashes.contains(ref.sha256())) {
                                continue;
                            }
                            Integer rejectedAt = rejectedBudgetByHash.get(ref.sha256());
                            if (rejectedAt != null && remainingBudget <= rejectedAt) continue;
                            seenHashes.add(ref.sha256());
                            candidates.add(new CandidateRef(i, blockIdx, ref));
                        }
                    }
                }
            }
        }

        // ========== 阶段2：从后向前选取（优先最新），直到达到限额 ==========
        List<CandidateRef> selected = new ArrayList<>();
        long estimatedBytes = 0;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (selected.size() >= MAX_IMAGES_PER_CALL) break;
            CandidateRef c = candidates.get(i);
            long estimatedSize = (long) Math.ceil(c.ref().fileSize() * 4.0 / 3.0);
            if (estimatedSize > MAX_SINGLE_IMAGE_BYTES || estimatedSize > remainingBudget) {
                rejectedBudgetByHash.put(c.ref().sha256(), remainingBudget); continue;
            }
            if (estimatedBytes + estimatedSize > MAX_TOTAL_INJECT_BYTES
                    || estimatedBytes + estimatedSize > remainingBudget) {
                rejectedBudgetByHash.put(c.ref().sha256(), remainingBudget); continue;
            }
            selected.add(c);
            estimatedBytes += estimatedSize;
        }
        Collections.reverse(selected); // 恢复消息顺序（正向）

        // ========== 阶段3：对 selected 执行实际注入 ==========
        // 构建 selected 的快速查找：msgIndex → blockIndex set
        Map<Integer, Set<Integer>> selectedMap = new HashMap<>();
        Map<String, CandidateRef> selectedByHash = new HashMap<>();
        for (CandidateRef c : selected) {
            selectedMap.computeIfAbsent(c.msgIndex(), k -> new HashSet<>()).add(c.blockIndex());
            selectedByHash.put(c.ref().sha256(), c);
        }

        int budgetLeft = remainingBudget;
        long actualInjectedBytes = 0;

        for (int i = runStartIndex; i < result.size(); i++) {
            Message msg = result.get(i);
            List<ContentBlock> content = getContent(msg);
            if (content == null) continue;

            Set<Integer> selectedBlocks = selectedMap.get(i);
            if (selectedBlocks == null || selectedBlocks.isEmpty()) continue;

            List<ContentBlock> newContent = new ArrayList<>();
            boolean modified = false;

            for (int blockIdx = 0; blockIdx < content.size(); blockIdx++) {
                ContentBlock block = content.get(blockIdx);
                newContent.add(block);

                if (selectedBlocks.contains(blockIdx) && block instanceof ToolResultBlock toolResult) {
                    String toolContent = toolResult.content();
                    if (toolContent != null && toolContent.contains("[image_ref]")) {
                        Optional<ImageToolRef> refOpt = parseRef(toolContent);
                        if (refOpt.isPresent()) {
                            ImageToolRef ref = refOpt.get();
                            ContentBlock imageBlock = validateAndLoad(ref, budgetLeft, workingDirectory);
                            if (imageBlock != null && imageBlock instanceof ImageBlock ib) {
                                int dataLen = ib.base64Data().length();
                                // 二次硬校验：单张超限
                                if (dataLen > MAX_SINGLE_IMAGE_BYTES) {
                                    log.debug("Image single limit exceeded: size={} > max={}",
                                            dataLen, MAX_SINGLE_IMAGE_BYTES);
                                    rejectedBudgetByHash.put(ref.sha256(), remainingBudget); continue;
                                }
                                // 二次硬校验：累计超限
                                if (actualInjectedBytes + dataLen > MAX_TOTAL_INJECT_BYTES) {
                                    log.debug("Image injection total limit reached: actual={} + new={} > max={}",
                                            actualInjectedBytes, dataLen, MAX_TOTAL_INJECT_BYTES);
                                    rejectedBudgetByHash.put(ref.sha256(), remainingBudget);
                                    continue; // 跳过此图片，block 已在 newContent 中
                                }
                                newContent.add(imageBlock);
                                pendingHashes.add(ref.sha256());
                                budgetLeft -= dataLen;
                                actualInjectedBytes += dataLen;
                                modified = true;
                            } else {
                                // Integrity/path failures may be retried only if the budget changes;
                                // file-change detection creates a new content hash.
                                rejectedBudgetByHash.put(ref.sha256(), remainingBudget);
                            }
                        }
                    }
                }
            }

            if (modified) {
                result.set(i, rebuildMessage(msg, newContent));
            }
        }

        return new InjectResult(result, pendingHashes);
    }

    /**
     * 解析文本中的 [image_ref]...[/image_ref] 引用。
     */
    Optional<ImageToolRef> parseRef(String content) {
        int start = content.indexOf("[image_ref]");
        if (start < 0) return Optional.empty();
        int end = content.indexOf("[/image_ref]", start);
        if (end < 0) return Optional.empty();
        String json = content.substring(start + "[image_ref]".length(), end);
        try {
            return Optional.of(objectMapper.readValue(json, ImageToolRef.class));
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse image_ref JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 校验并加载图片为 ImageBlock。任何步骤失败则静默降级返回 null。
     */
    ContentBlock validateAndLoad(ImageToolRef ref, int budgetTokens, String workingDirectory) {
        try {
            if (ref.width() <= 0 || ref.height() <= 0
                    || (long) ref.width() * ref.height() > MAX_IMAGE_PIXELS) {
                log.debug("Image dimensions rejected: {}x{}", ref.width(), ref.height());
                return null;
            }
            // ① 路径安全检查
            PathSecurityService.PathCheckResult checkResult =
                    pathSecurityService.checkReadPermission(ref.path(), workingDirectory);
            if (!checkResult.isAllowed()) {
                log.debug("Image ref path security denied: {}", ref.path());
                return null;
            }

            Path path = Path.of(ref.path());

            // ② 文件存在检查
            if (!Files.isRegularFile(path)) {
                log.debug("Image ref file not found or not regular: {}", ref.path());
                return null;
            }

            // ③ 文件大小检查
            try {
                long size = Files.size(path);
                if (size > MAX_FILE_SIZE) {
                    log.debug("Image ref file too large: {} bytes (max {})", size, MAX_FILE_SIZE);
                    return null;
                }
                if (size != ref.fileSize()) {
                    log.debug("Image ref file size mismatch: expected={}, actual={}", ref.fileSize(), size);
                    return null;
                }
            } catch (IOException e) {
                log.debug("Failed to get file size for {}: {}", ref.path(), e.getMessage());
                return null;
            }

            // ④ SHA-256 完整性校验
            String actualSha256 = computeSha256(path);
            if (actualSha256.isEmpty() || !actualSha256.equals(ref.sha256())) {
                log.debug("Image ref SHA-256 mismatch for {}: expected={}, actual={}",
                        ref.path(), ref.sha256(), actualSha256);
                return null;
            }

            // ⑤ MIME 兼容性检查与编码
            String mimeType = ref.mimeType();
            String base64Data;

            if (SUPPORTED_MIMES.contains(mimeType)) {
                // 直接读取编码
                try {
                    byte[] fileBytes = Files.readAllBytes(path);
                    base64Data = Base64.getEncoder().encodeToString(fileBytes);
                } catch (IOException e) {
                    log.debug("Failed to read image file {}: {}", ref.path(), e.getMessage());
                    return null;
                }
            } else if ("image/bmp".equals(mimeType)) {
                // BMP → 转换为 PNG 后编码
                try {
                    BufferedImage image = ImageIO.read(path.toFile());
                    if (image == null) {
                        log.debug("Failed to decode BMP image: {}", ref.path());
                        return null;
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "PNG", baos);
                    base64Data = Base64.getEncoder().encodeToString(baos.toByteArray());
                    mimeType = "image/png"; // 转换后 MIME 变为 PNG
                } catch (IOException e) {
                    log.debug("Failed to convert BMP to PNG for {}: {}", ref.path(), e.getMessage());
                    return null;
                }
            } else {
                // svg, tiff, heic 等不支持的格式
                log.debug("Unsupported MIME type for image injection: {}", mimeType);
                return null;
            }

            // ⑥ 预算检查
            if (base64Data.length() > budgetTokens) {
                log.debug("Image base64 exceeds budget: size={}, budget={}", base64Data.length(), budgetTokens);
                return null;
            }

            return new ImageBlock(mimeType, base64Data, ref.width(), ref.height());
        } catch (InvalidPathException | NullPointerException | SecurityException e) {
            // 路径格式非法或安全拒绝，跳过此引用
            log.debug("Image ref invalid path [{}]: {}", ref.path(), e.getMessage());
            return null;
        } catch (Exception e) {
            // 其他意外异常也不应终止整个注入
            log.debug("Image ref unexpected error [{}]: {}", ref.path(), e.getMessage());
            return null;
        }
    }

    /**
     * 流式计算文件 SHA-256，8KB 缓冲区，返回小写 hex 字符串。
     */
    String computeSha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            return "";
        } catch (IOException e) {
            log.debug("Failed to compute SHA-256 for {}: {}", path, e.getMessage());
            return "";
        }
    }

    // ==================== 私有辅助方法 ====================

    private List<Message> deepCopyMessages(List<Message> messages) {
        List<Message> copy = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            copy.add(deepCopyMessage(msg));
        }
        return copy;
    }

    private Message deepCopyMessage(Message msg) {
        if (msg instanceof UserMessage um) {
            List<ContentBlock> contentCopy = um.content() != null
                    ? new ArrayList<>(um.content()) : null;
            return new UserMessage(um.uuid(), um.timestamp(), contentCopy,
                    MessageContentAccessor.rawLegacyToolResult(um), um.sourceToolAssistantUUID());
        } else if (msg instanceof AssistantMessage am) {
            List<ContentBlock> contentCopy = am.content() != null
                    ? new ArrayList<>(am.content()) : null;
            return new AssistantMessage(am.uuid(), am.timestamp(), contentCopy,
                    am.stopReason(), am.usage());
        } else if (msg instanceof SystemMessage sm) {
            return new SystemMessage(sm.uuid(), sm.timestamp(), sm.content(), sm.type());
        }
        return msg;
    }

    private List<ContentBlock> getContent(Message msg) {
        if (msg instanceof UserMessage um) {
            return um.content();
        } else if (msg instanceof AssistantMessage am) {
            return am.content();
        }
        return null;
    }

    private Message rebuildMessage(Message msg, List<ContentBlock> newContent) {
        if (msg instanceof UserMessage um) {
            return new UserMessage(um.uuid(), um.timestamp(), newContent,
                    MessageContentAccessor.rawLegacyToolResult(um), um.sourceToolAssistantUUID());
        } else if (msg instanceof AssistantMessage am) {
            return new AssistantMessage(am.uuid(), am.timestamp(), newContent,
                    am.stopReason(), am.usage());
        }
        return msg;
    }
}

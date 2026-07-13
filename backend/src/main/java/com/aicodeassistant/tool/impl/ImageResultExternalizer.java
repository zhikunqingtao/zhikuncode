package com.aicodeassistant.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/**
 * ImageResultExternalizer — 将大图片文件替换为 JSON 引用文本。
 * <p>
 * 避免 Base64 进入消息历史导致 token 超限。
 * 当图片文件超过阈值时，生成 [image_ref]...[/image_ref] 格式的引用。
 */
@Component
public class ImageResultExternalizer {

    private static final Logger log = LoggerFactory.getLogger(ImageResultExternalizer.class);

    public static final long EXTERNALIZE_THRESHOLD = 50 * 1024L; // 50KB

    private final ObjectMapper objectMapper;

    public ImageResultExternalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 图片工具引用记录，用于序列化为 JSON 嵌入消息。
     */
    public record ImageToolRef(String path, String mimeType, long fileSize, String sha256, int width, int height) {}

    /**
     * 将图片文件外置化为 JSON 引用文本。
     *
     * @param filePath 图片文件路径
     * @param mimeType MIME 类型
     * @param fileSize 文件大小（字节）
     * @return [image_ref]json[/image_ref] 格式的引用文本
     */
    public String externalize(Path filePath, String mimeType, long fileSize) {
        String sha256 = computeSha256(filePath);

        // 获取图片尺寸（仅读取头部元数据，不解码像素），异常时降级为 (0, 0)
        int[] dims = getImageDimensions(filePath);
        int width = dims[0];
        int height = dims[1];

        ImageToolRef ref = new ImageToolRef(
                filePath.toString(), mimeType, fileSize, sha256, width, height);

        try {
            String json = objectMapper.writeValueAsString(ref);
            return "[image_ref]" + json + "[/image_ref]";
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ImageToolRef for {}: {}", filePath, e.getMessage());
            // fallback: 手动拼接包含所有字段的引用
            return "[image_ref]{\"path\":\"" + filePath.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"mimeType\":\"" + mimeType
                    + "\",\"fileSize\":" + fileSize
                    + ",\"sha256\":\"" + sha256
                    + "\",\"width\":" + width
                    + ",\"height\":" + height + "}[/image_ref]";
        }
    }

    /**
     * 仅读取图片头部元数据获取尺寸，避免完整解码像素数据导致 OOM。
     *
     * @param filePath 图片文件路径
     * @return [width, height]，异常时返回 [0, 0]
     */
    private int[] getImageDimensions(Path filePath) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(filePath.toFile())) {
            if (iis == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    int w = reader.getWidth(0);
                    int h = reader.getHeight(0);

                    // 像素上限保护：超过 1 亿像素视为异常
                    if ((long) w * h > 100_000_000L) {
                        return new int[]{0, 0};
                    }

                    return new int[]{w, h};
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read image dimensions for {}: {}", filePath, e.getMessage());
        }
        return new int[]{0, 0};
    }

    /**
     * 流式计算文件 SHA-256 哈希值。
     *
     * @param path 文件路径
     * @return 小写 hex 字符串，异常时返回空字符串
     */
    private String computeSha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                byte[] buffer = new byte[8192]; // 8KB 缓冲区
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
            // SHA-256 is guaranteed to be available in all JVM implementations
            log.error("SHA-256 algorithm not available", e);
            return "";
        } catch (IOException e) {
            log.warn("Failed to compute SHA-256 for {}: {}", path, e.getMessage());
            return "";
        }
    }
}

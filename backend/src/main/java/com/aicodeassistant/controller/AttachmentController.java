package com.aicodeassistant.controller;

import com.aicodeassistant.util.ContentDispositionEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 附件上传 Controller — 处理前端文件/图片上传。
 *
 * <p>存储规则：payload 永远使用 UUID 文件名（至多附加安全后缀），原始显示名保存在
 * {@code .metadata/<uuid>.json}。元数据仅用于下载时的 Content-Disposition 显示名，
 * 绝不参与路径解析、文件定位或访问授权。</p>
 *
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final String METADATA_DIR_NAME = ".metadata";
    private static final String TEMP_FILE_PREFIX = ".upload-";

    private final Path uploadDir;
    private final Path metadataDir;
    private final ObjectMapper objectMapper;

    public AttachmentController(
            @Value("${app.upload-dir:${user.home}/.zhikun/uploads}") String dir,
            ObjectMapper objectMapper) {
        this.uploadDir = Path.of(dir);
        this.metadataDir = uploadDir.resolve(METADATA_DIR_NAME);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(uploadDir);
        Files.createDirectories(metadataDir);
        log.info("Attachment upload directory: {}", uploadDir);
    }

    /** 上传附件 — 支持图片和文件，返回 UUID 供消息引用 */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        // 验证文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body(
                    new UploadResponse(null, file.getOriginalFilename(), file.getSize(),
                            "File too large (max 10MB)"));
        }

        // 生成 UUID 文件名 (防止冲突和路径遍历)；后缀经加固只允许 ASCII 字母数字
        String fileUuid = UUID.randomUUID().toString();
        String originalName = file.getOriginalFilename();
        String storageFileName = fileUuid + safeExtension(originalName);

        Path tempPayload = uploadDir.resolve(TEMP_FILE_PREFIX + fileUuid + ".part");
        Path metadataFile = metadataDir.resolve(fileUuid + ".json");
        Path metadataTemp = metadataDir.resolve(fileUuid + ".json.tmp");
        boolean metadataPublished = false;
        try {
            // 1. 同文件系统临时 payload，避免半成品出现在下载目录
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, tempPayload, StandardCopyOption.REPLACE_EXISTING);
            }
            // 2. 先原子发布元数据（显示名）
            AttachmentMetadata metadata = new AttachmentMetadata(
                    1, fileUuid, storageFileName, originalName);
            objectMapper.writeValue(metadataTemp.toFile(), metadata);
            Files.move(metadataTemp, metadataFile, StandardCopyOption.ATOMIC_MOVE);
            metadataPublished = true;
            // 3. 再原子发布 payload
            Files.move(tempPayload, uploadDir.resolve(storageFileName),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            // 失败只清理本次上传创建的文件，不触碰存量数据
            deleteQuietly(tempPayload);
            deleteQuietly(metadataTemp);
            if (metadataPublished) {
                deleteQuietly(metadataFile);
            }
            throw failure;
        }

        log.info("Uploaded attachment: {} -> {}", originalName, storageFileName);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new UploadResponse(fileUuid, originalName, file.getSize(), null));
    }

    /** 下载/预览附件 — 需完整 UUID，精确定位，不猜选 */
    @GetMapping("/{fileUuid}")
    public ResponseEntity<Resource> download(@PathVariable String fileUuid) throws IOException {
        UUID uuid = parseCanonicalUuid(fileUuid);
        if (uuid == null) {
            // 明确拒绝短前缀/非规范 UUID
            return ResponseEntity.notFound().build();
        }
        List<Path> candidates = findCandidateFiles(uuid.toString());
        if (candidates.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (candidates.size() > 1) {
            log.warn("Ambiguous attachment lookup for uuid {}: {} candidate files",
                    uuid, candidates.size());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        Path filePath = candidates.get(0);
        String storageFileName = filePath.getFileName().toString();
        String displayName = resolveDisplayName(uuid, storageFileName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDispositionEncoder.header("attachment", displayName, storageFileName))
                .body(new FileSystemResource(filePath));
    }

    /** 解析并规范化 UUID：仅接受完整且规范的（小写）UUID 字符串。 */
    private static UUID parseCanonicalUuid(String input) {
        if (input == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(input);
            return parsed.toString().equals(input) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * 扫描存储目录定位候选文件：跳过目录、符号链接、非普通文件与临时文件；
     * 仅接受文件名等于 uuid 或 {@code uuid + "."} 前缀（保留旧件合法后缀）。
     */
    private List<Path> findCandidateFiles(String uuid) throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (!Files.isDirectory(uploadDir)) {
            return candidates;
        }
        try (Stream<Path> paths = Files.list(uploadDir)) {
            paths.forEach(candidate -> {
                if (!isEligibleStoredFile(candidate)) {
                    return;
                }
                String name = candidate.getFileName().toString();
                if (name.equals(uuid) || name.startsWith(uuid + ".")) {
                    candidates.add(candidate);
                }
            });
        }
        return candidates;
    }

    private static boolean isEligibleStoredFile(Path path) {
        if (Files.isSymbolicLink(path)) {
            return false;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        // 只排除隐藏文件（.upload-* 临时件、.metadata 等）。以点开头的规则不会误伤
        // uuid.part / uuid.tmp 这类合法后缀的存量附件——旧件必须保持可下载。
        return !path.getFileName().toString().startsWith(".");
    }

    /**
     * 显示名解析：元数据有效时使用原始名，否则回退到安全的存储文件名。
     * 元数据只用于展示，绝不用于拼路径或授权。
     */
    private String resolveDisplayName(UUID uuid, String storageFileName) {
        Path metadataFile = metadataDir.resolve(uuid + ".json");
        if (!isEligibleStoredFile(metadataFile)) {
            return storageFileName;
        }
        try {
            AttachmentMetadata metadata =
                    objectMapper.readValue(metadataFile.toFile(), AttachmentMetadata.class);
            if (metadata != null
                    && metadata.version() == 1
                    && uuid.toString().equals(metadata.uuid())
                    && storageFileName.equals(metadata.storageFileName())
                    && metadata.originalName() != null
                    && !metadata.originalName().isBlank()) {
                return metadata.originalName();
            }
        } catch (IOException | RuntimeException broken) {
            log.debug("Unreadable attachment metadata for {}: {}", uuid, broken.toString());
        }
        return storageFileName;
    }

    /**
     * 加固后缀：取最后一个 '.' 之后的部分，仅保留 {@code \.[A-Za-z0-9]{1,16}}，
     * 否则视为无后缀（防御路径逃逸/异常字符）。
     */
    private static String safeExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String extension = filename.substring(dot);
        return extension.matches("\\.[A-Za-z0-9]{1,16}") ? extension : "";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理尽力而为，不覆盖原始失败
        }
    }

    // ═══ DTO Records ═══
    public record UploadResponse(String fileUuid, String fileName, long size, String error) {}

    /** {@code .metadata/<uuid>.json} 内容 — 仅用于下载显示名，不构成访问授权。 */
    record AttachmentMetadata(int version, String uuid, String storageFileName, String originalName) {}
}

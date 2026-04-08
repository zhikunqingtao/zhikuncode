package com.aicodeassistant.controller;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 附件上传 Controller — 处理前端文件/图片上传。
 *
 * @see <a href="SPEC §6.1.6a">AttachmentController</a>
 */
@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private final Path uploadDir;

    public AttachmentController(
            @Value("${app.upload-dir:${user.home}/.qoder/uploads}") String dir) {
        this.uploadDir = Path.of(dir);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(uploadDir);
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

        // 生成 UUID 文件名 (防止冲突和路径遍历)
        String fileUuid = UUID.randomUUID().toString();
        String ext = getExtension(file.getOriginalFilename());
        Path target = uploadDir.resolve(fileUuid + ext);

        // 保存文件
        file.transferTo(target);
        log.info("Uploaded attachment: {} -> {}", file.getOriginalFilename(), target);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new UploadResponse(fileUuid, file.getOriginalFilename(), file.getSize(), null));
    }

    /** 下载/预览附件 */
    @GetMapping("/{fileUuid}")
    public ResponseEntity<Resource> download(@PathVariable String fileUuid) throws IOException {
        Path filePath = findByUuid(fileUuid);
        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    private Path findByUuid(String fileUuid) throws IOException {
        if (!Files.exists(uploadDir)) return null;
        try (Stream<Path> paths = Files.list(uploadDir)) {
            return paths
                    .filter(p -> p.getFileName().toString().startsWith(fileUuid))
                    .findFirst()
                    .orElse(null);
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    // ═══ DTO Records ═══
    public record UploadResponse(String fileUuid, String fileName, long size, String error) {}
}

package com.aicodeassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentControllerTest {

    private static final String CHINESE_NAME_UTF8_PCT = "%E4%B8%AD%E6%96%87%E6%8A%A5%E5%91%8A.txt";

    @TempDir
    Path uploadDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chineseUploadPublishesMetadataAndRestoresOriginalNameAfterRebuild() throws Exception {
        AttachmentController first = controller();
        String originalName = "中文报告.txt";
        byte[] content = "附件内容".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", originalName, "text/plain", content);

        ResponseEntity<AttachmentController.UploadResponse> uploaded = first.upload(file);

        assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
        assertThat(uploaded.getBody()).isNotNull();
        String uuid = uploaded.getBody().fileUuid();
        assertThat(uuid).isNotBlank();
        assertThat(uploaded.getBody().fileName()).isEqualTo(originalName);
        assertThat(uploaded.getBody().size()).isEqualTo(content.length);
        assertThat(uploaded.getBody().error()).isNull();

        Path payload = uploadDir.resolve(uuid + ".txt");
        Path metadata = uploadDir.resolve(".metadata").resolve(uuid + ".json");
        assertThat(payload).isRegularFile();
        assertThat(metadata).isRegularFile();
        assertThat(Files.readAllBytes(payload)).containsExactly(content);
        JsonNode meta = objectMapper.readTree(metadata.toFile());
        assertThat(meta.get("version").asInt()).isEqualTo(1);
        assertThat(meta.get("uuid").asText()).isEqualTo(uuid);
        assertThat(meta.get("storageFileName").asText()).isEqualTo(uuid + ".txt");
        assertThat(meta.get("originalName").asText()).isEqualTo(originalName);

        // 重建 controller 实例（同目录）后仍能恢复原始显示名
        AttachmentController rebuilt = controller();
        ResponseEntity<Resource> download = rebuilt.download(uuid);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"????.txt\"; filename*=UTF-8''"
                        + CHINESE_NAME_UTF8_PCT);
        assertThat(download.getBody().getContentAsByteArray()).containsExactly(content);
    }

    @Test
    void oldAttachmentWithoutMetadataFallsBackToStorageFileName() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(uuid + ".md"), "legacy");

        ResponseEntity<Resource> download = controller.download(uuid);

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"" + uuid + ".md\";"
                        + " filename*=UTF-8''" + uuid + ".md");
    }

    @Test
    void corruptedOrMismatchedMetadataFallsBackToStorageFileName() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(uuid + ".txt"), "payload");
        Path metadata = uploadDir.resolve(".metadata").resolve(uuid + ".json");

        Files.writeString(metadata, "not-json");
        assertThat(downloadDisposition(controller, uuid)).contains("filename=\"" + uuid + ".txt\"");

        Files.writeString(metadata, "{\"version\":1,\"uuid\":\"" + uuid
                + "\",\"storageFileName\":\"other.bin\",\"originalName\":\"中文.txt\"}");
        assertThat(downloadDisposition(controller, uuid)).contains("filename=\"" + uuid + ".txt\"");

        Files.writeString(metadata, "{\"version\":1,\"uuid\":\""
                + UUID.randomUUID() + "\",\"storageFileName\":\"" + uuid
                + ".txt\",\"originalName\":\"中文.txt\"}");
        assertThat(downloadDisposition(controller, uuid)).contains("filename=\"" + uuid + ".txt\"");
    }

    @Test
    void rejectsShortPrefixesAndNonCanonicalUuids() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(uuid + ".txt"), "payload");

        assertThat(controller.download(uuid.substring(0, 8)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.download("not-a-uuid").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.download(uuid.toUpperCase(Locale.ROOT)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void multipleCandidatesReturnConflictAndTempFilesAreNotCandidates() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(uuid), "payload");
        Files.writeString(uploadDir.resolve(uuid + ".txt"), "legacy-copy");
        // 临时文件与元数据不得成为候选
        Files.writeString(uploadDir.resolve(".upload-" + uuid + ".part"), "temp");
        Files.writeString(uploadDir.resolve(".metadata").resolve(uuid + ".json"), "{}");

        assertThat(controller.download(uuid).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void tempAndDirectoryEntriesAreIgnored() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(".upload-" + uuid + ".part"), "temp");
        Files.createDirectory(uploadDir.resolve(uuid + ".txt"));

        assertThat(controller.download(uuid).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void legitimatePartAndTmpSuffixesStayDownloadableWhileHiddenTempsAreIgnored() throws Exception {
        AttachmentController controller = controller();

        // 旧附件 uuid.part / uuid.tmp 是合法后缀，必须保持可下载。
        String legacyPart = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(legacyPart + ".part"), "legacy-payload");
        assertThat(controller.download(legacyPart).getStatusCode().is2xxSuccessful()).isTrue();

        // 新上传 ".part" 扩展名：存储名带加固后缀，下载走原名显示。
        ResponseEntity<AttachmentController.UploadResponse> uploaded = controller.upload(
                new MockMultipartFile("file", "note.part", null, new byte[]{1}));
        String uploadedUuid = uploaded.getBody().fileUuid();
        ResponseEntity<Resource> download = controller.download(uploadedUuid);
        assertThat(download.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("note.part");

        // 隐藏临时文件仍不构成候选（不能被当作附件）。
        String other = UUID.randomUUID().toString();
        Files.writeString(uploadDir.resolve(".upload-" + other + ".part"), "temp");
        assertThat(controller.download(other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void symlinkedCandidateIsIgnored() throws Exception {
        AttachmentController controller = controller();
        String uuid = UUID.randomUUID().toString();
        Path target = Files.writeString(uploadDir.resolve("symlink-target.txt"), "real");
        try {
            Files.createSymbolicLink(uploadDir.resolve(uuid + ".txt"), target);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links unsupported on this filesystem");
        }

        assertThat(controller.download(uuid).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadsAndDownloadsThroughRealAndSymlinkedRoots() throws Exception {
        Path realRoot = Files.createDirectory(uploadDir.resolve("real-upload"));
        Path linkedRoot = uploadDir.resolve("linked-upload");
        try {
            Files.createSymbolicLink(linkedRoot, realRoot);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links unsupported on this filesystem");
        }
        AttachmentController direct = new AttachmentController(realRoot.toString(), objectMapper);
        AttachmentController linked = new AttachmentController(linkedRoot.toString(), objectMapper);
        direct.init();
        linked.init();
        byte[] content = "附件内容".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "中文报告.txt", "text/plain", content);

        var directUpload = direct.upload(file);
        var linkedUpload = linked.upload(file);

        for (var uploaded : java.util.List.of(directUpload, linkedUpload)) {
            assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(uploaded.getBody()).isNotNull();
            String uuid = uploaded.getBody().fileUuid();
            assertThat(Files.readAllBytes(realRoot.resolve(uuid + ".txt"))).containsExactly(content);
            for (AttachmentController reader : java.util.List.of(direct, linked)) {
                ResponseEntity<Resource> downloaded = reader.download(uuid);
                assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(downloaded.getBody()).isNotNull();
                assertThat(downloaded.getBody().getContentAsByteArray()).containsExactly(content);
                assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                        .isEqualTo("attachment; filename=\"????.txt\"; filename*=UTF-8''"
                                + CHINESE_NAME_UTF8_PCT);
            }
        }
    }

    @Test
    void hardensExtensionToAsciiAlphanumericOrNothing() throws Exception {
        AttachmentController controller = controller();

        ResponseEntity<AttachmentController.UploadResponse> traversal = controller.upload(
                new MockMultipartFile("file", "payload.txt/../../boom", null, new byte[]{1}));
        assertThat(traversal.getStatusCode().value()).isEqualTo(201);
        String traversalUuid = traversal.getBody().fileUuid();
        assertThat(uploadDir.resolve(traversalUuid)).isRegularFile();

        ResponseEntity<AttachmentController.UploadResponse> chineseExt = controller.upload(
                new MockMultipartFile("file", "报告.最终版", null, new byte[]{2}));
        assertThat(uploadDir.resolve(chineseExt.getBody().fileUuid())).isRegularFile();

        ResponseEntity<AttachmentController.UploadResponse> upperExt = controller.upload(
                new MockMultipartFile("file", "data.TXT", null, new byte[]{3}));
        assertThat(uploadDir.resolve(upperExt.getBody().fileUuid() + ".TXT")).isRegularFile();

        try (Stream<Path> entries = Files.list(uploadDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.contains("..") || name.contains("/"));
        }
    }

    @Test
    void uploadFailureCleansThisAttemptsTempFileAndHalfPublishedState() throws Exception {
        AttachmentController controller = controller();
        // 把已初始化的 .metadata 目录替换为普通文件，令元数据发布必然失败
        Files.delete(uploadDir.resolve(".metadata"));
        Path occupied = Files.writeString(uploadDir.resolve(".metadata"), "sentinel");

        MockMultipartFile file = new MockMultipartFile(
                "file", "报告.txt", "text/plain", "payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.upload(file)).isInstanceOf(IOException.class);

        // 只清理本次文件：无临时 payload、无半成品，sentinel 原样保留
        assertThat(Files.readString(occupied)).isEqualTo("sentinel");
        try (Stream<Path> entries = Files.list(uploadDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly(".metadata");
        }
    }

    @Test
    void initFailsFastWhenMetadataPathIsOccupiedByARegularFile() throws Exception {
        Files.writeString(uploadDir.resolve(".metadata"), "occupied");
        AttachmentController controller =
                new AttachmentController(uploadDir.toString(), objectMapper);

        assertThatThrownBy(controller::init).isInstanceOf(IOException.class);

        try (Stream<Path> entries = Files.list(uploadDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly(".metadata");
        }
    }

    @Test
    void oversizeUploadIsRejectedWithoutWritingAnyFile() throws Exception {
        AttachmentController controller = controller();
        MockMultipartFile tooLarge = new MockMultipartFile(
                "file", "big.bin", null, new byte[10 * 1024 * 1024 + 1]);

        ResponseEntity<AttachmentController.UploadResponse> response = controller.upload(tooLarge);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error()).contains("too large");
        try (Stream<Path> entries = Files.list(uploadDir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly(".metadata");
        }
    }

    private AttachmentController controller() throws IOException {
        AttachmentController controller =
                new AttachmentController(uploadDir.toString(), objectMapper);
        controller.init();
        return controller;
    }

    private static String downloadDisposition(AttachmentController controller, String uuid)
            throws IOException {
        ResponseEntity<Resource> download = controller.download(uuid);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        return download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    }
}

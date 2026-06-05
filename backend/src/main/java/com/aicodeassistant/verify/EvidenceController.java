package com.aicodeassistant.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * EvidenceController — RV-4 证据包查询 REST API。
 *
 * <p>暴露 {@link EvidenceStore} 的查询能力，供前端 Evidence Viewer 使用。
 * 不包含写入端点（写入路径仍由 RV-1 验证流程内部完成）。</p>
 *
 * <p>当前项目无 RBAC，所有端点开放访问。</p>
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private static final Logger log = LoggerFactory.getLogger(EvidenceController.class);

    private final EvidenceStore evidenceStore;

    public EvidenceController(EvidenceStore evidenceStore) {
        this.evidenceStore = evidenceStore;
    }

    /**
     * 查询单个证据包（含 items 列表）。
     *
     * @param bundleId 证据包 ID
     * @return 200 + EvidenceBundle JSON；404 if not found
     */
    @GetMapping("/{bundleId}")
    public ResponseEntity<EvidenceBundle> getBundle(@PathVariable String bundleId) {
        Optional<EvidenceBundle> bundle = evidenceStore.findById(bundleId);
        if (bundle.isEmpty()) {
            log.debug("Evidence bundle not found: {}", bundleId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(bundle.get());
    }

    /**
     * 按 sessionId 查询所有证据包，按 created_at DESC 排序（由 EvidenceStore 保证）。
     *
     * @param sessionId 会话 ID
     * @return 200 + List<EvidenceBundle>（可能为空数组）
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<EvidenceBundle>> getBySession(@PathVariable String sessionId) {
        List<EvidenceBundle> bundles = evidenceStore.findBySession(sessionId);
        return ResponseEntity.ok(bundles);
    }

    /**
     * 流式下载 Blob 内容。
     *
     * @param sha256 Blob 的 SHA-256 十六进制
     * @return 200 + 二进制流（application/octet-stream）；404 if blob not found
     */
    @GetMapping("/blob/{sha256}")
    public ResponseEntity<byte[]> getBlob(@PathVariable String sha256) {
        Optional<byte[]> data = evidenceStore.readBlob(sha256);
        if (data.isEmpty()) {
            log.debug("Blob not found: {}", sha256);
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = data.get();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sha256 + "\"")
                .contentLength(bytes.length)
                .body(bytes);
    }
}

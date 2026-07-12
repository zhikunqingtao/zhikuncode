package com.aicodeassistant.artifact;

import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 产物清单 REST 控制器 — 提供清单查询和验证触发接口。
 * <p>
 * 安全保障:
 * <ul>
 *   <li>Session 归属验证 — 通过 runId 反查 sessionId，确认 session 在线</li>
 *   <li>路径安全校验 — 拒绝 symlink、设备文件、FIFO 等特殊路径</li>
 *   <li>调用频率限制 — verify 端点基于 session 的内存计数器限流</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

    /** verify 端点限流：每个 session 在时间窗口内的最大调用次数 */
    private static final int VERIFY_RATE_LIMIT = 10;
    /** 限流时间窗口（毫秒） */
    private static final long VERIFY_RATE_WINDOW_MS = 60_000L;

    private final ArtifactManifestService artifactManifestService;
    private final RunEnvelopeRepository runEnvelopeRepository;
    private final WebSocketSessionManager sessionManager;
    private final PathSecurityService pathSecurityService;

    /** 简单内存限流器：sessionId -> (count, windowStart) */
    private final ConcurrentHashMap<String, long[]> verifyRateMap = new ConcurrentHashMap<>();

    public ArtifactController(ArtifactManifestService artifactManifestService,
                              RunEnvelopeRepository runEnvelopeRepository,
                              WebSocketSessionManager sessionManager,
                              PathSecurityService pathSecurityService) {
        this.artifactManifestService = artifactManifestService;
        this.runEnvelopeRepository = runEnvelopeRepository;
        this.sessionManager = sessionManager;
        this.pathSecurityService = pathSecurityService;
    }

    /**
     * 获取运行的产物清单（含条目）。
     */
    @GetMapping("/{runId}/manifest")
    public ResponseEntity<ArtifactManifest> getManifest(@PathVariable String runId) {
        // 鉴权：通过 runId 查找所属 session 并验证 session 在线
        var runOpt = runEnvelopeRepository.findById(runId);
        if (runOpt.isEmpty() || !sessionManager.isSessionOnline(runOpt.get().sessionId())) {
            log.warn("Manifest access denied: runId={}, session not online", runId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return artifactManifestService.getManifest(runId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 触发重新验证并返回结果。
     */
    @PostMapping("/{runId}/manifest/verify")
    public ResponseEntity<VerificationResult> verifyManifest(@PathVariable String runId) {
        // 1. 鉴权：通过 runId 查找所属 session 并验证 session 在线
        var runOpt = runEnvelopeRepository.findById(runId);
        if (runOpt.isEmpty() || !sessionManager.isSessionOnline(runOpt.get().sessionId())) {
            log.warn("Manifest verify denied: runId={}, session not online", runId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String sessionId = runOpt.get().sessionId();

        // 2. 限流检查
        if (!checkRateLimit(sessionId)) {
            log.warn("Manifest verify rate-limited: sessionId={}, runId={}", sessionId, runId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        // 3. 获取清单
        var manifestOpt = artifactManifestService.getManifest(runId);
        if (manifestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ArtifactManifest manifest = manifestOpt.get();

        // 4. 路径安全校验：验证清单中所有文件路径的安全性
        String pathError = validateEntryPaths(manifest.entries());
        if (pathError != null) {
            log.warn("Manifest verify blocked by path security: runId={}, reason={}", runId, pathError);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new VerificationResult("blocked", 0, 0, manifest.totalFiles(),
                    List.of(new VerificationResult.FailureDetail("security", pathError))));
        }

        // 5. 执行验证
        VerificationResult result = artifactManifestService.verify(manifest.id());
        return ResponseEntity.ok(result);
    }

    // ───── 内部安全方法 ─────

    /**
     * 验证清单条目中的文件路径安全性。
     * 拒绝 symlink 解析到工作区外、设备文件、FIFO、其他特殊文件。
     *
     * @return null 表示安全，非 null 表示拒绝原因
     */
    private String validateEntryPaths(List<ArtifactEntry> entries) {
        String workingDirectory = System.getProperty("user.dir");

        for (ArtifactEntry entry : entries) {
            String filePath = entry.filePath();
            if (filePath == null || filePath.isBlank()) continue;

            // 跳过已删除文件的路径检查（文件不存在是预期状态）
            if ("deleted".equals(entry.operation())) continue;

            try {
                Path path = Path.of(filePath).toAbsolutePath().normalize();

                // 检查是否为 symlink 且解析到工作区外
                if (Files.isSymbolicLink(path)) {
                    Path realPath = path.toRealPath();
                    Path workspaceRoot = Path.of(workingDirectory).toRealPath();
                    if (!realPath.startsWith(workspaceRoot)) {
                        return "Symlink escapes workspace: " + filePath + " -> " + realPath;
                    }
                }

                // 检查是否为设备文件、FIFO、特殊文件（仅在文件存在时检查）
                if (Files.exists(path)) {
                    if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                        return "Special file type detected (device/FIFO): " + filePath;
                    }
                }

                // 使用 PathSecurityService 进行统一安全检查
                PathSecurityService.PathCheckResult checkResult =
                    pathSecurityService.checkReadPermission(filePath, workingDirectory);
                if (!checkResult.isAllowed()) {
                    return checkResult.message();
                }

            } catch (Exception e) {
                return "Path validation error for " + filePath + ": " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * 简单的滑动窗口限流器。
     *
     * @return true 表示允许，false 表示被限流
     */
    private boolean checkRateLimit(String sessionId) {
        long now = System.currentTimeMillis();
        long[] state = verifyRateMap.compute(sessionId, (key, existing) -> {
            if (existing == null || (now - existing[1]) > VERIFY_RATE_WINDOW_MS) {
                // 新窗口
                return new long[]{1, now};
            }
            // 同一窗口，递增计数
            existing[0]++;
            return existing;
        });
        return state[0] <= VERIFY_RATE_LIMIT;
    }
}

package com.aicodeassistant.controller;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.history.FileHistoryService.DiffStats;
import com.aicodeassistant.history.FileHistoryService.RewindResult;
import com.aicodeassistant.history.FileHistoryService.SnapshotInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件历史 Controller — 文件快照查看与回退。
 * <p>
 * F2 实装：三个端点从占位升级为端到端可用。
 *
 * @see <a href="SPEC §6.1.6a">FileHistoryController</a>
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/history")
public class FileHistoryController {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryController.class);

    private final FileHistoryService fileHistoryService;

    public FileHistoryController(FileHistoryService fileHistoryService) {
        this.fileHistoryService = fileHistoryService;
    }

    /** 列出会话的所有文件快照，按 messageId 分组 */
    @GetMapping("/snapshots")
    public ResponseEntity<Map<String, List<SnapshotSummary>>> listSnapshots(
            @PathVariable String sessionId) {
        log.debug("Listing snapshots for session: {}", sessionId);
        Map<String, List<SnapshotInfo>> grouped = fileHistoryService.listSnapshotsBySession(sessionId);

        // 转换为 Controller 层 DTO
        Map<String, List<SnapshotSummary>> result = grouped.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            List<String> files = e.getValue().stream()
                                    .map(SnapshotInfo::filePath).toList();
                            String timestamp = e.getValue().isEmpty() ? "" :
                                    e.getValue().getFirst().timestamp();
                            return List.of(new SnapshotSummary(
                                    e.getKey(), files, files.size(), timestamp));
                        }
                ));
        return ResponseEntity.ok(result);
    }

    /** 回退到指定快照 */
    @PostMapping("/rewind")
    public ResponseEntity<RewindResponse> rewindToSnapshot(
            @PathVariable String sessionId,
            @RequestBody RewindRequest request) {
        log.info("Rewind request for session={}, messageId={}, files={}",
                sessionId, request.messageId(), request.filePaths());

        RewindResult result = fileHistoryService.rewindFiles(
                sessionId, request.messageId(), request.filePaths());

        return ResponseEntity.ok(new RewindResponse(
                result.success(), result.restoredFiles(),
                result.skippedFiles(), result.errors()));
    }

    /** 获取两个快照之间的 diff 统计 */
    @GetMapping("/diff")
    public ResponseEntity<DiffStatsResponse> getDiffStats(
            @PathVariable String sessionId,
            @RequestParam String fromMessageId,
            @RequestParam String toMessageId) {
        log.debug("Diff request: session={}, from={}, to={}", sessionId, fromMessageId, toMessageId);

        DiffStats stats = fileHistoryService.computeDiffStats(
                sessionId, fromMessageId, toMessageId);

        return ResponseEntity.ok(new DiffStatsResponse(
                stats.filesAdded(), stats.filesModified(),
                stats.filesDeleted(), stats.changedFiles()));
    }

    // ═══ DTO Records ═══
    public record SnapshotSummary(String messageId, List<String> trackedFiles,
                                   int fileCount, String timestamp) {}
    public record RewindRequest(String messageId, List<String> filePaths) {}
    public record RewindResponse(boolean success, List<String> restoredFiles,
                                  List<String> skippedFiles, List<String> errors) {}
    public record DiffStatsResponse(int filesAdded, int filesModified, int filesDeleted,
                            List<String> changedFiles) {}
}

package com.aicodeassistant.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文件历史 Controller — 文件快照查看与回退。
 * <p>
 * P1 占位实现 — 完整的 FileHistoryService 在后续 Round 实现。
 *
 * @see <a href="SPEC §6.1.6a">FileHistoryController</a>
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/history")
public class FileHistoryController {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryController.class);

    /** 列出会话的所有文件快照 */
    @GetMapping("/snapshots")
    public ResponseEntity<Map<String, List<SnapshotSummary>>> listSnapshots(
            @PathVariable String sessionId) {
        // P1: FileHistoryService 集成后返回真实数据
        log.debug("Listing snapshots for session: {}", sessionId);
        return ResponseEntity.ok(Map.of("snapshots", List.of()));
    }

    /** 回退到指定快照 */
    @PostMapping("/rewind")
    public ResponseEntity<RewindResponse> rewindToSnapshot(
            @PathVariable String sessionId,
            @RequestBody RewindRequest request) {
        // P1: FileHistoryService 集成后执行真实回退
        log.info("Rewind request for session={}, messageId={}", sessionId, request.messageId());
        return ResponseEntity.ok(new RewindResponse(
                true, List.of(), List.of(), List.of()));
    }

    /** 获取两个快照之间的 diff 统计 */
    @GetMapping("/diff")
    public ResponseEntity<DiffStats> getDiffStats(
            @PathVariable String sessionId,
            @RequestParam String fromMessageId,
            @RequestParam String toMessageId) {
        // P1: FileHistoryService 集成后计算真实 diff
        log.debug("Diff request: session={}, from={}, to={}", sessionId, fromMessageId, toMessageId);
        return ResponseEntity.ok(new DiffStats(0, 0, 0, List.of()));
    }

    // ═══ DTO Records ═══
    public record SnapshotSummary(String messageId, List<String> trackedFiles,
                                   int fileCount, String timestamp) {}
    public record RewindRequest(String messageId, List<String> filePaths) {}
    public record RewindResponse(boolean success, List<String> restoredFiles,
                                  List<String> skippedFiles, List<String> errors) {}
    public record DiffStats(int filesAdded, int filesModified, int filesDeleted,
                            List<String> changedFiles) {}
}

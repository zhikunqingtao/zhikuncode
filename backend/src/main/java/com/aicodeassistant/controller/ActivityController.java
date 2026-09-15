package com.aicodeassistant.controller;

import com.aicodeassistant.service.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Activity REST API — 提供 Activity 分页查询能力，
 * 配合前端"加载更多"功能使用。
 */
@RestController
@RequestMapping("/api/sessions")
public class ActivityController {

    private static final Logger log = LoggerFactory.getLogger(ActivityController.class);
    private static final int MAX_LIMIT = 100;

    private final ActivityRepository activityRepository;

    public ActivityController(ActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /** Confirm a saved Activity decision; this does not change tool permission rules. */
    @PutMapping("/{sessionId}/activities/{id}/decision")
    public ResponseEntity<Map<String, Object>> updateDecision(
            @PathVariable String sessionId, @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String decision = body.get("decision");
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid decision"));
        }
        if (!activityRepository.updateDecisionForSession(sessionId, id, decision)) {
            return ResponseEntity.status(404).body(Map.of("error", "Activity not found in session"));
        }
        return ResponseEntity.ok(Map.of("id", id, "sessionId", sessionId, "decision", decision));
    }

    /**
     * 分页获取指定会话的 Activity 列表。
     *
     * @param sessionId 会话 ID（路径参数）
     * @param offset    跳过条数，默认 0
     * @param limit     返回条数，默认 50，最大 100
     * @return { activities: [...], hasMore: boolean, total: number }
     */
    @GetMapping("/{sessionId}/activities")
    public ResponseEntity<Map<String, Object>> getActivities(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {

        // 参数校验
        if (offset < 0) offset = 0;
        if (limit <= 0 || limit > MAX_LIMIT) limit = 50;

        int total = activityRepository.countBySessionId(sessionId);
        List<Map<String, Object>> activities = activityRepository.findBySessionIdPaged(sessionId, offset, limit);
        boolean hasMore = (offset + activities.size()) < total;

        log.debug("GET /api/sessions/{}/activities?offset={}&limit={} => total={}, returned={}, hasMore={}",
                sessionId, offset, limit, total, activities.size(), hasMore);

        return ResponseEntity.ok(Map.of(
                "activities", activities,
                "hasMore", hasMore,
                "total", total
        ));
    }
}

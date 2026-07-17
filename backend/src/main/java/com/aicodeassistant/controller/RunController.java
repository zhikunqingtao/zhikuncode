package com.aicodeassistant.controller;

import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunEvent;
import com.aicodeassistant.run.RunEventRepository;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RunController — 运行信封 REST API。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET /api/runs/session/{sessionId} — 会话运行列表</li>
 *   <li>GET /api/runs/{runId} — 运行详情</li>
 *   <li>GET /api/runs/{runId}/events — 运行事件列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final RunEnvelopeRepository envelopeRepository;
    private final RunEventRepository eventRepository;
    private final SessionAccessAuthorizer access;
    private final RunTerminationCoordinator termination;

    public RunController(RunEnvelopeRepository envelopeRepository,
                         RunEventRepository eventRepository,
                         SessionAccessAuthorizer access,
                         RunTerminationCoordinator termination) {
        this.envelopeRepository = envelopeRepository;
        this.eventRepository = eventRepository;
        this.access = access;
        this.termination=termination;
    }

    /**
     * 获取会话的运行列表。
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<RunEnvelope>> listRuns(
            @PathVariable String sessionId,
            @RequestHeader("X-Session-Id") String assertedSessionId,
            @RequestParam(defaultValue = "20") int limit) {
        if (!access.canAccessSession(sessionId, assertedSessionId)) {
            log.warn("Run list rejected: sessionId={} failed object authorization", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(envelopeRepository.findBySession(sessionId, limit));
    }

    /**
     * 获取单个运行详情。
     */
    @GetMapping("/{runId}")
    public ResponseEntity<RunEnvelope> getRun(@PathVariable String runId,
                                               @RequestHeader("X-Session-Id") String sessionId) {
        return access.accessibleRun(runId, sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取运行的事件列表 — 支持游标分页。
     */
    @GetMapping("/{runId}/events")
    public ResponseEntity<RunEventsResponse> getEvents(
            @PathVariable String runId,
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestParam(defaultValue = "0") int afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        // Validate runId belongs to an active session
        var runOpt = access.accessibleRun(runId, sessionId);
        if (runOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<RunEvent> events = eventRepository.getEvents(runId, afterSeq, boundedLimit);
        boolean hasMore = events.size() == boundedLimit;
        int nextSeq = events.isEmpty() ? afterSeq : events.getLast().seq();
        return ResponseEntity.ok(new RunEventsResponse(events, hasMore, nextSeq));
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RunEnvelope> cancel(@PathVariable String runId,
                                               @RequestHeader("X-Session-Id") String sessionId) {
        var current = access.accessibleRun(runId, sessionId);
        if (current.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!current.get().status().terminal()) {
            var result = termination.cancelByUser(runId, "user_cancelled");
            if (result.transition() != RunControlService.TransitionResult.APPLIED
                    && result.transition() != RunControlService.TransitionResult.ALREADY_TERMINAL) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }
        return envelopeRepository.findById(runId).map(snapshot ->
                ResponseEntity.status(snapshot.status() == RunEnvelope.RunStatus.CANCELLING
                        ? HttpStatus.ACCEPTED : HttpStatus.OK).body(snapshot))
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══ DTO Records ═══

    public record RunEventsResponse(
            List<RunEvent> events,
            boolean hasMore,
            int nextSeq
    ) {}
}

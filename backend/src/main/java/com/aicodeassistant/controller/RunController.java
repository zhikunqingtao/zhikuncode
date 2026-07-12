package com.aicodeassistant.controller;

import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunEvent;
import com.aicodeassistant.run.RunEventRepository;
import com.aicodeassistant.websocket.WebSocketSessionManager;
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
    private final WebSocketSessionManager sessionManager;

    public RunController(RunEnvelopeRepository envelopeRepository,
                         RunEventRepository eventRepository,
                         WebSocketSessionManager sessionManager) {
        this.envelopeRepository = envelopeRepository;
        this.eventRepository = eventRepository;
        this.sessionManager = sessionManager;
    }

    /**
     * 获取会话的运行列表。
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<RunEnvelope>> listRuns(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        if (!sessionManager.isSessionOnline(sessionId)) {
            log.warn("Run list rejected: sessionId={} not found in active sessions", sessionId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(envelopeRepository.findBySession(sessionId, limit));
    }

    /**
     * 获取单个运行详情。
     */
    @GetMapping("/{runId}")
    public ResponseEntity<RunEnvelope> getRun(@PathVariable String runId) {
        return envelopeRepository.findById(runId)
                .filter(run -> sessionManager.isSessionOnline(run.sessionId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取运行的事件列表 — 支持游标分页。
     */
    @GetMapping("/{runId}/events")
    public ResponseEntity<RunEventsResponse> getEvents(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        // Validate runId belongs to an active session
        var runOpt = envelopeRepository.findById(runId);
        if (runOpt.isEmpty() || !sessionManager.isSessionOnline(runOpt.get().sessionId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<RunEvent> events = eventRepository.getEvents(runId, afterSeq, limit);
        boolean hasMore = events.size() == limit;
        int nextSeq = events.isEmpty() ? afterSeq : events.getLast().seq();
        return ResponseEntity.ok(new RunEventsResponse(events, hasMore, nextSeq));
    }

    // ═══ DTO Records ═══

    public record RunEventsResponse(
            List<RunEvent> events,
            boolean hasMore,
            int nextSeq
    ) {}
}

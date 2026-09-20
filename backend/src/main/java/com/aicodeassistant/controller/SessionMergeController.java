package com.aicodeassistant.controller;

import com.aicodeassistant.session.merge.SessionMergeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SessionMergeController {
    private final SessionMergeService merges;
    public SessionMergeController(SessionMergeService merges) { this.merges = merges; }
    @PostMapping("/api/sessions/merge")
    public ResponseEntity<SessionMergeService.Operation> create(@RequestBody SessionMergeService.Request request,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(merges.start(request, key));
    }
    @GetMapping("/api/session-merges/{operationId}")
    public SessionMergeService.Operation get(@PathVariable String operationId) { return merges.get(operationId); }
}

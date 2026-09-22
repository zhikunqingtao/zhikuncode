package com.aicodeassistant.controller;

import com.aicodeassistant.session.merge.SessionMergeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(produces="application/json")
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
    @GetMapping("/api/session-merges/active")
    public ResponseEntity<SessionMergeService.Operation> active() {
        return merges.active().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/api/session-merges/{operationId}/resume")
    public ResponseEntity<SessionMergeService.Operation> resume(@PathVariable String operationId,
            @RequestBody SessionMergeService.Resume request) {
        return ResponseEntity.accepted().body(merges.resume(operationId,request));
    }
    @PostMapping("/api/session-merges/{operationId}/cancel")
    public SessionMergeService.Operation cancel(@PathVariable String operationId) { return merges.cancel(operationId); }
    @ExceptionHandler(SessionMergeService.Conflict.class)
    public ResponseEntity<java.util.Map<String,Object>> conflict(SessionMergeService.Conflict failure) {
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("error",java.util.Map.of("code",failure.code,"message",failure.getMessage()));
        if(failure.operation!=null) body.put("operation",failure.operation);
        return ResponseEntity.status(409).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }
}

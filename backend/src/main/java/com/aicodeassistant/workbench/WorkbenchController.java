package com.aicodeassistant.workbench;

import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final WorkbenchProjectionService projections;
    private final WorkbenchTaskService tasks;
    private final SessionAccessAuthorizer access;

    public WorkbenchController(WorkbenchProjectionService projections,
                               WorkbenchTaskService tasks,
                               SessionAccessAuthorizer access) {
        this.projections = projections;
        this.tasks = tasks;
        this.access = access;
    }

    @GetMapping("/sessions/{sessionId}/workbench/current")
    public ResponseEntity<WorkbenchCurrentView> current(
            @PathVariable String sessionId,
            @RequestHeader("X-Session-Id") String assertedSessionId) {
        if (!access.canAccessSession(sessionId, assertedSessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(projections.current(sessionId));
    }

    @GetMapping("/workbench/tasks")
    public WorkbenchTaskService.TaskListView tasks(
            @RequestParam(required = false) String query) {
        return tasks.list(query);
    }
}

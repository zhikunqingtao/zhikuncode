package com.aicodeassistant.controller;

import com.aicodeassistant.service.FileSearchService;
import com.aicodeassistant.service.FileSearchService.FileSearchResult;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import com.aicodeassistant.service.SessionFileAccessService;

/**
 * 文件搜索 API — 支持 @文件附件功能的后端端点。
 *
 */
@RestController
public class FileController {

    private final FileSearchService fileSearchService;
    private final SessionManager sessionManager;
    private final ProjectWorkspaceService projectWorkspaces;
    private final SessionFileAccessService sessionFiles;

    @org.springframework.beans.factory.annotation.Autowired
    public FileController(
            FileSearchService fileSearchService,
            SessionManager sessionManager,
            ProjectWorkspaceService projectWorkspaces,
            SessionFileAccessService sessionFiles) {
        this.fileSearchService = fileSearchService;
        this.sessionManager = sessionManager;
        this.projectWorkspaces = projectWorkspaces;
        this.sessionFiles = sessionFiles;
    }

    /** 仅保留给不涉及预览/原生打开的隔离单元测试。 */
    FileController(FileSearchService fileSearchService,
                   SessionManager sessionManager,
                   ProjectWorkspaceService projectWorkspaces) {
        this(fileSearchService, sessionManager, projectWorkspaces, null);
    }

    @GetMapping("/api/files/search")
    public ResponseEntity<List<FileSearchResult>> searchFiles(
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam String sessionId) {
        SessionData session = sessionManager.loadSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        String workspace = projectWorkspaces.requireCurrentBinding(
                session.workingDir()).toString();
        List<FileSearchResult> results = fileSearchService.fuzzySearch(
                query, workspace, limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/api/sessions/{sessionId}/files/preview")
    public ResponseEntity<Resource> preview(
            @org.springframework.web.bind.annotation.PathVariable String sessionId,
            @org.springframework.web.bind.annotation.RequestHeader("X-Session-Id") String assertedSessionId,
            @RequestParam String path) {
        requireMatchingSession(sessionId, assertedSessionId);
        var target = sessionFiles.preview(sessionId, path);
        String safeName = target.path().getFileName().toString().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(target.contentType()))
                .contentLength(target.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + safeName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(target.path()));
    }

    @PostMapping("/api/sessions/{sessionId}/files/reveal")
    public ResponseEntity<SessionFileAccessService.RevealResult> reveal(
            @org.springframework.web.bind.annotation.PathVariable String sessionId,
            @RequestBody RevealFileRequest request,
            @org.springframework.web.bind.annotation.RequestHeader("X-Session-Id") String assertedSessionId,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "X-Zhikun-User-Gesture", required = false) String userGesture,
            HttpServletRequest servletRequest) {
        requireMatchingSession(sessionId, assertedSessionId);
        if (!"reveal-file".equals(userGesture)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(sessionFiles.reveal(sessionId, request.path(),
                servletRequest.getRemoteAddr()));
    }

    private static void requireMatchingSession(String sessionId, String assertedSessionId) {
        if (!sessionId.equals(assertedSessionId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "SESSION_CONTEXT_MISMATCH");
        }
    }

    public record RevealFileRequest(String path) { }
}

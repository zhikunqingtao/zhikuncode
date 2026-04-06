package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.session.SessionPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * SessionController — 会话管理 REST API。
 * <p>
 * 端点:
 * <ul>
 *   <li>POST /api/sessions — 创建会话</li>
 *   <li>GET /api/sessions — 会话列表(游标分页)</li>
 *   <li>GET /api/sessions/{id} — 会话详情</li>
 *   <li>DELETE /api/sessions/{id} — 删除会话</li>
 *   <li>POST /api/sessions/{id}/resume — 恢复会话</li>
 *   <li>POST /api/sessions/{id}/compact — 压缩上下文</li>
 *   <li>POST /api/sessions/{id}/export — 导出会话</li>
 * </ul>
 *
 * @see <a href="SPEC §6.1.2">会话管理 API</a>
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessionManager;
    private final CompactService compactService;

    public SessionController(SessionManager sessionManager,
                             CompactService compactService) {
        this.sessionManager = sessionManager;
        this.compactService = compactService;
    }

    /**
     * 创建新会话。
     */
    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestBody CreateSessionRequest request) {
        String model = request.model() != null ? request.model() : "gpt-4o";
        String workingDir = request.workingDirectory() != null
                ? request.workingDirectory()
                : System.getProperty("user.dir");

        String sessionId = sessionManager.createSession(model, workingDir);

        return ResponseEntity.status(201).body(new CreateSessionResponse(
                sessionId,
                "/ws/session/" + sessionId,
                model,
                request.permissionMode(),
                Instant.now()
        ));
    }

    /**
     * 获取会话列表 — 游标分页 (v1.35.0)。
     * <p>
     * 游标 = Base64("updated_at|session_id")
     */
    @GetMapping
    public ResponseEntity<SessionListResponse> listSessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String workingDir) {

        boolean anchorToLatest = cursor == null;
        String beforeId = null;

        if (cursor != null) {
            try {
                String decoded = new String(Base64.getDecoder().decode(cursor));
                String[] parts = decoded.split("\\|", 2);
                if (parts.length == 2) {
                    beforeId = parts[1];
                }
            } catch (Exception e) {
                log.warn("Invalid cursor: {}", cursor);
            }
        }

        SessionPage page = sessionManager.listSessionsPaginated(anchorToLatest, beforeId, limit);

        // 生成 nextCursor
        String nextCursor = null;
        if (page.hasMore() && !page.sessions().isEmpty()) {
            SessionSummary last = page.sessions().getLast();
            nextCursor = Base64.getEncoder().encodeToString(
                    (last.updatedAt() + "|" + last.id()).getBytes());
        }

        return ResponseEntity.ok(new SessionListResponse(
                page.sessions(), page.hasMore(), nextCursor));
    }

    /**
     * 获取单个会话详情。
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionData> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(getSessionOrThrow(sessionId));
    }

    /**
     * 删除会话。
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Boolean>> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 恢复会话 — 重新建立 WebSocket 连接。
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<ResumeSessionResponse> resumeSession(@PathVariable String sessionId) {
        SessionData data = sessionManager.resumeSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return ResponseEntity.ok(new ResumeSessionResponse(
                data.sessionId(),
                "/ws/session/" + data.sessionId(),
                data.messages()
        ));
    }

    /**
     * 压缩会话上下文 — 减少 Token 使用。
     */
    @PostMapping("/{sessionId}/compact")
    public ResponseEntity<CompactResponse> compactSession(@PathVariable String sessionId) {
        SessionData data = getSessionOrThrow(sessionId);

        // 使用 CompactService 执行压缩
        CompactService.CompactResult result = compactService.compact(
                data.messages(),
                128000, // 默认上下文窗口大小
                false);

        return ResponseEntity.ok(new CompactResponse(
                true, result.beforeTokens(), result.afterTokens()));
    }

    /**
     * 导出会话 — JSON 或 Markdown 格式。
     */
    @PostMapping("/{sessionId}/export")
    public ResponseEntity<byte[]> exportSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "json") String format) {
        SessionData data = getSessionOrThrow(sessionId);

        byte[] exportData;
        MediaType contentType;
        String filename = "session-" + sessionId + "." + format;

        if ("markdown".equals(format) || "md".equals(format)) {
            exportData = exportAsMarkdown(data).getBytes();
            contentType = MediaType.TEXT_PLAIN;
            filename = "session-" + sessionId + ".md";
        } else {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.findAndRegisterModules();
                exportData = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to export session as JSON", e);
            }
            contentType = MediaType.APPLICATION_JSON;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(contentType)
                .body(exportData);
    }

    /**
     * 获取会话消息列表 — 游标分页。
     */
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<MessageListResponse> getMessages(
            @PathVariable String sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        SessionData data = getSessionOrThrow(sessionId);
        List<Message> messages = data.messages();

        // P0: 简单截取 — 后续 Round 实现完整游标分页
        int startIndex = 0;
        if (cursor != null) {
            try {
                startIndex = Integer.parseInt(
                        new String(Base64.getDecoder().decode(cursor)));
            } catch (Exception ignored) {}
        }

        int endIndex = Math.min(startIndex + limit, messages.size());
        List<Message> pageMessages = messages.subList(startIndex, endIndex);
        boolean hasMore = endIndex < messages.size();
        String nextCursor = hasMore
                ? Base64.getEncoder().encodeToString(String.valueOf(endIndex).getBytes())
                : null;

        return ResponseEntity.ok(new MessageListResponse(pageMessages, hasMore, nextCursor));
    }

    // ───── 辅助方法 ─────

    private SessionData getSessionOrThrow(String sessionId) {
        return sessionManager.loadSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private String exportAsMarkdown(SessionData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Session: ").append(data.title() != null ? data.title() : data.sessionId())
                .append("\n\n");
        sb.append("- **Model**: ").append(data.model()).append("\n");
        sb.append("- **Created**: ").append(data.createdAt()).append("\n");
        sb.append("- **Messages**: ").append(data.messages().size()).append("\n\n");
        sb.append("---\n\n");

        for (Message msg : data.messages()) {
            if (msg instanceof Message.UserMessage user) {
                sb.append("## User\n\n");
                if (user.toolUseResult() != null) {
                    sb.append(user.toolUseResult()).append("\n\n");
                }
            } else if (msg instanceof Message.AssistantMessage assistant) {
                sb.append("## Assistant\n\n");
                if (assistant.content() != null) {
                    for (var block : assistant.content()) {
                        if (block instanceof com.aicodeassistant.model.ContentBlock.TextBlock text) {
                            sb.append(text.text()).append("\n\n");
                        }
                    }
                }
            } else if (msg instanceof Message.SystemMessage system) {
                sb.append("## System\n\n").append(system.content()).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ═══ DTO Records ═══

    public record CreateSessionRequest(
            String workingDirectory,
            String model,
            String permissionMode,
            String resumeSessionId
    ) {}

    public record CreateSessionResponse(
            String sessionId,
            String webSocketUrl,
            String model,
            String permissionMode,
            Instant createdAt
    ) {}

    public record SessionListResponse(
            List<SessionSummary> sessions,
            boolean hasMore,
            String nextCursor
    ) {}

    public record ResumeSessionResponse(
            String sessionId,
            String webSocketUrl,
            List<Message> messages
    ) {}

    public record CompactResponse(
            boolean success,
            int tokensBefore,
            int tokensAfter
    ) {}

    public record MessageListResponse(
            List<Message> messages,
            boolean hasMore,
            String nextCursor
    ) {}
}

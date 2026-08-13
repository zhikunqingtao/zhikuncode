package com.aicodeassistant.service;

import com.aicodeassistant.security.ManagedWorkspacePathResolver;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionFileAccessServiceTest {
    @TempDir Path temp;

    @Test
    void previewsOnlyRealFilesInsideTheSessionWorkspace() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace")).toRealPath();
        Path markdown = Files.writeString(root.resolve("report.md"), "# result");
        SessionFileAccessService service = service(root);

        var target = service.preview("s-1", "report.md");
        assertThat(target.path()).isEqualTo(markdown);
        assertThat(target.contentType()).contains("markdown");

        assertThatThrownBy(() -> service.preview("s-1", "../outside.md"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void rejectsSymlinkEvenWhenItPointsToAReadableFile() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace-link")).toRealPath();
        Path outside = Files.writeString(temp.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        SessionFileAccessService service = service(root);
        assertThatThrownBy(() -> service.preview("s-1", "link.txt"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void previewsSourceTextButLeavesHtmlForFileManagerHandling() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace-types")).toRealPath();
        Files.writeString(root.resolve("main.js"), "export const ready = true;");
        Files.writeString(root.resolve("index.html"), "<h1>result</h1>");
        SessionFileAccessService service = service(root);

        assertThat(service.preview("s-1", "main.js").contentType())
                .contains("text");
        assertThatThrownBy(() -> service.preview("s-1", "index.html"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(415));
    }

    @Test
    void revealsAWorkspaceFileThroughTheFileManager() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace-open")).toRealPath();
        Path html = Files.writeString(root.resolve("index.html"), "<h1>result</h1>");
        AtomicReference<Path> revealed = new AtomicReference<>();
        SessionFileAccessService service = service(root, path -> {
            revealed.set(path);
            return "FINDER";
        });

        assertThat(service.reveal("s-1", "index.html", "127.0.0.1"))
                .isEqualTo(new SessionFileAccessService.RevealResult(true, "FINDER"));
        assertThat(revealed).hasValue(html);
    }

    private SessionFileAccessService service(Path root) {
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.loadSession("s-1")).thenReturn(Optional.of(new SessionData(
                "s-1", "model", root.toString(), null, "active", List.of(),
                java.util.Map.of(), com.aicodeassistant.model.Usage.zero(), 0.0,
                null, Instant.now(), Instant.now())));
        ProjectWorkspaceService workspaces = mock(ProjectWorkspaceService.class);
        when(workspaces.requireCurrentBinding(root.toString())).thenReturn(root);
        when(workspaces.localDesktopAccessAllowed("127.0.0.1")).thenReturn(true);
        return new SessionFileAccessService(sessions, workspaces,
                new ManagedWorkspacePathResolver());
    }

    private SessionFileAccessService service(
            Path root,
            SessionFileAccessService.FileRevealer launcher) {
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.loadSession("s-1")).thenReturn(Optional.of(new SessionData(
                "s-1", "model", root.toString(), null, "active", List.of(),
                java.util.Map.of(), com.aicodeassistant.model.Usage.zero(), 0.0,
                null, Instant.now(), Instant.now())));
        ProjectWorkspaceService workspaces = mock(ProjectWorkspaceService.class);
        when(workspaces.requireCurrentBinding(root.toString())).thenReturn(root);
        when(workspaces.localDesktopAccessAllowed("127.0.0.1")).thenReturn(true);
        return new SessionFileAccessService(sessions, workspaces,
                new ManagedWorkspacePathResolver(), launcher);
    }
}

package com.aicodeassistant.service;

import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.security.ManagedWorkspacePathResolver;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class SessionFileAccessService {
    private static final long MAX_PREVIEW_BYTES = 50L * 1024 * 1024;
    private static final Set<String> INLINE_EXTENSIONS = Set.of(
            "pdf", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "txt", "md", "markdown", "json", "yaml", "yml", "xml", "csv", "log",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss", "less",
            "java", "kt", "kts", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
            "sql", "properties", "toml", "ini", "conf");
    private final SessionManager sessions;
    private final ProjectWorkspaceService workspaces;
    private final ManagedWorkspacePathResolver paths;
    private final FileRevealer fileRevealer;

    @org.springframework.beans.factory.annotation.Autowired
    public SessionFileAccessService(SessionManager sessions,
                                    ProjectWorkspaceService workspaces,
                                    ManagedWorkspacePathResolver paths) {
        this(sessions, workspaces, paths,
                SessionFileAccessService::revealInFileManager);
    }

    SessionFileAccessService(SessionManager sessions,
                             ProjectWorkspaceService workspaces,
                             ManagedWorkspacePathResolver paths,
                             FileRevealer fileRevealer) {
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.paths = paths;
        this.fileRevealer = fileRevealer;
    }

    public PreviewTarget preview(String sessionId, String rawPath) {
        Path path = resolve(sessionId, rawPath);
        String extension = extension(path);
        if (!INLINE_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "FILE_PREVIEW_REQUIRES_NATIVE_OPEN");
        }
        try {
            long size = Files.size(path);
            if (size > MAX_PREVIEW_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "FILE_PREVIEW_TOO_LARGE");
            }
            String contentType = Files.probeContentType(path);
            if (contentType == null) contentType = fallbackContentType(extension);
            return new PreviewTarget(path, contentType, size);
        } catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "FILE_PREVIEW_UNAVAILABLE", unavailable);
        }
    }

    public RevealResult reveal(String sessionId, String rawPath, String remoteAddress) {
        if (!workspaces.localDesktopAccessAllowed(remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "FILE_REVEAL_LOCAL_ONLY");
        }
        Path path = resolve(sessionId, rawPath);
        try {
            return new RevealResult(true, fileRevealer.reveal(path));
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "FILE_REVEAL_UNAVAILABLE", unavailable);
        }
    }

    private static String revealInFileManager(Path path) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder command;
        String application;
        if (os.contains("mac")) {
            command = new ProcessBuilder("/usr/bin/open", "-R", path.toString());
            application = "FINDER";
        } else if (os.contains("win")) {
            command = new ProcessBuilder("explorer.exe", "/select," + path);
            application = "EXPLORER";
        } else {
            command = new ProcessBuilder("xdg-open", path.getParent().toString());
            application = "FILE_MANAGER";
        }
        Process process = command
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("File manager reveal command failed");
        }
        return application;
    }

    private Path resolve(String sessionId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FILE_PATH_REQUIRED");
        }
        SessionData session = sessions.loadSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        Path root = workspaces.requireCurrentBinding(session.workingDir());
        try {
            Path prospective = paths.resolveProspective(Path.of(rawPath), root.toString());
            if (!Files.isRegularFile(prospective, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(prospective)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SESSION_FILE_NOT_FOUND");
            }
            Path canonical = prospective.toRealPath();
            if (!canonical.equals(prospective.toAbsolutePath().normalize())
                    || !canonical.startsWith(root)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "SESSION_FILE_OUTSIDE_WORKSPACE");
            }
            return canonical;
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (IllegalArgumentException forbidden) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SESSION_FILE_OUTSIDE_WORKSPACE", forbidden);
        } catch (IOException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "SESSION_FILE_NOT_FOUND", unavailable);
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String fallbackContentType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "json" -> "application/json;charset=UTF-8";
            case "md", "markdown" -> "text/markdown;charset=UTF-8";
            default -> "text/plain;charset=UTF-8";
        };
    }

    public record PreviewTarget(Path path, String contentType, long size) { }
    public record RevealResult(boolean revealed, String application) { }

    @FunctionalInterface
    interface FileRevealer {
        String reveal(Path path) throws Exception;
    }
}

package com.aicodeassistant.authorization;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 规范授权根目录和稳定工作区身份的唯一解析权威。
 * 资源边界始终是当前 worktree 根目录；关联 Git worktree 只通过共同 Git 目录共享 workspaceKey。
 */
@Component
public final class WorkspaceIdentityService {
    public record Identity(Path authorizationRoot, String workspaceKey) { }

    public Identity resolve(Path configuredRoot) {
        if (configuredRoot == null) {
            throw invalid("Workspace root is missing", null);
        }
        try {
            Path absolute = configuredRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Workspace root must be an existing directory", null);
            }
            Path root = absolute.toRealPath();
            Path identityPath = gitCommonDirectory(root);
            return new Identity(root, hash("workspace-v2\0" + identityPath));
        } catch (AuthorizationException denied) {
            throw denied;
        } catch (Exception failure) {
            throw invalid("Workspace root cannot be canonicalized", failure);
        }
    }

    private Path gitCommonDirectory(Path root) throws IOException {
        Path marker = root.resolve(".git");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return root;

        Path gitDirectory;
        if (Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) {
            gitDirectory = marker.toRealPath();
        } else if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(marker)) {
            String line = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (!line.startsWith("gitdir:")) {
                throw new IOException("Invalid .git indirection file");
            }
            String raw = line.substring("gitdir:".length()).strip();
            if (raw.isEmpty()) throw new IOException("Empty gitdir target");
            Path target = Path.of(raw);
            gitDirectory = (target.isAbsolute() ? target : root.resolve(target)).normalize().toRealPath();
        } else {
            throw new IOException("Unsafe .git marker");
        }

        Path commonMarker = gitDirectory.resolve("commondir");
        if (!Files.exists(commonMarker, LinkOption.NOFOLLOW_LINKS)) return gitDirectory;
        if (!Files.isRegularFile(commonMarker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(commonMarker)) {
            throw new IOException("Unsafe Git commondir marker");
        }
        String raw = Files.readString(commonMarker, StandardCharsets.UTF_8).strip();
        if (raw.isEmpty()) throw new IOException("Empty Git commondir");
        Path common = Path.of(raw);
        return (common.isAbsolute() ? common : gitDirectory.resolve(common)).normalize().toRealPath();
    }

    private static AuthorizationException invalid(String message, Throwable cause) {
        return cause == null
                ? new AuthorizationException("AUTHORIZATION_WORKSPACE_INVALID", message)
                : new AuthorizationException("AUTHORIZATION_WORKSPACE_INVALID", message, cause);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

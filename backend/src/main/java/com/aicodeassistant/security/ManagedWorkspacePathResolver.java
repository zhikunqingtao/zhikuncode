package com.aicodeassistant.security;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Canonicalizes prospective workspace targets and safely materializes missing parent directories. */
@Component
public class ManagedWorkspacePathResolver {
    private static final LinkOption[] NO_FOLLOW = { LinkOption.NOFOLLOW_LINKS };

    public Path resolveProspective(Path raw, String workspaceRoot) throws IOException {
        Path lexicalRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Path root = lexicalRoot.toRealPath();
        Path candidate;
        if (raw.isAbsolute()) {
            Path lexicalCandidate = raw.toAbsolutePath().normalize();
            candidate = lexicalCandidate.startsWith(lexicalRoot)
                    ? root.resolve(lexicalRoot.relativize(lexicalCandidate)).normalize()
                    : lexicalCandidate;
        } else candidate = root.resolve(raw).normalize();
        if (candidate.equals(root))
            throw new IllegalArgumentException("target is the workspace root itself");
        if (candidate.startsWith(root)) {
            validateExistingSegments(root, candidate);
        }
        return candidate;
    }

    public MaterializedTarget materializeParents(Path prospective, String workspaceRoot) throws IOException {
        Path root = root(workspaceRoot);
        Path candidate = resolveProspective(prospective, workspaceRoot);
        Path parent = candidate.getParent();
        if (parent == null) throw new IllegalArgumentException("target parent is required");
        List<Path> created = new ArrayList<>();
        if (candidate.startsWith(root)) {
            Path current = root;
            for (Path segment : root.relativize(parent)) {
                current = current.resolve(segment);
                if (!Files.exists(current, NO_FOLLOW)) {
                    try {
                        Files.createDirectory(current);
                        created.add(current);
                    } catch (FileAlreadyExistsException raced) {
                        // Validate the winner below.
                    }
                }
                assertRealDirectory(root, current);
            }
            validateExistingSegments(root, candidate);
        } else {
            // 用户显式提供 workspace 外绝对路径：跳过 workspace 边界校验，仅确保父目录存在且为真实目录。
            if (!Files.exists(parent, NO_FOLLOW)) {
                Files.createDirectories(parent);
            }
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, NO_FOLLOW))
                throw new IllegalArgumentException("target parent is not a real directory: " + parent);
        }
        return new MaterializedTarget(candidate, List.copyOf(created));
    }

    public void assertUnchanged(Path candidate, String workspaceRoot) throws IOException {
        Path resolved = resolveProspective(candidate, workspaceRoot);
        if (!resolved.equals(candidate.toAbsolutePath().normalize()))
            throw new IOException("target path changed during write");
        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent, NO_FOLLOW))
            throw new IOException("target parent is not a real directory");
        Path root = root(workspaceRoot);
        if (candidate.startsWith(root)) {
            assertRealDirectory(root, parent);
        }
        // workspace 外绝对路径：不做 workspace 边界校验，父目录存在性已在上方检查。
    }

    public void cleanupEmptyDirectories(List<Path> createdDirectories) {
        List<Path> reversed = new ArrayList<>(createdDirectories);
        Collections.reverse(reversed);
        for (Path directory : reversed) {
            try { Files.delete(directory); }
            catch (IOException ignored) { break; }
        }
    }

    private void validateExistingSegments(Path root, Path candidate) throws IOException {
        Path current = root;
        Path parent = candidate.getParent();
        if (parent != null) {
            for (Path segment : root.relativize(parent)) {
                current = current.resolve(segment);
                if (!Files.exists(current, NO_FOLLOW)) break;
                assertRealDirectory(root, current);
            }
        }
        if (Files.exists(candidate, NO_FOLLOW) && Files.isSymbolicLink(candidate))
            throw new IllegalArgumentException("symbolic-link targets are not writable");
    }

    private static void assertRealDirectory(Path root, Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NO_FOLLOW))
            throw new IllegalArgumentException("path segment is not a real directory: " + directory);
        Path real = directory.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("path segment escapes workspace");
    }

    private static Path root(String workspaceRoot) throws IOException {
        if (workspaceRoot == null || workspaceRoot.isBlank())
            throw new IllegalArgumentException("working directory is required");
        return Path.of(workspaceRoot).toRealPath();
    }

    public record MaterializedTarget(Path path, List<Path> createdDirectories) { }
}

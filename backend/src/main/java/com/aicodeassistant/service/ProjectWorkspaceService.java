package com.aicodeassistant.service;

import com.aicodeassistant.exception.WorkspaceException;
import com.aicodeassistant.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Resolves a Session's persisted trusted workspace and default relative-path
 * root. Individual tools still apply their own path and authorization rules.
 */
@Service
public class ProjectWorkspaceService {

    private static final Logger log =
            LoggerFactory.getLogger(ProjectWorkspaceService.class);

    private final ProjectRepository projects;
    private final List<Path> allowedRoots;
    private final String configuredDefaultRoot;
    private final boolean localPickerEnabled;
    private final NativeDirectoryPicker nativeDirectoryPicker;

    @Autowired
    public ProjectWorkspaceService(
            ProjectRepository projects,
            @Value("${zhikuncode.workspace.allowed-roots:}")
            String configuredAllowedRoots,
            @Value("${zhikuncode.workspace.default-root:${user.dir}}")
            String configuredDefaultRoot,
            @Value("${zhikuncode.workspace.local-picker-enabled:false}")
            boolean localPickerEnabled) {
        this(projects, configuredAllowedRoots, configuredDefaultRoot,
                localPickerEnabled, new SystemNativeDirectoryPicker());
    }

    ProjectWorkspaceService(
            ProjectRepository projects,
            String configuredAllowedRoots,
            String configuredDefaultRoot,
            boolean localPickerEnabled,
            NativeDirectoryPicker nativeDirectoryPicker) {
        this.projects = projects;
        this.allowedRoots = parseAllowedRoots(configuredAllowedRoots);
        this.configuredDefaultRoot = configuredDefaultRoot;
        this.localPickerEnabled = localPickerEnabled;
        this.nativeDirectoryPicker = nativeDirectoryPicker;
    }

    public Project create(
            String name, String workspaceRoot, String remoteAddress) {
        assertCreateAllowed(remoteAddress);
        String normalizedName = requireName(name);
        Path canonical = canonicalizeForCreate(workspaceRoot);
        Project project = new Project(
                UUID.randomUUID().toString(),
                normalizedName,
                canonical.toString(),
                Instant.now());
        try {
            projects.create(project);
        } catch (DataIntegrityViolationException duplicate) {
            throw failure(HttpStatus.CONFLICT, "PROJECT_PATH_DUPLICATE",
                    "A Project already uses this workspace");
        }
        return project;
    }

    public List<Project> list() {
        return projects.list();
    }

    /**
     * Revokes a global Project authorization. Repeating the operation is safe:
     * a missing row is reported as {@code revoked=false}, not as a failure.
     */
    public RevocationResult revoke(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "PROJECT_ID_REQUIRED",
                    "projectId is required");
        }
        String normalized = projectId.trim();
        return new RevocationResult(
                normalized, projects.deleteById(normalized));
    }

    /**
     * Lists server-side directories without exposing an unrestricted filesystem
     * browser to remote callers. Configured allowed roots are strict browser
     * boundaries. Without that configuration, an explicitly enabled loopback
     * picker starts at the server default and may navigate the local filesystem
     * roots, matching the paths that local Project creation already accepts.
     */
    public DirectoryListing browseDirectories(
            String requestedPath, String remoteAddress) {
        assertBrowseAllowed(remoteAddress);
        List<Path> roots = currentBrowseRoots();
        Path current = resolveBrowseDirectory(requestedPath, roots);
        Path owningRoot = roots.stream()
                .filter(current::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount))
                .orElseThrow(() -> failure(
                        HttpStatus.FORBIDDEN,
                        "DIRECTORY_BROWSE_OUTSIDE_ROOTS",
                        "Directory is outside the configured browser roots"));

        List<DirectoryEntry> directories;
        try (Stream<Path> children = Files.list(current)) {
            directories = children
                    .filter(child -> isBrowsableDirectory(
                            child, owningRoot))
                    .map(ProjectWorkspaceService::directoryEntry)
                    .sorted(Comparator
                            .comparing(DirectoryEntry::name,
                                    String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(DirectoryEntry::path))
                    .toList();
        } catch (AccessDeniedException denied) {
            throw failure(HttpStatus.FORBIDDEN,
                    "WORKSPACE_ACCESS_DENIED",
                    "Directory is not accessible");
        } catch (Exception unavailable) {
            throw failure(HttpStatus.CONFLICT,
                    "WORKSPACE_UNAVAILABLE",
                    "Directory is no longer available");
        }

        String parent = current.equals(owningRoot)
                ? null : current.getParent().toString();
        return new DirectoryListing(
                roots.stream()
                        .map(Path::toString)
                        .toList(),
                current.toString(), parent, directories,
                nativePickerAvailable(remoteAddress));
    }

    /**
     * Whether this direct request may open a native host folder chooser.
     */
    public boolean nativePickerAvailable(String remoteAddress) {
        return localPickerEnabled
                && allowedRoots.isEmpty()
                && isLoopback(remoteAddress)
                && nativeDirectoryPicker.isAvailable();
    }

    /** 直接本机桌面能力的共同边界；不依赖文件夹选择器实现是否可用。 */
    public boolean localDesktopAccessAllowed(String remoteAddress) {
        return localPickerEnabled && allowedRoots.isEmpty() && isLoopback(remoteAddress);
    }

    /**
     * Opens the native chooser and returns the selected directory listing.
     * Selection alone never creates or authorizes a Project.
     */
    public Optional<DirectoryListing> pickDirectory(
            String remoteAddress) {
        assertNativePickerAllowed(remoteAddress);
        final Optional<String> selected;
        try {
            selected = nativeDirectoryPicker.pick();
        } catch (NativeDirectoryPicker.BusyException busy) {
            throw failure(HttpStatus.CONFLICT,
                    "NATIVE_PICKER_BUSY",
                    "Another folder chooser is already open");
        } catch (NativeDirectoryPicker.TimeoutException timeout) {
            throw failure(HttpStatus.GATEWAY_TIMEOUT,
                    "NATIVE_PICKER_TIMEOUT",
                    "The folder chooser timed out");
        } catch (NativeDirectoryPicker.UnavailableException unavailable) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "NATIVE_PICKER_UNAVAILABLE",
                    "The native folder chooser is unavailable");
        }
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        Path canonical = canonicalizeForCreate(selected.get());
        return Optional.of(browseDirectories(
                canonical.toString(), remoteAddress));
    }

    /**
     * Resolves either an explicitly registered Project or the server-owned
     * default. Client-provided raw paths are deliberately not accepted here.
     */
    public Path resolveWorkspace(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return canonicalizeForCreate(configuredDefaultRoot);
        }
        Project project = projects.findById(projectId)
                .orElseThrow(() -> failure(
                        HttpStatus.NOT_FOUND,
                        "PROJECT_NOT_FOUND",
                        "Project with id '" + projectId + "' was not found"));
        return requireCurrentBinding(project.workspaceRoot());
    }

    /**
     * A trusted file scope is an exact, canonical root explicitly registered
     * as a Project. The configured default remains a compatibility root,
     * but it does not grant automatic write authorization.
     */
    public boolean isTrustedFileScope(Path workspace) {
        final Path saved;
        final Path canonical;
        try {
            saved = workspace.toAbsolutePath().normalize();
            canonical = saved.toRealPath();
        } catch (Exception unavailable) {
            return false;
        }
        if (!canonical.equals(saved)) {
            return false;
        }

        final Project project;
        try {
            project = projects.findByWorkspaceRoot(
                    canonical.toString()).orElse(null);
        } catch (RuntimeException lookupFailure) {
            log.warn("Project trust lookup failed; denying automatic file scope");
            log.debug("Project trust lookup failure detail", lookupFailure);
            return false;
        }
        if (project == null) {
            return false;
        }
        try {
            return requireCurrentBinding(project.workspaceRoot())
                    .equals(canonical);
        } catch (WorkspaceException unavailableProject) {
            return false;
        }
    }

    public Path requireCurrentBinding(String savedWorkspaceRoot) {
        final Path saved;
        try {
            saved = Path.of(savedWorkspaceRoot)
                    .toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw failure(HttpStatus.CONFLICT, "WORKSPACE_UNAVAILABLE",
                    "Saved workspace path is invalid");
        }

        final Path current;
        try {
            current = saved.toRealPath();
        } catch (NoSuchFileException missing) {
            throw failure(HttpStatus.CONFLICT, "WORKSPACE_UNAVAILABLE",
                    "Workspace is no longer available");
        } catch (AccessDeniedException denied) {
            throw failure(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                    "Workspace is not accessible");
        } catch (Exception unavailable) {
            throw failure(HttpStatus.CONFLICT, "WORKSPACE_UNAVAILABLE",
                    "Workspace is no longer available");
        }

        if (!Files.isDirectory(current) || !Files.isReadable(current)) {
            throw failure(HttpStatus.CONFLICT, "WORKSPACE_UNAVAILABLE",
                    "Workspace is no longer an accessible directory");
        }
        if (!current.equals(saved)) {
            throw failure(HttpStatus.CONFLICT, "WORKSPACE_REBOUND",
                    "Workspace path now resolves to a different directory");
        }
        assertWithinAllowedRoots(current);
        return current;
    }

    public Path canonicalizeForCreate(String value) {
        if (value == null || value.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "WORKSPACE_REQUIRED",
                    "workspaceRoot is required");
        }

        final Path path;
        try {
            path = Path.of(value.trim());
        } catch (InvalidPathException invalid) {
            throw failure(HttpStatus.BAD_REQUEST, "WORKSPACE_PATH_INVALID",
                    "workspaceRoot is not a valid path");
        }
        if (!path.isAbsolute()) {
            throw failure(
                    HttpStatus.BAD_REQUEST,
                    "WORKSPACE_ABSOLUTE_REQUIRED",
                    "workspaceRoot must be absolute");
        }

        final Path canonical;
        try {
            canonical = path.toRealPath();
        } catch (NoSuchFileException missing) {
            throw failure(HttpStatus.BAD_REQUEST, "WORKSPACE_NOT_FOUND",
                    "workspaceRoot does not exist");
        } catch (AccessDeniedException denied) {
            throw failure(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                    "workspaceRoot is not accessible");
        } catch (Exception invalid) {
            throw failure(HttpStatus.BAD_REQUEST, "WORKSPACE_PATH_INVALID",
                    "workspaceRoot cannot be resolved");
        }

        if (!Files.isDirectory(canonical)) {
            throw failure(HttpStatus.BAD_REQUEST, "WORKSPACE_NOT_DIRECTORY",
                    "workspaceRoot must be a directory");
        }
        if (!Files.isReadable(canonical)) {
            throw failure(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                    "workspaceRoot is not readable");
        }
        if (canonical.getParent() == null) {
            throw failure(HttpStatus.FORBIDDEN, "WORKSPACE_ROOT_FORBIDDEN",
                    "The filesystem root cannot be a Project");
        }
        assertWithinAllowedRoots(canonical);
        return canonical;
    }

    private void assertCreateAllowed(String remoteAddress) {
        if (!allowedRoots.isEmpty()) return;
        assertLocalPickerEnabled();
        if (!isLoopback(remoteAddress)) {
            throw failure(
                    HttpStatus.FORBIDDEN,
                    "REMOTE_PROJECT_CREATE_FORBIDDEN",
                    "Remote Project creation requires "
                            + "ZHIKUN_WORKSPACE_ALLOWED_ROOTS");
        }
    }

    private void assertBrowseAllowed(String remoteAddress) {
        if (!allowedRoots.isEmpty()) return;
        assertLocalPickerEnabled();
        if (!isLoopback(remoteAddress)) {
            throw failure(
                    HttpStatus.FORBIDDEN,
                    "REMOTE_DIRECTORY_BROWSE_FORBIDDEN",
                    "Remote directory browsing requires "
                            + "ZHIKUN_WORKSPACE_ALLOWED_ROOTS");
        }
    }

    private void assertNativePickerAllowed(String remoteAddress) {
        if (!localPickerEnabled || !allowedRoots.isEmpty()
                || !isLoopback(remoteAddress)) {
            throw failure(HttpStatus.FORBIDDEN,
                    "NATIVE_PICKER_FORBIDDEN",
                    "Native folder selection is only available for direct "
                            + "local desktop access");
        }
        if (!nativeDirectoryPicker.isAvailable()) {
            throw failure(HttpStatus.NOT_IMPLEMENTED,
                    "NATIVE_PICKER_UNAVAILABLE",
                    "The native folder chooser is unavailable");
        }
    }

    private void assertLocalPickerEnabled() {
        if (!localPickerEnabled) {
            throw failure(
                    HttpStatus.FORBIDDEN,
                    "LOCAL_PICKER_DISABLED",
                    "Directory selection is disabled. For a direct local "
                            + "desktop server, set "
                            + "ZHIKUN_LOCAL_PICKER_ENABLED=true; for remote "
                            + "or proxied deployments, configure "
                            + "ZHIKUN_WORKSPACE_ALLOWED_ROOTS instead");
        }
    }

    private List<Path> currentBrowseRoots() {
        if (allowedRoots.isEmpty()) {
            List<Path> roots = new ArrayList<>();
            for (Path root : FileSystems.getDefault()
                    .getRootDirectories()) {
                try {
                    Path canonical = root.toRealPath();
                    if (Files.isDirectory(canonical)
                            && Files.isReadable(canonical)) {
                        roots.add(canonical);
                    }
                } catch (Exception unavailable) {
                    // An unavailable drive/root is omitted from this snapshot.
                }
            }
            List<Path> distinctRoots = roots.stream()
                    .distinct()
                    .toList();
            if (distinctRoots.isEmpty()) {
                throw failure(HttpStatus.CONFLICT,
                        "WORKSPACE_UNAVAILABLE",
                        "No local filesystem roots are available");
            }
            return distinctRoots;
        }
        List<Path> roots = allowedRoots.stream()
                .map(root -> {
                    try {
                        return requireCurrentBinding(root.toString());
                    } catch (WorkspaceException unavailable) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (roots.isEmpty()) {
            throw failure(HttpStatus.CONFLICT,
                    "WORKSPACE_UNAVAILABLE",
                    "Configured directory browser roots are unavailable");
        }
        return roots;
    }

    private Path resolveBrowseDirectory(
            String requestedPath, List<Path> roots) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return allowedRoots.isEmpty()
                    ? canonicalizeForCreate(configuredDefaultRoot)
                    : roots.getFirst();
        }

        final Path raw;
        try {
            raw = Path.of(requestedPath.trim());
        } catch (InvalidPathException invalid) {
            throw failure(HttpStatus.BAD_REQUEST,
                    "WORKSPACE_PATH_INVALID",
                    "path is not a valid directory path");
        }
        if (!raw.isAbsolute()) {
            throw failure(HttpStatus.BAD_REQUEST,
                    "WORKSPACE_ABSOLUTE_REQUIRED",
                    "path must be absolute");
        }
        Path lexical = raw.toAbsolutePath().normalize();
        if (!lexical.equals(raw.toAbsolutePath())) {
            throw failure(HttpStatus.BAD_REQUEST,
                    "DIRECTORY_PATH_NOT_CANONICAL",
                    "path must not contain relative segments");
        }

        final Path canonical;
        try {
            canonical = lexical.toRealPath();
        } catch (NoSuchFileException missing) {
            throw failure(HttpStatus.BAD_REQUEST,
                    "WORKSPACE_NOT_FOUND",
                    "Directory does not exist");
        } catch (AccessDeniedException denied) {
            throw failure(HttpStatus.FORBIDDEN,
                    "WORKSPACE_ACCESS_DENIED",
                    "Directory is not accessible");
        } catch (Exception unavailable) {
            throw failure(HttpStatus.BAD_REQUEST,
                    "WORKSPACE_PATH_INVALID",
                    "Directory cannot be resolved");
        }
        if (!canonical.equals(lexical)) {
            throw failure(HttpStatus.CONFLICT,
                    "WORKSPACE_REBOUND",
                    "Directory path resolves through an alias or symbolic link");
        }
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(canonical)) {
            throw failure(HttpStatus.FORBIDDEN,
                    "WORKSPACE_ACCESS_DENIED",
                    "Directory is not readable");
        }
        if (roots.stream().noneMatch(canonical::startsWith)) {
            throw failure(HttpStatus.FORBIDDEN,
                    "DIRECTORY_BROWSE_OUTSIDE_ROOTS",
                    "Directory is outside the configured browser roots");
        }
        return canonical;
    }

    private static boolean isBrowsableDirectory(
            Path child, Path owningRoot) {
        try {
            if (Files.isSymbolicLink(child)
                    || !Files.isDirectory(
                            child, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(child)) {
                return false;
            }
            Path lexical = child.toAbsolutePath().normalize();
            Path canonical = child.toRealPath();
            return canonical.equals(lexical)
                    && canonical.startsWith(owningRoot);
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static DirectoryEntry directoryEntry(Path directory) {
        Path name = directory.getFileName();
        return new DirectoryEntry(
                name == null ? directory.toString() : name.toString(),
                directory.toString());
    }

    private void assertWithinAllowedRoots(Path canonical) {
        if (!allowedRoots.isEmpty()
                && allowedRoots.stream().noneMatch(canonical::startsWith)) {
            throw failure(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED",
                    "Workspace is outside configured allowed roots");
        }
    }

    private static List<Path> parseAllowedRoots(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        Path root = Path.of(value).toRealPath();
                        if (!Files.isDirectory(root)) {
                            throw new IllegalArgumentException(
                                    "not a directory");
                        }
                        return root;
                    } catch (Exception invalid) {
                        throw new IllegalStateException(
                                "Configured workspace allowed root is "
                                        + "unavailable: " + value,
                                invalid);
                    }
                })
                .distinct()
                .toList();
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()
                || name.trim().length() > 80) {
            throw failure(
                    HttpStatus.BAD_REQUEST,
                    "PROJECT_NAME_INVALID",
                    "Project name must contain 1 to 80 characters");
        }
        return name.trim();
    }

    private static boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()
                || !remoteAddress.matches("^[0-9A-Fa-f:.%]+$")) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress)
                    .isLoopbackAddress();
        } catch (Exception invalid) {
            return false;
        }
    }

    private static WorkspaceException failure(
            HttpStatus status, String code, String message) {
        return new WorkspaceException(status, code, message);
    }

    public record RevocationResult(
            String projectId,
            boolean revoked
    ) {}

    public record DirectoryEntry(
            String name,
            String path
    ) {}

    public record DirectoryListing(
            List<String> roots,
            String current,
            String parent,
            List<DirectoryEntry> directories,
            boolean nativePickerAvailable
    ) {}
}

package com.aicodeassistant.service;

import com.aicodeassistant.exception.WorkspaceException;
import com.aicodeassistant.model.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectWorkspaceServiceTest {

    @TempDir
    Path temp;

    @Test
    void storesCanonicalRootAndRejectsDuplicates()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("workspace"));
        Path alias = temp.resolve("workspace-link");
        Files.createSymbolicLink(alias, workspace);
        ProjectRepository projects =
                mock(ProjectRepository.class);
        ProjectWorkspaceService service =
                service(projects, "", workspace);

        Project created = service.create(
                " Demo ", alias.toString(), "127.0.0.1");

        assertThat(created.name()).isEqualTo("Demo");
        assertThat(created.workspaceRoot()).isEqualTo(
                workspace.toRealPath().toString());
        ArgumentCaptor<Project> saved =
                ArgumentCaptor.forClass(Project.class);
        verify(projects).create(saved.capture());
        assertThat(saved.getValue()).isEqualTo(created);

        doThrow(new DataIntegrityViolationException(
                "duplicate"))
                .when(projects).create(any(Project.class));
        assertCode(() -> service.create(
                "Again", workspace.toString(), "::1"),
                "PROJECT_PATH_DUPLICATE");
    }

    @Test
    void enforcesAllowedRootsAndRemoteCreationPolicy()
            throws Exception {
        Path allowed = Files.createDirectory(
                temp.resolve("allowed"));
        Path workspace = Files.createDirectory(
                allowed.resolve("workspace"));
        Path outside = Files.createDirectory(
                temp.resolve("outside"));
        ProjectRepository projects =
                mock(ProjectRepository.class);
        ProjectWorkspaceService restricted =
                service(projects, allowed.toString(), workspace);

        assertThat(restricted.canonicalizeForCreate(
                workspace.toString()))
                .isEqualTo(workspace.toRealPath());
        assertCode(() -> restricted.canonicalizeForCreate(
                outside.toString()), "WORKSPACE_ACCESS_DENIED");
        assertCode(() -> restricted.canonicalizeForCreate(
                "relative"), "WORKSPACE_ABSOLUTE_REQUIRED");

        ProjectWorkspaceService localOnly =
                service(projects, "", workspace);
        assertCode(() -> localOnly.create(
                "Remote", workspace.toString(), "192.0.2.10"),
                "REMOTE_PROJECT_CREATE_FORBIDDEN");
    }

    @Test
    void resolvesDefaultOrRegisteredProjectAndTrustsOnlyExactRoots()
            throws Exception {
        Path defaultRoot = Files.createDirectory(
                temp.resolve("default")).toRealPath();
        Path projectRoot = Files.createDirectory(
                temp.resolve("project")).toRealPath();
        Path child = Files.createDirectory(
                projectRoot.resolve("child")).toRealPath();
        Path alias = temp.resolve("project-alias");
        Files.createSymbolicLink(alias, projectRoot);
        ProjectRepository projects =
                mock(ProjectRepository.class);
        Project project = new Project(
                "p1", "Project", projectRoot.toString(),
                Instant.now());
        when(projects.findById("p1"))
                .thenReturn(Optional.of(project));
        when(projects.findByWorkspaceRoot(
                projectRoot.toString()))
                .thenReturn(Optional.of(project));
        ProjectWorkspaceService service =
                service(projects, "", defaultRoot);

        assertThat(service.resolveWorkspace(null))
                .isEqualTo(defaultRoot);
        assertThat(service.resolveWorkspace("p1"))
                .isEqualTo(projectRoot);
        assertThat(service.isTrustedFileScope(defaultRoot))
                .isFalse();
        assertThat(service.isTrustedFileScope(projectRoot))
                .isTrue();
        assertThat(service.isTrustedFileScope(alias))
                .isFalse();
        assertThat(service.isTrustedFileScope(child))
                .isFalse();
        assertCode(() -> service.resolveWorkspace("missing"),
                "PROJECT_NOT_FOUND");
    }

    @Test
    void detectsDeletedAndReboundWorkspace()
            throws Exception {
        Path saved = Files.createDirectory(
                temp.resolve("saved")).toRealPath();
        Path replacement = Files.createDirectory(
                temp.resolve("replacement")).toRealPath();
        ProjectWorkspaceService service = service(
                mock(ProjectRepository.class), "", replacement);

        Files.delete(saved);
        assertCode(() -> service.requireCurrentBinding(
                saved.toString()), "WORKSPACE_UNAVAILABLE");

        Files.createSymbolicLink(saved, replacement);
        assertCode(() -> service.requireCurrentBinding(
                saved.toString()), "WORKSPACE_REBOUND");
    }

    @Test
    void trustLookupFailureIsFailClosed() throws Exception {
        Path defaultRoot = Files.createDirectory(
                temp.resolve("default-fail-closed")).toRealPath();
        Path candidate = Files.createDirectory(
                temp.resolve("candidate")).toRealPath();
        ProjectRepository projects =
                mock(ProjectRepository.class);
        when(projects.findByWorkspaceRoot(
                candidate.toString()))
                .thenThrow(new IllegalStateException(
                        "database unavailable"));
        ProjectWorkspaceService service =
                service(projects, "", defaultRoot);

        assertThat(service.isTrustedFileScope(candidate))
                .isFalse();
    }

    @Test
    void revokeIsIdempotentAndReportsWhetherTrustExisted() {
        ProjectRepository projects =
                mock(ProjectRepository.class);
        when(projects.deleteById("p1"))
                .thenReturn(true, false);
        ProjectWorkspaceService service = service(
                projects, "", temp);

        assertThat(service.revoke(" p1 "))
                .isEqualTo(new ProjectWorkspaceService.RevocationResult(
                        "p1", true));
        assertThat(service.revoke("p1"))
                .isEqualTo(new ProjectWorkspaceService.RevocationResult(
                        "p1", false));
        assertCode(() -> service.revoke("  "),
                "PROJECT_ID_REQUIRED");
    }

    @Test
    void localBrowserStartsAtDefaultAndCanNavigateFilesystemRoots()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("browser-root")).toRealPath();
        Path alpha = Files.createDirectory(
                root.resolve("alpha")).toRealPath();
        Files.createDirectory(root.resolve("Beta"));
        Files.writeString(root.resolve("file.txt"), "not a directory");
        Path external = Files.createDirectory(
                temp.resolve("external-browser-root"))
                .toRealPath();
        Path filesystemRoot = root.getRoot().toRealPath();
        Files.createSymbolicLink(
                root.resolve("external-link"), external);
        ProjectWorkspaceService service = service(
                mock(ProjectRepository.class), "", root);

        ProjectWorkspaceService.DirectoryListing listing =
                service.browseDirectories(null, "127.0.0.1");

        assertThat(listing.roots())
                .contains(filesystemRoot.toString());
        assertThat(listing.current()).isEqualTo(root.toString());
        assertThat(listing.parent()).isEqualTo(
                root.getParent().toString());
        assertThat(listing.directories())
                .extracting(ProjectWorkspaceService
                        .DirectoryEntry::name)
                .containsExactly("alpha", "Beta");

        ProjectWorkspaceService.DirectoryListing child =
                service.browseDirectories(
                        alpha.toString(), "::1");
        assertThat(child.current()).isEqualTo(alpha.toString());
        assertThat(child.parent()).isEqualTo(root.toString());
        assertThat(service.browseDirectories(
                        external.toString(), "127.0.0.1")
                .current()).isEqualTo(external.toString());
        ProjectWorkspaceService.DirectoryListing rootListing =
                service.browseDirectories(
                        filesystemRoot.toString(), "127.0.0.1");
        assertThat(rootListing.current())
                .isEqualTo(filesystemRoot.toString());
        assertThat(rootListing.parent()).isNull();
        assertCode(() -> service.create(
                        "Filesystem root", filesystemRoot.toString(),
                        "127.0.0.1"),
                "WORKSPACE_ROOT_FORBIDDEN");
        assertCode(() -> service.browseDirectories(
                        root.resolve("external-link").toString(),
                        "127.0.0.1"),
                "WORKSPACE_REBOUND");
    }

    @Test
    void directoryBrowserRequiresLoopbackWithoutAllowedRoots()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("local-browser-root")).toRealPath();
        ProjectWorkspaceService localOnly = service(
                mock(ProjectRepository.class), "", root);

        assertCode(() -> localOnly.browseDirectories(
                        null, "192.0.2.10"),
                "REMOTE_DIRECTORY_BROWSE_FORBIDDEN");

        ProjectWorkspaceService restricted = service(
                mock(ProjectRepository.class),
                root.toString(), root);
        assertThat(restricted.browseDirectories(
                        null, "192.0.2.10").roots())
                .containsExactly(root.toString());
        assertCode(() -> restricted.browseDirectories(
                        temp.toRealPath().toString(), "192.0.2.10"),
                "DIRECTORY_BROWSE_OUTSIDE_ROOTS");
    }

    @Test
    void reverseProxyLoopbackPeerDoesNotEnableLocalPickerByDefault()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("explicit-local-picker-root")).toRealPath();
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectWorkspaceService defaultSafe = new ProjectWorkspaceService(
                projects, "", root.toString(), false);

        assertCode(() -> defaultSafe.browseDirectories(
                        null, "127.0.0.1"),
                "LOCAL_PICKER_DISABLED");
        assertCode(() -> defaultSafe.create(
                        "Proxy peer", root.toString(), "127.0.0.1"),
                "LOCAL_PICKER_DISABLED");

        ProjectWorkspaceService explicitlyLocal =
                new ProjectWorkspaceService(
                        projects, "", root.toString(), true);
        assertThat(explicitlyLocal.browseDirectories(
                        null, "127.0.0.1").roots())
                .contains(root.getRoot().toRealPath().toString());

        ProjectWorkspaceService configuredRoots =
                new ProjectWorkspaceService(
                        projects, root.toString(),
                        root.toString(), false);
        assertThat(configuredRoots.browseDirectories(
                        null, "192.0.2.10").roots())
                .containsExactly(root.toString());
    }

    @Test
    void nativePickerIsOnlyAdvertisedForDirectLoopbackDesktopUse()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("native-picker-root")).toRealPath();
        NativeDirectoryPicker picker = mock(NativeDirectoryPicker.class);
        when(picker.isAvailable()).thenReturn(true);
        ProjectRepository projects = mock(ProjectRepository.class);

        assertThat(nativeService(
                projects, "", root, true, picker)
                .browseDirectories(null, "127.0.0.1")
                .nativePickerAvailable()).isTrue();
        assertThat(nativeService(
                projects, "", root, true, picker)
                .nativePickerAvailable("192.0.2.10")).isFalse();
        assertThat(nativeService(
                projects, "", root, false, picker)
                .nativePickerAvailable("127.0.0.1")).isFalse();
        assertThat(nativeService(
                projects, root.toString(), root,
                true, picker)
                .nativePickerAvailable("127.0.0.1")).isFalse();
    }

    @Test
    void nativePickerCanonicalizesSelectionAndRecognizesCancellation()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("native-selection-root")).toRealPath();
        Path selected = Files.createDirectory(
                root.resolve("selected")).toRealPath();
        Path alias = root.resolve("selected-alias");
        Files.createSymbolicLink(alias, selected);
        NativeDirectoryPicker picker = mock(NativeDirectoryPicker.class);
        when(picker.isAvailable()).thenReturn(true);
        when(picker.pick()).thenReturn(
                Optional.of(alias.toString()), Optional.empty());
        ProjectWorkspaceService service = nativeService(
                mock(ProjectRepository.class), "", root,
                true, picker);

        Optional<ProjectWorkspaceService.DirectoryListing> listing =
                service.pickDirectory("127.0.0.1");

        assertThat(listing).isPresent();
        assertThat(listing.orElseThrow().current())
                .isEqualTo(selected.toString());
        assertThat(listing.orElseThrow().nativePickerAvailable())
                .isTrue();
        assertThat(service.pickDirectory("127.0.0.1")).isEmpty();
    }

    @Test
    void nativePickerRejectsUnsafeInvocationAndMapsProcessFailures()
            throws Exception {
        Path root = Files.createDirectory(
                temp.resolve("native-picker-errors")).toRealPath();
        NativeDirectoryPicker picker = mock(NativeDirectoryPicker.class);
        when(picker.isAvailable()).thenReturn(true);
        ProjectWorkspaceService service = nativeService(
                mock(ProjectRepository.class), "", root,
                true, picker);

        assertCode(() -> service.pickDirectory("192.0.2.10"),
                "NATIVE_PICKER_FORBIDDEN");

        doThrow(new NativeDirectoryPicker.BusyException())
                .when(picker).pick();
        assertCode(() -> service.pickDirectory("127.0.0.1"),
                "NATIVE_PICKER_BUSY");

        doThrow(new NativeDirectoryPicker.TimeoutException())
                .when(picker).pick();
        assertCode(() -> service.pickDirectory("127.0.0.1"),
                "NATIVE_PICKER_TIMEOUT");

    }

    @Test
    void filePickerReturnsCanonicalMetadataAndCancellation() throws Exception {
        Path root = Files.createDirectory(temp.resolve("file-picker-root")).toRealPath();
        Path file = Files.writeString(root.resolve("notes.txt"), "hello").toRealPath();
        Path alias = root.resolve("notes-link.txt");
        Files.createSymbolicLink(alias, file);
        NativeDirectoryPicker picker = mock(NativeDirectoryPicker.class);
        when(picker.isAvailable()).thenReturn(true);
        when(picker.pickFile()).thenReturn(Optional.of(alias.toString()), Optional.empty());
        ProjectWorkspaceService service = nativeService(
                mock(ProjectRepository.class), "", root, true, picker);

        var selected = service.pickFile("127.0.0.1").orElseThrow();
        assertThat(selected.path()).isEqualTo(file.toString());
        assertThat(selected.name()).isEqualTo("notes.txt");
        assertThat(selected.size()).isEqualTo(5);
        assertThat(service.pickFile("::1")).isEmpty();
    }

    @Test
    void filePickerRejectsInvalidSelections() throws Exception {
        Path root = Files.createDirectory(temp.resolve("file-picker-errors")).toRealPath();
        NativeDirectoryPicker picker = mock(NativeDirectoryPicker.class);
        when(picker.isAvailable()).thenReturn(true);
        ProjectWorkspaceService service = nativeService(
                mock(ProjectRepository.class), "", root, true, picker);

        when(picker.pickFile()).thenReturn(Optional.of("relative.txt"));
        assertCode(() -> service.pickFile("127.0.0.1"), "FILE_ABSOLUTE_REQUIRED");
        when(picker.pickFile()).thenReturn(Optional.of(root.resolve("missing.txt").toString()));
        assertCode(() -> service.pickFile("127.0.0.1"), "FILE_NOT_FOUND");
        when(picker.pickFile()).thenReturn(Optional.of(root.toString()));
        assertCode(() -> service.pickFile("127.0.0.1"), "FILE_NOT_REGULAR");
    }

    private ProjectWorkspaceService service(
            ProjectRepository projects,
            String allowedRoots,
            Path defaultRoot) {
        return new ProjectWorkspaceService(
                projects, allowedRoots, defaultRoot.toString(), true);
    }

    private ProjectWorkspaceService nativeService(
            ProjectRepository projects,
            String allowedRoots,
            Path defaultRoot,
            boolean localPickerEnabled,
            NativeDirectoryPicker picker) {
        return new ProjectWorkspaceService(
                projects, allowedRoots, defaultRoot.toString(),
                localPickerEnabled, picker);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            String expectedCode) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(expectedCode));
    }
}

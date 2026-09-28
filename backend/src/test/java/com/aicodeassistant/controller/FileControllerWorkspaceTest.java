package com.aicodeassistant.controller;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.service.FileSearchService;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.SessionFileAccessService;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileControllerWorkspaceTest {

    @TempDir
    Path workspace;

    @Test
    void searchesOnlyTheWorkspaceSavedOnSession()
            throws Exception {
        Path project = Files.createDirectory(
                workspace.resolve("project")).toRealPath();
        Files.writeString(
                project.resolve("README.md"), "inside");
        Path outside = Files.createDirectory(
                workspace.resolve("outside-search"));
        Files.writeString(
                outside.resolve("README-secret.md"), "outside");
        Files.createSymbolicLink(
                project.resolve("README-link.md"),
                outside.resolve("README-secret.md"));
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.loadSession("session-1"))
                .thenReturn(Optional.of(session(
                        "session-1", project.toString())));
        ProjectWorkspaceService projectWorkspaces =
                mock(ProjectWorkspaceService.class);
        when(projectWorkspaces.requireCurrentBinding(
                project.toString())).thenReturn(project);
        FileController controller = new FileController(
                new FileSearchService(), sessions,
                projectWorkspaces);

        var results = controller.searchFiles(
                "README", 20, "session-1").getBody();

        assertThat(results).isNotNull();
        assertThat(results)
                .extracting(FileSearchService.FileSearchResult::path)
                .contains("README.md")
                .doesNotContain(
                        "README-secret.md", "README-link.md");
    }

    @Test
    void previewsChineseFilenamesWithInlineUtf8Disposition() throws Exception {
        Path project = Files.createDirectory(
                workspace.resolve("preview-project")).toRealPath();
        Path file = Files.writeString(
                project.resolve("中文报告.txt"), "预览内容");
        SessionFileAccessService sessionFiles =
                mock(SessionFileAccessService.class);
        when(sessionFiles.preview("session-p", "中文报告.txt"))
                .thenReturn(new SessionFileAccessService.PreviewTarget(
                        file, "text/plain", Files.size(file)));
        FileController controller = new FileController(
                new FileSearchService(), mock(SessionManager.class),
                mock(ProjectWorkspaceService.class), sessionFiles,
                new OssPublishProperties());

        var response = controller.preview(
                "session-p", "session-p", "中文报告.txt");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getHeaders().getFirst(
                HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=\"????.txt\";"
                        + " filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%8A%A5%E5%91%8A.txt");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void rejectsUnknownSession() {
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.loadSession("missing"))
                .thenReturn(Optional.empty());
        FileController controller = new FileController(
                new FileSearchService(), sessions,
                mock(ProjectWorkspaceService.class));

        assertThatThrownBy(() -> controller.searchFiles(
                "readme", 20, "missing"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void reportsNativePathOnlyWhenTheRequestCanUseTheNativePicker() {
        ProjectWorkspaceService workspaces = mock(ProjectWorkspaceService.class);
        when(workspaces.nativePickerAvailable("127.0.0.1")).thenReturn(true);
        FileController controller = controller(workspaces, new OssPublishProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        var capability = controller.referenceCapability(request).getBody();

        assertThat(capability).isNotNull();
        assertThat(capability.mode()).isEqualTo("native_path");
        assertThat(capability.maxFileBytes()).isNull();
    }

    @Test
    void reportsOssUploadForForwardedOrRemoteRequestsWhenOssIsReady() {
        ProjectWorkspaceService workspaces = mock(ProjectWorkspaceService.class);
        when(workspaces.nativePickerAvailable(null)).thenReturn(false);
        OssPublishProperties properties = readyOss();
        FileController controller = controller(workspaces, properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Forwarded", "for=198.51.100.7");

        var capability = controller.referenceCapability(request).getBody();

        assertThat(capability).isNotNull();
        assertThat(capability.mode()).isEqualTo("oss_upload");
        assertThat(capability.maxFileBytes()).isEqualTo(100L * 1024 * 1024);
    }

    @Test
    void reportsUnavailableInsteadOfCallingTheNativePickerRemotely() {
        ProjectWorkspaceService workspaces = mock(ProjectWorkspaceService.class);
        when(workspaces.nativePickerAvailable("192.0.2.10")).thenReturn(false);
        FileController controller = controller(workspaces, new OssPublishProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");

        var capability = controller.referenceCapability(request).getBody();

        assertThat(capability).isNotNull();
        assertThat(capability.mode()).isEqualTo("unavailable");
        assertThat(capability.error()).isEqualTo("OSS_PUBLISHING_DISABLED");
    }

    private static FileController controller(ProjectWorkspaceService workspaces,
                                             OssPublishProperties properties) {
        return new FileController(new FileSearchService(), mock(SessionManager.class),
                workspaces, null, properties);
    }

    private static OssPublishProperties readyOss() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setCredentialMode("default-chain");
        return properties;
    }

    private static SessionData session(
            String id, String workingDirectory) {
        Instant now = Instant.now();
        return new SessionData(
                id, "model", workingDirectory,
                null, "active", List.of(), Map.of(),
                Usage.zero(), 0.0, null, now, now);
    }
}

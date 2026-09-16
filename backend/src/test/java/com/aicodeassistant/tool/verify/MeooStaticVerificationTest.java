package com.aicodeassistant.tool.verify;

import com.aicodeassistant.artifact.meoo.MeooPublicationPolicy;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.service.*;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.ProcessTreeManager;
import com.aicodeassistant.verify.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeooStaticVerificationTest {
    @TempDir Path workspace;
    final PythonCapabilityAwareClient client = mock(PythonCapabilityAwareClient.class);
    final BrowserVerifier browser = mock(BrowserVerifier.class);
    final EvidenceStore evidence = mock(EvidenceStore.class);
    final PreviewStackDetector detector = mock(PreviewStackDetector.class);
    final DevServerLauncher launcher = spy(new DevServerLauncher(new ProcessTreeManager()));
    VerifyJourneyTool tool;

    @BeforeEach void setup() throws Exception {
        workspace = workspace.toRealPath();
        var props = mock(MeooPublishProperties.class);
        var credential = mock(MeooPublishProperties.Credential.class);
        when(credential.account()).thenReturn("test-account");
        when(props.credential()).thenReturn(credential);
        when(props.getMaxBytes()).thenReturn(1_000_000L);
        when(props.getMaxFiles()).thenReturn(100);
        var factory = mock(VerifierFactory.class);
        when(factory.selectVerifier(any(), anyString())).thenReturn(browser);
        when(client.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        when(evidence.save(any())).thenAnswer(c -> c.getArgument(0));
        tool = new VerifyJourneyTool(client, launcher, factory, detector, evidence,
                mock(SimpMessagingTemplate.class), mock(FeatureFlagService.class), mock(ActivityRepository.class),
                new ObjectMapper(), mock(NotificationService.class));
        tool.setMeooPublicationPolicy(new MeooPublicationPolicy(props, evidence));
    }

    @AfterEach void cleanup() { launcher.shutdownAll(); }

    ToolInput input(String path) {
        return ToolInput.from(Map.of("publication_path", path, "publication_runtime", "static",
                "verification_mode", "browser", "journey", List.of(Map.of("action", "navigate", "url", "/"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servesExactDirectoryOrSingleHtmlWithoutModifyingSource(boolean single) throws Exception {
        Path site = Files.createDirectory(workspace.resolve("site with spaces"));
        Path entry = site.resolve(single ? "独立页面.html" : "index.html");
        Files.writeString(entry, "<h1>Expected publication</h1>");
        Files.writeString(workspace.resolve("index.html"), "WRONG WORKSPACE PAGE");
        Files.writeString(site.resolve("package.json"), "{\"scripts\":{\"postinstall\":\"exit 1\"}}");
        Files.writeString(site.resolve(".env"), "must not be served");
        var ctx = ToolUseContext.of(workspace.toString(), "session");
        when(browser.verify(any(), anyString())).thenAnswer(c -> {
            JourneyRequest req = c.getArgument(0);
            var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create(req.baseUrl() + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("<h1>Expected publication</h1>");
            assertThat(URI.create(req.baseUrl()).getPort()).isNotEqualTo(8080);
            assertThat(http.send(HttpRequest.newBuilder(URI.create(req.baseUrl() + "/.env")).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            return new JourneyResult("verified", null, List.of(), Map.of());
        });
        var result = tool.call(input(single ? entry.toString() : site.toString()), ctx);
        assertThat(result.isError()).as(result.content()).isFalse();
        verifyNoInteractions(detector);
        verify(launcher, never()).start(any(), anyString(), anyInt(), any());
        var stage = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(launcher).startStatic(stage.capture(), any());
        assertThat(stage.getValue()).doesNotExist();
        assertThat(site.resolve("node_modules")).doesNotExist();
        assertThat(site.resolve(".ai-code-assistant")).doesNotExist();
        var bundle = org.mockito.ArgumentCaptor.forClass(EvidenceBundle.class);
        verify(evidence).save(bundle.capture());
        assertThat(bundle.getValue().verdict()).isEqualTo("verified");
        assertThat(bundle.getValue().sessionId()).isEqualTo("session");
        String realSite = site.toRealPath().toString();
        assertThat(bundle.getValue().items()).anySatisfy(item -> assertThat(item.meta()).containsEntry("workspace", realSite));
    }

    @Test void unavailablePublicationIsErrorButOrdinaryVerificationStillSkips() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Site</h1>");
        when(client.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(false);
        var ctx = ToolUseContext.of(workspace.toString(), "session");
        assertThat(tool.call(input("."), ctx).failureCode()).isEqualTo("MEOO_VERIFICATION_UNAVAILABLE");
        var ordinary = ToolInput.from(Map.of("journey", List.of(Map.of("action", "navigate", "url", "/"))));
        assertThat(tool.call(ordinary, ctx).isError()).isFalse();
        verifyNoInteractions(browser, evidence, detector);
        verify(launcher, never()).startStatic(any(), any());
    }

    @Test void staticPublicationRejectsUnrelatedNavigationBeforeStarting() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Site</h1>");
        Map<String, Object> data = new HashMap<>(input(".").getRawData());
        data.put("journey", List.of(Map.of("action", "navigate", "url", "https://example.com/")));
        assertThat(tool.call(ToolInput.from(data), ToolUseContext.of(workspace.toString(), "session")).failureCode())
                .isEqualTo("VERIFY_JOURNEY_INVALID_STEP");
        verify(launcher, never()).startStatic(any(), any());
        verifyNoInteractions(browser, evidence);
    }

    @Test void unavailableVerifierResultDoesNotBecomePublicationSuccessAndCleansStage() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Site</h1>");
        when(browser.verify(any(), anyString())).thenReturn(JourneyResult.unavailable("Browser stopped"));
        assertThat(tool.call(input("."), ToolUseContext.of(workspace.toString(), "s")).failureCode())
                .isEqualTo("MEOO_VERIFICATION_UNAVAILABLE");
        var stage = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(launcher).startStatic(stage.capture(), any());
        assertThat(stage.getValue()).doesNotExist();
        var bundle = org.mockito.ArgumentCaptor.forClass(EvidenceBundle.class);
        verify(evidence).save(bundle.capture());
        assertThat(bundle.getValue().verdict()).isNotEqualTo("verified");
    }

    @Test void imagePublicationUsesCheckedSubdirectoryAndExplicitCommandForUnknownStack() throws Exception {
        Path site = Files.createDirectories(workspace.resolve("node-service/scripts")).getParent();
        Files.writeString(site.resolve("scripts/setup.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(site.resolve("scripts/start.sh"), "#!/bin/sh\nPORT=9000 node server.js\n");
        when(detector.detect(any())).thenReturn(new StackInfo("unknown", 0, ""));
        doReturn(null).when(launcher).start(any(), anyString(), anyInt(), any());
        when(browser.verify(any(), anyString())).thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        Map<String, Object> data = new HashMap<>(input(site.toString()).getRawData());
        data.put("publication_runtime", "image");
        data.put("start_command", "sh scripts/start.sh");
        data.put("base_url", "http://127.0.0.1:9000");
        var result = tool.call(ToolInput.from(data), ToolUseContext.of(workspace.toString(), "s"));
        assertThat(result.isError()).as(result.content()).isFalse();
        verify(launcher).start(eq(site), eq("sh scripts/start.sh"), eq(9000), any());
    }
}

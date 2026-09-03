package com.aicodeassistant.authorization;

import com.aicodeassistant.artifact.publication.ArtifactPublicationPolicy;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactPublishAuthorizationTest {
    @TempDir Path workspace;

    @Test
    void permissionDescriptorFreezesPermanentPublicDestinationAndIntegrityFacts() throws Exception {
        ArtifactPublicationPolicy policy = mock(ArtifactPublicationPolicy.class);
        ArtifactPublicationPolicy.Snapshot approvedFacts = snapshot("hash-a", "object-a");
        when(policy.inspect("report.html", "run-1", workspace.toString()))
                .thenReturn(approvedFacts);
        OperationAnalyzerRegistry registry = registry(policy);
        Tool tool = publishTool();
        ToolInput input = ToolInput.from(Map.of("file_path", "report.html"));
        ToolUseContext context = ToolUseContext.of(workspace.toString(), "session-1")
                .withCurrentRunId("run-1");
        AuthorizationSubject subject = new AuthorizationSubject(
                "session-1", "run-1", "run-1", "workspace", workspace.toRealPath());

        try (FrozenToolInput frozen = new FrozenToolInputFactory(
                new ObjectMapper(), 1024 * 1024, 4 * 1024 * 1024).freeze(tool.getName(), input)) {
            OperationAnalyzer analyzer = registry.analyzerFor(tool);
            OperationDescriptor descriptor = analyzer.analyze(tool, frozen, input, context, subject);

            assertThat(descriptor.analyzerId()).isEqualTo("artifact-publish-v1");
            assertThat(descriptor.risk()).isEqualTo(RiskClass.HIGH);
            assertThat(descriptor.redactedSummary())
                    .contains("PERMANENT PUBLIC OSS upload", "report.html", "test-artifacts", "hash-a");
            assertThatCode(() -> analyzer.recheck(tool, descriptor, input, context, subject))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void finalRecheckRejectsChangedHashOrObjectDestination() throws Exception {
        ArtifactPublicationPolicy policy = mock(ArtifactPublicationPolicy.class);
        when(policy.inspect("report.html", "run-1", workspace.toString()))
                .thenReturn(snapshot("hash-a", "object-a"), snapshot("hash-b", "object-b"));
        OperationAnalyzerRegistry registry = registry(policy);
        Tool tool = publishTool();
        ToolInput input = ToolInput.from(Map.of("file_path", "report.html"));
        ToolUseContext context = ToolUseContext.of(workspace.toString(), "session-1")
                .withCurrentRunId("run-1");
        AuthorizationSubject subject = new AuthorizationSubject(
                "session-1", "run-1", "run-1", "workspace", workspace.toRealPath());

        try (FrozenToolInput frozen = new FrozenToolInputFactory(
                new ObjectMapper(), 1024 * 1024, 4 * 1024 * 1024).freeze(tool.getName(), input)) {
            OperationAnalyzer analyzer = registry.analyzerFor(tool);
            OperationDescriptor descriptor = analyzer.analyze(tool, frozen, input, context, subject);

            assertThatThrownBy(() -> analyzer.recheck(tool, descriptor, input, context, subject))
                    .isInstanceOfSatisfying(AuthorizationException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("AUTHORIZATION_FINAL_RECHECK_DENIED"));
        }
    }

    private OperationAnalyzerRegistry registry(ArtifactPublicationPolicy policy) {
        OperationAnalyzerRegistry registry = new OperationAnalyzerRegistry(new ObjectMapper(),
                mock(BashSecurityAnalyzer.class), new SensitiveDataFilter(), mock(PathSecurityService.class));
        registry.setArtifactPublicationPolicy(policy);
        return registry;
    }

    private ArtifactPublicationPolicy.Snapshot snapshot(String hash, String objectKey) {
        Path path = workspace.resolve("report.html").toAbsolutePath().normalize();
        return new ArtifactPublicationPolicy.Snapshot("artifact-1", "manifest-1", "run-1",
                "report.html", path, "report.html", 42, hash, "text/html; charset=utf-8",
                objectKey, "https://test-artifacts.oss-cn-beijing.aliyuncs.com/" + objectKey,
                "test-artifacts", "https://oss-cn-beijing.aliyuncs.com");
    }

    private static Tool publishTool() {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("PublishArtifact");
        return tool;
    }
}

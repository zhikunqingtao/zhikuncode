package com.aicodeassistant.tool;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.authorization.AuthorizationDiagnostic;
import com.aicodeassistant.authorization.AuthorizationService;
import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizedOperation;
import com.aicodeassistant.authorization.EffectClass;
import com.aicodeassistant.authorization.FrozenToolInputFactory;
import com.aicodeassistant.authorization.OperationDescriptor;
import com.aicodeassistant.authorization.PreparedOperation;
import com.aicodeassistant.authorization.ResourceRef;
import com.aicodeassistant.authorization.RiskClass;
import com.aicodeassistant.authorization.ToolExecutionGateway;
import com.aicodeassistant.authorization.TypedFileOperation;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionPipelineExternalArtifactTest {

    @TempDir Path temp;

    @Test
    void admittedExternalWriteUsesAuthorizedArtifactDeclarationAndSeal()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("workspace")).toRealPath();
        Path outside = temp.resolve("outside.txt")
                .toAbsolutePath().normalize();
        ObjectMapper json = new ObjectMapper();
        AuthorizationService authorization = mock(AuthorizationService.class);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        ArtifactManifestService artifacts = mock(ArtifactManifestService.class);
        AuthorizationSubject subject = new AuthorizationSubject(
                "session", "run", "run", "workspace", workspace);
        OperationDescriptor descriptor = new OperationDescriptor(
                1, "Write", TypedFileOperation.REPLACE_FILE.name(),
                "input-hash", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                List.of(new ResourceRef(
                        "path", outside.toString(), true)),
                List.of(), List.of(), RiskClass.GUARDED,
                "operation-hash",
                "Write outside Project: " + outside);
        PreparedOperation prepared = new PreparedOperation(
                subject, descriptor, "attempt");
        when(authorization.prepare(any(), any(), any(), any()))
                .thenReturn(prepared);
        when(authorization.authorizePrepared(
                any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ToolInput raw = invocation.getArgument(2);
                    Map<String, Object> bound = new java.util.LinkedHashMap<>(
                            raw.getRawData());
                    bound.put("file_path", outside.toString());
                    return new AuthorizedOperation(
                            subject, descriptor, ToolInput.from(bound),
                            AuthorizationDiagnostic.Source.MODE,
                            "AUTO_APPROVE", null, null,
                            null, "attempt");
                });
        when(gateway.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Runnable admission = invocation.getArgument(3);
                    admission.run();
                    Runnable started = invocation.getArgument(4);
                    started.run();
                    Tool tool = invocation.getArgument(0);
                    AuthorizedOperation allowed = invocation.getArgument(1);
                    ToolUseContext context = invocation.getArgument(2);
                    return tool.call(allowed.executionInput(), context);
                });
        ArtifactEntry entry = mock(ArtifactEntry.class);
        when(entry.operation()).thenReturn("created");
        when(artifacts.declareAuthorizedExternalInCurrentTransaction(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(entry);

        Tool write = new Tool() {
            @Override public String getName() { return "Write"; }
            @Override public String getDescription() { return "test write"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public String getPath(ToolInput input) {
                return input.getString("file_path");
            }
            @Override public ToolResult call(
                    ToolInput input, ToolUseContext context) {
                try {
                    Files.writeString(
                            Path.of(input.getString("file_path")),
                            input.getString("content"));
                    return ToolResult.successWithEffect(
                            "written", ToolResult.EffectState.APPLIED);
                } catch (Exception failure) {
                    return ToolResult.internalError(
                            "TEST_WRITE_FAILED", failure.getMessage(),
                            ToolResult.EffectState.NOT_STARTED);
                }
            }
        };
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new HookService(new HookRegistry(), null), json,
                new SensitiveDataFilter(),
                new FrozenToolInputFactory(
                        json, 1024 * 1024, 4 * 1024 * 1024),
                authorization, gateway,
                new ToolRecoveryFramework(List.of()),
                null, artifacts);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session")
                .withToolUseId("tool-use")
                .withCurrentRunId("run");

        ToolExecutionResult result = pipeline.execute(
                write, ToolInput.from(Map.of(
                        "file_path", outside.toString(),
                        "content", "outside-content")),
                context);

        assertThat(result.result().isError()).isFalse();
        assertThat(Files.readString(outside))
                .isEqualTo("outside-content");
        verify(artifacts).declareAuthorizedExternalInCurrentTransaction(
                "run", "session", "tool-use", outside.toString(),
                "created", "sha256", workspace.toString());
        verify(artifacts).sealAuthorizedExternalFromFile(
                "run", outside.toString(), workspace.toString());
    }
}

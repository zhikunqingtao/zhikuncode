package com.aicodeassistant.permission;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionGrantKeyFactoryTest {
    @TempDir Path workspace;
    private final PermissionGrantKeyFactory factory = new PermissionGrantKeyFactory();

    /** Bash grant is per-cwd and per-risk-class, not per-command. */
    @Test
    void bashGrantIsPerCwdAndPerRiskClassNotPerCommand() throws Exception {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("Bash");
        ToolInput first = ToolInput.from(Map.of("command", "git status"));
        ToolInput second = ToolInput.from(Map.of("command", "git diff"));

        var key = factory.create(tool, first, workspace.toString(), "low").orElseThrow();
        assertThat(key.workspaceAllowed()).isFalse();

        // Same cwd + same risk class → same hash regardless of command
        assertThat(factory.create(tool, second, workspace.toString(), "low").orElseThrow().hash())
                .isEqualTo(key.hash());

        // Different canonicalCwd → different hash
        Path otherDir = Files.createTempDirectory("other");
        assertThat(factory.create(tool, first, otherDir.toString(), "low").orElseThrow().hash())
                .isNotEqualTo(key.hash());

        // High-risk commands fail closed (returns empty)
        assertThat(factory.create(tool, first, workspace.toString(), "high")).isEmpty();
    }

    @Test
    void typedFileGrantUsesCanonicalWorkspaceContainedPath() throws Exception {
        Path file = Files.writeString(workspace.resolve("App.java"), "class App {}");
        Tool tool = mock(Tool.class);
        ToolInput input = ToolInput.from(Map.of("file_path", file.toString()));
        when(tool.getName()).thenReturn("FileReadTool");
        when(tool.getPath(input)).thenReturn(file.toString());
        when(tool.isReadOnly(input)).thenReturn(true);

        var key = factory.create(tool, input, workspace.toString(), "low").orElseThrow();
        assertThat(key.workspaceAllowed()).isTrue();
        assertThat(key.action()).isEqualTo("read");
        assertThat(key.canonicalCwd()).isEqualTo(workspace.toRealPath().toString());
    }

    @Test
    void workspaceGrantMatchesSameTypedActionAndExtensionButSessionGrantRemainsExact() throws Exception {
        Path first = Files.writeString(workspace.resolve("One.java"), "class One {}");
        Path second = Files.writeString(workspace.resolve("Two.java"), "class Two {}");
        Path otherType = Files.writeString(workspace.resolve("notes.txt"), "notes");
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("FileReadTool");

        ToolInput firstInput = ToolInput.from(Map.of("file_path", first.toString()));
        ToolInput secondInput = ToolInput.from(Map.of("file_path", second.toString()));
        ToolInput otherInput = ToolInput.from(Map.of("file_path", otherType.toString()));
        when(tool.getPath(firstInput)).thenReturn(first.toString());
        when(tool.getPath(secondInput)).thenReturn(second.toString());
        when(tool.getPath(otherInput)).thenReturn(otherType.toString());
        when(tool.isReadOnly(firstInput)).thenReturn(true);
        when(tool.isReadOnly(secondInput)).thenReturn(true);
        when(tool.isReadOnly(otherInput)).thenReturn(true);

        var firstKey = factory.create(tool, firstInput, workspace.toString(), "low").orElseThrow();
        var secondKey = factory.create(tool, secondInput, workspace.toString(), "low").orElseThrow();
        var otherKey = factory.create(tool, otherInput, workspace.toString(), "low").orElseThrow();

        assertThat(firstKey.hash()).isNotEqualTo(secondKey.hash());
        assertThat(firstKey.hashForScope("WORKSPACE"))
                .isEqualTo(secondKey.hashForScope("WORKSPACE"))
                .isNotEqualTo(otherKey.hashForScope("WORKSPACE"));
    }
}

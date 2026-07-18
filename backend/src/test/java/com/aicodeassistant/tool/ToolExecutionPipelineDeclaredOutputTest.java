package com.aicodeassistant.tool;

import com.aicodeassistant.artifact.ArtifactManifestService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutionPipelineDeclaredOutputTest {

    @Test
    void normalizesSupportedArtifactOperationAliases() {
        assertEquals("created", ArtifactManifestService.normalizeOperation("create"));
        assertEquals("modified", ArtifactManifestService.normalizeOperation("UPDATE"));
        assertEquals("deleted", ArtifactManifestService.normalizeOperation("delete"));
        assertEquals("deleted", ArtifactManifestService.normalizeOperation("deleted"));
    }

    @Test
    void rejectsUnknownArtifactOperation() {
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactManifestService.normalizeOperation("copy"));
    }
}

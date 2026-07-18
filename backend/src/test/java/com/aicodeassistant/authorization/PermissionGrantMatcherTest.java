package com.aicodeassistant.authorization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionGrantMatcherTest {
    @Test
    void prefixMatchingUsesPathSegmentsAndChecksEveryResource() {
        GrantConstraint read = new GrantConstraint.WorkspaceRead(
                List.of("src/main"), List.of(TypedFileOperation.READ_FILE));
        assertThat(PermissionGrantMatcher.matches(read, operation(TypedFileOperation.READ_FILE,
                new ResourceRef("path", "src/main/App.java", false)))).isTrue();
        assertThat(PermissionGrantMatcher.matches(read, operation(TypedFileOperation.READ_FILE,
                new ResourceRef("path", "src/main2/App.java", false)))).isFalse();
        assertThat(PermissionGrantMatcher.matches(read, operation(TypedFileOperation.READ_FILE,
                new ResourceRef("path", "src/main/App.java", false),
                new ResourceRef("path", "test/Other.java", false)))).isFalse();
    }

    @Test
    void constraintsRejectTraversalAbsoluteAndCrossClassOperations() {
        assertThatThrownBy(() -> new GrantConstraint.WorkspaceRead(
                List.of("src/../secret"), List.of(TypedFileOperation.READ_FILE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrantConstraint.WorkspaceRead(
                List.of("/tmp"), List.of(TypedFileOperation.READ_FILE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrantConstraint.WorkspaceEdit(
                List.of("src"), List.of(TypedFileOperation.READ_FILE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OperationDescriptor operation(TypedFileOperation action, ResourceRef... resources) {
        return new OperationDescriptor(1, "Read", action.name(), "input", "file-v1",
                List.of(EffectClass.READ_RESOURCE), List.of(resources), List.of(), List.of(),
                RiskClass.SAFE, "operation", "read");
    }
}

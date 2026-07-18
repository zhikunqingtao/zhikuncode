package com.aicodeassistant.authorization;

import java.util.List;

/** 由 V019 持久化的封闭授权约束模型。 */
public sealed interface GrantConstraint permits GrantConstraint.Exact,
        GrantConstraint.WorkspaceRead, GrantConstraint.WorkspaceEdit {
    record Exact(String operationHash) implements GrantConstraint { }
    record WorkspaceRead(List<String> relativeDirectoryPrefixes,
                         List<TypedFileOperation> allowedOperations) implements GrantConstraint {
        public WorkspaceRead {
            relativeDirectoryPrefixes = normalized(relativeDirectoryPrefixes);
            allowedOperations = allowedOperations.stream().distinct().sorted().toList();
            if (allowedOperations.isEmpty() || allowedOperations.stream().anyMatch(op ->
                    op != TypedFileOperation.READ_FILE && op != TypedFileOperation.LIST_DIRECTORY)) {
                throw new IllegalArgumentException("Invalid workspace read operations");
            }
        }
    }
    record WorkspaceEdit(List<String> relativeDirectoryPrefixes,
                         List<TypedFileOperation> allowedOperations) implements GrantConstraint {
        public WorkspaceEdit {
            relativeDirectoryPrefixes = normalized(relativeDirectoryPrefixes);
            allowedOperations = allowedOperations.stream().distinct().sorted().toList();
            if (allowedOperations.isEmpty() || allowedOperations.stream().anyMatch(op ->
                    op == TypedFileOperation.READ_FILE || op == TypedFileOperation.LIST_DIRECTORY)) {
                throw new IllegalArgumentException("Invalid workspace edit operations");
            }
        }
    }

    private static List<String> normalized(List<String> prefixes) {
        List<String> result = prefixes.stream().map(PermissionGrantMatcher::normalizeRelativePath)
                .distinct().sorted().toList();
        if (result.isEmpty()) throw new IllegalArgumentException("Capability requires at least one path prefix");
        return result;
    }
}

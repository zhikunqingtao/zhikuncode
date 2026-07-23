package com.aicodeassistant.authorization;

import java.nio.file.Path;

/** 面向 V019 封闭约束模型、感知路径段边界的精确匹配器。 */
public final class PermissionGrantMatcher {
    private PermissionGrantMatcher() { }

    public static boolean matches(GrantConstraint constraint, OperationDescriptor operation) {
        return switch (constraint) {
            case GrantConstraint.Exact exact -> exact.operationHash().equals(operation.operationHash());
            case GrantConstraint.ToolWide ignored -> true;
            case GrantConstraint.WorkspaceRead read -> matchesFile(
                    read.relativeDirectoryPrefixes(), read.allowedOperations(), operation);
            case GrantConstraint.WorkspaceEdit edit -> matchesFile(
                    edit.relativeDirectoryPrefixes(), edit.allowedOperations(), operation);
        };
    }

    private static boolean matchesFile(java.util.List<String> prefixes,
            java.util.List<TypedFileOperation> operations, OperationDescriptor operation) {
        TypedFileOperation requested;
        try { requested = TypedFileOperation.valueOf(operation.action()); }
        catch (Exception invalid) { return false; }
        if (!operations.contains(requested) || operation.resources().isEmpty()) return false;
        for (ResourceRef resource : operation.resources()) {
            if (resource.outsideWorkspace() || !"path".equals(resource.kind())) return false;
            String candidate;
            try { candidate = normalizeRelativePath(resource.value()); }
            catch (IllegalArgumentException invalid) { return false; }
            if (prefixes.stream().noneMatch(prefix -> segmentContains(prefix, candidate))) return false;
        }
        return true;
    }

    static boolean segmentContains(String prefix, String candidate) {
        String normalizedPrefix = normalizeRelativePath(prefix);
        String normalizedCandidate = normalizeRelativePath(candidate);
        return ".".equals(normalizedPrefix)
                || normalizedCandidate.equals(normalizedPrefix)
                || normalizedCandidate.startsWith(normalizedPrefix + "/");
    }

    static String normalizeRelativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid relative capability path");
        }
        if (".".equals(value)) return value;
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("Invalid relative capability path");
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IllegalArgumentException("Absolute capability path");
        for (Path segment : path) {
            String part = segment.toString();
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Traversal in capability path");
            }
        }
        String normalized = path.normalize().toString().replace('\\', '/');
        if (!normalized.equals(value)) throw new IllegalArgumentException("Non-canonical capability path");
        return normalized;
    }
}

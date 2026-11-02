package com.aicodeassistant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class ProjectMemoryServiceBoundaryTest {

    @TempDir
    Path temp;

    private final ProjectMemoryService memory = new ProjectMemoryService();

    @Test
    void loadMemoryReadsOnlyFilesInSelectedProjectRoot() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project")).toRealPath();
        Path selected = Files.createDirectory(project.resolve("selected")).toRealPath();
        Files.writeString(project.resolve("zhikun.md"), "parent-secret");
        Files.writeString(selected.resolve("zhikun.local.md"), "selected-memory");

        String loaded = memory.loadMemory(selected);

        assertThat(loaded).contains("selected-memory");
        assertThat(loaded).doesNotContain("parent-secret");
    }

    @Test
    void loadMemoryRejectsSymlinkEvenWhenTargetIsReadable() throws Exception {
        Path project = Files.createDirectory(temp.resolve("symlink-project")).toRealPath();
        Path outside = Files.writeString(temp.resolve("outside-memory.md"), "outside-secret")
                .toRealPath();
        Files.createSymbolicLink(project.resolve("zhikun.md"), outside);

        String loaded = memory.loadMemory(project);

        assertThat(loaded).isEmpty();
        assertThat(memory.hasMemory(project)).isFalse();
    }

    @Test
    void loadMemoryReadsAtMostOneHundredKiB() throws Exception {
        Path project = Files.createDirectory(temp.resolve("large-project")).toRealPath();
        String allowedPrefix = "A".repeat(100 * 1024);
        Files.writeString(project.resolve("zhikun.md"),
                allowedPrefix + "must-not-be-read", StandardCharsets.UTF_8);

        String loaded = memory.loadMemory(project);
        String content = loaded.substring(loaded.indexOf('\n') + 1);
        String marker = "\n<!-- truncated: exceeded " + (100 * 1024) + " bytes -->";

        assertThat(content).endsWith(marker);
        String body = content.substring(0, content.length() - marker.length());
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(100 * 1024);
        assertThat(content).doesNotContain("must-not-be-read");
    }

    @Test
    void loadMemoryHandlesFileBecomingEmptyBeforeRead() throws Exception {
        Path project = Files.createDirectory(temp.resolve("shrinking-project")).toRealPath();
        Path file = Files.writeString(project.resolve("zhikun.md"), "");

        // Model stale metadata from before a concurrent truncate: size reports a large
        // file, while the real stream is already empty. Reading must use actual bytes.
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.size(file)).thenReturn(110_000L);
            assertThat(memory.loadMemory(project)).isEmpty();
        }
    }

    @Test
    void loadMemoryAtExactLimitDoesNotAddTruncationMarker() throws Exception {
        Path project = Files.createDirectory(temp.resolve("exact-limit-project")).toRealPath();
        String content = "A".repeat(100 * 1024);
        Path file = Files.writeString(project.resolve("zhikun.md"), content);

        assertThat(memory.loadMemory(project)).isEqualTo("<!-- " + file + " -->\n" + content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"é", "中", "😀"})
    void loadMemoryPreservesOnlyCompleteUtf8CharactersAtLimit(String character) throws Exception {
        Path project = Files.createDirectory(temp.resolve("utf8-project")).toRealPath();
        Path file = project.resolve("zhikun.md");
        int limit = 100 * 1024;
        String marker = "\n<!-- truncated: exceeded " + limit + " bytes -->";
        String header = "<!-- " + file + " -->\n";

        String incompletePrefix = "A".repeat(limit - 1);
        Files.writeString(file, incompletePrefix + character + "tail");
        assertThat(memory.loadMemory(project)).isEqualTo(header + incompletePrefix + marker);

        String completePrefix = "A".repeat(limit - character.getBytes(StandardCharsets.UTF_8).length)
                + character;
        Files.writeString(file, completePrefix + "tail");
        assertThat(memory.loadMemory(project)).isEqualTo(header + completePrefix + marker);
    }

    @Test
    void createMemoryIfAbsentNeverOverwritesExistingFile() throws Exception {
        Path project = Files.createDirectory(temp.resolve("init-project")).toRealPath();
        memory.createMemoryIfAbsent(project, "FIRST", false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> memory.createMemoryIfAbsent(project, "SECOND", false))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(project.resolve("zhikun.md"))).isEqualTo("FIRST");
    }

    @Test
    void writeMemoryRejectsSymlinkToSensitiveProjectFile()
            throws Exception {
        Path project = Files.createDirectory(
                temp.resolve("write-symlink-project")).toRealPath();
        Path environment = Files.writeString(
                project.resolve(".env"), "TOKEN=keep-me");
        Files.createSymbolicLink(
                project.resolve("zhikun.md"), environment);

        assertThatThrownBy(() -> memory.writeMemory(
                project, "replacement", false))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
        assertThat(Files.readString(environment))
                .isEqualTo("TOKEN=keep-me");
        assertThat(memory.hasMemory(project)).isFalse();
    }

    @Test
    void writeMemoryRejectsDanglingSymlink() throws Exception {
        Path project = Files.createDirectory(
                temp.resolve("write-dangling-project")).toRealPath();
        Path missingTarget = project.resolve("missing-memory-target");
        Files.createSymbolicLink(
                project.resolve("zhikun.md"), missingTarget);

        assertThatThrownBy(() -> memory.writeMemory(
                project, "replacement", false))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
        assertThat(missingTarget).doesNotExist();
    }

    @Test
    void writeMemoryCreatesAndReplacesRegularMemoryFile()
            throws Exception {
        Path project = Files.createDirectory(
                temp.resolve("regular-write-project")).toRealPath();

        memory.writeMemory(project, "first", false);
        memory.writeMemory(project, "second", false);

        assertThat(Files.readString(project.resolve("zhikun.md")))
                .isEqualTo("second");
    }
}

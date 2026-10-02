package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShellStateManagerTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void wrappedCommandPersistsOnlyPrivateCwdState(int exitCode) throws Exception {
        ShellStateManager manager = new ShellStateManager();
        String sessionId = "shell-state-' quoted-" + UUID.randomUUID();
        Path cwdFile = manager.getCwdTrackingPath(sessionId);
        Path legacyEnvironmentFile = ShellStateManager.stateDirectory().resolve(sessionId + ".env");
        Path child = Files.createDirectory(temp.resolve("child ' quoted"));
        String marker = "must-not-be-persisted-" + UUID.randomUUID();

        try {
            String wrapped = manager.wrapCommand(
                    "export ZHIKUN_TEST_SECRET='" + marker + "'; false; cd " + quote(child)
                            + "; (exit " + exitCode + ")", sessionId);
            ShellResult result = runShell(wrapped);

            assertThat(result.exitCode()).as("Shell output: %s", result.output()).isEqualTo(exitCode);
            assertThat(Files.readString(cwdFile).trim()).isEqualTo(child.toString());
            assertThat(legacyEnvironmentFile).doesNotExist();
            assertThat(Files.readString(cwdFile)).doesNotContain(marker);
            if (Files.getFileStore(cwdFile).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(cwdFile))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThat(Files.getPosixFilePermissions(ShellStateManager.stateDirectory()))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
            assertNoTemporaryFiles(sessionId);
        } finally {
            Files.deleteIfExists(cwdFile);
            Files.deleteIfExists(legacyEnvironmentFile);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"command-temp", "cwd-temp", "script-write"})
    void initializationFailurePreservesCwdAndDoesNotRunUserCommand(String stage) throws Exception {
        ShellStateManager manager = new ShellStateManager();
        String sessionId = "shell-init-" + UUID.randomUUID();
        Path cwdFile = manager.getCwdTrackingPath(sessionId);
        Path previous = Files.createDirectory(temp.resolve("previous"));
        Path executed = temp.resolve("user-command-executed");
        Path firstTempCreated = temp.resolve("first-temp-created");
        String failure = switch (stage) {
            case "command-temp" -> "mktemp() { return 1; }\n";
            case "cwd-temp" -> "mktemp() { if [ -e " + quote(firstTempCreated)
                    + " ]; then return 1; fi; : > " + quote(firstTempCreated)
                    + "; command mktemp \"$@\"; }\n";
            case "script-write" -> "cat() { command cat \"$@\"; return 1; }\n";
            default -> throw new IllegalArgumentException(stage);
        };
        manager.resetCwd(sessionId, previous.toString());
        try {
            ShellResult result = runShell(failure + manager.wrapCommand(
                    "printf executed > " + quote(executed) + "; cd " + quote(temp), sessionId));
            assertThat(result.exitCode()).as("Shell output: %s", result.output()).isEqualTo(1);
            assertThat(result.output()).contains("Shell initialization failed");
            assertThat(executed).doesNotExist();
            assertThat(Files.readString(cwdFile)).isEqualTo(previous.toString());
            assertNoTemporaryFiles(sessionId);
        } finally {
            Files.deleteIfExists(cwdFile);
        }
    }

    private ShellResult runShell(String script) throws Exception {
        Path output = temp.resolve("shell-output-" + UUID.randomUUID());
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", script)
                .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        // The wrapper must use its configured private directory even when the default is unusable.
        builder.environment().put("TMPDIR", temp.resolve("missing-default-tmp").toString());
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            String captured = Files.readString(output);
            assertThat(finished).as("Shell timed out; output: %s", captured).isTrue();
            return new ShellResult(process.exitValue(), captured);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    private void assertNoTemporaryFiles(String sessionId) throws Exception {
        try (var files = Files.list(ShellStateManager.stateDirectory())) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith(sessionId + ".cwd."))
                    .toList()).isEmpty();
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private record ShellResult(int exitCode, String output) {}

    @Test
    void startupDeletesLegacyEnvironmentSnapshot() throws Exception {
        String sessionId = "legacy-shell-state-" + UUID.randomUUID();
        Path legacyEnvironmentFile = ShellStateManager.stateDirectory().resolve(sessionId + ".env");
        Files.createDirectories(legacyEnvironmentFile.getParent());
        Files.writeString(legacyEnvironmentFile, "declare -x SECRET=\"legacy-value\"\n");

        new ShellStateManager();

        assertThat(legacyEnvironmentFile).doesNotExist();
    }

    @Test
    void pathNormalizationPreservesCurrentDirectorySemantics() {
        String separator = File.pathSeparator;
        String withEmptySegment = separator + "/usr/bin" + separator + "/usr/bin" + separator;
        String withExplicitCurrentDirectory = "." + separator + "/usr/bin";

        assertThat(ShellStateManager.normalizePathForAuthorization(withEmptySegment))
                .isEqualTo(withExplicitCurrentDirectory);
        assertThat(ShellStateManager.normalizePathForAuthorization(withEmptySegment))
                .isNotEqualTo("/usr/bin");
    }
}

package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ShellStateManagerTest {
    @TempDir Path temp;

    @Test
    void wrappedCommandPersistsOnlyPrivateCwdState() throws Exception {
        ShellStateManager manager = new ShellStateManager();
        String sessionId = "shell-state-" + UUID.randomUUID();
        Path cwdFile = manager.getCwdTrackingPath(sessionId);
        Path legacyEnvironmentFile = ShellStateManager.stateDirectory().resolve(sessionId + ".env");
        Path child = Files.createDirectory(temp.resolve("child"));
        String marker = "must-not-be-persisted-" + UUID.randomUUID();

        try {
            String wrapped = manager.wrapCommand(
                    "export ZHIKUN_TEST_SECRET='" + marker + "'; cd '" + child + "'", sessionId);
            Process process = new ProcessBuilder("bash", "-c", wrapped).directory(temp.toFile()).start();

            assertThat(process.waitFor()).isZero();
            assertThat(Files.readString(cwdFile).trim()).isEqualTo(child.toString());
            assertThat(legacyEnvironmentFile).doesNotExist();
            assertThat(Files.readString(cwdFile)).doesNotContain(marker);
            if (Files.getFileStore(cwdFile).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(cwdFile))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThat(Files.getPosixFilePermissions(ShellStateManager.stateDirectory()))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
        } finally {
            Files.deleteIfExists(cwdFile);
            Files.deleteIfExists(legacyEnvironmentFile);
        }
    }

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

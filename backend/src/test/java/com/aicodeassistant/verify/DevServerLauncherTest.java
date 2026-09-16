package com.aicodeassistant.verify;

import com.aicodeassistant.tool.bash.ProcessTreeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class DevServerLauncherTest {
    @TempDir Path root;

    @Test void occupiedPortFailsBeforeProjectCodeRuns() throws Exception {
        var launcher = new DevServerLauncher(new ProcessTreeManager());
        try (var occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            assertThatThrownBy(() -> launcher.start(root, "touch should-not-exist", occupied.getLocalPort(), Duration.ofSeconds(3)))
                    .hasMessageContaining("port is already in use");
        }
        assertThat(root.resolve("should-not-exist")).doesNotExist();
    }

    @Test void exitedProcessIsNotReportedReady() throws Exception {
        var launcher = new DevServerLauncher(new ProcessTreeManager());
        int port;
        try (var free = new ServerSocket(0)) { port = free.getLocalPort(); }
        assertThatThrownBy(() -> launcher.start(root, "exit 7", port, Duration.ofSeconds(3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(root.resolve(".ai-code-assistant/devserver.pid")).doesNotExist();
    }
}

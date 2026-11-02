package com.aicodeassistant.memdir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryFileSnapshotTest {
    @TempDir Path temp;

    @Test void readRetriesAnExternalAtomicReplacementInsteadOfPairingOldContentWithNewVersion() throws Exception {
        var service = new MemdirService(temp);
        Path file = service.getMemoryFile();
        Files.writeString(file, "OLD");
        Path replacement = Files.writeString(temp.resolve("replacement"), "NEW CONTENT");
        Instant newVersion = Instant.parse("2026-01-01T00:00:00Z");
        Files.setLastModifiedTime(replacement, FileTime.from(newVersion));
        AtomicInteger reads = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readString(file, StandardCharsets.UTF_8)).thenAnswer(call -> {
                String content = (String) call.callRealMethod();
                if (reads.incrementAndGet() == 1)
                    Files.move(replacement, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return content;
            });
            var snapshot = service.readMemoryFileSnapshot();
            assertThat(snapshot.content()).isEqualTo("NEW CONTENT");
            assertThat(snapshot.updatedAt()).isEqualTo(newVersion);
            assertThat(reads).hasValue(2);
        }
    }

    @Test void continuouslyChangingFileFailsRatherThanReturningAnUnreliableVersion() throws Exception {
        var service = new MemdirService(temp);
        Path file = Files.writeString(service.getMemoryFile(), "seed");
        AtomicInteger reads = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readString(file, StandardCharsets.UTF_8)).thenAnswer(call -> {
                String content = (String) call.callRealMethod();
                Files.writeString(file, content + reads.incrementAndGet());
                return content;
            });
            assertThatThrownBy(service::readMemoryFileSnapshot).isInstanceOf(MemdirService.MemdirException.class);
            assertThat(reads).hasValue(3);
        }
    }

    @Test void invalidUtf8IsNotReportedAsAnEmptyFile() throws Exception {
        var service = new MemdirService(temp);
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Files.write(service.getMemoryFile(), invalid);
        assertThatThrownBy(service::readMemoryFileSnapshot).isInstanceOf(MemdirService.MemdirException.class);
        assertThat(Files.readAllBytes(service.getMemoryFile())).isEqualTo(invalid);
    }
}

package com.aicodeassistant.session.merge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Disk reserve checks for text writes, including manifests and temporary summary inputs. */
final class MergeTextBudget {
    static final long DEFAULT_MIN_FREE_BYTES = 1024L * 1024 * 1024;
    @FunctionalInterface interface AvailableBytes { long get() throws IOException; }
    private final long reserve;
    private final AvailableBytes available;

    MergeTextBudget(long reserve, AvailableBytes available) {
        this.reserve = Math.max(0, reserve); this.available = available;
    }
    static MergeTextBudget defaults(Path directory) {
        return new MergeTextBudget(DEFAULT_MIN_FREE_BYTES,
                () -> Files.getFileStore(directory).getUsableSpace());
    }
    OutputStream output(Path path, OpenOption... options) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(path, options)) {
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                reserve(count); out.write(bytes, offset, count);
            }
        };
    }
    void write(Path path, CharSequence text, OpenOption... options) throws IOException {
        try (var writer = new OutputStreamWriter(output(path, options), StandardCharsets.UTF_8)) {
            writer.append(text);
        }
    }
    private void reserve(int bytes) throws IOException {
        if (available.get() - bytes < reserve) throw new IOException("MERGE_DISK_SPACE_LOW");
    }
}

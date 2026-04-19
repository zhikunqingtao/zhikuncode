package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProjectMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryService.class);
    private static final String[] MEMORY_FILES = { "zhikun.md", "zhikun.local.md" };
    private static final long MAX_MEMORY_SIZE = 100 * 1024;

    public String loadMemory(Path workingDir) {
        if (workingDir == null) {
            log.warn("loadMemory called with null workingDir");
            return "";
        }

        List<String> memories = new ArrayList<>();
        Path current = workingDir;
        int depth = 0;
        while (current != null && depth < 5) {
            for (String fileName : MEMORY_FILES) {
                Path memFile = current.resolve(fileName);
                if (Files.isRegularFile(memFile)) {
                    try {
                        long size = Files.size(memFile);
                        if (size > MAX_MEMORY_SIZE) {
                            log.warn("Memory file too large ({}KB), truncating: {}", size / 1024, memFile);
                        }
                        String content = Files.readString(memFile, StandardCharsets.UTF_8);
                        if (size > MAX_MEMORY_SIZE) {
                            content = content.substring(0, Math.min(content.length(), (int) MAX_MEMORY_SIZE));
                        }
                        memories.add("<!-- " + memFile + " -->\n" + content);
                        log.info("Loaded memory file: {} ({}B)", memFile, size);
                    } catch (IOException e) {
                        log.warn("Failed to read memory file: {}", memFile, e);
                    }
                }
            }
            current = current.getParent();
            depth++;
        }

        if (memories.isEmpty()) return "";
        return String.join("\n\n---\n\n", memories);
    }

    public void writeMemory(Path workingDir, String content, boolean isLocal) throws IOException {
        String fileName = isLocal ? "zhikun.local.md" : "zhikun.md";
        Path normalizedWorkingDir = workingDir.normalize();
        Path memFile = normalizedWorkingDir.resolve(fileName).normalize();

        if (!memFile.startsWith(normalizedWorkingDir)) {
            throw new IOException("Path traversal detected: " + memFile);
        }
        if (Files.exists(memFile)) {
            Path realPath = memFile.toRealPath();
            if (!realPath.startsWith(normalizedWorkingDir.toRealPath())) {
                throw new IOException("Symlink path traversal detected: " + memFile + " -> " + realPath);
            }
        }

        Files.writeString(memFile, content, StandardCharsets.UTF_8);
        log.info("Written memory file: {} ({}B)", memFile, content.length());
    }

    public boolean hasMemory(Path workingDir) {
        for (String fileName : MEMORY_FILES) {
            if (Files.isRegularFile(workingDir.resolve(fileName))) return true;
        }
        return false;
    }
}

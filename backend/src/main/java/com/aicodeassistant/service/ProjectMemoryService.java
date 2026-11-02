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
    /** 项目共享记忆（随代码库提交） */
    public static final String SHARED_FILE = "zhikun.md";
    /** 项目个人本地记忆（不提交，优先级高于 zhikun.md） */
    public static final String LOCAL_FILE = "zhikun.local.md";
    private static final String[] MEMORY_FILES = { SHARED_FILE, LOCAL_FILE };
    private static final long MAX_MEMORY_SIZE = 100 * 1024;

    /** 读取两个项目记忆文件并拼接（zhikun.md 在前，zhikun.local.md 在后）。 */
    public String loadMemory(Path workingDir) {
        List<String> memories = new ArrayList<>();
        for (String fileName : MEMORY_FILES) {
            String content = loadMemoryFile(workingDir, fileName);
            if (!content.isEmpty()) memories.add(content);
        }
        if (memories.isEmpty()) return "";
        return String.join("\n\n---\n\n", memories);
    }

    /**
     * 读取单个项目记忆文件（仅限 {@link #SHARED_FILE} / {@link #LOCAL_FILE}）。
     * 复用 Project 根目录与符号链接安全检查；不存在、不安全或读取失败时返回空字符串。
     * 超过 100KB 时截断并追加截断标记。
     */
    public String loadMemoryFile(Path workingDir, String fileName) {
        if (!SHARED_FILE.equals(fileName) && !LOCAL_FILE.equals(fileName)) {
            throw new IllegalArgumentException("Unsupported project memory file: " + fileName);
        }
        Path projectRoot = resolveProjectRoot(workingDir);
        if (projectRoot == null) return "";

        Path memFile = projectRoot.resolve(fileName);
        if (Files.isSymbolicLink(memFile)
                || !Files.isRegularFile(memFile, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            Path realFile = memFile.toRealPath();
            if (!realFile.startsWith(projectRoot)
                    || !realFile.equals(memFile.toAbsolutePath().normalize())) {
                log.warn("Ignoring Project memory outside the authorized root: {}", memFile);
                return "";
            }
            // 以实际读到的字节判断是否截断（多读 1 字节），不依赖读取前的文件大小，
            // 避免文件在 size() 与读取之间被并发截短导致空数组越界
            byte[] contentBytes;
            try (var input = Files.newInputStream(
                    realFile, LinkOption.NOFOLLOW_LINKS)) {
                contentBytes = input.readNBytes((int) MAX_MEMORY_SIZE + 1);
            }
            if (contentBytes.length == 0) return "";
            boolean truncated = contentBytes.length > MAX_MEMORY_SIZE;
            int length = truncated ? (int) MAX_MEMORY_SIZE : contentBytes.length;
            if (truncated) {
                // 退回到 UTF-8 字符边界：仅当末尾多字节字符不完整时才丢弃它
                int lead = length - 1;
                while (lead > 0 && (contentBytes[lead] & 0xC0) == 0x80) lead--;
                int b = contentBytes[lead] & 0xFF;
                int expected = b < 0x80 ? 1 : b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : 2;
                if (length - lead < expected) length = lead;
                log.warn("Memory file exceeds {}KB, truncating: {}", MAX_MEMORY_SIZE / 1024, realFile);
            }
            String content = new String(contentBytes, 0, length, StandardCharsets.UTF_8);
            if (content.isBlank()) return "";
            if (truncated) {
                content += "\n<!-- truncated: exceeded " + MAX_MEMORY_SIZE + " bytes -->";
            }
            log.debug("Loaded memory file: {} ({}B read)", realFile, length);
            return "<!-- " + realFile + " -->\n" + content;
        } catch (IOException e) {
            log.warn("Failed to read memory file: {}", memFile, e);
            return "";
        }
    }

    /** 解析并校验 Project 根目录；不可用或经别名解析时返回 null。 */
    private Path resolveProjectRoot(Path workingDir) {
        if (workingDir == null) {
            log.warn("loadMemory called with null workingDir");
            return null;
        }
        try {
            Path savedRoot = workingDir.toAbsolutePath().normalize();
            Path projectRoot = savedRoot.toRealPath();
            if (!projectRoot.equals(savedRoot)
                    || !Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Project memory root is unavailable or resolves through an alias: {}", workingDir);
                return null;
            }
            return projectRoot;
        } catch (IOException | RuntimeException unavailable) {
            log.warn("Project memory root is unavailable: {}", workingDir);
            return null;
        }
    }

    /**
     * 仅在文件不存在时创建项目记忆（原子 CREATE_NEW，已存在则抛 FileAlreadyExistsException，原文件不变）。
     * 供 /memory init 使用，避免覆盖已有 zhikun.md。
     */
    public void createMemoryIfAbsent(Path workingDir, String content, boolean isLocal) throws IOException {
        Path memFile = resolveWritableMemoryFile(workingDir, isLocal);
        Files.writeString(
                memFile,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        log.info("Created memory file: {} ({}B)", memFile, content.length());
    }

    public void writeMemory(Path workingDir, String content, boolean isLocal) throws IOException {
        Path memFile = resolveWritableMemoryFile(workingDir, isLocal);
        Files.writeString(
                memFile,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        log.info("Written memory file: {} ({}B)", memFile, content.length());
    }

    /** 写入前的 Project 根目录、路径穿越与符号链接校验。 */
    private Path resolveWritableMemoryFile(Path workingDir, boolean isLocal) throws IOException {
        String fileName = isLocal ? LOCAL_FILE : SHARED_FILE;
        Path savedWorkingDir = workingDir.toAbsolutePath().normalize();
        Path projectRoot = savedWorkingDir.toRealPath();
        if (!projectRoot.equals(savedWorkingDir)
                || !Files.isDirectory(
                        projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Project memory root is unavailable or unsafe");
        }
        Path memFile = projectRoot.resolve(fileName).normalize();

        if (!memFile.startsWith(projectRoot)) {
            throw new IOException("Path traversal detected: " + memFile);
        }
        if (Files.isSymbolicLink(memFile)) {
            throw new IOException(
                    "Refusing to write Project memory through a symbolic link: "
                            + memFile);
        }
        if (Files.exists(memFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(
                        memFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Project memory path is not a regular file: " + memFile);
        }
        return memFile;
    }

    public boolean hasMemory(Path workingDir) {
        for (String fileName : MEMORY_FILES) {
            Path memoryFile = workingDir.resolve(fileName);
            if (!Files.isSymbolicLink(memoryFile)
                    && Files.isRegularFile(
                            memoryFile, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }
}

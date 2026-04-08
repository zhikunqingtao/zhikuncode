package com.aicodeassistant.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Git 提交归因 — 通过 ProcessBuilder 调用 git 命令。
 * 对齐原版 commitAttribution.ts
 */
@Service
public class CommitAttributionService {

    private static final Logger log = LoggerFactory.getLogger(CommitAttributionService.class);

    /**
     * 获取最近的提交信息。
     */
    public List<CommitInfo> getRecentCommits(Path workingDir, int limit) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--oneline", "-n", String.valueOf(limit),
                    "--format=%H|%an|%ae|%s|%ci");
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<CommitInfo> commits = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", 5);
                    if (parts.length >= 4) {
                        commits.add(new CommitInfo(parts[0], parts[1], parts[2],
                                parts.length > 3 ? parts[3] : "",
                                parts.length > 4 ? parts[4] : ""));
                    }
                }
            }
            process.waitFor();
            return commits;
        } catch (Exception e) {
            log.debug("Failed to get git log: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取文件的 git blame 信息。
     */
    public List<BlameEntry> getBlame(Path workingDir, String filePath, int startLine, int endLine) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "blame", "-L", startLine + "," + endLine,
                    "--porcelain", filePath);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<BlameEntry> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                String currentHash = null;
                String currentAuthor = null;
                while ((line = reader.readLine()) != null) {
                    if (line.matches("^[0-9a-f]{40} .*")) {
                        currentHash = line.substring(0, 40);
                    } else if (line.startsWith("author ")) {
                        currentAuthor = line.substring(7);
                    } else if (line.startsWith("\t")) {
                        if (currentHash != null) {
                            entries.add(new BlameEntry(currentHash,
                                    currentAuthor != null ? currentAuthor : "unknown",
                                    line.substring(1)));
                        }
                    }
                }
            }
            process.waitFor();
            return entries;
        } catch (Exception e) {
            log.debug("Failed to get git blame: {}", e.getMessage());
            return List.of();
        }
    }

    public record CommitInfo(String hash, String author, String email,
                             String subject, String date) {}

    public record BlameEntry(String commitHash, String author, String lineContent) {}
}

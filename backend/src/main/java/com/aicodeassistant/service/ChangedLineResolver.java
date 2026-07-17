package com.aicodeassistant.service;

import com.github.difflib.DiffUtils;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves real changed line numbers; never fabricates an empty successful request. */
@Service
public class ChangedLineResolver {
    private static final Logger log = LoggerFactory.getLogger(ChangedLineResolver.class);
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private final FileSnapshotRepository snapshots;
    private final ManagedProcessRunner processes;

    public ChangedLineResolver(FileSnapshotRepository snapshots, ManagedProcessRunner processes) {
        this.snapshots = snapshots;
        this.processes = processes;
    }

    public Optional<List<Integer>> resolve(String sessionId, String filePath, String projectRoot) {
        try {
            Path root = Path.of(projectRoot).toRealPath();
            Path candidate = Path.of(filePath);
            Path file = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(file)) return Optional.empty();
            file = file.toRealPath();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)) return Optional.empty();
            String relative = root.relativize(file).toString();

            Optional<List<Integer>> git = fromGit(root, relative);
            if (git.isPresent() && !git.get().isEmpty()) return git;

            Optional<FileSnapshotRepository.FileSnapshot> previous =
                    snapshots.findLatestBySessionAndPath(sessionId, filePath);
            if (previous.isEmpty() && !relative.equals(filePath)) {
                previous = snapshots.findLatestBySessionAndPath(sessionId, relative);
            }
            if (previous.isPresent()) {
                List<String> oldLines = Arrays.asList(previous.get().content().split("\\R", -1));
                List<String> newLines = Files.readAllLines(file, StandardCharsets.UTF_8);
                Set<Integer> lines = new LinkedHashSet<>();
                DiffUtils.diff(oldLines, newLines).getDeltas().forEach(delta -> {
                    int start = delta.getTarget().getPosition() + 1;
                    int size = delta.getTarget().size();
                    // Pure deletion has no target lines; anchor it to the closest surviving line.
                    if (size == 0 && !newLines.isEmpty()) lines.add(Math.min(start, newLines.size()));
                    else for (int i = 0; i < size; i++) lines.add(start + i);
                });
                if (!lines.isEmpty()) return Optional.of(List.copyOf(lines));
            }

            // Untracked/new text file: every current line is changed.
            if (!isTracked(root, relative)) {
                int count = Files.readAllLines(file, StandardCharsets.UTF_8).size();
                if (count > 0) {
                    List<Integer> all = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) all.add(i);
                    return Optional.of(all);
                }
            }
        } catch (Exception e) {
            log.info("CHANGE_LINES_UNAVAILABLE file={} reason={}", filePath, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<List<Integer>> fromGit(Path root, String relative) throws IOException, InterruptedException {
        ManagedProcessRunner.Result result = processes.run(ManagedProcessRunner.Request.serviceOwned(
                List.of("git", "diff", "--unified=0", "--", relative), root,
                Duration.ofSeconds(3), "git-diff-" + java.util.UUID.randomUUID()));
        if (result.exitCode() != 0 || result.timedOut() || result.stdoutTruncated()) return Optional.empty();
        String output = result.stdout();
        Set<Integer> lines = new LinkedHashSet<>();
        for (String row : output.split("\\R")) {
            Matcher matcher = HUNK.matcher(row);
            if (!matcher.matches()) continue;
            int start = Integer.parseInt(matcher.group(1));
            int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            for (int i = 0; i < count; i++) lines.add(start + i);
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(lines));
    }

    private boolean isTracked(Path root, String relative) throws IOException, InterruptedException {
        ManagedProcessRunner.Result result = processes.run(ManagedProcessRunner.Request.serviceOwned(
                List.of("git", "ls-files", "--error-unmatch", "--", relative), root,
                Duration.ofSeconds(3), "git-ls-files-" + java.util.UUID.randomUUID()));
        return !result.timedOut() && result.exitCode() == 0;
    }
}

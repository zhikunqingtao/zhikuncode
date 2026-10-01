package com.aicodeassistant.tool.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs only an isolated copy with inert service commands, never the developer stop.sh. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class StopScriptWorktreeTest {
    @TempDir Path temp;

    @Test
    void stoppingPreservesDirtyCommittedConflictAndUserWorktrees() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.name", "Fixture");
        git(repo, "config", "user.email", "fixture@example.invalid");
        Files.writeString(repo.resolve("shared.txt"), "base\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "base");
        Path dirty = add(repo, "agent-dirty");
        Files.writeString(dirty.resolve("uncommitted.txt"), "keep dirty\n");
        Path committed = add(repo, "agent-committed");
        Files.writeString(committed.resolve("result.txt"), "keep committed\n");
        git(committed, "add", ".");
        git(committed, "commit", "-m", "undelivered");
        Path conflict = add(repo, "agent-conflict");
        Files.writeString(conflict.resolve("shared.txt"), "agent\n");
        git(conflict, "commit", "-am", "agent side");
        Path user = add(repo, "user-feature");
        Files.writeString(repo.resolve("shared.txt"), "parent\n");
        git(repo, "commit", "-am", "parent side");
        assertThat(run(conflict, Map.of(), "git", "merge", "main").exitCode()).isNotZero();

        String beforeRefs = git(repo, "show-ref");
        String beforeTrees = git(repo, "worktree", "list", "--porcelain");
        Path script = Path.of("..", "stop.sh").toAbsolutePath().normalize();
        Files.copy(script, repo.resolve("stop.sh"));
        Path bin = Files.createDirectory(temp.resolve("bin"));
        executable(bin.resolve("lsof"), "#!/bin/sh\nexit 1\n");
        executable(bin.resolve("sleep"), "#!/bin/sh\nexit 0\n");
        var result = run(repo, Map.of("PATH", bin + ":" + System.getenv("PATH")),
                "/bin/bash", repo.resolve("stop.sh").toString());
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(git(repo, "show-ref")).isEqualTo(beforeRefs);
        assertThat(git(repo, "worktree", "list", "--porcelain")).isEqualTo(beforeTrees);
        assertThat(Files.readString(dirty.resolve("uncommitted.txt"))).isEqualTo("keep dirty\n");
        assertThat(Files.readString(committed.resolve("result.txt"))).isEqualTo("keep committed\n");
        assertThat(git(conflict, "ls-files", "-u")).isNotBlank();
        assertThat(user).isDirectory();
    }

    private Path add(Path repo, String branch) throws Exception {
        Path path = temp.resolve(branch);
        git(repo, "worktree", "add", "-b", branch, path.toString(), "HEAD");
        return path;
    }

    private String git(Path cwd, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Result result = run(cwd, Map.of(), command.toArray(String[]::new));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return result.output();
    }

    private Result run(Path cwd, Map<String, String> extra, String... command) throws Exception {
        Path output = Files.createTempFile(temp, "process-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().putAll(extra);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void executable(Path path, String content) throws Exception {
        Files.writeString(path, content);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private record Result(int exitCode, String output) { }
}

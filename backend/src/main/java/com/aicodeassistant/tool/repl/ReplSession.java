package com.aicodeassistant.tool.repl;

import java.io.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REPL 会话 — 管理单个解释器进程的生命周期。
 * <p>
 * 包含进程引用、stdout/stderr 读取器、最后活动时间等。
 *
 * @see <a href="SPEC §4.1.16">REPLTool</a>
 */
public class ReplSession {

    private final String id;
    private final String language;
    private final Process process;
    private final BufferedWriter stdinWriter;
    private final BufferedReader stdoutReader;
    private final BufferedReader stderrReader;
    private final AtomicReference<Instant> lastActive;
    private final Instant createdAt;

    public ReplSession(String id, String language, Process process) {
        this.id = id;
        this.language = language;
        this.process = process;
        this.stdinWriter = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream()));
        this.stdoutReader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
        this.stderrReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
        this.lastActive = new AtomicReference<>(Instant.now());
        this.createdAt = Instant.now();
    }

    /** 向进程 stdin 写入代码 */
    public void writeStdin(String code) throws IOException {
        stdinWriter.write(code);
        stdinWriter.newLine();
        stdinWriter.flush();
        updateLastActive();
    }

    /** 读取 stdout 可用输出（非阻塞，带超时） */
    public String readAvailableOutput(long timeoutMs) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        // 等待一小段时间让输出产生
        Thread.sleep(Math.min(200, timeoutMs));

        while (System.currentTimeMillis() < deadline && stdoutReader.ready()) {
            char[] buf = new char[4096];
            int read = stdoutReader.read(buf);
            if (read > 0) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    /** 读取 stderr 可用输出 */
    public String readAvailableStderr() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (stderrReader.ready()) {
            char[] buf = new char[4096];
            int read = stderrReader.read(buf);
            if (read > 0) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    /** 更新最后活动时间 */
    public void updateLastActive() {
        lastActive.set(Instant.now());
    }

    /** 销毁会话 — 终止进程 */
    public void destroy() {
        try {
            stdinWriter.close();
        } catch (IOException ignored) {}
        process.destroyForcibly();
    }

    /** 进程是否仍活着 */
    public boolean isAlive() {
        return process.isAlive();
    }

    public String id() { return id; }
    public String language() { return language; }
    public Process process() { return process; }
    public Instant lastActive() { return lastActive.get(); }
    public Instant createdAt() { return createdAt; }
}

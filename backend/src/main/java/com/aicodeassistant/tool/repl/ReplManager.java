package com.aicodeassistant.tool.repl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REPL 进程池管理器 — REPLTool 的底层服务层。
 * <p>
 * v1.45.0 明确: P1 阶段仅支持 Python REPL (python3 -i)。
 * Node/Ruby REPL 延后到 P2 实现。
 * <p>
 * 设计要点:
 * 1. 进程池: 最多 3 个并发 REPL 会话 (避免资源耗尽)
 * 2. 空闲回收: 10min 无输入自动 destroy 进程
 * 3. 会话总超时: 1小时强制销毁
 * 4. 输出截断: 单次输出超过 100KB 时截断
 *
 * @see <a href="SPEC §4.1.16">REPLTool</a>
 */
@Service
public class ReplManager {

    private static final Logger log = LoggerFactory.getLogger(ReplManager.class);

    /** 最大并发 REPL 会话数 */
    static final int MAX_CONCURRENT_SESSIONS = 3;
    /** 单次执行超时 (30秒) */
    static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);
    /** 会话空闲超时 (10分钟) */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    /** 会话总超时 (1小时) */
    static final Duration SESSION_MAX_LIFETIME = Duration.ofHours(1);
    /** 单次输出最大字节数 (100KB) */
    static final int MAX_OUTPUT_BYTES = 100 * 1024;

    /** P1 支持的语言列表 */
    static final Set<String> SUPPORTED_LANGUAGES = Set.of("python");
    /** P2 扩展语言 */
    static final Set<String> P2_LANGUAGES = Set.of("node", "ruby");

    private final Map<String, ReplSession> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建 REPL 会话。
     *
     * @param sessionId 会话 ID
     * @param language  语言 (python/node/ruby)
     * @param workDir   工作目录
     * @return REPL 会话
     */
    public ReplSession getOrCreate(String sessionId, String language, String workDir) {
        // 检查语言支持
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            if (P2_LANGUAGES.contains(language)) {
                throw new UnsupportedOperationException(
                        "P1 only supports Python REPL. " + language + " is planned for P2.");
            }
            throw new IllegalArgumentException("Unsupported REPL language: " + language);
        }

        // 检查已有会话
        ReplSession existing = sessions.get(sessionId);
        if (existing != null) {
            if (existing.isAlive()) {
                existing.updateLastActive();
                return existing;
            }
            // 进程已死，清理
            sessions.remove(sessionId);
        }

        // 检查并发限制
        if (sessions.size() >= MAX_CONCURRENT_SESSIONS) {
            evictLeastRecentlyUsed();
        }

        // 创建新会话
        ReplSession session = createSession(sessionId, language, workDir);
        sessions.put(sessionId, session);
        log.info("REPL session created: id={}, language={}", sessionId, language);
        return session;
    }

    /**
     * 创建 REPL 会话。
     */
    ReplSession createSession(String id, String language, String workDir) {
        String[] cmd = getInterpreterCommand(language);

        try {
            // 尝试 pty4j (如果可用)
            return createPtySession(id, language, cmd, workDir);
        } catch (Exception e) {
            // 降级到 ProcessBuilder
            log.warn("pty4j unavailable, falling back to ProcessBuilder: {}", e.getMessage());
            return createProcessBuilderSession(id, language, cmd, workDir);
        }
    }

    /**
     * 获取解释器命令。
     */
    String[] getInterpreterCommand(String language) {
        return switch (language) {
            case "python" -> new String[]{"python3", "-u", "-i"};
            case "node" -> new String[]{"node", "--interactive"};
            case "ruby" -> new String[]{"irb", "--simple-prompt"};
            default -> throw new IllegalArgumentException("Unsupported: " + language);
        };
    }

    /**
     * 使用 pty4j 创建 PTY 会话。
     * <p>
     * pty4j 提供真正的伪终端 (PTY)，解决:
     * - 无 ANSI 颜色输出 (isatty()=false)
     * - 无 readline 交互式提示符
     * - 无 Tab 补全
     */
    private ReplSession createPtySession(String id, String language, String[] cmd, String workDir) {
        // pty4j 动态检测 — P1 阶段提供桩实现
        // 实际部署时引入 pty4j 依赖后替换
        throw new UnsupportedOperationException("pty4j not available in current classpath");
    }

    /**
     * 使用 ProcessBuilder 创建会话 (降级方案)。
     * <p>
     * 降级时: 无 ANSI 颜色, 无 Tab 补全, 但代码执行功能完整。
     */
    private ReplSession createProcessBuilderSession(String id, String language,
                                                     String[] cmd, String workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workDir != null ? new File(workDir) : null);
            // 不 redirectErrorStream — 分离 stdout/stderr
            Process process = pb.start();
            return new ReplSession(id, language, process);
        } catch (IOException e) {
            throw new ReplException("Failed to start REPL process: " + e.getMessage(), e);
        }
    }

    /**
     * 获取现有会话。
     */
    public Optional<ReplSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 销毁指定会话。
     */
    public boolean destroySession(String sessionId) {
        ReplSession session = sessions.remove(sessionId);
        if (session != null) {
            session.destroy();
            log.info("REPL session destroyed: id={}", sessionId);
            return true;
        }
        return false;
    }

    /**
     * 淘汰最久未使用的会话。
     */
    void evictLeastRecentlyUsed() {
        sessions.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().lastActive()))
                .ifPresent(e -> {
                    e.getValue().destroy();
                    sessions.remove(e.getKey());
                    log.info("REPL session evicted (LRU): id={}", e.getKey());
                });
    }

    /**
     * 清理空闲和过期会话 — 每分钟检查。
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupSessions() {
        Instant idleCutoff = Instant.now().minus(IDLE_TIMEOUT);
        Instant lifetimeCutoff = Instant.now().minus(SESSION_MAX_LIFETIME);

        sessions.entrySet().removeIf(e -> {
            ReplSession s = e.getValue();
            boolean idle = s.lastActive().isBefore(idleCutoff);
            boolean expired = s.createdAt().isBefore(lifetimeCutoff);
            boolean dead = !s.isAlive();

            if (idle || expired || dead) {
                s.destroy();
                log.info("REPL session cleaned: id={}, idle={}, expired={}, dead={}",
                        e.getKey(), idle, expired, dead);
                return true;
            }
            return false;
        });
    }

    /**
     * 截断输出 — 超过 MAX_OUTPUT_BYTES 时截断。
     */
    public String truncateOutput(String output) {
        if (output == null) return "";
        if (output.length() > MAX_OUTPUT_BYTES) {
            return output.substring(0, MAX_OUTPUT_BYTES)
                    + "\n... [output truncated at " + MAX_OUTPUT_BYTES + " bytes]";
        }
        return output;
    }

    /**
     * 获取活跃会话数。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 获取所有会话 ID。
     */
    public Set<String> getSessionIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    /**
     * 销毁所有会话。
     */
    public void destroyAll() {
        sessions.values().forEach(ReplSession::destroy);
        sessions.clear();
        log.info("All REPL sessions destroyed");
    }

    /** REPL 异常 */
    public static class ReplException extends RuntimeException {
        public ReplException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator 服务 — 多代理协作模式核心。
 * <p>
 * 对标原版 src/coordinator/coordinatorMode.ts (370行)。
 * Coordinator 模式让主 LLM 扮演协调者角色，不直接执行工具，
 * 而是通过 AgentTool 生成工人代理并行处理复杂任务的不同部分。
 * <p>
 * 激活条件：
 * 1. FeatureFlag COORDINATOR_MODE = true
 * 2. 环境变量 CLAUDE_CODE_COORDINATOR_MODE = '1' (可选，用于运行时切换)
 *
 * @see <a href="SPEC §4.16">Coordinator 多代理协调模式</a>
 */
@Service
public class CoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorService.class);

    private final FeatureFlagService featureFlags;
    private final ToolRegistry toolRegistry;

    /**
     * 运行时环境变量存储 — 替代 System.getenv()/System.setProperty()。
     * <p>
     * 修复原 getenv() vs setProperty() 命名空间不匹配 bug：
     * Java 中 System.getenv() 返回只读 OS 环境变量 Map，
     * System.setProperty() 操作的是 JVM 系统属性，二者完全独立。
     * 使用会话级 ConcurrentHashMap 统一读写，初始化时回退读取 System.getenv()。
     */
    private final ConcurrentHashMap<String, String> runtimeEnv = new ConcurrentHashMap<>();

    /** 工人不可见的内部工具 */
    private static final Set<String> INTERNAL_WORKER_TOOLS = Set.of(
            "TeamCreate", "TeamDelete", "SendMessage", "SyntheticOutput"
    );

    /** Coordinator 模式下协调者可用的工具 */
    private static final Set<String> COORDINATOR_ALLOWED_TOOLS = Set.of(
            "Agent", "TaskStop", "SendMessage", "SyntheticOutput"
    );

    public CoordinatorService(FeatureFlagService featureFlags,
                              ToolRegistry toolRegistry) {
        this.featureFlags = featureFlags;
        this.toolRegistry = toolRegistry;
    }

    // ============ 模式检测 ============

    /**
     * 检查是否处于 Coordinator 模式。
     * 对齐原版 isCoordinatorMode()。
     */
    public boolean isCoordinatorMode() {
        return featureFlags.isEnabled("COORDINATOR_MODE")
                && isEnvTruthy(runtimeEnv.getOrDefault(
                        "CLAUDE_CODE_COORDINATOR_MODE",
                        System.getenv("CLAUDE_CODE_COORDINATOR_MODE")));
    }

    /**
     * 自动检测任务复杂度决定是否建议启用 Coordinator。
     * 基础版：基于用户消息中的关键词启发式判断。
     */
    public boolean shouldSuggestCoordinator(String userMessage) {
        if (!featureFlags.isEnabled("COORDINATOR_MODE")) return false;
        if (userMessage == null) return false;
        String lower = userMessage.toLowerCase();
        int signals = 0;
        if (lower.contains("refactor") || lower.contains("重构")) signals++;
        if (lower.contains("migrate") || lower.contains("迁移")) signals++;
        if (lower.contains("test") && lower.contains("implement")) signals++;
        if (lower.contains("multiple files") || lower.contains("多个文件")) signals++;
        if (lower.contains("parallel") || lower.contains("并行")) signals++;
        if (lower.contains("comprehensive") || lower.contains("全面")) signals++;
        return signals >= 2;
    }

    /**
     * 会话恢复时同步模式。
     * 对齐原版 matchSessionMode()。
     *
     * @param sessionMode 存储的会话模式 ("coordinator" | "normal" | null)
     * @return 切换提示消息，或 null 如果无需切换
     */
    public String matchSessionMode(String sessionMode) {
        if (sessionMode == null) return null;
        boolean current = isCoordinatorMode();
        boolean target = "coordinator".equals(sessionMode);
        if (current == target) return null;

        if (target) {
            runtimeEnv.put("CLAUDE_CODE_COORDINATOR_MODE", "1");
        } else {
            runtimeEnv.remove("CLAUDE_CODE_COORDINATOR_MODE");
        }

        return target
                ? "Entered coordinator mode to match resumed session."
                : "Exited coordinator mode to match resumed session.";
    }

    // ============ 工人工具上下文 ============

    /**
     * 构建工人可用工具列表上下文。
     * 对齐原版 getCoordinatorUserContext()。
     */
    public Map<String, String> getWorkerToolsContext(String sessionId) {
        if (!isCoordinatorMode()) return Map.of();

        List<String> workerTools = toolRegistry.getEnabledTools(sessionId).stream()
                .map(Tool::getName)
                .filter(name -> !INTERNAL_WORKER_TOOLS.contains(name))
                .sorted()
                .toList();

        String content = "Workers spawned via the Agent tool have access to these tools: "
                + String.join(", ", workerTools);

        return Map.of("workerToolsContext", content);
    }

    /**
     * 获取 Coordinator 模式下协调者可用的工具集。
     * 协调者只能使用代理管理工具，不能直接执行文件操作。
     */
    public Set<String> getCoordinatorAllowedTools() {
        return COORDINATOR_ALLOWED_TOOLS;
    }

    // ============ Scratchpad ============

    /**
     * 获取会话的 scratchpad 目录（Worker 间共享文件交换区）。
     * 自动创建，位于 .claude/scratchpad/{sessionId}/
     */
    public Path getScratchpadDir(String sessionId) {
        Path dir = Path.of(System.getProperty("user.dir"), ".claude", "scratchpad", sessionId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("Failed to create scratchpad dir: {}", dir, e);
        }
        return dir;
    }

    // ============ 辅助方法 ============

    private boolean isEnvTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}

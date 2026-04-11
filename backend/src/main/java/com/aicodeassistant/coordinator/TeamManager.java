package com.aicodeassistant.coordinator;

import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentRequest;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队生命周期管理器 — 创建、查询、销毁多 Agent 团队。
 * <p>
 * 每个团队包含一组 Worker Agent，由 Coordinator 调度。
 * 团队通过 InProcessBackend 使用 Virtual Thread 并发执行子任务。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作体系</a>
 */
@Service
public class TeamManager {

    private static final Logger log = LoggerFactory.getLogger(TeamManager.class);

    private final InProcessBackend backend;
    private final Map<String, TeamInfo> teams = new ConcurrentHashMap<>();

    public TeamManager(InProcessBackend backend) {
        this.backend = backend;
    }

    /**
     * 创建一个新团队。
     *
     * @param teamName   团队名称（唯一标识）
     * @param workerCount Worker 数量
     * @param sessionId  父会话 ID
     * @return 团队信息
     */
    public TeamInfo createTeam(String teamName, int workerCount, String sessionId) {
        if (teams.containsKey(teamName)) {
            throw new IllegalArgumentException("Team already exists: " + teamName);
        }
        if (workerCount < 1 || workerCount > 20) {
            throw new IllegalArgumentException("Worker count must be between 1 and 20, got: " + workerCount);
        }

        TeamInfo info = new TeamInfo(teamName, workerCount, sessionId, Instant.now());
        teams.put(teamName, info);
        log.info("Team created: name={}, workers={}, session={}", teamName, workerCount, sessionId);
        return info;
    }

    /**
     * 向团队分发任务。
     *
     * @param teamName 团队名称
     * @param tasks    任务列表 (每个任务包含 prompt 和可选的 agentType)
     * @return 执行结果列表
     */
    public List<AgentResult> dispatchTasks(String teamName, List<TaskSpec> tasks,
                                            com.aicodeassistant.tool.ToolUseContext parentContext) {
        TeamInfo info = teams.get(teamName);
        if (info == null) {
            throw new IllegalArgumentException("Team not found: " + teamName);
        }

        List<AgentRequest> requests = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            TaskSpec spec = tasks.get(i);
            String agentId = teamName + "-worker-" + i;
            requests.add(new AgentRequest(
                    agentId, spec.prompt(), spec.agentType(),
                    spec.model(), SubAgentExecutor.IsolationMode.NONE, false
            ));
        }

        log.info("Dispatching {} tasks to team '{}'", requests.size(), teamName);
        return backend.executeParallel(requests, parentContext, info.workerCount());
    }

    /**
     * 获取团队信息。
     */
    public Optional<TeamInfo> getTeam(String teamName) {
        return Optional.ofNullable(teams.get(teamName));
    }

    /**
     * 列出所有团队。
     */
    public List<TeamInfo> listTeams() {
        return List.copyOf(teams.values());
    }

    /**
     * 销毁团队。
     */
    public boolean destroyTeam(String teamName) {
        TeamInfo removed = teams.remove(teamName);
        if (removed != null) {
            log.info("Team destroyed: {}", teamName);
            return true;
        }
        return false;
    }

    /**
     * 清除所有团队。
     */
    public void destroyAll() {
        teams.clear();
        log.info("All teams destroyed");
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 团队信息 */
    public record TeamInfo(
            String name,
            int workerCount,
            String sessionId,
            Instant createdAt
    ) {}

    /** 任务规格 */
    public record TaskSpec(
            String prompt,
            String agentType,
            String model
    ) {
        public TaskSpec(String prompt) {
            this(prompt, null, null);
        }
    }
}

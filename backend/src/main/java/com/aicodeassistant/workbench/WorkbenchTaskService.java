package com.aicodeassistant.workbench;

import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.service.SessionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkbenchTaskService {
    private final SessionRepository sessions;
    private final RunEnvelopeRepository runs;
    private final JdbcTemplate jdbc;

    public WorkbenchTaskService(SessionRepository sessions,
                                RunEnvelopeRepository runs,
                                @Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    public TaskListView list(String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        Map<TaskGroup, List<TaskItem>> grouped = new LinkedHashMap<>();
        for (TaskGroup group : TaskGroup.values()) grouped.put(group, new ArrayList<>());
        for (SessionSummary session : sessions.listAll(500)) {
            String title = title(session);
            String folder = folder(session.workingDirectory());
            if (!needle.isBlank() && !(title + " " + folder + " "
                    + nullToEmpty(session.goalPreview())).toLowerCase(Locale.ROOT).contains(needle)) continue;
            int pending = pendingCount(session.id());
            RunEnvelope run = runs.findLatestRootBySession(session.id()).orElse(null);
            TaskGroup group = group(session.id(), run, pending);
            grouped.get(group).add(new TaskItem(session.id(), title, folder, group,
                    session.updatedAt(), pending, hint(group, pending)));
        }
        return new TaskListView(List.of(
                view(TaskGroup.ACTION_REQUIRED, "待我处理", grouped),
                view(TaskGroup.RUNNING, "进行中", grouped),
                view(TaskGroup.REVIEWABLE, "可查看结果", grouped),
                view(TaskGroup.OTHER, "其他任务", grouped)));
    }

    private int pendingCount(String sessionId) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM interaction_requests
                WHERE session_id=? AND status='pending'
                """, Integer.class, sessionId);
        return value == null ? 0 : value;
    }

    private TaskGroup group(String sessionId, RunEnvelope run, int pending) {
        if (pending > 0) return TaskGroup.ACTION_REQUIRED;
        if (run == null) return TaskGroup.OTHER;
        if (!run.status().terminal()) return TaskGroup.RUNNING;
        if (run.status() != RunEnvelope.RunStatus.COMPLETED) return TaskGroup.REVIEWABLE;
        Integer resultCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM run_workbench_bindings
                WHERE root_run_id=? AND result_message_id IS NOT NULL
                """, Integer.class, run.id());
        if (resultCount != null && resultCount > 0) return TaskGroup.REVIEWABLE;
        if (artifactsExist(run.id()) || legacyResultExists(sessionId, run)) {
            return TaskGroup.REVIEWABLE;
        }
        return TaskGroup.OTHER;
    }

    private boolean legacyResultExists(String sessionId, RunEnvelope run) {
        Instant upper = run.finishedAt() == null ? Instant.now() : run.finishedAt().plusSeconds(2);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM messages
                WHERE session_id=? AND role='assistant'
                  AND created_at>=? AND created_at<=?
                  AND content_json LIKE '%"type":"text"%'
                """, Integer.class, sessionId, run.startedAt().toString(), upper.toString());
        return count != null && count > 0;
    }

    private boolean artifactsExist(String rootRunId) {
        Integer count = jdbc.queryForObject("""
                WITH RECURSIVE run_tree(id) AS (
                  SELECT id FROM run_envelopes WHERE id=?
                  UNION ALL
                  SELECT child.id FROM run_envelopes child
                  JOIN run_tree parent ON child.parent_run_id=parent.id
                )
                SELECT COUNT(*) FROM artifact_manifests manifest
                JOIN run_tree tree ON tree.id=manifest.run_id
                """, Integer.class, rootRunId);
        return count != null && count > 0;
    }

    private static String hint(TaskGroup group, int pending) {
        return switch (group) {
            case ACTION_REQUIRED -> pending + " 项需要处理";
            case RUNNING -> "正在执行";
            case REVIEWABLE -> "结果可查看";
            case OTHER -> "尚未开始执行";
        };
    }

    private static String title(SessionSummary session) {
        if (session.title() != null && !session.title().isBlank()) return session.title();
        if (session.goalPreview() != null && !session.goalPreview().isBlank()) return session.goalPreview();
        String folder = folder(session.workingDirectory());
        return folder.isBlank() ? "未命名任务" : "在 " + folder + " 中的新任务";
    }

    private static String folder(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) return "未选择文件夹";
        try {
            Path name = Path.of(workingDirectory).getFileName();
            return name == null ? workingDirectory : name.toString();
        } catch (RuntimeException ignored) {
            return workingDirectory;
        }
    }

    private static GroupView view(TaskGroup group, String label,
                                  Map<TaskGroup, List<TaskItem>> grouped) {
        return new GroupView(group, label, List.copyOf(grouped.get(group)));
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    public enum TaskGroup { ACTION_REQUIRED, RUNNING, REVIEWABLE, OTHER }
    public record TaskItem(String sessionId, String title, String folderName,
                           TaskGroup status, Instant updatedAt, int pendingCount,
                           String hint) { }
    public record GroupView(TaskGroup status, String label, List<TaskItem> tasks) { }
    public record TaskListView(List<GroupView> groups) { }
}

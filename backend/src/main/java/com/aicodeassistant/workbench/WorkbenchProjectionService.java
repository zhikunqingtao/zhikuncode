package com.aicodeassistant.workbench;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.verify.EvidenceBundle;
import com.aicodeassistant.verify.EvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.aicodeassistant.workbench.WorkbenchCurrentView.*;

@Service
@DependsOn("migrationRunner")
public class WorkbenchProjectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RunEnvelopeRepository runs;
    private final ArtifactManifestService artifacts;
    private final EvidenceStore evidenceStore;
    private final StructuredSummaryExtractor summaries;
    private final AcceptanceCriteriaExtractor criteriaExtractor;
    private final DurableInteractionService interactions;

    public WorkbenchProjectionService(
            @Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper json,
            RunEnvelopeRepository runs,
            ArtifactManifestService artifacts,
            EvidenceStore evidenceStore,
            StructuredSummaryExtractor summaries,
            AcceptanceCriteriaExtractor criteriaExtractor,
            DurableInteractionService interactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.runs = runs;
        this.artifacts = artifacts;
        this.evidenceStore = evidenceStore;
        this.summaries = summaries;
        this.criteriaExtractor = criteriaExtractor;
        this.interactions = interactions;
    }

    public WorkbenchCurrentView current(String sessionId) {
        RunEnvelope root = runs.findLatestRootBySession(sessionId).orElse(null);
        if (root == null) return withoutRun(sessionId);

        Map<String, Object> binding = binding(root.id()).orElse(null);
        CorrelationMode correlation = binding == null
                ? CorrelationMode.LEGACY_FALLBACK : CorrelationMode.EXACT;
        MessageView request = binding == null
                ? legacyRequest(sessionId, root).orElse(null)
                : message(String.valueOf(binding.get("request_message_id")), "user").orElse(null);
        String resultId = binding == null ? null : nullable(binding.get("result_message_id"));
        MessageView result = binding == null
                ? legacyResult(sessionId, root).orElse(null)
                : message(resultId, "assistant")
                        .or(() -> exactResultFromRunEvent(root.id()))
                        .orElse(null);

        List<RunEnvelope> tree = runs.findTree(root.id());
        List<ArtifactManifest> manifests = artifacts.getManifestsForRunTree(root.id());
        List<EvidenceBundle> evidence = evidenceStore.findByRunIds(
                tree.stream().map(RunEnvelope::id).toList());
        DeliveryView delivery = delivery(manifests);
        VerificationView verification = verification(root, tree, request, manifests, evidence, correlation);
        List<String> runIds = tree.stream().map(RunEnvelope::id).toList();
        List<InteractionView> pendingActions = pendingActionsForRuns(runIds);
        List<ActivityView> currentActivities = activitiesForRuns(runIds);
        PreviousDeliveryView previous = root.status().terminal()
                && root.status() != RunEnvelope.RunStatus.COMPLETED
                ? previousDelivery(sessionId, root.id()).orElse(null) : null;
        FailureView failure = root.status().terminal()
                && root.status() != RunEnvelope.RunStatus.COMPLETED
                ? new FailureView(root.status().name(), firstNonBlank(
                        root.errorSummary(), root.abortReason(), root.waitingReason(),
                        root.exitReason() == null ? null : root.exitReason().name()))
                : null;
        return new WorkbenchCurrentView(
                correlation,
                request == null ? null : request.messageId(),
                result == null ? null : result.messageId(),
                root, request, result,
                summaries.extract(result == null ? null : result.text()),
                delivery, verification, pendingActions.size(), pendingActions,
                currentActivities, previous, failure);
    }

    private WorkbenchCurrentView withoutRun(String sessionId) {
        MessageView request = latestMessage(sessionId, "user").orElse(null);
        MessageView result = latestMessage(sessionId, "assistant").orElse(null);
        return new WorkbenchCurrentView(CorrelationMode.LEGACY_FALLBACK,
                request == null ? null : request.messageId(),
                result == null ? null : result.messageId(), null, request, result,
                summaries.extract(result == null ? null : result.text()),
                new DeliveryView(List.of(), List.of(), 0, null),
                new VerificationView(List.of(), List.of(), List.of(),
                        CriterionStatus.NOT_VERIFIED), 0, List.of(), List.of(), null, null);
    }

    private List<InteractionView> pendingActionsForRuns(List<String> runIds) {
        if (runIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
        List<String> ids = jdbc.queryForList(
                "SELECT interaction_id FROM interaction_requests WHERE status='pending' AND run_id IN ("
                        + placeholders + ") ORDER BY created_at",
                String.class, runIds.toArray());
        List<InteractionView> result = new ArrayList<>();
        for (String id : ids) {
            try {
                var request = interactions.findById(id);
                if (request.status() == com.aicodeassistant.interaction.InteractionRequest.Status.PENDING) {
                    result.add(interactions.view(request));
                }
            } catch (RuntimeException resolvedDuringProjection) {
                // A decision may win between the id query and DTO projection.
            }
        }
        return List.copyOf(result);
    }

    private VerificationView verification(
            RunEnvelope root, List<RunEnvelope> tree, MessageView request,
            List<ArtifactManifest> manifests, List<EvidenceBundle> evidence,
            CorrelationMode correlation) {
        List<CriterionView> business = correlation == CorrelationMode.EXACT
                ? persistedCriteria(root.id(), evidence)
                : criteriaExtractor.extract(request == null ? null : request.text()).stream()
                    .map(text -> new CriterionView(null, "business", text,
                            CriterionStatus.NOT_VERIFIED,
                            "历史记录没有Root Run级验收关联", null)).toList();
        List<CriterionView> technical = technicalChecks(root, tree, manifests, evidence);
        CriterionStatus overall = overall(business, technical);
        return new VerificationView(business, technical, evidence, overall);
    }

    private List<CriterionView> persistedCriteria(String rootRunId, List<EvidenceBundle> evidence) {
        Map<String, EvidenceBundle> exactClaims = new LinkedHashMap<>();
        for (EvidenceBundle bundle : evidence) {
            if (bundle.claim() != null) exactClaims.putIfAbsent(normalize(bundle.claim()), bundle);
        }
        return jdbc.query("""
                SELECT * FROM run_acceptance_criteria
                WHERE root_run_id=? ORDER BY ordinal ASC
                """, (rs, row) -> {
            String text = rs.getString("source_text");
            EvidenceBundle matched = exactClaims.get(normalize(text));
            CriterionStatus status = matched == null
                    ? CriterionStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT))
                    : statusForVerdict(matched.verdict());
            return new CriterionView(rs.getString("criterion_id"), "business", text,
                    status, matched == null ? "尚无明确关联的确定性证据" : matched.claim(),
                    matched == null ? rs.getString("evidence_bundle_id") : matched.bundleId());
        }, rootRunId);
    }

    private List<CriterionView> technicalChecks(
            RunEnvelope root, List<RunEnvelope> tree,
            List<ArtifactManifest> manifests, List<EvidenceBundle> evidence) {
        List<CriterionView> checks = new ArrayList<>();
        boolean terminal = root.status().terminal();
        boolean hasCurrentFile = manifests.stream().flatMap(m -> m.entries().stream())
                .filter(entry -> !"deleted".equals(entry.operation()))
                .anyMatch(this::currentlyAvailable);
        checks.add(new CriterionView("technical-primary-artifact", "technical",
                "主成果文件存在",
                hasCurrentFile ? CriterionStatus.PASSED
                        : manifests.isEmpty() ? CriterionStatus.NOT_VERIFIED
                        : terminal ? CriterionStatus.FAILED : CriterionStatus.NOT_VERIFIED,
                hasCurrentFile ? "当前Root Run子树至少有一个仍可读取的交付文件"
                        : manifests.isEmpty() ? "当前Root Run没有结构化Manifest，不能据此判断成果不存在"
                        : "当前Root Run的Manifest没有可读取的交付文件", null));

        CriterionStatus manifestStatus = manifests.isEmpty() ? CriterionStatus.NOT_VERIFIED
                : manifests.stream().anyMatch(m -> "failed".equals(m.status()))
                    ? CriterionStatus.FAILED
                    : manifests.stream().allMatch(m -> "verified".equals(m.status()))
                        ? CriterionStatus.PASSED : CriterionStatus.PARTIAL;
        checks.add(new CriterionView("technical-manifest-integrity", "technical",
                "交付文件与Manifest一致", manifestStatus,
                manifests.isEmpty() ? "当前Root Run没有Manifest"
                        : "只统计当前Root Run及递归子Run的Manifest", null));

        CriterionStatus runtime = evidence.isEmpty() ? CriterionStatus.NOT_VERIFIED
                : evidence.stream().anyMatch(bundle -> "failed".equals(bundle.verdict()))
                    ? CriterionStatus.FAILED
                    : evidence.stream().allMatch(bundle -> "verified".equals(bundle.verdict()))
                        ? CriterionStatus.PASSED : CriterionStatus.PARTIAL;
        checks.add(new CriterionView("technical-runtime-verification", "technical",
                "页面或程序完成运行时检查", runtime,
                evidence.isEmpty() ? "当前Run树没有运行时验收证据"
                        : "仅使用明确绑定到当前Run树的证据", null));

        long failedChildRuns = tree.stream()
                .filter(run -> run.parentRunId() != null)
                .filter(run -> run.status() == RunEnvelope.RunStatus.FAILED)
                .count();
        boolean failedRoot = terminal && root.status() != RunEnvelope.RunStatus.COMPLETED;
        boolean failedEvidence = evidence.stream().anyMatch(bundle -> "failed".equals(bundle.verdict()));
        checks.add(new CriterionView("technical-no-failure-evidence", "technical",
                "本轮交付没有明确失败结论",
                !terminal ? CriterionStatus.NOT_VERIFIED
                        : failedRoot || failedEvidence ? CriterionStatus.FAILED
                        : failedChildRuns > 0 ? CriterionStatus.PARTIAL : CriterionStatus.PASSED,
                failedRoot ? "当前Root Run未成功完成"
                        : failedEvidence ? "当前Run树存在明确失败证据"
                        : failedChildRuns > 0 ? "Root Run已完成；" + failedChildRuns
                                + "个子Run失败或被回收，不等同于根任务失败"
                        : terminal ? "Root Run已完成且未发现失败证据" : "执行尚未结束", null));
        return List.copyOf(checks);
    }

    private Optional<PreviousDeliveryView> previousDelivery(String sessionId, String currentRootId) {
        for (RunEnvelope candidate : runs.findRootRunsBySession(sessionId, 50)) {
            if (candidate.id().equals(currentRootId)
                    || candidate.status() != RunEnvelope.RunStatus.COMPLETED) continue;
            DeliveryView delivery = delivery(artifacts.getManifestsForRunTree(candidate.id()));
            if (delivery.totalFiles() == 0) continue;
            MessageView result = binding(candidate.id())
                    .flatMap(row -> message(nullable(row.get("result_message_id")), "assistant"))
                    .or(() -> legacyResult(sessionId, candidate)).orElse(null);
            return Optional.of(new PreviousDeliveryView(candidate.id(),
                    candidate.finishedAt(), result, delivery));
        }
        return Optional.empty();
    }

    private DeliveryView delivery(List<ArtifactManifest> manifests) {
        Map<String, ManifestEntry> latest = new LinkedHashMap<>();
        manifests.stream()
                .sorted(Comparator.comparing(ArtifactManifest::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(manifest -> manifest.entries().forEach(entry ->
                        latest.putIfAbsent(entry.filePath(), new ManifestEntry(manifest, entry))));
        List<ManifestEntry> available = latest.values().stream()
                .filter(item -> !"deleted".equals(item.entry().operation()))
                .filter(item -> insideWorkspace(item.manifest(), item.entry()))
                .filter(item -> currentlyAvailable(item.entry()))
                .sorted(Comparator
                        .comparing((ManifestEntry item) -> !item.entry().verified())
                        .thenComparingInt(item -> extensionRank(item.entry().filePath()))
                        .thenComparing(item -> item.entry().filePath()))
                .toList();
        String primary = available.isEmpty() ? null : available.getFirst().entry().filePath();
        List<DeliveryFileView> files = available.stream().map(item -> new DeliveryFileView(
                item.manifest().id(), item.manifest().workspaceRoot(), item.entry().id(),
                item.entry().filePath(), relativePath(item.manifest(), item.entry()),
                item.entry().operation(), item.entry().state(), item.entry().fileSize(),
                item.entry().verified(), item.entry().mismatchDetail(),
                item.entry().filePath().equals(primary))).toList();
        return new DeliveryView(manifests, files, files.size(), primary);
    }

    private List<ActivityView> activitiesForRuns(List<String> runIds) {
        if (runIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT run_id,event_data FROM run_event_log WHERE event_type='tool_finished' AND run_id IN ("
                        + placeholders + ") ORDER BY ts ASC,id ASC", runIds.toArray());
        LinkedHashMap<String, ToolEventRef> toolRuns = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            ToolEventRef projected = toolEvent(String.valueOf(event.get("event_data")),
                    String.valueOf(event.get("run_id")));
            if (!projected.toolUseId().isBlank()) toolRuns.put(projected.toolUseId(), projected);
        }
        if (toolRuns.isEmpty()) return List.of();
        String activityPlaceholders = String.join(",",
                java.util.Collections.nCopies(toolRuns.size(), "?"));
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM activities WHERE id IN (" + activityPlaceholders + ")",
                toolRuns.keySet().toArray()).forEach(row -> rows.put(String.valueOf(row.get("id")), row));
        List<ActivityView> result = new ArrayList<>();
        toolRuns.forEach((toolUseId, event) -> {
            Map<String, Object> row = rows.get(toolUseId);
            if (row != null) result.add(activity(row, event));
        });
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private ActivityView activity(Map<String, Object> row, ToolEventRef event) {
        Object toolResult = parseJson(row.get("tool_result_json"), null);
        Object insight = parseJson(row.get("insight_json"), null);
        Object changed = parseJson(row.get("changed_files_json"), List.of());
        List<Map<String, Object>> changedFiles = changed instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList()
                : List.of();
        return new ActivityView(String.valueOf(row.get("id")),
                String.valueOf(row.get("session_id")), event.runId(),
                String.valueOf(row.get("operation_type")), String.valueOf(row.get("summary")),
                event.toolName(), String.valueOf(row.get("status")),
                ((Number) row.get("timestamp")).longValue(),
                row.get("duration") instanceof Number value ? value.intValue() : null,
                row.get("file_count") instanceof Number value ? value.intValue() : 0,
                nullable(row.get("decision")), toolResult, changedFiles, insight);
    }

    private Object parseJson(Object value, Object fallback) {
        if (value == null) return fallback;
        try { return json.readValue(String.valueOf(value), Object.class); }
        catch (Exception ignored) { return fallback; }
    }

    private ToolEventRef toolEvent(String payload, String runId) {
        try {
            JsonNode envelope = json.readTree(payload);
            String id = envelope.path("toolUseId").asText("");
            JsonNode data = envelope.path("data");
            if (id.isBlank()) id = data.path("toolUseId").asText("");
            return new ToolEventRef(id, runId, data.path("toolName").asText(""));
        } catch (Exception ignored) { return new ToolEventRef("", runId, ""); }
    }

    private boolean insideWorkspace(ArtifactManifest manifest, ArtifactEntry entry) {
        try {
            Path root = Path.of(manifest.workspaceRoot()).toAbsolutePath().normalize();
            Path file = Path.of(entry.filePath()).toAbsolutePath().normalize();
            return file.startsWith(root);
        } catch (RuntimeException ignored) { return false; }
    }

    private String relativePath(ArtifactManifest manifest, ArtifactEntry entry) {
        try {
            return Path.of(manifest.workspaceRoot()).toAbsolutePath().normalize()
                    .relativize(Path.of(entry.filePath()).toAbsolutePath().normalize()).toString();
        } catch (RuntimeException ignored) { return entry.filePath(); }
    }

    private record ManifestEntry(ArtifactManifest manifest, ArtifactEntry entry) { }
    private record ToolEventRef(String toolUseId, String runId, String toolName) { }

    private Optional<Map<String, Object>> binding(String rootRunId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM run_workbench_bindings WHERE root_run_id=?", rootRunId);
        return rows.stream().findFirst();
    }

    /**
     * Best-effort repair for an exact binding whose final message update failed.
     * Only message_completed events from the same Root Run are considered; this
     * deliberately never falls back to another assistant message in the session.
     */
    private Optional<MessageView> exactResultFromRunEvent(String rootRunId) {
        List<String> events;
        try {
            events = jdbc.queryForList("""
                    SELECT event_data FROM run_event_log
                    WHERE run_id=? AND event_type='message_completed'
                    ORDER BY seq DESC LIMIT 200
                    """, String.class, rootRunId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        for (String payload : events) {
            try {
                JsonNode data = json.readTree(payload).path("data");
                if (!"end_turn".equals(data.path("stopReason").asText())) continue;
                Optional<MessageView> result = message(data.path("messageId").asText(), "assistant");
                if (result.isPresent()) return result;
            } catch (Exception ignored) {
                // A malformed observability row must not break the workbench read model.
            }
        }
        return Optional.empty();
    }

    private Optional<MessageView> message(String id, String role) {
        if (id == null || id.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,content_json,created_at FROM messages WHERE id=? AND role=?", id, role);
        return rows.stream().map(this::mapMessage).filter(view -> !view.text().isBlank()).findFirst();
    }

    private Optional<MessageView> latestMessage(String sessionId, String role) {
        return messages("""
                SELECT id,content_json,created_at FROM messages
                WHERE session_id=? AND role=? ORDER BY seq_num DESC LIMIT 50
                """, sessionId, role);
    }

    private Optional<MessageView> legacyRequest(String sessionId, RunEnvelope root) {
        return messages("""
                SELECT id,content_json,created_at FROM messages
                WHERE session_id=? AND role='user' AND created_at<=?
                ORDER BY created_at DESC,seq_num DESC LIMIT 50
                """, sessionId, root.startedAt().plusSeconds(2).toString());
    }

    private Optional<MessageView> legacyResult(String sessionId, RunEnvelope root) {
        String upper = Optional.ofNullable(root.finishedAt()).orElse(Instant.now()).plusSeconds(2).toString();
        return messages("""
                SELECT id,content_json,created_at FROM messages
                WHERE session_id=? AND role='assistant' AND created_at>=? AND created_at<=?
                ORDER BY created_at DESC,seq_num DESC LIMIT 100
                """, sessionId, root.startedAt().toString(), upper);
    }

    private Optional<MessageView> messages(String sql, Object... parameters) {
        return jdbc.queryForList(sql, parameters).stream().map(this::mapMessage)
                .filter(view -> !view.text().isBlank()).findFirst();
    }

    private MessageView mapMessage(Map<String, Object> row) {
        return new MessageView(String.valueOf(row.get("id")),
                extractText(String.valueOf(row.get("content_json"))),
                Instant.parse(String.valueOf(row.get("created_at"))));
    }

    private String extractText(String contentJson) {
        try {
            JsonNode root = json.readTree(contentJson);
            if (!root.isArray()) return "";
            List<String> parts = new ArrayList<>();
            for (JsonNode block : root) {
                if ("text".equals(block.path("type").asText())
                        && !block.path("text").asText("").isBlank()) {
                    parts.add(block.path("text").asText());
                }
            }
            return String.join("\n", parts).strip();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean currentlyAvailable(ArtifactEntry entry) {
        try {
            Path path = Path.of(entry.filePath());
            return !Files.isSymbolicLink(path)
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static CriterionStatus overall(List<CriterionView> business,
                                           List<CriterionView> technical) {
        List<CriterionView> all = new ArrayList<>(business); all.addAll(technical);
        if (all.stream().anyMatch(item -> item.status() == CriterionStatus.FAILED)) return CriterionStatus.FAILED;
        if (!business.isEmpty() && business.stream().anyMatch(item -> item.status() == CriterionStatus.NOT_VERIFIED)) {
            return technical.stream().anyMatch(item -> item.status() == CriterionStatus.PASSED)
                    ? CriterionStatus.PARTIAL : CriterionStatus.NOT_VERIFIED;
        }
        if (all.stream().anyMatch(item -> item.status() == CriterionStatus.PARTIAL)) return CriterionStatus.PARTIAL;
        if (!all.isEmpty() && all.stream().allMatch(item -> item.status() == CriterionStatus.PASSED)) return CriterionStatus.PASSED;
        return CriterionStatus.NOT_VERIFIED;
    }

    private static CriterionStatus statusForVerdict(String verdict) {
        return switch (verdict == null ? "" : verdict.toLowerCase(Locale.ROOT)) {
            case "verified", "passed" -> CriterionStatus.PASSED;
            case "failed" -> CriterionStatus.FAILED;
            default -> CriterionStatus.PARTIAL;
        };
    }

    private static int extensionRank(String path) {
        String name = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String[] ranked = {".html", ".md", ".pdf", ".docx", ".xlsx", ".pptx", ".csv", ".txt",
                ".png", ".jpg", ".jpeg", ".svg"};
        for (int index = 0; index < ranked.length; index++) if (name.endsWith(ranked[index])) return index;
        return 100;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "执行未成功完成";
    }
}

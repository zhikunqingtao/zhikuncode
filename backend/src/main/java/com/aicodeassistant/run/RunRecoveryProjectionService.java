package com.aicodeassistant.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a deterministic UI projection at a persisted Run event cursor. */
@Service
public class RunRecoveryProjectionService {
    private static final int PAGE_SIZE = 500;
    private final RunEnvelopeRepository runs;
    private final RunEventRepository events;
    private final ObjectMapper json;

    public RunRecoveryProjectionService(RunEnvelopeRepository runs, RunEventRepository events, ObjectMapper json) {
        this.runs = runs; this.events = events; this.json = json;
    }

    @Transactional(transactionManager = "projectTransactionManager", readOnly = true)
    public Projection latestForSession(String sessionId) {
        RunEnvelope run = runs.findBySession(sessionId, 1).stream().findFirst().orElse(null);
        if (run == null) return new Projection(null, 0, List.of(), List.of());
        int cursor = events.getMaxSeq(run.id());
        Map<String, Map<String, Object>> active = new LinkedHashMap<>();
        Map<String, Map<String, Object>> settledResults = new LinkedHashMap<>();
        int after = 0;
        while (after < cursor) {
            List<RunEvent> page = events.getEvents(run.id(), after, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (RunEvent event : page) {
                if (event.seq() > cursor) break;
                EventPayload projected = payload(event.eventData());
                Map<String, Object> payload = projected.data();
                String toolUseId = projected.toolUseId();
                if (toolUseId.isBlank()) continue;
                if ("tool_started".equals(event.eventType())) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("toolUseId", toolUseId);
                    tool.put("toolName", string(payload.get("toolName")));
                    tool.put("input", payload.getOrDefault("input", Map.of()));
                    tool.put("startedAt", event.ts());
                    active.put(toolUseId, tool);
                    settledResults.remove(toolUseId);
                } else if ("tool_finished".equals(event.eventType())) {
                    active.remove(toolUseId);
                    boolean isError = Boolean.TRUE.equals(payload.get("isError"));
                    String outputPreview = string(payload.get("outputPreview"));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "tool_result");
                    result.put("toolUseId", toolUseId);
                    result.put("content", outputPreview);
                    result.put("isError", isError);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("executionStatus", string(payload.get("executionStatus")));
                    metadata.put("outputTruncated", Boolean.TRUE.equals(payload.get("outputTruncated"))
                            || longValue(payload.get("outputLength")) > outputPreview.length());
                    metadata.put("durationMs", longValue(payload.get("durationMs")));
                    result.put("metadata", metadata);
                    settledResults.put(toolUseId, result);
                }
            }
            after = page.getLast().seq();
        }
        return new Projection(run, cursor, List.copyOf(active.values()), List.copyOf(settledResults.values()));
    }

    @SuppressWarnings("unchecked")
    private EventPayload payload(String value) {
        try {
            JsonNode envelope = json.readTree(value);
            JsonNode dataNode = envelope.path("data");
            Map<String, Object> data = dataNode.isObject()
                    ? json.convertValue(dataNode, Map.class) : Map.of();
            String toolUseId = envelope.path("toolUseId").asText("");
            if (toolUseId.isBlank()) {
                toolUseId = string(data.get("toolUseId"));
            }
            return new EventPayload(toolUseId, data);
        } catch (Exception ignored) {
            return new EventPayload("", Map.of());
        }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try { return Math.max(0L, Long.parseLong(string(value))); }
        catch (NumberFormatException ignored) { return 0L; }
    }
    private record EventPayload(
            String toolUseId, Map<String, Object> data) {}
    public record Projection(RunEnvelope runSnapshot, int snapshotEventSeq,
                             List<Map<String, Object>> activeToolCalls,
                             List<Map<String, Object>> settledToolResults) { }
}

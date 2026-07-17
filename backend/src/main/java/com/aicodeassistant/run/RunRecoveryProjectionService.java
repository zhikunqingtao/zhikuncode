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
        if (run == null) return new Projection(null, 0, List.of());
        int cursor = events.getMaxSeq(run.id());
        Map<String, Map<String, Object>> active = new LinkedHashMap<>();
        int after = 0;
        while (after < cursor) {
            List<RunEvent> page = events.getEvents(run.id(), after, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (RunEvent event : page) {
                if (event.seq() > cursor) break;
                Map<String, Object> payload = payload(event.eventData());
                String toolUseId = string(payload.get("toolUseId"));
                if (toolUseId.isBlank()) continue;
                if ("tool_started".equals(event.eventType())) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("toolUseId", toolUseId);
                    tool.put("toolName", string(payload.get("toolName")));
                    tool.put("input", payload.getOrDefault("input", Map.of()));
                    tool.put("startedAt", event.ts());
                    active.put(toolUseId, tool);
                } else if ("tool_finished".equals(event.eventType())) active.remove(toolUseId);
            }
            after = page.getLast().seq();
        }
        return new Projection(run, cursor, List.copyOf(active.values()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String value) {
        try {
            JsonNode data = json.readTree(value).path("data");
            return data.isObject() ? json.convertValue(data, Map.class) : Map.of();
        } catch (Exception ignored) { return Map.of(); }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    public record Projection(RunEnvelope runSnapshot, int snapshotEventSeq,
                             List<Map<String, Object>> activeToolCalls) { }
}

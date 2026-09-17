package com.aicodeassistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw provider usage is preserved, including absent fields; never synthesized from Usage.zero(). */
public record SummaryResult(String content, String finishReason, JsonNode usage,
                            String responseModel, String responseId, String failureReason) {
    public SummaryResult { usage = usage == null ? null : usage.deepCopy(); }
    @Override public JsonNode usage() { return usage == null ? null : usage.deepCopy(); }
    public static SummaryResult failed(String reason) {
        return new SummaryResult(null, null, null, null, null, reason);
    }
}

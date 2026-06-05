package com.aicodeassistant.verify;

import java.util.List;
import java.util.Map;

public record JourneyRequest(
    String sessionId,
    String baseUrl,
    List<Map<String, Object>> steps,
    Map<String, Object> recordOptions
) {}

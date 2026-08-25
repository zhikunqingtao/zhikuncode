package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_AMAP_ALL_TOOL_CALLS", matches = "true")
class AmapAllToolsLiveTest {

    private static final String AMAP_URL =
            "https://dashscope.aliyuncs.com/api/v1/mcps/amap-maps/sse";
    private static final String ORIGIN = "120.130203,30.259324";
    private static final String DESTINATION = "120.155100,30.274100";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern POI_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @Test
    void allThirteenSelectedAmapToolsReturnUsableResults() {
        McpServerConnection connection = connect(requireApiKey());
        Map<String, JsonNode> results = new LinkedHashMap<>();
        try {
            results.put("maps_text_search", call(connection, "maps_text_search",
                    Map.of("keywords", "西湖", "city", "杭州", "citylimit", true)));
            String poiId = extractPoiId(results.get("maps_text_search"));
            if (poiId.isBlank()) poiId = "B0FFFAB6J2";
            results.put("maps_search_detail", call(connection, "maps_search_detail",
                    Map.of("id", poiId)));
            results.put("maps_geo", call(connection, "maps_geo",
                    Map.of("address", "浙江省杭州市西湖区西湖风景名胜区", "city", "杭州")));
            results.put("maps_regeocode", call(connection, "maps_regeocode",
                    Map.of("location", ORIGIN)));
            results.put("maps_ip_location", call(connection, "maps_ip_location",
                    Map.of("ip", "8.8.8.8")));
            results.put("maps_around_search", call(connection, "maps_around_search",
                    Map.of("location", ORIGIN, "keywords", "咖啡", "radius", "1000")));
            results.put("maps_direction_bicycling", call(connection, "maps_direction_bicycling",
                    Map.of("origin", ORIGIN, "destination", DESTINATION)));
            results.put("maps_direction_driving", call(connection, "maps_direction_driving",
                    Map.of("origin", ORIGIN, "destination", DESTINATION)));
            results.put("maps_direction_transit_integrated", call(connection,
                    "maps_direction_transit_integrated",
                    Map.of("origin", ORIGIN, "destination", DESTINATION,
                            "city", "杭州", "cityd", "杭州")));
            results.put("maps_direction_walking", call(connection, "maps_direction_walking",
                    Map.of("origin", ORIGIN, "destination", DESTINATION)));
            results.put("maps_distance", call(connection, "maps_distance",
                    Map.of("origins", ORIGIN, "destination", DESTINATION, "type", "1")));
            results.put("maps_schema_navi", call(connection, "maps_schema_navi",
                    Map.of("lon", "120.155100", "lat", "30.274100")));
            results.put("maps_weather", call(connection, "maps_weather",
                    Map.of("city", "杭州")));

            assertEquals(Set.of(
                    "maps_direction_bicycling", "maps_direction_driving",
                    "maps_direction_transit_integrated", "maps_direction_walking",
                    "maps_distance", "maps_geo", "maps_regeocode", "maps_ip_location",
                    "maps_around_search", "maps_search_detail", "maps_text_search",
                    "maps_schema_navi", "maps_weather"), results.keySet());
            List<String> failed = results.entrySet().stream()
                    .filter(entry -> !isUsable(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            assertTrue(failed.isEmpty(), "AMap tools failed: " + failed);
        } finally {
            connection.close();
        }
    }

    private static JsonNode call(McpServerConnection connection, String tool,
                                 Map<String, Object> arguments) {
        long started = System.nanoTime();
        JsonNode result = connection.callTool(tool, arguments, 30_000);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("AMAP_CALL tool=%s elapsedMs=%d usable=%s result=%s%n",
                tool, elapsedMs, isUsable(result), compact(result));
        return result;
    }

    private static boolean isUsable(JsonNode result) {
        if (result == null || result.path("isError").asBoolean(false)) return false;
        JsonNode content = result.path("content");
        return content.isArray() && !content.isEmpty()
                && content.findValuesAsText("text").stream().anyMatch(text -> !text.isBlank());
    }

    private static String compact(JsonNode result) {
        String text = result == null ? "null" : result.toString().replaceAll("\\s+", " ");
        return text.substring(0, Math.min(text.length(), 700));
    }

    private static String extractPoiId(JsonNode result) {
        for (JsonNode item : result.path("content")) {
            String text = item.path("text").asText("");
            if (text.isBlank()) continue;
            try {
                JsonNode parsed = MAPPER.readTree(text);
                JsonNode id = findFirstField(parsed, "id");
                if (id != null && id.isValueNode() && !id.asText().isBlank()) return id.asText();
            } catch (Exception ignored) {
                Matcher matcher = POI_ID.matcher(text);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return "";
    }

    private static JsonNode findFirstField(JsonNode node, String field) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null) return direct;
            for (JsonNode child : node) {
                JsonNode found = findFirstField(child, field);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findFirstField(child, field);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static McpServerConnection connect(String apiKey) {
        McpServerConfig config = new McpServerConfig(
                "amap-maps", McpTransportType.SSE, null, List.of(), Map.of(), AMAP_URL,
                Map.of("Authorization", "Bearer " + apiKey), McpConfigScope.USER);
        McpServerConnection connection = new McpServerConnection(config);
        connection.connect();
        assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus());
        return connection;
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_PROVIDER_DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("DashScope API key required");
        return apiKey;
    }
}

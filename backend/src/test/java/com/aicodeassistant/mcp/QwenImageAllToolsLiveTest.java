package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_QWEN_IMAGE_TOOL_CALLS", matches = "true")
class QwenImageAllToolsLiveTest {

    private static final String URL =
            "https://dashscope.aliyuncs.com/api/v1/mcps/QwenImage/mcp";
    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PUBLIC_IMAGE_ONE =
            "https://cdn.translate.alibaba.com/r/wanx-demo-1.png";
    private static final String PUBLIC_IMAGE_TWO =
            "https://img.alicdn.com/imgextra/i3/O1CN011FObkp1T7Ttowoq4F_!!6000000002335-0-tps-1440-1797.jpg";

    @Test
    void generationSingleImageEditAndMultiImageFusionAllReturnImages() {
        McpServerConnection connection = connect(requireApiKey());
        try {
            JsonNode generated = call(connection, "modelstudio_qwen_image_gen", Map.of(
                    "prompt", "一只戴蓝色安全帽的橙色机器人，在白色背景上举着写有 ZhikunCode 的木牌，扁平插画风格",
                    "size", "1328*1328",
                    "n", 1,
                    "watermark", false));
            String generatedUrl = requireResultUrl("image_gen", generated);

            JsonNode edited = call(connection, "modelstudio_qwen_image_edit", Map.of(
                    "image_url", generatedUrl,
                    "prompt", "把安全帽改成绿色，并保持木牌文字 ZhikunCode 清晰可读",
                    "watermark", false));
            String editedUrl = requireResultUrl("image_edit", edited);

            JsonNode fused = call(connection, "modelstudio_qwen_image_edit_new", Map.of(
                    "image_urls", List.of(generatedUrl, editedUrl),
                    "prompt", "将两张图融合成左右并排的产品对比海报，保留两种安全帽颜色和 ZhikunCode 木牌",
                    "watermark", false));
            requireResultUrl("image_fusion", fused);
        } finally {
            connection.close();
        }
    }

    @Test
    void multiImageFusionWithStablePublicImagesReturnsAnImage() {
        McpServerConnection connection = connect(requireApiKey());
        try {
            JsonNode fused = call(connection, "modelstudio_qwen_image_edit_new", Map.of(
                    "image_urls", List.of(PUBLIC_IMAGE_ONE, PUBLIC_IMAGE_TWO),
                    "prompt", "把第一张图的主体放到第二张图的左侧，保持画面自然",
                    "watermark", false));
            requireResultUrl("image_fusion", fused);
        } finally {
            connection.close();
        }
    }

    private static JsonNode call(McpServerConnection connection, String tool,
                                 Map<String, Object> arguments) {
        long started = System.nanoTime();
        JsonNode result = connection.callTool(tool, arguments, 600_000);
        assertNotNull(result, tool);
        assertFalse(result.path("isError").asBoolean(false),
                tool + " returned an MCP error: " + compact(result));
        assertTrue(result.path("content").isArray() && !result.path("content").isEmpty(),
                tool + " returned no content: " + compact(result));
        System.out.printf("QWEN_IMAGE_CALL tool=%s elapsedMs=%d contentItems=%d%n",
                tool, (System.nanoTime() - started) / 1_000_000, result.path("content").size());
        return result;
    }

    private static String requireResultUrl(String step, JsonNode result) {
        for (JsonNode item : result.path("content")) {
            String text = item.path("text").asText("");
            if (text.isBlank()) continue;
            try {
                String url = findFirstHttpUrl(MAPPER.readTree(text));
                if (url != null) {
                    URI uri = URI.create(url);
                    System.out.printf("QWEN_IMAGE_RESULT step=%s host=%s%n", step, uri.getHost());
                    return url;
                }
            } catch (Exception ignored) {
                // Fall through to tolerant text extraction.
            }
        }
        Matcher matcher = HTTP_URL.matcher(result.toString().replace("\\/", "/"));
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[\\\\),.;]+$", "");
            try {
                URI uri = URI.create(candidate);
                if (uri.getHost() != null) {
                    System.out.printf("QWEN_IMAGE_RESULT step=%s host=%s%n", step, uri.getHost());
                    return candidate;
                }
            } catch (Exception ignored) {
                // Try the next URL in the structured result.
            }
        }
        throw new AssertionError(step + " returned no public URL: " + compact(result));
    }

    private static String findFirstHttpUrl(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) {
            String value = node.asText();
            return value.startsWith("http://") || value.startsWith("https://") ? value : null;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                String found = findFirstHttpUrl(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String compact(JsonNode result) {
        String text = result == null ? "null" : result.toString().replaceAll("\\s+", " ");
        text = HTTP_URL.matcher(text).replaceAll("<redacted-url>");
        return text.substring(0, Math.min(text.length(), 900));
    }

    private static McpServerConnection connect(String apiKey) {
        McpServerConfig config = new McpServerConfig(
                "QwenImage", McpTransportType.HTTP, null, List.of(), Map.of(), URL,
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

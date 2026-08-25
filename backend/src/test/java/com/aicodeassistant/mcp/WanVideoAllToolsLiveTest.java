package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_WAN_VIDEO_TOOL_CALLS", matches = "true")
class WanVideoAllToolsLiveTest {

    private static final String URL =
            "https://dashscope.aliyuncs.com/api/v1/mcps/WanVideo/mcp";
    private static final String FIRST_FRAME =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20260126/ixdxvt/wan-kf2v-blue-1.png";
    private static final String LAST_FRAME =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20260126/nhtdrc/wan-kf2v-blue-2.png";
    private static final String I2V_IMAGE = "https://cdn.translate.alibaba.com/r/wanx-demo-1.png";
    private static final String HUMAN_IMAGE =
            "https://img.alicdn.com/imgextra/i3/O1CN011FObkp1T7Ttowoq4F_!!6000000002335-0-tps-1440-1797.jpg";
    private static final String HUMAN_AUDIO =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250825/iaqpio/input_audio.MP3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allEightWanVideoToolsAcceptRealTasksAndReturnStatuses() {
        McpServerConnection connection = connect(requireApiKey());
        Set<String> called = new LinkedHashSet<>();
        try {
            String textTask = requireTaskId(call(connection, called,
                    "modelstudio_text_to_video_submit_task",
                    Map.of("prompt", "清晨的西湖边，一台小型橙色机器人沿湖慢慢行走，镜头平稳跟随",
                            "duration", 5, "watermark", false)));
            call(connection, called, "modelstudio_text_to_video_fetch_result",
                    Map.of("task_id", textTask));

            String imageTask = requireTaskId(call(connection, called,
                    "modelstudio_image_to_video_submit_task",
                    Map.of("image_url", I2V_IMAGE,
                            "prompt", "猫在草地上向前小跑，镜头缓慢跟随",
                            "resolution", "480P", "duration", 5, "watermark", false)));
            call(connection, called, "modelstudio_image_to_video_fetch_result",
                    Map.of("task_id", imageTask));

            String firstLastTask = requireTaskId(call(connection, called,
                    "modelstudio_image_to_video_fl_wan22_submit_task",
                    Map.of("first_frame_url", FIRST_FRAME, "last_frame_url", LAST_FRAME,
                            "prompt", "一只黑猫好奇地抬头看向天空，镜头从平视缓慢上升",
                            "resolution", "480P", "watermark", false)));
            call(connection, called, "modelstudio_wan_video_fetch_result",
                    Map.of("task_id", firstLastTask));

            String speechTask = requireTaskId(call(connection, called,
                    "modelstudio_speech_to_video_submit_task",
                    Map.of("image_url", HUMAN_IMAGE, "audio_url", HUMAN_AUDIO,
                            "resolution", "480P")));
            call(connection, called, "modelstudio_speech_to_video_fetch_result",
                    Map.of("task_id", speechTask));

            assertEquals(Set.of(
                    "modelstudio_text_to_video_submit_task",
                    "modelstudio_text_to_video_fetch_result",
                    "modelstudio_image_to_video_submit_task",
                    "modelstudio_image_to_video_fetch_result",
                    "modelstudio_speech_to_video_submit_task",
                    "modelstudio_speech_to_video_fetch_result",
                    "modelstudio_image_to_video_fl_wan22_submit_task",
                    "modelstudio_wan_video_fetch_result"), called);
        } finally {
            connection.close();
        }
    }

    @Test
    void remainingFirstLastFrameAndDigitalHumanToolsReturnStatuses() {
        McpServerConnection connection = connect(requireApiKey());
        Set<String> called = new LinkedHashSet<>();
        try {
            String firstFrame = jpegDataUrl(0x2F80ED, 0xFFFFFF);
            String lastFrame = jpegDataUrl(0x13233A, 0xFDBA2D);
            String firstLastTask = requireTaskId(call(connection, called,
                    "modelstudio_image_to_video_fl_wan22_submit_task",
                    Map.of("first_frame_url", firstFrame, "last_frame_url", lastFrame,
                            "prompt", "一只蓝色小怪兽站在雨中，镜头缓慢推进，它抬头看向天空",
                            "resolution", "720P", "watermark", false)));
            call(connection, called, "modelstudio_wan_video_fetch_result",
                    Map.of("task_id", firstLastTask));

            String speechTask = requireTaskId(call(connection, called,
                    "modelstudio_speech_to_video_submit_task",
                    Map.of("image_url", HUMAN_IMAGE, "audio_url", HUMAN_AUDIO,
                            "resolution", "480P")));
            call(connection, called, "modelstudio_speech_to_video_fetch_result",
                    Map.of("task_id", speechTask));

            assertEquals(Set.of(
                    "modelstudio_image_to_video_fl_wan22_submit_task",
                    "modelstudio_wan_video_fetch_result",
                    "modelstudio_speech_to_video_submit_task",
                    "modelstudio_speech_to_video_fetch_result"), called);
        } finally {
            connection.close();
        }
    }

    @Test
    void digitalHumanToolsAcceptARealTaskAndReturnStatus() {
        McpServerConnection connection = connect(requireApiKey());
        Set<String> called = new LinkedHashSet<>();
        try {
            String speechTask = requireTaskId(call(connection, called,
                    "modelstudio_speech_to_video_submit_task",
                    Map.of("image_url", HUMAN_IMAGE, "audio_url", HUMAN_AUDIO,
                            "resolution", "480P")));
            call(connection, called, "modelstudio_speech_to_video_fetch_result",
                    Map.of("task_id", speechTask));
            assertEquals(Set.of(
                    "modelstudio_speech_to_video_submit_task",
                    "modelstudio_speech_to_video_fetch_result"), called);
        } finally {
            connection.close();
        }
    }

    @Test
    void fetchToolsCanQueryTasksSubmittedByTheNativeApi() {
        McpServerConnection connection = connect(requireApiKey());
        Set<String> called = new LinkedHashSet<>();
        try {
            call(connection, called, "modelstudio_speech_to_video_fetch_result",
                    Map.of("task_id", requireEnvironment("WAN_SPEECH_TASK_ID")));
            call(connection, called, "modelstudio_wan_video_fetch_result",
                    Map.of("task_id", requireEnvironment("WAN_FIRST_LAST_TASK_ID")));
            assertEquals(Set.of(
                    "modelstudio_speech_to_video_fetch_result",
                    "modelstudio_wan_video_fetch_result"), called);
        } finally {
            connection.close();
        }
    }

    private static JsonNode call(McpServerConnection connection, Set<String> called,
                                 String tool, Map<String, Object> arguments) {
        long started = System.nanoTime();
        JsonNode result = connection.callTool(tool, arguments, 600_000);
        called.add(tool);
        assertNotNull(result, tool);
        assertFalse(result.path("isError").asBoolean(false),
                tool + " returned an MCP error: " + compact(result));
        assertTrue(result.path("content").isArray() && !result.path("content").isEmpty(),
                tool + " returned no content: " + compact(result));
        System.out.printf("WAN_VIDEO_CALL tool=%s elapsedMs=%d contentItems=%d%n",
                tool, (System.nanoTime() - started) / 1_000_000, result.path("content").size());
        return result;
    }

    private static String requireTaskId(JsonNode result) {
        for (JsonNode item : result.path("content")) {
            String text = item.path("text").asText("");
            if (text.isBlank()) continue;
            try {
                JsonNode parsed = MAPPER.readTree(text);
                JsonNode taskId = findField(parsed, "task_id");
                if (taskId != null && !taskId.asText().isBlank()) {
                    System.out.printf("WAN_VIDEO_TASK accepted=%s%n",
                            taskId.asText().substring(0, Math.min(8, taskId.asText().length())));
                    return taskId.asText();
                }
            } catch (Exception ignored) {
                // Continue searching other content blocks.
            }
        }
        throw new AssertionError("submit result contained no task_id: " + compact(result));
    }

    private static JsonNode findField(JsonNode node, String name) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode direct = node.get(name);
            if (direct != null) return direct;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String compact(JsonNode result) {
        String text = result == null ? "null" : result.toString().replaceAll("\\s+", " ");
        text = text.replaceAll("https?://[^\\s\\\"'<>]+", "<redacted-url>");
        return text.substring(0, Math.min(text.length(), 900));
    }

    private static String jpegDataUrl(int backgroundRgb, int subjectRgb) {
        try {
            BufferedImage image = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    boolean subject = x >= 95 && x <= 225 && y >= 70 && y <= 260;
                    image.setRGB(x, y, subject ? subjectRgb : backgroundRgb);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpeg", output)) {
                throw new IllegalStateException("No JPEG writer available");
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create live-test JPEG", e);
        }
    }

    private static McpServerConnection connect(String apiKey) {
        McpServerConfig config = new McpServerConfig(
                "WanVideo", McpTransportType.HTTP, null, List.of(), Map.of(), URL,
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this live test");
        }
        return value;
    }
}

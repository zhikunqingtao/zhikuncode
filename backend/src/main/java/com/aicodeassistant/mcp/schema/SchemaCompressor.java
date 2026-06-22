package com.aicodeassistant.mcp.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * MCP 工具 inputSchema 智能压缩器。
 * <p>
 * 当 schema JSON 序列化后超过阈值（默认 2048 字节）时，按优先级逐步剥离低价值
 * 信息以缩小体积：
 * <ol>
 *   <li>移除所有 {@code examples} / {@code example} 字段</li>
 *   <li>截断超过 200 字符的 {@code description} 字段（保留前 200 字符 + "..."）</li>
 *   <li>{@code enum} 数组超过 10 项时仅保留前 5 项 + 添加 "..." 占位</li>
 * </ol>
 * 压缩在 {@code properties} / {@code items} / {@code oneOf} / {@code anyOf} /
 * {@code allOf} 等嵌套子 schema 中递归执行。
 * <p>
 * 压缩保留 {@code type}、{@code required}、{@code properties}（字段名）、短
 * {@code description} 等核心结构信息，保证产物仍是合法 JSON Schema。
 */
@Component
public class SchemaCompressor {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompressor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int DESCRIPTION_MAX = 200;
    static final int ENUM_KEEP = 5;
    static final int ENUM_THRESHOLD = 10;
    static final String TRUNCATE_MARK = "...";

    private final boolean enabled;
    private final int thresholdBytes;

    public SchemaCompressor(
            @Value("${app.mcp.schema-compression.enabled:true}") boolean enabled,
            @Value("${app.mcp.schema-compression.threshold-bytes:2048}") int thresholdBytes) {
        this.enabled = enabled;
        this.thresholdBytes = thresholdBytes;
    }

    /** 测试便捷构造 */
    public SchemaCompressor() {
        this(true, 2048);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getThresholdBytes() {
        return thresholdBytes;
    }

    /**
     * 压缩 schema。如果未启用、为空或未超阈值，原样返回。
     */
    public JsonNode compress(JsonNode originalSchema) {
        if (!enabled || originalSchema == null || originalSchema.isNull()) {
            return originalSchema;
        }
        int originalSize = sizeOf(originalSchema);
        if (originalSize <= thresholdBytes) {
            return originalSchema;
        }

        // Deep copy 避免修改入参
        JsonNode working = originalSchema.deepCopy();

        // 步骤 1: 移除 examples / example
        stripExamples(working);
        if (sizeOf(working) <= thresholdBytes) {
            return working;
        }

        // 步骤 2: 截断长 description
        truncateDescriptions(working);
        if (sizeOf(working) <= thresholdBytes) {
            return working;
        }

        // 步骤 3: 裁剪大 enum
        truncateEnums(working);

        int finalSize = sizeOf(working);
        if (log.isDebugEnabled()) {
            log.debug("SchemaCompressor: {} bytes -> {} bytes (threshold={})",
                    originalSize, finalSize, thresholdBytes);
        }
        return working;
    }

    /**
     * 压缩 Map 表示的 schema — McpToolAdapter 中的便捷入口。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compress(Map<String, Object> originalSchema) {
        if (!enabled || originalSchema == null || originalSchema.isEmpty()) {
            return originalSchema;
        }
        JsonNode node = MAPPER.valueToTree(originalSchema);
        JsonNode compressed = compress(node);
        if (compressed == node) {
            return originalSchema;
        }
        return MAPPER.convertValue(compressed, Map.class);
    }

    // ===== 内部工具 =====

    private int sizeOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 递归移除所有 examples / example 字段 */
    private void stripExamples(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove("examples");
            obj.remove("example");
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                stripExamples(it.next().getValue());
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode item : arr) {
                stripExamples(item);
            }
        }
    }

    /** 递归截断长 description */
    private void truncateDescriptions(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            JsonNode desc = obj.get("description");
            if (desc != null && desc.isTextual()) {
                String text = desc.asText();
                if (text.length() > DESCRIPTION_MAX) {
                    obj.set("description",
                            new TextNode(text.substring(0, DESCRIPTION_MAX) + TRUNCATE_MARK));
                }
            }
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!"description".equals(entry.getKey())) {
                    truncateDescriptions(entry.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode item : arr) {
                truncateDescriptions(item);
            }
        }
    }

    /** 递归裁剪大 enum 数组 */
    private void truncateEnums(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            JsonNode enumNode = obj.get("enum");
            if (enumNode instanceof ArrayNode enumArr && enumArr.size() > ENUM_THRESHOLD) {
                ArrayNode trimmed = MAPPER.createArrayNode();
                for (int i = 0; i < ENUM_KEEP; i++) {
                    trimmed.add(enumArr.get(i));
                }
                trimmed.add(TRUNCATE_MARK);
                obj.set("enum", trimmed);
            }
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!"enum".equals(entry.getKey())) {
                    truncateEnums(entry.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode item : arr) {
                truncateEnums(item);
            }
        }
    }
}

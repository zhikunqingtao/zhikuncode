package com.aicodeassistant.mcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** SchemaCompressor 单元测试 */
class SchemaCompressorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int THRESHOLD = 2048;

    private final SchemaCompressor compressor = new SchemaCompressor(true, THRESHOLD);

    @Test
    void testSmallSchemaNotCompressed() throws Exception {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string").put("description", "用户姓名");
        // examples 字段保留作为对比
        ArrayNode examples = props.with("name").putArray("examples");
        examples.add("alice");

        JsonNode result = compressor.compress(schema);
        // 未触发阈值，原样返回（应保留 examples）
        assertSame(schema, result);
        assertTrue(result.path("properties").path("name").has("examples"));
    }

    @Test
    void testLargeSchemaCompressed() {
        ObjectNode schema = buildLargeSchema();
        int originalSize = sizeOf(schema);
        assertTrue(originalSize > THRESHOLD,
                "构造的 schema 应超过阈值，实际 " + originalSize);

        JsonNode result = compressor.compress(schema);
        int compressedSize = sizeOf(result);
        assertTrue(compressedSize <= THRESHOLD,
                "压缩后应不超过阈值，实际 " + compressedSize);
    }

    @Test
    void testExamplesRemoved() {
        ObjectNode schema = buildLargeSchema();
        JsonNode result = compressor.compress(schema);

        // 不能在任意层级再找到 examples / example
        assertFalse(hasField(result, "examples"));
        assertFalse(hasField(result, "example"));
    }

    @Test
    void testLongDescriptionTruncated() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        // 构造一个超过阈值且 description 超过 200 字符的 schema
        ObjectNode props = schema.putObject("properties");
        String longDesc = "A".repeat(500);
        // 添加足够多字段超过阈值
        for (int i = 0; i < 20; i++) {
            ObjectNode field = props.putObject("field" + i);
            field.put("type", "string");
            field.put("description", longDesc);
        }

        JsonNode result = compressor.compress(schema);
        JsonNode field0Desc = result.path("properties").path("field0").path("description");
        assertTrue(field0Desc.isTextual());
        String descText = field0Desc.asText();
        assertTrue(descText.length() <= SchemaCompressor.DESCRIPTION_MAX
                        + SchemaCompressor.TRUNCATE_MARK.length(),
                "description 应被截断，实际长度 " + descText.length());
        assertTrue(descText.endsWith(SchemaCompressor.TRUNCATE_MARK));
    }

    @Test
    void testLargeEnumTruncated() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // 制造超阈值的 schema：多个字段含大 enum
        for (int k = 0; k < 8; k++) {
            ObjectNode field = props.putObject("field" + k);
            field.put("type", "string");
            field.put("description", "X".repeat(150));
            ArrayNode enumArr = field.putArray("enum");
            for (int i = 0; i < 30; i++) {
                enumArr.add("value_" + i + "_" + "y".repeat(20));
            }
        }

        JsonNode result = compressor.compress(schema);
        JsonNode enumNode = result.path("properties").path("field0").path("enum");
        assertTrue(enumNode.isArray());
        // 5 项 + 1 个 "..." 占位
        assertEquals(SchemaCompressor.ENUM_KEEP + 1, enumNode.size());
        assertEquals(SchemaCompressor.TRUNCATE_MARK,
                enumNode.get(enumNode.size() - 1).asText());
    }

    @Test
    void testNestedSchemaCompressed() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // 嵌套结构：properties.items.properties + oneOf + anyOf
        ObjectNode list = props.putObject("list");
        list.put("type", "array");
        ObjectNode items = list.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");

        for (int i = 0; i < 10; i++) {
            ObjectNode inner = itemProps.putObject("inner" + i);
            inner.put("type", "string");
            inner.putArray("examples").add("example-text-" + "z".repeat(50));
            inner.put("description", "深层描述-" + "D".repeat(300));
        }

        // oneOf / anyOf 中也放 examples
        ArrayNode oneOf = props.with("union").putArray("oneOf");
        ObjectNode opt = MAPPER.createObjectNode();
        opt.put("type", "string");
        opt.putArray("examples").add("opt-example");
        oneOf.add(opt);

        JsonNode result = compressor.compress(schema);

        // 嵌套 examples 已移除
        assertFalse(hasField(result, "examples"));

        // 嵌套 description 截断
        JsonNode innerDesc = result.path("properties").path("list").path("items")
                .path("properties").path("inner0").path("description");
        assertTrue(innerDesc.isTextual());
        assertTrue(innerDesc.asText().length()
                <= SchemaCompressor.DESCRIPTION_MAX + SchemaCompressor.TRUNCATE_MARK.length());

        // 结构信息保留
        assertEquals("object", result.path("type").asText());
        assertEquals("array", result.path("properties").path("list").path("type").asText());
    }

    @Test
    void testDisabledReturnsOriginal() {
        SchemaCompressor disabled = new SchemaCompressor(false, THRESHOLD);
        ObjectNode schema = buildLargeSchema();
        JsonNode result = disabled.compress(schema);
        assertSame(schema, result);
    }

    // ===== helpers =====

    private ObjectNode buildLargeSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        // 4 个字段，每个包含长 description / examples / 大 enum
        // 原始体积超过 2048，压缩后（移除 examples + 截断 desc + 裁 enum）可降至阈值内。
        for (int i = 0; i < 4; i++) {
            ObjectNode field = props.putObject("field" + i);
            field.put("type", "string");
            field.put("description", "字段说明-" + "Z".repeat(300));
            ArrayNode examples = field.putArray("examples");
            for (int j = 0; j < 6; j++) {
                examples.add("example-" + j + "-" + "x".repeat(40));
            }
            ArrayNode enumArr = field.putArray("enum");
            for (int j = 0; j < 20; j++) {
                enumArr.add("v-" + j);
            }
        }
        return schema;
    }

    private int sizeOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private boolean hasField(JsonNode node, String field) {
        if (node == null) return false;
        if (node.isObject()) {
            if (node.has(field)) return true;
            var it = node.fields();
            while (it.hasNext()) {
                if (hasField(it.next().getValue(), field)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasField(item, field)) return true;
            }
        }
        return false;
    }
}

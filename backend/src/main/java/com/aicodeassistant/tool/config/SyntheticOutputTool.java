package com.aicodeassistant.tool.config;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * SyntheticOutputTool — 结构化输出工具。
 * <p>
 * 模型通过此工具返回符合 Schema 的 JSON 数据。
 * Schema 由 QueryConfig.structuredOutputSchema 动态注入。
 * <p>
 * 使用场景:
 * <ul>
 *   <li>/commit 命令生成结构化 commit message</li>
 *   <li>自动分类器的判断结果</li>
 *   <li>MCP prompt 的结构化参数</li>
 * </ul>
 *
 * @see <a href="SPEC §4.1.9">SyntheticOutputTool</a>
 */
@Component
public class SyntheticOutputTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SyntheticOutputTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 动态 Schema — 由 QueryEngine 在发起结构化输出查询时注入 */
    private volatile Map<String, Object> currentSchema;

    @Override
    public String getName() {
        return "SyntheticOutput";
    }

    @Override
    public String getDescription() {
        return "Provide structured output conforming to a specified JSON schema. " +
                "Used for commit messages, classifier results, and other structured data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        // 动态 Schema: 由 currentSchema 决定
        if (currentSchema != null) {
            return currentSchema;
        }
        // 默认 — 接受任意 JSON
        return Map.of(
                "type", "object",
                "additionalProperties", true
        );
    }

    @Override
    public String getGroup() {
        return "config";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        if (currentSchema == null) {
            log.warn("SyntheticOutput called without schema — accepting raw data");
        }

        // 1. 获取模型返回的 JSON 数据
        Map<String, Object> rawData = input.getRawData();

        if (rawData.isEmpty()) {
            return ToolResult.error("Empty structured output.");
        }

        try {
            // 2. P1 占位: JSON Schema 验证将在引入 json-schema-validator 后完善
            // Set<ValidationMessage> errors = currentSchema.validate(jsonNode);

            // 3. 验证通过 — 结果存储在 metadata.structured_output
            String jsonStr = MAPPER.writeValueAsString(rawData);
            log.info("SyntheticOutput received: {} chars", jsonStr.length());

            return ToolResult.success("Structured output provided successfully.")
                    .withMetadata("structured_output", rawData);

        } catch (Exception e) {
            return ToolResult.error(
                    "Failed to process structured output: " + e.getMessage());
        }
    }

    /** 由 QueryEngine 在发起结构化输出查询时调用 */
    public void setSchema(Map<String, Object> schema) {
        this.currentSchema = schema;
    }

    /** 清除 Schema */
    public void clearSchema() {
        this.currentSchema = null;
    }

    /** 获取当前 Schema（测试用） */
    Map<String, Object> getCurrentSchema() {
        return currentSchema;
    }
}

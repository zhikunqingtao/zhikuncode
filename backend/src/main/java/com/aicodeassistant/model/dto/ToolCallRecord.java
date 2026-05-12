package com.aicodeassistant.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 工具调用记录 — Phase 2 异常检测基础数据
 */
public record ToolCallRecord(
    String toolName,
    String paramsHash,
    String status,          // "success" | "error" | "timeout"
    long timestamp,
    String errorDetail,
    Long durationMs
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 计算参数哈希（MD5），用于重复调用检测。
     * 序列化规则：TreeMap 按 key 字母序 -> JSON 紧凑序列化 -> MD5 hex(32位小写)
     */
    public static String computeParamsHash(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "empty";
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(params);
            // 过滤 null 值
            sorted.values().removeIf(Objects::isNull);
            String json = objectMapper.writeValueAsString(sorted);
            return DigestUtils.md5DigestAsHex(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "error_" + System.currentTimeMillis();
        }
    }
}

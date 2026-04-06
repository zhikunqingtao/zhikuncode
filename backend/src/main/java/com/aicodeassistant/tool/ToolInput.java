package com.aicodeassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具输入参数包装器 — 对 JSON 输入的类型安全访问。
 * <p>
 * 所有工具的 call()/checkPermissions() 方法均接收此类型。
 * 内部持有 {@code Map<String, Object>}（从 LLM 的 tool_use.input JSON 解析而来），
 * 提供类型安全的取值方法。
 *
 * @see <a href="SPEC §3.2.1a-1">ToolInput 接口定义</a>
 */
public class ToolInput {

    private final Map<String, Object> data;

    public ToolInput(Map<String, Object> data) {
        this.data = data != null ? data : Map.of();
    }

    public static ToolInput from(Map<String, Object> data) {
        return new ToolInput(data);
    }

    /** 从 Jackson JsonNode 构建 ToolInput */
    @SuppressWarnings("unchecked")
    public static ToolInput fromJsonNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new ToolInput(Map.of());
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.convertValue(node, Map.class);
            return new ToolInput(map != null ? map : Map.of());
        } catch (Exception e) {
            return new ToolInput(Map.of());
        }
    }

    // ===== 必需字段（缺失时抛 ToolInputValidationException） =====

    public String getString(String key) {
        Object value = data.get(key);
        if (value == null) {
            throw new ToolInputValidationException("Required field '" + key + "' is missing");
        }
        return value.toString();
    }

    public int getInt(String key) {
        Object value = data.get(key);
        if (value == null) {
            throw new ToolInputValidationException("Required field '" + key + "' is missing");
        }
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new ToolInputValidationException("Field '" + key + "' is not a valid integer");
        }
    }

    public boolean getBoolean(String key) {
        Object value = data.get(key);
        if (value == null) {
            throw new ToolInputValidationException("Required field '" + key + "' is missing");
        }
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object value = data.get(key);
        if (value == null) {
            throw new ToolInputValidationException("Required field '" + key + "' is missing");
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        throw new ToolInputValidationException("Field '" + key + "' is not a list");
    }

    // ===== 带默认值的可选字段 =====

    public String getString(String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    // ===== 返回 Optional 的可选字段 =====

    public Optional<String> getOptionalString(String key) {
        Object value = data.get(key);
        return value != null ? Optional.of(value.toString()) : Optional.empty();
    }

    public Optional<Integer> getOptionalInt(String key) {
        Object value = data.get(key);
        if (value == null) return Optional.empty();
        if (value instanceof Number n) return Optional.of(n.intValue());
        try {
            return Optional.of(Integer.parseInt(value.toString()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<Boolean> getOptionalBoolean(String key) {
        Object value = data.get(key);
        if (value == null) return Optional.empty();
        if (value instanceof Boolean b) return Optional.of(b);
        return Optional.of(Boolean.parseBoolean(value.toString()));
    }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> getOptionalStringList(String key) {
        Object value = data.get(key);
        if (value == null) return Optional.empty();
        if (value instanceof List<?> list) {
            return Optional.of(list.stream().map(Object::toString).toList());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<List<T>> getOptionalList(String key, Class<T> elementType) {
        Object value = data.get(key);
        if (value == null) return Optional.empty();
        if (value instanceof List<?> list) {
            return Optional.of(list.stream().map(elementType::cast).toList());
        }
        return Optional.empty();
    }

    /** 获取原始 Map (供 JSON 序列化) */
    public Map<String, Object> getRawData() {
        return data;
    }

    /** 是否包含指定键 */
    public boolean has(String key) {
        return data.containsKey(key);
    }
}

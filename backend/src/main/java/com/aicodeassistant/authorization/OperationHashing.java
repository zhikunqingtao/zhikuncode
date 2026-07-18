package com.aicodeassistant.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class OperationHashing {
    private OperationHashing() { }
    static String hash(ObjectMapper mapper, Map<String, Object> facts) {
        try {
            JsonNode node = sort(mapper, mapper.valueToTree(facts));
            byte[] bytes = mapper.writeValueAsBytes(node);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("authz-operation-v1\0".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception failure) { throw new IllegalStateException("Cannot hash operation facts", failure); }
    }
    private static JsonNode sort(ObjectMapper mapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), sort(mapper, e.getValue())));
            sorted.forEach(result::set); return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode(); node.forEach(e -> result.add(sort(mapper, e))); return result;
        }
        return node.deepCopy();
    }
}

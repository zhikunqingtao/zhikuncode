package com.aicodeassistant.engine.tokenizer;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 精确 Tokenizer 服务 — 通过 Python tiktoken 实现精确 Token 计数。
 * <p>
 * 调用 Python 服务的 /api/tokenizer/count 端点，使用 tiktoken cl100k_base 编码器。
 * 失败时返回 -1，由调用方自行 fallback。
 */
@Service
public class TokenizerService {

    private static final Logger log = LoggerFactory.getLogger(TokenizerService.class);
    private static final int TIMEOUT_SECONDS = 5;

    private final PythonCapabilityAwareClient pythonClient;
    private final ObjectMapper objectMapper;

    public TokenizerService(PythonCapabilityAwareClient pythonClient, ObjectMapper objectMapper) {
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 精确计算 token 数量（通过 Python tiktoken 服务）。
     *
     * @param text  待计数文本
     * @param model 模型标识（影响编码器选择，默认使用 cl100k_base）
     * @return token 数量，失败返回 -1
     */
    public int countExact(String text, String model) {
        if (text == null || text.isEmpty()) return 0;

        try {
            Map<String, String> requestBody = Map.of(
                    "text", text,
                    "model", model != null ? model : "default"
            );

            Optional<JsonNode> response = pythonClient.post(
                    "/api/tokenizer/count", requestBody, JsonNode.class);

            if (response.isPresent()) {
                JsonNode node = response.get();
                if (node.has("token_count")) {
                    int count = node.get("token_count").asInt(-1);
                    log.debug("[TOKENIZER] Exact count: {} tokens for {}chars text",
                            count, text.length());
                    return count;
                }
            }

            log.debug("[TOKENIZER] Empty response from Python service");
            return -1;
        } catch (Exception e) {
            log.debug("[TOKENIZER] Exact count failed, will fallback: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 健康检查 — 验证 Python tokenizer 服务是否可用。
     */
    public boolean isAvailable() {
        try {
            return countExact("test", "default") >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}

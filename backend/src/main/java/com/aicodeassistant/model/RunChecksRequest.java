package com.aicodeassistant.model;

import java.util.List;

/**
 * 确定性验证请求。
 */
public record RunChecksRequest(
    String sessionId,
    String operationId,
    List<String> checks,       // "typescript", "eslint", "test_match", "build"
    List<String> filePaths,
    Integer timeout            // 单项检查超时 ms，默认 30000
) {
    public int effectiveTimeout() {
        return timeout != null ? timeout : 30000;
    }
}

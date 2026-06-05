package com.aicodeassistant.verify;

/**
 * 运行时验证器接口 — 支持多种验证模式
 */
public interface Verifier {
    /**
     * 执行验证流程
     * @param req 验证请求（journey 步骤 + base URL）
     * @param principal 用户标识
     * @return 验证结果
     */
    JourneyResult verify(JourneyRequest req, String principal);
}

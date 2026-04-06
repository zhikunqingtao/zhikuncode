package com.aicodeassistant.model;

/**
 * 代理身份信息（占位 — 完整实现见 Agent 模块）。
 */
public record AgentIdentity(
        AgentId id,
        String name,
        String type
) {}

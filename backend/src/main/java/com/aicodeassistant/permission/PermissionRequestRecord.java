package com.aicodeassistant.permission;

import java.time.Instant;

/**
 * 持久化权限请求记录 — 对应 permission_requests 表。
 */
public record PermissionRequestRecord(
    String id,
    String runId,
    String sessionId,
    String toolUseId,
    String toolName,
    String riskLevel,
    String reason,
    String inputSummary,
    String status,
    String decision,
    String decidedBy,
    boolean remember,
    String rememberScope,
    Instant requestedAt,
    Instant decidedAt,
    Instant timeoutAt,
    String source,
    String childSessionId,
    Instant createdAt
) {}

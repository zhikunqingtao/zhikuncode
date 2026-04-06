package com.aicodeassistant.model;

import java.time.Instant;

/**
 * OAuth Token。
 *
 * @see <a href="SPEC §5.3">配置模型</a>
 */
public record OAuthToken(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String accountId
) {}

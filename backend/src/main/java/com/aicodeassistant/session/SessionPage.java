package com.aicodeassistant.session;

import com.aicodeassistant.model.SessionSummary;

import java.util.List;

/**
 * 会话分页结果 — 游标分页。
 *
 * @see <a href="SPEC §3.6">会话持久化</a>
 */
public record SessionPage(
        List<SessionSummary> sessions,
        boolean hasMore,
        String oldestId
) {}

package com.aicodeassistant.verify;

import java.time.Instant;
import java.util.List;

/**
 * 证据包 — 一次验证操作产生的完整证据集合。
 *
 * @param bundleId  唯一标识
 * @param sessionId 所属会话
 * @param agentId   产生证据的 Agent
 * @param kind      类型: journey | qa | visual | repro
 * @param claim     验证声明
 * @param verdict   判定: verified | failed | inconclusive
 * @param items     关联的证据条目
 * @param createdAt 创建时间
 */
public record EvidenceBundle(
    String bundleId,
    String sessionId,
    String agentId,
    String kind,
    String claim,
    String verdict,
    List<EvidenceItem> items,
    Instant createdAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String bundleId;
        private String sessionId;
        private String agentId;
        private String kind;
        private String claim;
        private String verdict;
        private List<EvidenceItem> items = List.of();
        private Instant createdAt;

        public Builder bundleId(String bundleId) { this.bundleId = bundleId; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder kind(String kind) { this.kind = kind; return this; }
        public Builder claim(String claim) { this.claim = claim; return this; }
        public Builder verdict(String verdict) { this.verdict = verdict; return this; }
        public Builder items(List<EvidenceItem> items) { this.items = items; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public EvidenceBundle build() {
            if (bundleId == null) bundleId = "ev-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            if (createdAt == null) createdAt = Instant.now();
            return new EvidenceBundle(bundleId, sessionId, agentId, kind, claim, verdict, items, createdAt);
        }
    }
}

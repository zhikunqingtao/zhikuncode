package com.aicodeassistant.authorization;

import com.aicodeassistant.model.PermissionScope;

import java.util.List;

/** 结构化授权结果；message 文本仅用于展示，不参与裁决。 */
public record AuthorizationDecision(
        Outcome outcome,
        String source,
        String stage,
        String reasonCode,
        String message,
        String matchedGrantId,
        List<PermissionScope> scopeOptions) {
    public enum Outcome { ALLOW, ASK, DENY }
    public static AuthorizationDecision allow(String source, String grantId) {
        return new AuthorizationDecision(Outcome.ALLOW, source, "INITIAL", "AUTHORIZED", null,
                grantId, List.of());
    }
    public static AuthorizationDecision ask(String reasonCode, String message,
                                             List<PermissionScope> scopes) {
        return new AuthorizationDecision(Outcome.ASK, "POLICY", "INITIAL", reasonCode, message,
                null, List.copyOf(scopes));
    }
    public static AuthorizationDecision deny(String stage, String reasonCode, String message) {
        return new AuthorizationDecision(Outcome.DENY, "POLICY", stage, reasonCode, message,
                null, List.of());
    }
}

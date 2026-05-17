package com.aicodeassistant.state;

import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.PermissionRule;

import java.util.List;
import java.util.Map;

/**
 * 权限系统状态 — 权限模式、规则、拒绝追踪。
 *
 */
public record PermissionState(
        PermissionMode permissionMode,
        Map<String, List<PermissionRule>> alwaysAllowRules,
        Map<String, List<PermissionRule>> alwaysDenyRules,
        DenialTrackingState denialTracking,
        boolean isSkipAllPrompts
) {
    /**
     * 拒绝追踪状态 — 电路断路器模式。
     */
    public record DenialTrackingState(
            int classifierDenials,
            int consecutiveDenials
    ) {
        public static DenialTrackingState empty() {
            return new DenialTrackingState(0, 0);
        }
    }

    public static PermissionState empty() {
        return new PermissionState(
                PermissionMode.DEFAULT, Map.of(), Map.of(),
                DenialTrackingState.empty(), false
        );
    }

    public PermissionState withPermissionMode(PermissionMode mode) {
        return new PermissionState(mode, alwaysAllowRules, alwaysDenyRules, denialTracking, isSkipAllPrompts);
    }

    public PermissionState withAlwaysAllowRules(Map<String, List<PermissionRule>> rules) {
        return new PermissionState(permissionMode, rules, alwaysDenyRules, denialTracking, isSkipAllPrompts);
    }

    public PermissionState withAlwaysDenyRules(Map<String, List<PermissionRule>> rules) {
        return new PermissionState(permissionMode, alwaysAllowRules, rules, denialTracking, isSkipAllPrompts);
    }

    public PermissionState withDenialTracking(DenialTrackingState tracking) {
        return new PermissionState(permissionMode, alwaysAllowRules, alwaysDenyRules, tracking, isSkipAllPrompts);
    }
}

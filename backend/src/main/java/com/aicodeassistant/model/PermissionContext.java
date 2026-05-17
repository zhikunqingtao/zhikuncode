package com.aicodeassistant.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限上下文 — 用于权限检查的完整上下文。
 *
 */
public record PermissionContext(
        PermissionMode mode,
        Set<String> additionalWorkingDirectories,
        Map<String, List<PermissionRule>> alwaysAllowRules,
        Map<String, List<PermissionRule>> alwaysDenyRules,
        Map<String, List<PermissionRule>> alwaysAskRules,
        boolean isSkipAllPromptsModeAvailable,
        boolean isAutoModeAvailable,
        boolean isHeadless,
        boolean hasLocalDenialTracking
) {
    /** 向后兼容构造（无 isHeadless / hasLocalDenialTracking） */
    public PermissionContext(
            PermissionMode mode,
            Set<String> additionalWorkingDirectories,
            Map<String, List<PermissionRule>> alwaysAllowRules,
            Map<String, List<PermissionRule>> alwaysDenyRules,
            Map<String, List<PermissionRule>> alwaysAskRules,
            boolean isSkipAllPromptsModeAvailable,
            boolean isAutoModeAvailable) {
        this(mode, additionalWorkingDirectories, alwaysAllowRules, alwaysDenyRules,
                alwaysAskRules, isSkipAllPromptsModeAvailable, isAutoModeAvailable,
                false, false);
    }
}

package com.aicodeassistant.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限上下文 — 用于权限检查的完整上下文。
 *
 * @see <a href="SPEC §5.4">权限模型</a>
 */
public record PermissionContext(
        PermissionMode mode,
        Set<String> additionalWorkingDirectories,
        Map<String, List<PermissionRule>> alwaysAllowRules,
        Map<String, List<PermissionRule>> alwaysDenyRules,
        Map<String, List<PermissionRule>> alwaysAskRules,
        boolean isBypassPermissionsModeAvailable,
        boolean isAutoModeAvailable
) {}

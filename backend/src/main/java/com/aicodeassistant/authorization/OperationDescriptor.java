package com.aicodeassistant.authorization;

import java.util.List;

/** 根据 FrozenToolInput 生成的不可变授权事实。 */
public record OperationDescriptor(
        int authorizationSchemaVersion,
        String toolName,
        String action,
        String inputHash,
        String analyzerId,
        List<EffectClass> effects,
        List<ResourceRef> resources,
        List<String> inheritedEnvironmentNames,
        List<String> networkEndpoints,
        RiskClass risk,
        String operationHash,
        String redactedSummary) {

    public OperationDescriptor {
        effects = AuthorizationFactCanonicalizer.effects(effects);
        resources = AuthorizationFactCanonicalizer.resources(resources);
        inheritedEnvironmentNames = AuthorizationFactCanonicalizer.strings(inheritedEnvironmentNames);
        networkEndpoints = AuthorizationFactCanonicalizer.strings(networkEndpoints);
    }
}

package com.aicodeassistant.authorization;

/** 权限策略和持久化授权共同使用的稳定副作用分类。 */
public enum EffectClass {
    SAFE_INTERNAL, READ_RESOURCE, WRITE_RESOURCE, PROCESS, NETWORK, CONTROL_PLANE, UNKNOWN
}

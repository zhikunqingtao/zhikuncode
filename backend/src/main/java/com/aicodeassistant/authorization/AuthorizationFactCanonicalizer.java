package com.aicodeassistant.authorization;

import java.util.Comparator;
import java.util.List;

/**
 * 授权事实的唯一规范化入口。
 * <p>
 * 授权哈希和最终描述符必须使用完全相同的有序事实；否则同一操作会因为集合构造顺序不同，
 * 在执行前复检时被误判为安全事实发生变化。
 */
final class AuthorizationFactCanonicalizer {
    private static final Comparator<ResourceRef> RESOURCE_ORDER = Comparator
            .comparing(ResourceRef::kind)
            .thenComparing(ResourceRef::value)
            .thenComparing(ResourceRef::outsideWorkspace);

    private AuthorizationFactCanonicalizer() { }

    static List<EffectClass> effects(List<EffectClass> values) {
        return values.stream().distinct().sorted(Comparator.comparing(Enum::name)).toList();
    }

    static List<ResourceRef> resources(List<ResourceRef> values) {
        return values.stream().distinct().sorted(RESOURCE_ORDER).toList();
    }

    static List<String> strings(List<String> values) {
        return values.stream().distinct().sorted().toList();
    }
}

package com.aicodeassistant.authorization;

/** 已规范化且不包含秘密信息的资源事实。 */
public record ResourceRef(String kind, String value, boolean outsideWorkspace) implements Comparable<ResourceRef> {
    @Override public int compareTo(ResourceRef other) {
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : value.compareTo(other.value);
    }
}

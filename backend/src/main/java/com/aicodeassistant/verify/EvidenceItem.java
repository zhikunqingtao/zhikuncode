package com.aicodeassistant.verify;

import java.util.Map;

/**
 * 证据条目 — 一个 Bundle 内的单个证据项。
 *
 * @param id         唯一标识
 * @param type       类型: command | screenshot | video | har | console | test | diff
 * @param summary    人类可读摘要
 * @param blobSha256 大对象的 SHA-256（存储在 blob 目录）
 * @param meta       额外元数据
 */
public record EvidenceItem(
    String id,
    String type,
    String summary,
    String blobSha256,
    Map<String, Object> meta
) {
    public EvidenceItem {
        if (id == null) id = java.util.UUID.randomUUID().toString();
    }
}

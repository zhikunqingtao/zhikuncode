package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiProviderConfiguration.splitApiKeys 拆分逻辑单测 — 逗号分隔多 Key 通用能力。
 * <p>
 * 同一 Provider 可配置多把 key（如 ZenMux 订阅 key sk-ss-v1- 优先 + 按量 key 兜底），
 * 单 key 配置拆分后仍为单元素列表，与原有单 key 构造器语义完全一致。
 */
class MultiApiKeySplitTest {

    @Test
    void nullOrBlankReturnsEmptyList() {
        assertThat(MultiProviderConfiguration.splitApiKeys(null)).isEmpty();
        assertThat(MultiProviderConfiguration.splitApiKeys("")).isEmpty();
        assertThat(MultiProviderConfiguration.splitApiKeys("   ")).isEmpty();
    }

    @Test
    void singleKeyReturnsSingletonList() {
        assertThat(MultiProviderConfiguration.splitApiKeys("sk-ai-v1-payg"))
                .containsExactly("sk-ai-v1-payg");
    }

    @Test
    void multipleKeysSplitInOrder() {
        assertThat(MultiProviderConfiguration.splitApiKeys(
                        "sk-ss-v1-subscription,sk-ai-v1-payg"))
                .containsExactly("sk-ss-v1-subscription", "sk-ai-v1-payg");
    }

    @Test
    void trimsWhitespaceAroundKeys() {
        assertThat(MultiProviderConfiguration.splitApiKeys(" key-a , key-b ,key-c "))
                .containsExactly("key-a", "key-b", "key-c");
    }

    @Test
    void trailingCommaAndEmptySegmentsAreDropped() {
        assertThat(MultiProviderConfiguration.splitApiKeys("key-a,,key-b,"))
                .containsExactly("key-a", "key-b");
        assertThat(MultiProviderConfiguration.splitApiKeys(",")).isEmpty();
    }

    @Test
    void duplicateKeysAreDeduplicatedKeepingFirstOccurrence() {
        // 重复 key 去重，避免 getKeyCount 虚高误触发多 key 冷却分支
        assertThat(MultiProviderConfiguration.splitApiKeys("key-a,key-b,key-a"))
                .containsExactly("key-a", "key-b");
        assertThat(MultiProviderConfiguration.splitApiKeys(" k1 , k1 ,k2, k2 "))
                .containsExactly("k1", "k2");
        assertThat(new ApiKeyRotationManager(
                MultiProviderConfiguration.splitApiKeys("k1,k1")).getKeyCount())
                .isEqualTo(1);
    }

    @Test
    void singleKeyRotationKeepsOriginalSemantics() {
        // 单 key 拆分后与单 key 构造器等价：getNextKey() 始终返回唯一 key
        ApiKeyRotationManager fromSplit = new ApiKeyRotationManager(
                MultiProviderConfiguration.splitApiKeys("only-key"));
        ApiKeyRotationManager fromSingle = new ApiKeyRotationManager("only-key");

        assertThat(fromSplit.getKeyCount()).isEqualTo(1);
        assertThat(fromSingle.getKeyCount()).isEqualTo(1);
        assertThat(fromSplit.getNextKey()).isEqualTo("only-key");
        assertThat(fromSplit.getNextKey()).isEqualTo("only-key");
        assertThat(fromSplit.getNextKey()).isEqualTo(fromSingle.getNextKey());
    }

    @Test
    void multiKeyRotationKeepsRoundRobinOrder() {
        // 拆分后的 key 列表保持顺序：round-robin 依次返回 key[0] → key[1] → key[0]
        List<String> keys = MultiProviderConfiguration.splitApiKeys(
                "sk-ss-v1-first,sk-ai-v1-second");
        ApiKeyRotationManager manager = new ApiKeyRotationManager(keys);

        assertThat(manager.getKeyCount()).isEqualTo(2);
        assertThat(manager.getNextKey()).isEqualTo("sk-ss-v1-first");
        assertThat(manager.getNextKey()).isEqualTo("sk-ai-v1-second");
        assertThat(manager.getNextKey()).isEqualTo("sk-ss-v1-first");
    }
}

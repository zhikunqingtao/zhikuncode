package com.aicodeassistant.workbench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptanceCriteriaExtractorTest {
    private final AcceptanceCriteriaExtractor extractor = new AcceptanceCriteriaExtractor();

    @Test
    void extractsExplicitListsAndConstraintsWithoutInventingRequirements() {
        var result = extractor.extract("""
                请修改页面。
                - 保留全部内容
                1. 支持搜索
                不得把旧成果混入当前交付。普通描述只是背景。
                """);

        assertThat(result).containsExactly(
                "保留全部内容", "支持搜索", "不得把旧成果混入当前交付。");
        assertThat(extractor.extract("帮我看看这个页面")).isEmpty();
    }

    @Test
    void deduplicatesAndCapsAtTwenty() {
        StringBuilder request = new StringBuilder();
        for (int i = 0; i < 25; i++) request.append("- 条款").append(i).append('\n');
        assertThat(extractor.extract(request.toString())).hasSize(20);
    }
}

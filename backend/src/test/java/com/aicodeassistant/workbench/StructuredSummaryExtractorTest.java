package com.aicodeassistant.workbench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredSummaryExtractorTest {
    private final StructuredSummaryExtractor extractor = new StructuredSummaryExtractor();

    @Test
    void conservativelyProjectsOnlyExplicitSections() {
        var summary = extractor.extract("""
                这次改造已经完成，当前交付可用。

                ## 已完成
                - 修复数据关联
                - 增加结果摘要

                ## 风险
                - 旧会话只能兼容读取

                ## 下一步
                - 请检查页面效果
                """);
        assertThat(summary.conclusion()).isEqualTo("这次改造已经完成，当前交付可用。");
        assertThat(summary.completed()).containsExactly("修复数据关联", "增加结果摘要");
        assertThat(summary.issues()).containsExactly("旧会话只能兼容读取");
        assertThat(summary.nextSteps()).containsExactly("请检查页面效果");
    }

    @Test
    void doesNotReclassifyUnheadedBodyTextAsCompletedWork() {
        var summary = extractor.extract("整体判断如下。\n\n这是普通正文，不是完成清单。");
        assertThat(summary.conclusion()).isEqualTo("整体判断如下。");
        assertThat(summary.completed()).isEmpty();
        assertThat(summary.issues()).isEmpty();
    }
}

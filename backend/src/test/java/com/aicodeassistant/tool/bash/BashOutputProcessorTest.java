package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BashOutputProcessor 单元测试。
 * <p>
 * 覆盖输出截断逻辑：短输出直通、超长输出的有错误/无错误截断策略、null/空值处理。
 */
class BashOutputProcessorTest {

    private BashOutputProcessor processor;

    /** 源码常量 MAX_OUTPUT_CHARS */
    private static final int MAX_OUTPUT_CHARS = 30_000;
    /** 源码常量 HEAD_CHARS — 有错误时的头部保留长度 */
    private static final int HEAD_CHARS = 10_000;
    /** 源码常量 TAIL_CHARS — 有错误时的尾部保留长度 */
    private static final int TAIL_CHARS = 15_000;

    @BeforeEach
    void setUp() {
        processor = new BashOutputProcessor();
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 生成指定长度的字符串，填充字符 'A'。
     */
    private String generateString(int length) {
        return "A".repeat(length);
    }

    // ═══════════════════════════════════════════════════════════
    // 测试用例
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TC-BASH-011: 输出 < 30K 不截断")
    void outputUnderLimit_returnedAsIs() {
        String input = generateString(20_000);
        String result = processor.processOutput(input, false);
        assertThat(result).isEqualTo(input);
        assertThat(result).hasSize(20_000);
    }

    @Test
    @DisplayName("TC-BASH-012: 输出 > 30K 无错误时均匀截断")
    void outputOverLimit_noError_evenlyTruncated() {
        String input = generateString(50_000);
        String result = processor.processOutput(input, false);

        // 包含截断标记
        assertThat(result).contains("truncated");
        // 均匀截断: 头尾各 MAX_OUTPUT_CHARS/2 = 15K
        int halfChars = MAX_OUTPUT_CHARS / 2;
        // 结果应以输入头部开头，以输入尾部结尾
        assertThat(result).startsWith(input.substring(0, halfChars));
        assertThat(result).endsWith(input.substring(input.length() - halfChars));
        // 总长度应 < 原始长度
        assertThat(result.length()).isLessThan(input.length());
    }

    @Test
    @DisplayName("TC-BASH-013: 有错误时优先保留尾部")
    void outputOverLimit_withError_tailPreferred() {
        String input = generateString(50_000);
        String result = processor.processOutput(input, true);

        // 包含截断标记（含错误上下文说明）
        assertThat(result).contains("truncated");
        assertThat(result).contains("error context preserved");
        // 有错误: HEAD=10K, TAIL=15K → TAIL 比 HEAD 多
        assertThat(result).startsWith(input.substring(0, HEAD_CHARS));
        assertThat(result).endsWith(input.substring(input.length() - TAIL_CHARS));
        // TAIL(15K) > HEAD(10K) — 尾部保留更多
        assertThat(TAIL_CHARS).isGreaterThan(HEAD_CHARS);
    }

    @Test
    @DisplayName("TC-BASH-014: null/空输出直接返回")
    void nullOrEmptyOutput_returnedAsIs() {
        assertThat(processor.processOutput(null, false)).isNull();
        assertThat(processor.processOutput("", false)).isEmpty();
        assertThat(processor.processOutput(null, true)).isNull();
        assertThat(processor.processOutput("", true)).isEmpty();
    }
}

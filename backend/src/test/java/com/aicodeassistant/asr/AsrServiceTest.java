package com.aicodeassistant.asr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsrService 单元测试 — 热词纠正规则配置（{@code asr.corrections}）纯函数测试，无需 Spring 上下文。
 * <p>
 * 覆盖 {@link AsrService#parseCorrections}（配置解析）、{@link AsrService#variantToPattern}
 * （变体宽容正则，含中文/英文混合 token 切分）、{@link AsrService#applyCorrections}（确定性后处理
 * 纠正）、{@link AsrService#buildSystemText}（system 词表偏置）。
 */
class AsrServiceTest {

    /** 与 {@code @Value("${asr.corrections:…}")} 默认值一致的默认纠正配置串 */
    private static final String DEFAULT_CORRECTIONS =
            "zhikuncode:zhi kun code,zkun code,智坤code,志鲲Code,智kuncode,zhi坤code";

    private static final String DEFAULT_PREFIX = "实体词表：zhikuncode";

    /** 用默认配置串构造规则（与 recognize() 运行时注入语义一致） */
    private static List<AsrService.CorrectionRule> defaultRules() {
        return AsrService.parseCorrections(DEFAULT_CORRECTIONS);
    }

    // ═══════════════ parseCorrections：配置解析 ═══════════════

    @Test
    @DisplayName("默认配置串（与 @Value 默认值一致）→ 1 条规则、canonical=zhikuncode、6 个变体模式")
    void parseCorrections_defaultConfig_shouldYieldOneRuleWithSixVariants() {
        List<AsrService.CorrectionRule> rules = defaultRules();
        assertEquals(1, rules.size());
        assertEquals("zhikuncode", rules.get(0).canonical());
        assertEquals(6, rules.get(0).patterns().size());
    }

    @Test
    @DisplayName("多条目 a:x,y;b:z → 2 条规则，canonical 与变体数量各自正确")
    void parseCorrections_multipleEntries_shouldParseEachRule() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("a:x,y;b:z");
        assertEquals(2, rules.size());
        assertEquals("a", rules.get(0).canonical());
        assertEquals(2, rules.get(0).patterns().size());
        assertEquals("b", rules.get(1).canonical());
        assertEquals(1, rules.get(1).patterns().size());
    }

    @Test
    @DisplayName("全角分隔符（；：，）与半角等价")
    void parseCorrections_fullWidthSeparators_shouldParse() {
        List<AsrService.CorrectionRule> rules =
                AsrService.parseCorrections("zhikuncode：zhi kun code，zkuncode；qwen：通义千问");
        assertEquals(2, rules.size());
        assertEquals("zhikuncode", rules.get(0).canonical());
        assertEquals(2, rules.get(0).patterns().size());
        assertEquals("qwen", rules.get(1).canonical());
        assertEquals(1, rules.get(1).patterns().size());
    }

    @Test
    @DisplayName("null/空串/纯空白/纯分隔符 → 空列表")
    void parseCorrections_nullOrBlankOrSeparatorOnly_shouldReturnEmpty() {
        assertEquals(List.of(), AsrService.parseCorrections(null));
        assertEquals(List.of(), AsrService.parseCorrections(""));
        assertEquals(List.of(), AsrService.parseCorrections("   "));
        assertEquals(List.of(), AsrService.parseCorrections(";;；"));
        assertEquals(List.of(), AsrService.parseCorrections("；"));
    }

    @Test
    @DisplayName("缺冒号的条目跳过，其余条目正常解析")
    void parseCorrections_entryWithoutColon_shouldSkipEntry() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("noColon;a:x");
        assertEquals(1, rules.size());
        assertEquals("a", rules.get(0).canonical());
        assertEquals(1, rules.get(0).patterns().size());
    }

    @Test
    @DisplayName("canonical 为空的条目（'  :x'）跳过")
    void parseCorrections_blankCanonical_shouldSkipEntry() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("  :x;a:y");
        assertEquals(1, rules.size());
        assertEquals("a", rules.get(0).canonical());
    }

    @Test
    @DisplayName("'a:'（无变体）→ 条目保留且 patterns 为空列表（纯词表偏置用法）")
    void parseCorrections_entryWithoutVariants_shouldKeepRuleWithEmptyPatterns() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("a:");
        assertEquals(1, rules.size());
        assertEquals("a", rules.get(0).canonical());
        assertEquals(List.of(), rules.get(0).patterns());
    }

    @Test
    @DisplayName("重复变体去重（保序）：a:x,x,y → 2 个模式")
    void parseCorrections_duplicateVariants_shouldDeduplicate() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("a:x,x,y");
        assertEquals(1, rules.size());
        assertEquals(2, rules.get(0).patterns().size());
    }

    @Test
    @DisplayName("canonical 与变体各自 trim：'  a : x , y ' → canonical=a，2 个模式")
    void parseCorrections_shouldTrimTokens() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("  a : x , y ");
        assertEquals(1, rules.size());
        assertEquals("a", rules.get(0).canonical());
        assertEquals(2, rules.get(0).patterns().size());
    }

    // ═══════════════ variantToPattern：变体宽容正则 ═══════════════

    @Test
    @DisplayName("变体 'zhi kun code' 的模式匹配空格/连字符/驼峰/连写四种形态")
    void variantToPattern_spacesAndHyphens_shouldMatchAllSeparatorForms() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("zhi kun code");
        assertTrue(pattern.matcher("zhi kun code").find());
        assertTrue(pattern.matcher("zhi-kun-code").find());
        assertTrue(pattern.matcher("zhiKunCode").find());
        assertTrue(pattern.matcher("zhikuncode").find());
    }

    @Test
    @DisplayName("变体大小写不敏感：'ZHI KUN CODE' / 'ZhiKunCode' 均匹配")
    void variantToPattern_shouldBeCaseInsensitive() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("zhi kun code");
        assertTrue(pattern.matcher("ZHI KUN CODE").find());
        assertTrue(pattern.matcher("ZhiKunCode").find());
    }

    @Test
    @DisplayName("中文+英文混合 token 被拆开：'智坤code' 的模式匹配 '智坤code' 与 '智坤 code'")
    void variantToPattern_cjkMixedToken_shouldSplitAtBoundary() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("智坤code");
        assertTrue(pattern.matcher("智坤code").find());
        assertTrue(pattern.matcher("智坤 code").find());
        // 字面量 '智坤code' 模式匹配不到 '智坤 code'，此断言成立即证明 token 在 CJK/ASCII 边界被拆开
        assertFalse(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("智坤code"))
                .matcher("智坤 code").find());
    }

    @Test
    @DisplayName("三段混合 token 拆分：'zhi坤code' 的模式匹配 'zhi坤code' 与 'zhi 坤 code'")
    void variantToPattern_threeWayMixedToken_shouldSplitEachBoundary() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("zhi坤code");
        assertTrue(pattern.matcher("zhi坤code").find());
        assertTrue(pattern.matcher("zhi 坤 code").find());
        assertTrue(pattern.matcher("zhi-坤-code").find());
    }

    @Test
    @DisplayName("纯 CJK 变体整体字面匹配：'志鲲Code' 的模式匹配 '志鲲code'（大小写不敏感）")
    void variantToPattern_cjkVariant_shouldMatchLiterally() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("志鲲Code");
        assertTrue(pattern.matcher("志鲲Code").find());
        assertTrue(pattern.matcher("志鲲code").find());
        assertTrue(pattern.matcher("志鲲 code").find());
    }

    @Test
    @DisplayName("仅由连字符构成的变体 → 永不匹配（防御行为）")
    void variantToPattern_separatorOnlyVariant_shouldNeverMatch() {
        java.util.regex.Pattern pattern = AsrService.variantToPattern("---");
        assertFalse(pattern.matcher("任意文本").find());
        assertFalse(pattern.matcher("").find());
    }

    // ═══════════════ applyCorrections：确定性后处理 ═══════════════

    @Test
    @DisplayName("实测变体：zkuncode（英文发音识别结果）→ zhikuncode")
    void corrections_zkuncodeVariant_shouldNormalize() {
        assertEquals("介绍一下zhikuncode这个项目。",
                AsrService.applyCorrections("介绍一下zkuncode这个项目。", defaultRules()));
    }

    @Test
    @DisplayName("默认变体 zkun code 分词模式 → 连写、空格、连字符形态均纠正")
    void corrections_zkunCodeFamily_shouldNormalizeAllForms() {
        assertEquals("zhikuncode", AsrService.applyCorrections("zkun code", defaultRules()));
        assertEquals("zhikuncode", AsrService.applyCorrections("zkun-code", defaultRules()));
        assertEquals("zhikuncode", AsrService.applyCorrections("ZKUN CODE", defaultRules()));
    }

    @Test
    @DisplayName("实测变体：智坤code（中文发音识别结果）→ zhikuncode")
    void corrections_chineseVariant_shouldNormalize() {
        assertEquals("请介绍一下zhikuncode这个项目。",
                AsrService.applyCorrections("请介绍一下智坤code这个项目。", defaultRules()));
    }

    @Test
    @DisplayName("实测输入：zhi kun code 与 智坤 code 并存 → 全部纠正")
    void corrections_mixedVariants_shouldNormalizeAll() {
        assertEquals("是 zhikuncode zhikuncode。",
                AsrService.applyCorrections("是 zhi kun code 智坤 code。", defaultRules()));
    }

    @Test
    @DisplayName("大小写/驼峰变体 → 统一纠正")
    void corrections_caseVariants_shouldNormalize() {
        assertEquals("zhikuncode", AsrService.applyCorrections("ZhiKunCode", defaultRules()));
    }

    @Test
    @DisplayName("谐音字与混合形态变体 → 统一纠正")
    void corrections_homophoneAndMixedVariants_shouldNormalize() {
        assertEquals("zhikuncode", AsrService.applyCorrections("志鲲Code", defaultRules()));
        assertEquals("zhikuncode", AsrService.applyCorrections("智kuncode", defaultRules()));
        assertEquals("zhikuncode", AsrService.applyCorrections("zhi坤code", defaultRules()));
    }

    @Test
    @DisplayName("已是正确写法 → 幂等不变")
    void corrections_canonicalText_shouldBeIdempotent() {
        assertEquals("介绍一下zhikuncode这个项目。",
                AsrService.applyCorrections("介绍一下zhikuncode这个项目。", defaultRules()));
        assertEquals("zhikuncode", AsrService.applyCorrections("zhikuncode", defaultRules()));
    }

    @Test
    @DisplayName("普通文本不误伤")
    void corrections_plainText_shouldRemainUnchanged() {
        assertEquals("这段 code 质量不错",
                AsrService.applyCorrections("这段 code 质量不错", defaultRules()));
        assertEquals("kun 是一个拼音",
                AsrService.applyCorrections("kun 是一个拼音", defaultRules()));
    }

    @Test
    @DisplayName("null/空串 → 原样返回")
    void corrections_nullOrEmpty_shouldReturnAsIs() {
        assertNull(AsrService.applyCorrections(null, defaultRules()));
        assertEquals("", AsrService.applyCorrections("", defaultRules()));
    }

    @Test
    @DisplayName("空规则列表 → 原样返回")
    void corrections_emptyRules_shouldReturnAsIs() {
        assertEquals("介绍一下zkuncode这个项目。",
                AsrService.applyCorrections("介绍一下zkuncode这个项目。", List.of()));
    }

    @Test
    @DisplayName("多条目规则各自独立生效：zkuncode→zhikuncode、千问→qwen")
    void corrections_multipleRules_shouldApplyEachCanonical() {
        List<AsrService.CorrectionRule> rules = AsrService.parseCorrections("zhikuncode:zkuncode;qwen:千问");
        assertEquals("用zhikuncode和qwen写代码",
                AsrService.applyCorrections("用zkuncode和千问写代码", rules));
    }

    // ═══════════════ buildSystemText：词表偏置 system 消息 ═══════════════

    @Test
    @DisplayName("canonical 列表 + null context → 仅返回实体词表前缀行")
    void buildSystemText_hotwords_nullContext_shouldReturnPrefixOnly() {
        assertEquals(DEFAULT_PREFIX, AsrService.buildSystemText(List.of("zhikuncode"), null));
    }

    @Test
    @DisplayName("canonical 列表 + 非空 context → 返回前缀 + 空行 + context")
    void buildSystemText_hotwords_withContext_shouldReturnPrefixAndContext() {
        String context = "用户：介绍一下 zhikuncode\n助手：zhikuncode 是一个 AI 编程助手";
        assertEquals(DEFAULT_PREFIX + "\n\n" + context,
                AsrService.buildSystemText(List.of("zhikuncode"), context));
    }

    @Test
    @DisplayName("canonical 列表 + 空白 context → 仅返回前缀行（空白 context 视为无上下文）")
    void buildSystemText_hotwords_blankContext_shouldReturnPrefixOnly() {
        assertEquals(DEFAULT_PREFIX, AsrService.buildSystemText(List.of("zhikuncode"), "   "));
    }

    @Test
    @DisplayName("空列表 + 非空 context → 直接返回 context 原文（向后兼容）")
    void buildSystemText_emptyHotwords_withContext_shouldReturnContextAsIs() {
        String context = "用户：介绍一下华为MetaERP\n助手：华为MetaERP 是企业管理软件";
        assertEquals(context, AsrService.buildSystemText(List.of(), context));
    }

    @Test
    @DisplayName("空列表 + null context → 返回 null")
    void buildSystemText_emptyHotwords_nullContext_shouldReturnNull() {
        assertNull(AsrService.buildSystemText(List.of(), null));
    }

    @Test
    @DisplayName("null 列表 + null context → 返回 null（防御）")
    void buildSystemText_nullHotwords_nullContext_shouldReturnNull() {
        assertNull(AsrService.buildSystemText(null, null));
    }

    @Test
    @DisplayName("空白热词串 + 空白 context → 返回 null")
    void buildSystemText_blankHotwords_blankContext_shouldReturnNull() {
        assertNull(AsrService.buildSystemText(List.of("  "), "  "));
    }

    @Test
    @DisplayName("多 canonical 顿号连接、去重保序")
    void buildSystemText_multipleCanonicals_shouldJoinWithDunHao() {
        assertEquals("实体词表：zhikuncode、qwen、deepseek",
                AsrService.buildSystemText(List.of("zhikuncode", "qwen", "deepseek", "qwen"), null));
    }

    // ═══════════════ 默认配置驱动两层（与 @Value 注入语义一致） ═══════════════

    @Test
    @DisplayName("默认配置串统一驱动两层：canonical 进 system 词表，变体被后处理纠正")
    void defaultConfig_shouldDriveBothVocabularyBiasAndPostCorrection() {
        List<AsrService.CorrectionRule> rules = defaultRules();
        List<String> canonicals = rules.stream().map(AsrService.CorrectionRule::canonical).toList();
        assertEquals(List.of("zhikuncode"), canonicals);
        assertEquals(DEFAULT_PREFIX, AsrService.buildSystemText(canonicals, null));
        assertEquals("介绍一下zhikuncode这个项目。",
                AsrService.applyCorrections("介绍一下zkuncode这个项目。", rules));
    }
}

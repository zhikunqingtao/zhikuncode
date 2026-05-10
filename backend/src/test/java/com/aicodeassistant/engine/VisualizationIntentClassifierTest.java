package com.aicodeassistant.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对应 Task3-5差异化升级功能测试方案 §11.11 资产 #7。
 *
 * <p>覆盖三道闸门与静默失败（总 22 用例，MVP 落地 6 条；剩余 16 条 @Disabled 占位，预备周补完）。
 */
@ExtendWith(MockitoExtension.class)
class VisualizationIntentClassifierTest {

    @Mock
    private SideQueryService sideQueryService;

    private ObjectMapper objectMapper;
    private Cache<String, VisualizationHint> cache;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        cache = Caffeine.newBuilder().maximumSize(100).build();
    }

    private VisualizationIntentClassifier newClassifier(boolean enabled) {
        return new VisualizationIntentClassifier(sideQueryService, objectMapper, cache, enabled);
    }

    // ═══ MVP 6 用例 ═══

    @Test
    @DisplayName("VIC-01 开关闸门：disabled 直返 EMPTY，不触 LLM")
    void vic01_disabledGateReturnsEmpty() {
        VisualizationIntentClassifier c = newClassifier(false);

        VisualizationHint hint = c.classify("sess-1", "画一张时间线图", List.of());

        assertThat(hint).isSameAs(VisualizationHint.EMPTY);
        verify(sideQueryService, never()).query(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("VIC-02 关键词闸门：未命中且无 tool 时返回 EMPTY")
    void vic02_noKeywordNoToolReturnsEmpty() {
        VisualizationIntentClassifier c = newClassifier(true);

        VisualizationHint hint = c.classify("sess-1", "how are you?", List.of());

        assertThat(hint.isEmpty()).isTrue();
        verify(sideQueryService, never()).query(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("VIC-03 关键词闸门：命中触发 LLM 并解析 viewType")
    void vic03_keywordHitInvokesLlm() {
        VisualizationIntentClassifier c = newClassifier(true);
        when(sideQueryService.query(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn("{\"viewType\":\"git-timeline\",\"dataSource\":\"git-log\",\"params\":{}}");

        VisualizationHint hint = c.classify("sess-1", "帮我画一张 git 时间线", List.of());

        assertThat(hint.viewType()).isEqualTo("git-timeline");
        assertThat(hint.dataSource()).isEqualTo("git-log");
    }

    @Test
    @DisplayName("VIC-04 缓存闸门：同 sessionId+question+toolSummary 二次命中不触 LLM")
    void vic04_cacheHitDoesNotInvokeLlm() {
        VisualizationIntentClassifier c = newClassifier(true);
        when(sideQueryService.query(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn("{\"viewType\":\"schema-viewer\",\"dataSource\":\"schema-catalog\",\"params\":{}}");

        c.classify("sess-1", "给我看 schema 图表", List.of());
        c.classify("sess-1", "给我看 schema 图表", List.of());

        verify(sideQueryService, times(1)).query(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("VIC-05 静默失败：LLM 抛异常返回 EMPTY，不向上传播")
    void vic05_llmErrorReturnsEmpty() {
        VisualizationIntentClassifier c = newClassifier(true);
        when(sideQueryService.query(anyString(), anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("DashScope 500"));

        VisualizationHint hint = c.classify("sess-1", "可视化这个调用链", List.of());

        assertThat(hint.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("VIC-06 Markdown fence 包裹 JSON 能被剥离并解析")
    void vic06_markdownFenceStripped() {
        VisualizationIntentClassifier c = newClassifier(true);
        when(sideQueryService.query(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn("```json\n{\"viewType\":\"mermaid\",\"dataSource\":\"tool-result\",\"params\":{}}\n```");

        VisualizationHint hint = c.classify("sess-1", "画流程图 mermaid", List.of());

        assertThat(hint.viewType()).isEqualTo("mermaid");
    }

    // ═══ 下列 16 用例在预备周补完（对齐方案 §1.2 VIC-07～VIC-22） ═══

    @Test @Disabled("TODO VIC-07：userQuestion=null 返回 EMPTY") void vic07() {}
    @Test @Disabled("TODO VIC-08：sessionId=blank 返回 EMPTY") void vic08() {}
    @Test @Disabled("TODO VIC-09：tool_use 存在时即便关键词未命中也放行") void vic09() {}
    @Test @Disabled("TODO VIC-10：toolSummary 超 512 字符被截断") void vic10() {}
    @Test @Disabled("TODO VIC-11：empty viewType 返回 EMPTY 哨兵（模型空 JSON）") void vic11() {}
    @Test @Disabled("TODO VIC-12：非 JSON 响应静默失败") void vic12() {}
    @Test @Disabled("TODO VIC-13：非 object params 被归一化为空 Map") void vic13() {}
    @Test @Disabled("TODO VIC-14：params >8 键也只透传（方案 §Rules 约束由 prompt 负责）") void vic14() {}
    @Test @Disabled("TODO VIC-15：sessionId+question 相同但 toolSummary 不同会重新触发 LLM") void vic15() {}
    @Test @Disabled("TODO VIC-16：缓存 TTL 到期后再次触发 LLM") void vic16() {}
    @Test @Disabled("TODO VIC-17：缓存满后 LRU 清理") void vic17() {}
    @Test @Disabled("TODO VIC-18：LLM timeout（>15s）返回 EMPTY") void vic18() {}
    @Test @Disabled("TODO VIC-19：assistantMessage.tool_use 与 userMessage.tool_result 配对 summary") void vic19() {}
    @Test @Disabled("TODO VIC-20：仅 tool_use 无 tool_result 仍生成 summary") void vic20() {}
    @Test @Disabled("TODO VIC-21：仅 tool_result 无 tool_use 仍生成 summary") void vic21() {}
    @Test @Disabled("TODO VIC-22：并发 50 个 classify 无异常（ThreadSafety 红线）") void vic22() {}
}

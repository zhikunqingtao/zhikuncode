package com.aicodeassistant.permission;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.PermissionContext;
import com.aicodeassistant.model.PermissionDecision;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TC-PERM-002: AutoModeClassifier 两阶段分类与降级验证
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-PERM-002: AutoModeClassifier 分类与降级验证")
class AutoModeClassifierDeepTest {

    @Mock private LlmProviderRegistry mockRegistry;
    @Mock private LlmProvider mockProvider;
    @Mock private Tool mockTool;

    private AutoModeClassifier classifier;
    private ToolInput testInput;

    @BeforeEach
    void setUp() {
        testInput = ToolInput.from(Map.of("path", "/tmp/test.txt"));

        lenient().when(mockTool.getName()).thenReturn("Read");
        lenient().when(mockTool.toAutoClassifierInput(any())).thenReturn("Read file /tmp/test.txt");
        lenient().when(mockRegistry.resolveClassifierModel()).thenReturn("qwen-plus");
        lenient().when(mockRegistry.getProvider(anyString())).thenReturn(mockProvider);

        classifier = new AutoModeClassifier(mockRegistry);
    }

    @AfterEach
    void tearDown() {
        classifier.resetFailureCount();
        classifier.clearCache();
    }

    // ========== Quick 阶段解析测试 ==========

    @Test
    @DisplayName("Quick 阶段：<block>no</block> 解析为 ALLOW")
    void quickStageAllowParsing() {
        var result = classifier.parseQuickResponse("<block>no</block>");
        assertNotNull(result);
        assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision(),
            "Quick 阶段 <block>no</block> 应解析为 ALLOW");
    }

    @Test
    @DisplayName("Quick 阶段：<block>yes</block> 解析为 DENY")
    void quickStageDenyParsing() {
        var result = classifier.parseQuickResponse("<block>yes</block>");
        assertNotNull(result);
        assertEquals(AutoModeClassifier.ClassifierDecision.DENY, result.decision(),
            "Quick 阶段 <block>yes</block> 应解析为 DENY");
    }

    @Test
    @DisplayName("Quick 阶段：空响应返回 ASK")
    void quickStageEmptyResponseFallback() {
        var result1 = classifier.parseQuickResponse("");
        assertNotNull(result1, "空字符串应返回非 null 结果");
        assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result1.decision());

        var result2 = classifier.parseQuickResponse("invalid xml garbage");
        assertNotNull(result2, "无效 XML 应返回非 null 结果");
        assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result2.decision());
    }

    // ========== Thinking 阶段解析测试 ==========

    @Test
    @DisplayName("Thinking 阶段：带 reason 的 DENY 解析")
    void thinkingStageWithReason() {
        String response = "<thinking>文件包含敏感信息</thinking><block>yes</block><reason>该文件可能包含密码</reason>";
        var result = classifier.parseThinkingResponse(response);
        assertNotNull(result);
        assertEquals(AutoModeClassifier.ClassifierDecision.DENY, result.decision());
    }

    @Test
    @DisplayName("Thinking 阶段：<block>no</block> 解析为 ALLOW")
    void thinkingStageAllowParsing() {
        String response = "<thinking>安全操作</thinking><block>no</block>";
        var result = classifier.parseThinkingResponse(response);
        assertNotNull(result);
        assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision());
    }

    // ========== 降级机制测试 ==========

    @Test
    @DisplayName("连续失败 3 次后应降级为 ASK（不调用 LLM）")
    void degradeAfterThreeConsecutiveFailures() {
        // 模拟 LLM 连续抛异常
        when(mockProvider.chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong()))
            .thenThrow(new RuntimeException("LLM service unavailable"));

        PermissionContext ctx = new PermissionContext(
            PermissionMode.AUTO, Set.of(), Map.of(), Map.of(), Map.of(), false, true);

        // 前 3 次调用触发异常
        for (int i = 0; i < 3; i++) {
            PermissionDecision decision = classifier.classify(mockTool, testInput, ctx);
            assertNotNull(decision, "第 " + (i + 1) + " 次异常后应返回非 null 决策");
        }

        // 第 4 次调用：consecutiveFailures >= 3，应直接在入口返回 ASK
        reset(mockProvider);
        PermissionDecision degraded = classifier.classify(mockTool, testInput, ctx);
        assertNotNull(degraded, "降级后应返回有效决策");
        // 验证 LLM 未被调用（短路）
        verify(mockProvider, never()).chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("降级后重置失败计数应恢复正常分类")
    void recoverAfterResetFailureCount() {
        when(mockProvider.chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong()))
            .thenThrow(new RuntimeException("timeout"))
            .thenThrow(new RuntimeException("timeout"))
            .thenThrow(new RuntimeException("timeout"))
            .thenReturn("<block>no</block>");

        PermissionContext ctx = new PermissionContext(
            PermissionMode.AUTO, Set.of(), Map.of(), Map.of(), Map.of(), false, true);

        // 触发 3 次失败
        for (int i = 0; i < 3; i++) {
            classifier.classify(mockTool, testInput, ctx);
        }

        // 手动重置失败计数
        classifier.resetFailureCount();
        classifier.clearCache();

        // 下一次调用应正常使用 LLM
        PermissionDecision recovered = classifier.classify(mockTool, testInput, ctx);
        assertNotNull(recovered, "恢复后应返回有效决策");
    }

    @Test
    @DisplayName("缓存命中时跳过 LLM 调用")
    void cacheHitShouldSkipLlm() {
        when(mockProvider.chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong()))
            .thenReturn("<block>no</block>");

        PermissionContext ctx = new PermissionContext(
            PermissionMode.AUTO, Set.of(), Map.of(), Map.of(), Map.of(), false, true);

        // 第一次调用
        PermissionDecision first = classifier.classify(mockTool, testInput, ctx);
        assertNotNull(first);

        // 第二次调用相同工具和输入：应命中缓存
        PermissionDecision second = classifier.classify(mockTool, testInput, ctx);
        assertNotNull(second);

        // 验证 LLM 只被调用 1 次（第二次命中缓存）
        verify(mockProvider, times(1)).chatSync(anyString(), anyString(), anyString(), anyInt(), any(), anyLong());
    }
}

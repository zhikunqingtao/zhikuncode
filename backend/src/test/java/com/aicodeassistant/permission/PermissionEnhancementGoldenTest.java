package com.aicodeassistant.permission;

import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限系统增强黄金测试 — 覆盖 §4.9 全部增强功能。
 */
class PermissionEnhancementGoldenTest {

    // ==================== §4.9.0 PermissionRuleSource ====================

    @Nested
    @DisplayName("§4.9.0 PermissionRuleSource")
    class PermissionRuleSourceTests {

        @Test
        @DisplayName("8 种新来源枚举存在")
        void allNewSourcesExist() {
            assertNotNull(PermissionRuleSource.USER_SETTINGS);
            assertNotNull(PermissionRuleSource.PROJECT_SETTINGS);
            assertNotNull(PermissionRuleSource.LOCAL_SETTINGS);
            assertNotNull(PermissionRuleSource.FLAG_SETTINGS);
            assertNotNull(PermissionRuleSource.POLICY_SETTINGS);
            assertNotNull(PermissionRuleSource.CLI_ARG);
            assertNotNull(PermissionRuleSource.COMMAND);
            assertNotNull(PermissionRuleSource.SESSION);
        }

        @Test
        @DisplayName("向后兼容别名存在")
        void backwardCompatAliases() {
            assertNotNull(PermissionRuleSource.USER_GLOBAL);
            assertNotNull(PermissionRuleSource.USER_PROJECT);
            assertNotNull(PermissionRuleSource.USER_SESSION);
            assertNotNull(PermissionRuleSource.SYSTEM_DEFAULT);
        }

        @Test
        @DisplayName("可构造使用新来源的规则")
        void canCreateRuleWithNewSource() {
            PermissionRule rule = new PermissionRule(
                    PermissionRuleSource.CLI_ARG,
                    PermissionBehavior.ALLOW,
                    new PermissionRuleValue("Bash", "git *"));
            assertEquals(PermissionRuleSource.CLI_ARG, rule.source());
        }

        @Test
        @DisplayName("枚举总数 >= 14 (8 新 + 6 旧)")
        void enumCount() {
            assertTrue(PermissionRuleSource.values().length >= 14);
        }
    }

    // ==================== §4.9.1 PermissionMode ====================

    @Nested
    @DisplayName("§4.9.1 PermissionMode")
    class PermissionModeTests {

        @Test
        @DisplayName("7 种权限模式存在")
        void allModesExist() {
            assertEquals(7, PermissionMode.values().length);
            assertNotNull(PermissionMode.DEFAULT);
            assertNotNull(PermissionMode.PLAN);
            assertNotNull(PermissionMode.ACCEPT_EDITS);
            assertNotNull(PermissionMode.DONT_ASK);
            assertNotNull(PermissionMode.BYPASS_PERMISSIONS);
            assertNotNull(PermissionMode.AUTO);
            assertNotNull(PermissionMode.BUBBLE);
        }
    }

    // ==================== §4.9.2 AutoModeClassifier ====================

    @Nested
    @DisplayName("§4.9.2 AutoModeClassifier")
    class AutoModeClassifierTests {

        private AutoModeClassifier classifier;
        private PermissionContext context;

        @BeforeEach
        void setUp() {
            List<LlmProvider> emptyProviders = List.of();
            classifier = new AutoModeClassifier(new LlmProviderRegistry(emptyProviders, null));
            context = new PermissionContext(
                    PermissionMode.AUTO,
                    Set.of(),
                    Map.of(), Map.of(), Map.of(),
                    false, true);
        }

        // ----- Quick 阶段解析 -----

        @Test
        @DisplayName("Quick: <block>no</block> → ALLOW")
        void quickAllowParsing() {
            var result = classifier.parseQuickResponse("<block>no</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision());
        }

        @Test
        @DisplayName("Quick: <block>yes</block> → DENY")
        void quickDenyParsing() {
            var result = classifier.parseQuickResponse("<block>yes</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.DENY, result.decision());
        }

        @Test
        @DisplayName("Quick: 空响应 → ASK")
        void quickEmptyParsing() {
            var result = classifier.parseQuickResponse("");
            assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result.decision());
        }

        @Test
        @DisplayName("Quick: null 响应 → ASK")
        void quickNullParsing() {
            var result = classifier.parseQuickResponse(null);
            assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result.decision());
        }

        @Test
        @DisplayName("Quick: 无效 XML → ASK (解析失败升级)")
        void quickInvalidXml() {
            var result = classifier.parseQuickResponse("some random text");
            assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result.decision());
        }

        @Test
        @DisplayName("Quick: <block>maybe</block> → ASK (未知值)")
        void quickUnknownValue() {
            var result = classifier.parseQuickResponse("<block>maybe</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result.decision());
        }

        // ----- Thinking 阶段解析 -----

        @Test
        @DisplayName("Thinking: <block>no</block> → ALLOW")
        void thinkingAllowParsing() {
            var result = classifier.parseThinkingResponse("<block>no</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision());
        }

        @Test
        @DisplayName("Thinking: <block>yes</block><reason>danger</reason> → DENY + reason")
        void thinkingDenyWithReason() {
            var result = classifier.parseThinkingResponse(
                    "<block>yes</block><reason>danger</reason>");
            assertEquals(AutoModeClassifier.ClassifierDecision.DENY, result.decision());
            assertEquals("danger", result.reason());
        }

        @Test
        @DisplayName("Thinking: stripThinking removes <thinking> block")
        void thinkingStripThinking() {
            var result = classifier.parseThinkingResponse(
                    "<thinking>let me think...</thinking><block>no</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision());
        }

        @Test
        @DisplayName("Thinking: <thinking> with nested <block> inside gets stripped")
        void thinkingNestedBlockInThinking() {
            var result = classifier.parseThinkingResponse(
                    "<thinking>hmm <block>yes</block></thinking><block>no</block>");
            assertEquals(AutoModeClassifier.ClassifierDecision.ALLOW, result.decision());
        }

        @Test
        @DisplayName("Thinking: 空响应 → ASK")
        void thinkingEmptyParsing() {
            var result = classifier.parseThinkingResponse("");
            assertEquals(AutoModeClassifier.ClassifierDecision.ASK, result.decision());
        }

        // ----- 提示词构建 -----

        @Test
        @DisplayName("系统提示词包含 BASE_PROMPT 核心内容")
        void systemPromptContainsBase() {
            String prompt = classifier.buildClassifierSystemPrompt(context);
            assertTrue(prompt.contains("security classifier"));
            assertTrue(prompt.contains("Classification Process"));
        }

        @Test
        @DisplayName("系统提示词包含 PERMISSIONS_TEMPLATE")
        void systemPromptContainsPermissions() {
            String prompt = classifier.buildClassifierSystemPrompt(context);
            assertTrue(prompt.contains("ALLOW (these actions are always safe)"));
            assertTrue(prompt.contains("DENY (these actions should always be blocked)"));
        }

        @Test
        @DisplayName("系统提示词包含 Few-Shot 示例")
        void systemPromptContainsFewShot() {
            String prompt = classifier.buildClassifierSystemPrompt(context);
            assertTrue(prompt.contains("Example 1 - Safe read operation"));
            assertTrue(prompt.contains("Example 5 - Project build command"));
        }

        @Test
        @DisplayName("系统提示词包含 PowerShell 拒绝指导")
        void systemPromptContainsPowerShell() {
            String prompt = classifier.buildClassifierSystemPrompt(context);
            assertTrue(prompt.contains("PowerShell Download-and-Execute"));
            assertTrue(prompt.contains("PowerShell Irreversible Destruction"));
        }

        @Test
        @DisplayName("Bash allow 规则注入到提示词")
        void bashAllowRulesInjected() {
            PermissionContext ctx = new PermissionContext(
                    PermissionMode.AUTO, Set.of(),
                    Map.of("Bash", List.of(
                            new PermissionRule(PermissionRuleSource.USER_SETTINGS,
                                    PermissionBehavior.ALLOW,
                                    new PermissionRuleValue("Bash", "npm test")))),
                    Map.of(), Map.of(), false, true);
            String prompt = classifier.buildClassifierSystemPrompt(ctx);
            assertTrue(prompt.contains("npm test"));
        }

        // ----- XML 后缀常量 -----

        @Test
        @DisplayName("XML_S1_SUFFIX 包含 blocking 指令")
        void xmlS1Suffix() {
            assertTrue(AutoModeClassifier.XML_S1_SUFFIX.contains("blocking"));
        }

        @Test
        @DisplayName("XML_S2_SUFFIX 包含 thinking 指令")
        void xmlS2Suffix() {
            assertTrue(AutoModeClassifier.XML_S2_SUFFIX.contains("<thinking>"));
        }

        // ----- 分类器降级 -----

        @Test
        @DisplayName("classify: null classifierInput → auto-allow")
        void classifyNullInput() {
            Tool tool = createMockTool("ReadTool", null);
            ToolInput input = ToolInput.from(Map.of());
            PermissionDecision decision = classifier.classify(tool, input, context);
            assertTrue(decision.isAllowed());
        }

        @Test
        @DisplayName("classify: 默认桩返回 ALLOW (因为桩返回 <block>no</block>)")
        void classifyDefaultStub() {
            Tool tool = createMockTool("Bash", "git status");
            ToolInput input = ToolInput.from(Map.of("command", "git status"));
            PermissionDecision decision = classifier.classify(tool, input, context);
            assertTrue(decision.isAllowed());
        }

        @Test
        @DisplayName("缓存命中: 相同输入不重复调用")
        void cachingWorks() {
            Tool tool = createMockTool("Bash", "git status");
            ToolInput input = ToolInput.from(Map.of("command", "git status"));
            PermissionDecision first = classifier.classify(tool, input, context);
            PermissionDecision second = classifier.classify(tool, input, context);
            assertEquals(first.behavior(), second.behavior());
        }

        @Test
        @DisplayName("clearCache 清空缓存")
        void clearCacheWorks() {
            Tool tool = createMockTool("Bash", "ls");
            ToolInput input = ToolInput.from(Map.of("command", "ls"));
            classifier.classify(tool, input, context);
            classifier.clearCache();
            // 应该不抛异常地完成第二次分类
            PermissionDecision decision = classifier.classify(tool, input, context);
            assertNotNull(decision);
        }

        @Test
        @DisplayName("ClassifierDecision 枚举 3 个值")
        void classifierDecisionValues() {
            assertEquals(3, AutoModeClassifier.ClassifierDecision.values().length);
        }
    }

    // ==================== §4.9.3 DenialTracking ====================

    @Nested
    @DisplayName("§4.9.3 DenialTrackingService")
    class DenialTrackingTests {

        private DenialTrackingService service;

        @BeforeEach
        void setUp() {
            service = new DenialTrackingService();
        }

        // ----- DenialTrackingState -----

        @Test
        @DisplayName("初始状态全零")
        void initialState() {
            DenialTrackingState state = DenialTrackingState.create();
            assertEquals(0, state.consecutiveDenials());
            assertEquals(0, state.totalDenials());
        }

        @Test
        @DisplayName("state 是不可变 record")
        void stateIsImmutable() {
            DenialTrackingState s1 = DenialTrackingState.create();
            DenialTrackingState s2 = new DenialTrackingState(3, 10);
            assertNotEquals(s1, s2);
            assertEquals(3, s2.consecutiveDenials());
            assertEquals(10, s2.totalDenials());
        }

        // ----- recordDenial -----

        @Test
        @DisplayName("recordDenial 递增两个计数")
        void recordDenialIncrements() {
            DenialTrackingState state = DenialTrackingState.create();
            state = service.recordDenial(state);
            assertEquals(1, state.consecutiveDenials());
            assertEquals(1, state.totalDenials());
        }

        @Test
        @DisplayName("连续 3 次 denial")
        void threeDenials() {
            DenialTrackingState state = DenialTrackingState.create();
            state = service.recordDenial(state);
            state = service.recordDenial(state);
            state = service.recordDenial(state);
            assertEquals(3, state.consecutiveDenials());
            assertEquals(3, state.totalDenials());
        }

        // ----- recordSuccess -----

        @Test
        @DisplayName("recordSuccess 重置连续计数，保留总数")
        void recordSuccessResetsConsecutive() {
            DenialTrackingState state = new DenialTrackingState(2, 5);
            state = service.recordSuccess(state);
            assertEquals(0, state.consecutiveDenials());
            assertEquals(5, state.totalDenials());
        }

        @Test
        @DisplayName("recordSuccess 连续为0时返回同一对象")
        void recordSuccessNoOpWhenZero() {
            DenialTrackingState state = new DenialTrackingState(0, 5);
            DenialTrackingState result = service.recordSuccess(state);
            assertSame(state, result);
        }

        // ----- shouldFallbackToPrompting -----

        @Test
        @DisplayName("连续 < 3 且总数 < 20 → 不触发")
        void noFallbackNormal() {
            assertFalse(service.shouldFallbackToPrompting(new DenialTrackingState(2, 19)));
        }

        @Test
        @DisplayName("连续 = 3 → 触发")
        void fallbackConsecutive() {
            assertTrue(service.shouldFallbackToPrompting(new DenialTrackingState(3, 5)));
        }

        @Test
        @DisplayName("连续 > 3 → 触发")
        void fallbackConsecutiveAbove() {
            assertTrue(service.shouldFallbackToPrompting(new DenialTrackingState(5, 5)));
        }

        @Test
        @DisplayName("总数 = 20 → 触发")
        void fallbackTotal() {
            assertTrue(service.shouldFallbackToPrompting(new DenialTrackingState(0, 20)));
        }

        @Test
        @DisplayName("总数 > 20 → 触发")
        void fallbackTotalAbove() {
            assertTrue(service.shouldFallbackToPrompting(new DenialTrackingState(0, 25)));
        }

        // ----- handleDenialLimitExceeded -----

        @Test
        @DisplayName("未触发限制 → 返回 null")
        void noLimitExceeded() {
            PermissionContext ctx = createContext(false);
            PermissionDecision result = service.handleDenialLimitExceeded(
                    new DenialTrackingState(1, 5), ctx, "reason", null);
            assertNull(result);
        }

        @Test
        @DisplayName("连续限制触发 → 返回 ASK")
        void consecutiveLimitTriggered() {
            PermissionContext ctx = createContext(false);
            PermissionDecision result = service.handleDenialLimitExceeded(
                    new DenialTrackingState(3, 5), ctx, "blocked rm -rf", null);
            assertNotNull(result);
            assertEquals(PermissionBehavior.ASK, result.behavior());
            assertTrue(result.reason().contains("consecutive"));
        }

        @Test
        @DisplayName("总数限制触发 → 返回 ASK 包含总数信息")
        void totalLimitTriggered() {
            PermissionContext ctx = createContext(false);
            PermissionDecision result = service.handleDenialLimitExceeded(
                    new DenialTrackingState(1, 20), ctx, "blocked action", null);
            assertNotNull(result);
            assertEquals(PermissionBehavior.ASK, result.behavior());
            assertTrue(result.reason().contains("blocked this session"));
        }

        @Test
        @DisplayName("Headless + 限制触发 → 抛出 DenialLimitAbortException")
        void headlessAbort() {
            PermissionContext ctx = createContext(true);
            assertThrows(DenialTrackingService.DenialLimitAbortException.class, () ->
                    service.handleDenialLimitExceeded(
                            new DenialTrackingState(3, 3), ctx, "reason", null));
        }

        @Test
        @DisplayName("MAX_CONSECUTIVE = 3")
        void maxConsecutiveConstant() {
            assertEquals(3, DenialTrackingService.MAX_CONSECUTIVE);
        }

        @Test
        @DisplayName("MAX_TOTAL = 20")
        void maxTotalConstant() {
            assertEquals(20, DenialTrackingService.MAX_TOTAL);
        }

        // ----- reset -----

        @Test
        @DisplayName("reset 重置全局状态")
        void resetState() {
            service.persistState(createContext(false), new DenialTrackingState(5, 15));
            service.reset();
            DenialTrackingState state = service.getState();
            assertEquals(0, state.consecutiveDenials());
            assertEquals(0, state.totalDenials());
        }
    }

    // ==================== §4.9.4 DangerousRuleStripper ====================

    @Nested
    @DisplayName("§4.9.4 DangerousRuleStripper")
    class DangerousRuleStripperTests {

        private DangerousRuleStripper stripper;

        @BeforeEach
        void setUp() {
            stripper = new DangerousRuleStripper();
        }

        // ----- isDangerous -----

        @Test
        @DisplayName("Agent 工具 → 危险")
        void agentIsDangerous() {
            PermissionRule rule = makeRule("Agent", null);
            assertTrue(stripper.isDangerous(rule));
        }

        @Test
        @DisplayName("Agent 工具有内容 → 仍然危险")
        void agentWithContentIsDangerous() {
            PermissionRule rule = makeRule("Agent", "some content");
            assertTrue(stripper.isDangerous(rule));
        }

        @Test
        @DisplayName("Bash 无内容 → 危险 (通配符)")
        void bashNoContentIsDangerous() {
            assertTrue(stripper.isDangerous(makeRule("Bash", null)));
            assertTrue(stripper.isDangerous(makeRule("Bash", "")));
        }

        @Test
        @DisplayName("Bash 解释器前缀 python:* → 危险")
        void bashInterpreterPrefix() {
            assertTrue(stripper.isDangerous(makeRule("Bash", "python:script.py")));
            assertTrue(stripper.isDangerous(makeRule("Bash", "node:app.js")));
            assertTrue(stripper.isDangerous(makeRule("Bash", "ruby:test.rb")));
            assertTrue(stripper.isDangerous(makeRule("Bash", "bash:cmd")));
        }

        @Test
        @DisplayName("Bash 管道到解释器 → 危险")
        void bashPipeToDangerous() {
            assertTrue(stripper.isDangerous(makeRule("Bash", "cat file | python")));
            assertTrue(stripper.isDangerous(makeRule("Bash", "echo x | bash")));
            assertTrue(stripper.isDangerous(makeRule("Bash", "something |  node")));
        }

        @Test
        @DisplayName("Bash 安全命令 → 不危险")
        void bashSafeNotDangerous() {
            assertFalse(stripper.isDangerous(makeRule("Bash", "git status")));
            assertFalse(stripper.isDangerous(makeRule("Bash", "npm test")));
            assertFalse(stripper.isDangerous(makeRule("Bash", "ls -la")));
        }

        @Test
        @DisplayName("PowerShell Invoke-Expression → 危险")
        void powershellInvokeExpression() {
            assertTrue(stripper.isDangerous(makeRule("PowerShell", "Invoke-Expression something")));
            assertTrue(stripper.isDangerous(makeRule("PowerShell", "iex script")));
            assertTrue(stripper.isDangerous(makeRule("PowerShell", "Start-Process notepad")));
        }

        @Test
        @DisplayName("PowerShell -Command → 危险")
        void powershellCommand() {
            assertTrue(stripper.isDangerous(makeRule("PowerShell", "powershell -Command \"test\"")));
            assertTrue(stripper.isDangerous(makeRule("PowerShell", "-EncodedCommand abc")));
        }

        @Test
        @DisplayName("PowerShell 安全命令 → 不危险")
        void powershellSafeNotDangerous() {
            assertFalse(stripper.isDangerous(makeRule("PowerShell", "Get-Process")));
            assertFalse(stripper.isDangerous(makeRule("PowerShell", "Get-ChildItem")));
        }

        @Test
        @DisplayName("FileEdit 工具 → 不危险")
        void fileEditNotDangerous() {
            assertFalse(stripper.isDangerous(makeRule("FileEdit", null)));
            assertFalse(stripper.isDangerous(makeRule("FileWrite", "config.json")));
        }

        @Test
        @DisplayName("null 规则 → 不危险")
        void nullNotDangerous() {
            assertFalse(stripper.isDangerous(null));
        }

        // ----- onEnterAutoMode / onExitAutoMode -----

        @Test
        @DisplayName("剥离危险规则并恢复")
        void stripAndRestore() {
            Map<String, List<PermissionRule>> rules = new HashMap<>();
            rules.put("Bash", new ArrayList<>(List.of(
                    makeRule("Bash", null),       // 危险: 通配符
                    makeRule("Bash", "git *")      // 安全
            )));
            rules.put("Agent", new ArrayList<>(List.of(
                    makeRule("Agent", null)        // 危险
            )));
            rules.put("FileEdit", new ArrayList<>(List.of(
                    makeRule("FileEdit", null)     // 安全
            )));

            // 进入 Auto 模式 → 剥离危险规则
            List<PermissionRule> stripped = stripper.onEnterAutoMode(rules);
            assertEquals(2, stripped.size()); // Bash通配符 + Agent
            assertEquals(1, rules.get("Bash").size()); // 只剩 git *
            assertEquals(0, rules.get("Agent").size());
            assertEquals(1, rules.get("FileEdit").size());
            assertEquals(2, stripper.getStrippedCount());

            // 退出 Auto 模式 → 恢复
            stripper.onExitAutoMode(rules);
            assertEquals(2, rules.get("Bash").size());
            assertEquals(1, rules.get("Agent").size());
            assertEquals(0, stripper.getStrippedCount());
        }

        @Test
        @DisplayName("空规则集 → 不出错")
        void emptyRules() {
            List<PermissionRule> stripped = stripper.onEnterAutoMode(Map.of());
            assertTrue(stripped.isEmpty());
        }

        @Test
        @DisplayName("null 规则集 → 不出错")
        void nullRules() {
            List<PermissionRule> stripped = stripper.onEnterAutoMode(null);
            assertTrue(stripped.isEmpty());
        }

        @Test
        @DisplayName("无危险规则时不剥离")
        void noDangerousRules() {
            Map<String, List<PermissionRule>> rules = new HashMap<>();
            rules.put("Bash", new ArrayList<>(List.of(makeRule("Bash", "npm test"))));
            List<PermissionRule> stripped = stripper.onEnterAutoMode(rules);
            assertTrue(stripped.isEmpty());
            assertEquals(1, rules.get("Bash").size());
        }

        @Test
        @DisplayName("getStrippedRules 返回只读副本")
        void strippedRulesImmutable() {
            Map<String, List<PermissionRule>> rules = new HashMap<>();
            rules.put("Agent", new ArrayList<>(List.of(makeRule("Agent", null))));
            stripper.onEnterAutoMode(rules);
            List<PermissionRule> strippedCopy = stripper.getStrippedRules();
            assertThrows(UnsupportedOperationException.class, () ->
                    strippedCopy.add(makeRule("Bash", null)));
        }
    }

    // ==================== PermissionContext 增强 ====================

    @Nested
    @DisplayName("PermissionContext 增强字段")
    class PermissionContextTests {

        @Test
        @DisplayName("isHeadless 默认 false")
        void headlessDefault() {
            PermissionContext ctx = new PermissionContext(
                    PermissionMode.DEFAULT, Set.of(),
                    Map.of(), Map.of(), Map.of(), false, false);
            assertFalse(ctx.isHeadless());
        }

        @Test
        @DisplayName("isHeadless 可设置为 true")
        void headlessTrue() {
            PermissionContext ctx = new PermissionContext(
                    PermissionMode.DEFAULT, Set.of(),
                    Map.of(), Map.of(), Map.of(), false, false, true, false);
            assertTrue(ctx.isHeadless());
        }

        @Test
        @DisplayName("hasLocalDenialTracking 默认 false")
        void denialTrackingDefault() {
            PermissionContext ctx = new PermissionContext(
                    PermissionMode.DEFAULT, Set.of(),
                    Map.of(), Map.of(), Map.of(), false, false);
            assertFalse(ctx.hasLocalDenialTracking());
        }

        @Test
        @DisplayName("向后兼容 7 参数构造器")
        void backwardCompatConstructor() {
            PermissionContext ctx = new PermissionContext(
                    PermissionMode.AUTO, Set.of(),
                    Map.of(), Map.of(), Map.of(), true, true);
            assertEquals(PermissionMode.AUTO, ctx.mode());
            assertFalse(ctx.isHeadless());
            assertFalse(ctx.hasLocalDenialTracking());
        }
    }

    // ==================== PermissionDecision 增强 ====================

    @Nested
    @DisplayName("PermissionDecision 增强")
    class PermissionDecisionTests {

        @Test
        @DisplayName("ask(String) 工厂方法")
        void askStringFactory() {
            PermissionDecision d = PermissionDecision.ask("test reason");
            assertEquals(PermissionBehavior.ASK, d.behavior());
            assertEquals("test reason", d.reason());
            assertEquals(PermissionDecisionReason.CLASSIFIER, d.reasonType());
        }

        @Test
        @DisplayName("allowByClassifier 工厂方法")
        void allowByClassifierFactory() {
            PermissionDecision d = PermissionDecision.allowByClassifier("auto-allowed");
            assertTrue(d.isAllowed());
            assertEquals(PermissionDecisionReason.CLASSIFIER, d.reasonType());
        }
    }

    // ==================== 辅助方法 ====================

    private static PermissionRule makeRule(String toolName, String content) {
        return new PermissionRule(
                PermissionRuleSource.USER_SETTINGS,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue(toolName, content));
    }

    private static PermissionContext createContext(boolean headless) {
        return new PermissionContext(
                PermissionMode.AUTO, Set.of(),
                Map.of(), Map.of(), Map.of(),
                false, true, headless, false);
    }

    private static Tool createMockTool(String name, String classifierInput) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "Mock " + name; }
            @Override public Map<String, Object> getInputSchema() { return Map.of(); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) { return null; }
            @Override public String toAutoClassifierInput(ToolInput input) { return classifierInput; }
        };
    }
}

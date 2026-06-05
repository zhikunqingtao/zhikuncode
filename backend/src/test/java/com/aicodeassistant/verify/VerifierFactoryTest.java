package com.aicodeassistant.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * VerifierFactory 单元测试 — 验证 RV-1 验证器选择策略。
 * <p>
 * 选择规则：
 * <ul>
 *   <li>verificationMode="http_api" → HttpApiVerifier</li>
 *   <li>verificationMode="browser" → BrowserVerifier</li>
 *   <li>其它（含 null/"auto"）→ 自动检测：steps 含 action.startsWith("http_") 则 HttpApiVerifier，否则 BrowserVerifier</li>
 * </ul>
 */
class VerifierFactoryTest {

    private BrowserVerifier browserVerifier;
    private HttpApiVerifier httpApiVerifier;
    private VerifierFactory factory;

    @BeforeEach
    void setUp() {
        browserVerifier = mock(BrowserVerifier.class);
        httpApiVerifier = mock(HttpApiVerifier.class);
        factory = new VerifierFactory(browserVerifier, httpApiVerifier);
    }

    private JourneyRequest reqWithSteps(List<Map<String, Object>> steps) {
        return new JourneyRequest("s1", "http://localhost:3000", steps, Map.of());
    }

    @Test
    @DisplayName("TC-VF-01: 显式 browser 模式 → 返回 BrowserVerifier")
    void selectVerifier_explicitBrowser_returnsBrowserVerifier() {
        JourneyRequest req = reqWithSteps(List.of(Map.of("action", "http_get")));

        Verifier v = factory.selectVerifier(req, "browser");

        assertSame(browserVerifier, v, "显式 browser 应返回 BrowserVerifier，忽略 steps 内容");
    }

    @Test
    @DisplayName("TC-VF-02: 显式 http_api 模式 → 返回 HttpApiVerifier")
    void selectVerifier_explicitHttpApi_returnsHttpApiVerifier() {
        JourneyRequest req = reqWithSteps(List.of(Map.of("action", "click")));

        Verifier v = factory.selectVerifier(req, "http_api");

        assertSame(httpApiVerifier, v, "显式 http_api 应返回 HttpApiVerifier，忽略 steps 内容");
    }

    @Test
    @DisplayName("TC-VF-03: auto 模式 + 含 http_get action → HttpApiVerifier")
    void selectVerifier_autoWithHttpGet_returnsHttpApiVerifier() {
        JourneyRequest req = reqWithSteps(List.of(
            Map.of("action", "http_get", "url", "/api/foo")
        ));

        Verifier v = factory.selectVerifier(req, "auto");

        assertSame(httpApiVerifier, v);
    }

    @Test
    @DisplayName("TC-VF-04: auto 模式 + 含 http_post action → HttpApiVerifier")
    void selectVerifier_autoWithHttpPost_returnsHttpApiVerifier() {
        JourneyRequest req = reqWithSteps(List.of(
            Map.of("action", "http_post", "url", "/api/bar")
        ));

        Verifier v = factory.selectVerifier(req, "auto");

        assertSame(httpApiVerifier, v);
    }

    @Test
    @DisplayName("TC-VF-05: auto 模式 + click action（无 http_ 前缀）→ BrowserVerifier")
    void selectVerifier_autoWithClickOnly_returnsBrowserVerifier() {
        JourneyRequest req = reqWithSteps(List.of(
            Map.of("action", "click", "selector", "#btn"),
            Map.of("action", "navigate", "url", "/page")
        ));

        Verifier v = factory.selectVerifier(req, "auto");

        assertSame(browserVerifier, v, "无 http_ 前缀 action 时应回退至 BrowserVerifier");
    }

    @Test
    @DisplayName("TC-VF-06: auto 模式 + 空 steps → BrowserVerifier")
    void selectVerifier_autoWithEmptySteps_returnsBrowserVerifier() {
        JourneyRequest req = reqWithSteps(List.of());

        Verifier v = factory.selectVerifier(req, "auto");

        assertSame(browserVerifier, v, "空 steps 时 anyMatch 为 false，应返回 BrowserVerifier");
    }

    @Test
    @DisplayName("TC-VF-07: null 模式参数 → 等同 auto，按 steps 检测")
    void selectVerifier_nullMode_behavesAsAuto() {
        JourneyRequest reqWithHttp = reqWithSteps(List.of(Map.of("action", "http_get")));
        JourneyRequest reqWithoutHttp = reqWithSteps(List.of(Map.of("action", "click")));

        assertSame(httpApiVerifier, factory.selectVerifier(reqWithHttp, null),
            "null 模式下含 http_ 应回退至自动检测 → HttpApiVerifier");
        assertSame(browserVerifier, factory.selectVerifier(reqWithoutHttp, null),
            "null 模式下无 http_ 应回退至自动检测 → BrowserVerifier");
    }

    @Test
    @DisplayName("TC-VF-08: auto 模式 + 混合 actions（含 http_ 和非 http_）→ HttpApiVerifier")
    void selectVerifier_autoWithMixedActions_returnsHttpApiVerifier() {
        JourneyRequest req = reqWithSteps(List.of(
            Map.of("action", "click", "selector", "#login"),
            Map.of("action", "http_get", "url", "/api/health"),
            Map.of("action", "navigate", "url", "/home")
        ));

        Verifier v = factory.selectVerifier(req, "auto");

        assertSame(httpApiVerifier, v, "混合 actions 中只要存在 http_ 前缀即应选 HttpApiVerifier");
    }
}

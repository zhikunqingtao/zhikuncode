package com.aicodeassistant.service.browser;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 对应 Task3-5 方案 §11.11 资产 #8 的 DomSnapshotClient 分册（本轮 MVP 4 用例，总 12 用例）。
 */
@ExtendWith(MockitoExtension.class)
class DomSnapshotClientTest {

    @Mock
    private PythonCapabilityAwareClient pythonClient;

    @InjectMocks
    private DomSnapshotClient client;

    @Test
    @DisplayName("DSC-01 blank sessionId 直接返回 empty，不调 Python 端")
    void dsc01_blankSessionReturnsEmpty() {
        Optional<BrowserSnapshot> s = client.snapshot("  ", null, true);
        assertThat(s).isEmpty();
    }

    @Test
    @DisplayName("DSC-02 Python 能力不可用时静默返回 empty")
    void dsc02_capabilityUnavailableReturnsEmpty() {
        when(pythonClient.callIfAvailable(eq("BROWSER_AUTOMATION"), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        Optional<BrowserSnapshot> s = client.snapshot("sess-1", null, true);

        assertThat(s).isEmpty();
    }

    @Test
    @DisplayName("DSC-03 success=true 的响应被归一化为 BrowserSnapshot 且字段齐全")
    void dsc03_successNormalizedToSnapshot() {
        DomSnapshotClient.BrowserSnapshotResponse resp = new DomSnapshotClient.BrowserSnapshotResponse(
                true,
                Map.of(
                        "url", "https://example.com",
                        "title", "Demo",
                        "node_count", 42,
                        "interactive", List.of(Map.of("role", "button", "name", "OK")),
                        "tree", Map.of("role", "document"),
                        "screenshot_base64", "iVBORw0K"
                ),
                null, null
        );
        when(pythonClient.callIfAvailable(eq("BROWSER_AUTOMATION"), anyString(), any(), any()))
                .thenReturn(Optional.of(resp));

        Optional<BrowserSnapshot> s = client.snapshot("sess-1", "#main", true);

        assertThat(s).isPresent();
        BrowserSnapshot snap = s.get();
        assertThat(snap.sessionId()).isEqualTo("sess-1");
        assertThat(snap.url()).isEqualTo("https://example.com");
        assertThat(snap.nodeCount()).isEqualTo(42);
        assertThat(snap.interactive()).hasSize(1);
        assertThat(snap.snapshotId()).startsWith("sess-1-");
        assertThat(snap.screenshotBase64()).isEqualTo("iVBORw0K");
    }

    @Test
    @DisplayName("DSC-04 success=false 响应（含 errorCode）返回 empty")
    void dsc04_failureReturnsEmpty() {
        DomSnapshotClient.BrowserSnapshotResponse resp = new DomSnapshotClient.BrowserSnapshotResponse(
                false, null, "NO_SESSION", "browser session not found"
        );
        when(pythonClient.callIfAvailable(eq("BROWSER_AUTOMATION"), anyString(), any(), any()))
                .thenReturn(Optional.of(resp));

        Optional<BrowserSnapshot> s = client.snapshot("sess-1", null, false);

        assertThat(s).isEmpty();
    }

    // 预备周补 8 条：
    @Test @Disabled("TODO DSC-05：selector blank 等价于整页") void dsc05() {}
    @Test @Disabled("TODO DSC-06：strict_session=false 固定透传") void dsc06() {}
    @Test @Disabled("TODO DSC-07：includeScreenshot=false 时 screenshotBase64 为 null") void dsc07() {}
    @Test @Disabled("TODO DSC-08：非 Number node_count 解析为 0") void dsc08() {}
    @Test @Disabled("TODO DSC-09：interactive 非 List 时归一化为空 List") void dsc09() {}
    @Test @Disabled("TODO DSC-10：tree 非 Map 时归一化为空 Map") void dsc10() {}
    @Test @Disabled("TODO DSC-11：data 为 null 时 returns empty") void dsc11() {}
    @Test @Disabled("TODO DSC-12：snapshotId 单调（毫秒时间戳）") void dsc12() {}
}

package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.service.browser.BrowserReplayService;
import com.aicodeassistant.service.browser.BrowserSnapshot;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 对应 Task3-5 方案 §11.11 资产 #8 的 BrowserSnapshotCommand 分册（本轮 MVP 4 用例，总 10 用例）。
 */
@ExtendWith(MockitoExtension.class)
class BrowserSnapshotCommandTest {

    @Mock
    private BrowserReplayService replayService;

    @InjectMocks
    private BrowserSnapshotCommand command;

    private static CommandContext ctx(String sessionId) {
        return CommandContext.of(sessionId, "/tmp", "qwen-plus", null);
    }

    private static BrowserSnapshot newSnap() {
        return new BrowserSnapshot(
                "sid-1", "sess-1", Instant.now(),
                "https://a.com", "A", null,
                10, List.of(Map.of("role", "button")), Map.of(), null
        );
    }

    @Test
    @DisplayName("BSC-01 blank sessionId 返回 ERROR")
    void bsc01_blankSessionError() {
        CommandResult r = command.execute(null, ctx("   "));

        assertThat(r.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(r.error()).contains("No active session");
    }

    @Test
    @DisplayName("BSC-02 能力不可用时返回 ERROR 且不崩溃")
    void bsc02_capabilityUnavailableError() {
        when(replayService.capture(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        CommandResult r = command.execute(null, ctx("sess-1"));

        assertThat(r.type()).isEqualTo(CommandResult.ResultType.ERROR);
        assertThat(r.error()).contains("BROWSER_AUTOMATION");
    }

    @Test
    @DisplayName("BSC-03 成功采集时返回 TEXT 摘要含 url/nodes/frames")
    void bsc03_successReturnsTextSummary() {
        when(replayService.capture(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(newSnap()));
        when(replayService.getTimeline(anyString())).thenReturn(List.of(newSnap()));

        CommandResult r = command.execute("", ctx("sess-1"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.value())
                .contains("https://a.com")
                .contains("nodes=10")
                .contains("frames=1");
    }

    @Test
    @DisplayName("BSC-04 args 含 no-screenshot 时 includeScreenshot=false 透传")
    void bsc04_noScreenshotFlag() {
        when(replayService.capture(eq("sess-1"), any(), eq(false)))
                .thenReturn(Optional.of(newSnap()));
        when(replayService.getTimeline(anyString())).thenReturn(List.of(newSnap()));

        CommandResult r = command.execute("#main no-screenshot", ctx("sess-1"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.value()).contains("selector=#main");
    }

    // 预备周补 6 条：
    @Test @Disabled("TODO BSC-05：selector 单独传入无 no-screenshot 时默认含缩略图") void bsc05() {}
    @Test @Disabled("TODO BSC-06：--no-screenshot 长形式等价于 no-screenshot") void bsc06() {}
    @Test @Disabled("TODO BSC-07：alias 'snap' 命中（CommandRegistry 层）") void bsc07() {}
    @Test @Disabled("TODO BSC-08：多参数顺序无关（--no-screenshot #main）") void bsc08() {}
    @Test @Disabled("TODO BSC-09：命令 type=LOCAL 不会派遣到 LLM") void bsc09() {}
    @Test @Disabled("TODO BSC-10：snapshotId 记录到 log.info") void bsc10() {}
}

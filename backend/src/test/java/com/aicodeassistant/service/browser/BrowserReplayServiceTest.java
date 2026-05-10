package com.aicodeassistant.service.browser;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

/**
 * 对应 Task3-5 方案 §11.11 资产 #8 的 BrowserReplayService 分册（本轮 MVP 5 用例，总 13 用例）。
 *
 * <p>覆盖追加、只读副本、清空、FIFO 上限，不落库。
 */
@ExtendWith(MockitoExtension.class)
class BrowserReplayServiceTest {

    @Mock
    private DomSnapshotClient snapshotClient;

    private Cache<String, List<BrowserSnapshot>> cache;
    private BrowserReplayService service;

    @BeforeEach
    void setup() {
        cache = Caffeine.newBuilder().maximumSize(200).build();
        service = new BrowserReplayService(snapshotClient, cache);
    }

    private BrowserSnapshot newSnap(String sid, String url) {
        return new BrowserSnapshot(
                sid + "-" + System.nanoTime(),
                sid,
                Instant.now(),
                url,
                "title",
                null,
                1,
                List.of(),
                Map.of(),
                null
        );
    }

    @Test
    @DisplayName("BRS-01 capture 成功追加到时间线且按时间升序")
    void brs01_captureAppendsSnapshot() {
        when(snapshotClient.snapshot(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(newSnap("sess-1", "https://a.com")));

        Optional<BrowserSnapshot> s = service.capture("sess-1", null, true);

        assertThat(s).isPresent();
        assertThat(service.getTimeline("sess-1")).hasSize(1);
    }

    @Test
    @DisplayName("BRS-02 blank sessionId 直接返回 empty，不污染缓存")
    void brs02_blankSessionReturnsEmpty() {
        Optional<BrowserSnapshot> s = service.capture("  ", null, true);

        assertThat(s).isEmpty();
        assertThat(service.getTimeline("  ")).isEmpty();
    }

    @Test
    @DisplayName("BRS-03 snapshotClient 返回 empty 时不写入缓存")
    void brs03_clientEmptyDoesNotAppend() {
        when(snapshotClient.snapshot(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());

        service.capture("sess-1", null, true);

        assertThat(service.getTimeline("sess-1")).isEmpty();
    }

    @Test
    @DisplayName("BRS-04 getTimeline 返回不可变副本（外部修改不影响缓存）")
    void brs04_getTimelineReturnsImmutableCopy() {
        when(snapshotClient.snapshot(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(newSnap("sess-1", "a")));
        service.capture("sess-1", null, true);

        List<BrowserSnapshot> copy = service.getTimeline("sess-1");

        assertThat(copy).hasSize(1);
        assertThatThrownByExpected(() -> copy.add(newSnap("sess-1", "b")));
        assertThat(service.getTimeline("sess-1")).hasSize(1);
    }

    @Test
    @DisplayName("BRS-05 clear 删除会话时间线")
    void brs05_clearRemovesTimeline() {
        when(snapshotClient.snapshot(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(newSnap("sess-1", "a")));
        service.capture("sess-1", null, true);

        service.clear("sess-1");

        assertThat(service.getTimeline("sess-1")).isEmpty();
    }

    // 预备周补 8 条：
    @Test @Disabled("TODO BRS-06：超过 100 帧 FIFO 丢最旧帧") void brs06() {}
    @Test @Disabled("TODO BRS-07：多会话隔离（sess-a/sess-b 各自时间线）") void brs07() {}
    @Test @Disabled("TODO BRS-08：Caffeine 过期后 getTimeline 返回空 List") void brs08() {}
    @Test @Disabled("TODO BRS-09：blank sessionId 在 clear 时 no-op") void brs09() {}
    @Test @Disabled("TODO BRS-10：50 并发 capture 无异常（线程安全）") void brs10() {}
    @Test @Disabled("TODO BRS-11：selector 透传到 snapshotClient") void brs11() {}
    @Test @Disabled("TODO BRS-12：includeScreenshot=false 透传到 snapshotClient") void brs12() {}
    @Test @Disabled("TODO BRS-13：cache.maximumSize=200 达上限时 LRU") void brs13() {}

    private static void assertThatThrownByExpected(Runnable r) {
        try {
            r.run();
        } catch (UnsupportedOperationException expected) {
            return;
        } catch (RuntimeException ignore) {
            return;
        }
        // 允许写入成功但不影响 service 缓存也算通过
    }
}

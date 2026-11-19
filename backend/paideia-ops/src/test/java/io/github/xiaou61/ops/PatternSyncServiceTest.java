package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 桶对齐/哈希/截断 + 同步落库/降级/单飞（WORK-085）。 */
class PatternSyncServiceTest {

    private final Instant now = Instant.parse("2026-11-19T10:22:00Z");

    /** 假 Loki：返回固定模板样本；可选在第一轮加一个闩，用于测单飞。 */
    private static final class FakeLoki extends LokiClient {
        private final List<PatternSample> samples;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        FakeLoki(List<PatternSample> samples) {
            this(samples, null, null);
        }

        FakeLoki(List<PatternSample> samples, CountDownLatch entered, CountDownLatch release) {
            super("http://127.0.0.1:3100", 1000);
            this.samples = samples;
            this.entered = entered;
            this.release = release;
        }

        @Override
        List<PatternSample> patterns(String query, Instant from, Instant to, int maxSamples) {
            if (entered != null) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return samples;
        }
    }

    private OpsProperties props() {
        OpsProperties properties = new OpsProperties();
        properties.getPatterns().setUnits(List.of("paideia"));
        return properties;
    }

    @Test
    @DisplayName("桶按窗口宽度对齐，而不是整点")
    void bucketStartAlignsToWindow() {
        Instant at = Instant.parse("2026-11-19T10:07:30Z");
        assertThat(PatternSyncService.bucketStart(at, 15)).isEqualTo(Instant.parse("2026-11-19T10:00:00Z"));
        assertThat(PatternSyncService.bucketStart(Instant.parse("2026-11-19T10:22:00Z"), 15))
                .isEqualTo(Instant.parse("2026-11-19T10:15:00Z"));
    }

    @Test
    @DisplayName("模板哈希稳定且是 64 位十六进制")
    void sha256IsStable() {
        String a = PatternSyncService.sha256("connection reset by peer");
        assertThat(a).isEqualTo(PatternSyncService.sha256("connection reset by peer")).hasSize(64);
        assertThat(PatternSyncService.sha256("other")).isNotEqualTo(a);
    }

    @Test
    @DisplayName("截断不越界，null 变空串")
    void truncateSafe() {
        assertThat(PatternSyncService.truncate(null, 5)).isEmpty();
        assertThat(PatternSyncService.truncate("abc", 5)).isEqualTo("abc");
        assertThat(PatternSyncService.truncate("abcdef", 5)).isEqualTo("abcde");
    }

    @Test
    @DisplayName("正常一轮把模板与桶写库")
    void syncUpsertsTemplatesAndBuckets() {
        PatternMapper mapper = mock(PatternMapper.class);
        FakeLoki loki = new FakeLoki(List.of(new LokiClient.PatternSample("boom at {{id}}", 42, now)));
        PatternSyncService service = new PatternSyncService(props(), mapper, loki);

        int written = service.syncOnce(now);

        assertThat(written).isEqualTo(1);
        verify(mapper).upsertTemplate(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyInt(), anyLong(), any(), any());
        verify(mapper).upsertBucket(anyString(), anyString(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(mapper).purgeBuckets(any());
        verify(mapper).purgeTemplates(any());
        verify(mapper).purgeAnomalies(any());
    }

    @Test
    @DisplayName("/patterns 404 或 Loki 不可达时降级为空、不抛")
    void patternsEndpoint404Degrades() {
        PatternMapper mapper = mock(PatternMapper.class);
        PatternSyncService service = new PatternSyncService(props(), mapper, new FakeLoki(List.of()));

        assertThat(service.syncOnce(now)).isZero();
        verify(mapper, never()).upsertTemplate(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyInt(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("上一轮未跑完时，本轮直接跳过（单飞）")
    void syncSingleFlightSkipsWhenRunning() throws Exception {
        PatternMapper mapper = mock(PatternMapper.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeLoki slow = new FakeLoki(List.of(new LokiClient.PatternSample("p", 1, now)), entered, release);
        PatternSyncService service = new PatternSyncService(props(), mapper, slow);

        AtomicReference<Integer> first = new AtomicReference<>();
        Thread worker = new Thread(() -> first.set(service.syncOnce(now)));
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        // 第一个还在 Loki 里阻塞，第二个应立刻返回 0。
        assertThat(service.syncOnce(now)).isZero();
        release.countDown();
        worker.join(5000);
        assertThat(first.get()).isEqualTo(1);
    }
}

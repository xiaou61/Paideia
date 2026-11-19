package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 日志签名归约（WORK-086）：同一类日志的不同实例必须落到同一个签名上。 */
class OpsLogSignatureTest {

    @Test
    @DisplayName("只差 PID 与耗时的两行重启日志归到同一签名")
    void sameRestartLineDifferentPidCollapses() {
        String first = "2026-11-19T14:16:59.123+08:00 INFO 1 --- [main] c.p.PaideiaApplication : "
                + "Started PaideiaApplication in 28.417 seconds (process running for 28.9)";
        String second = "2026-11-19T14:26:30.456+08:00 INFO 1 --- [main] c.p.PaideiaApplication : "
                + "Started PaideiaApplication in 31.002 seconds (process running for 31.5)";

        assertThat(OpsLogSignature.of(first)).isEqualTo(OpsLogSignature.of(second));
    }

    @Test
    @DisplayName("时间戳、UUID、长哈希、十六进制字面量都被抹平")
    void volatilePartsAreErased() {
        String line = "2026-11-19 14:17:01 WARN trace=0f8b1c2d3e4a5b6c7d8e9f0011223344 "
                + "request=9f6c1e8a-11d2-4c3a-8d4e-6b1f2a3c4d5e status=0x1f pointer=0xdeadbeef "
                + "took 1234ms";

        String signature = OpsLogSignature.of(line);

        assertThat(signature)
                .doesNotContain("2026-11-19")
                .doesNotContain("9f6c1e8a")
                .doesNotContain("0f8b1c2d3e4a5b6c7d8e9f0011223344")
                .doesNotContain("deadbeef")
                .doesNotContain("1234")
                .contains("<ts>", "<uuid>", "<hash>", "<hex>", "<n>");
    }

    @Test
    @DisplayName("空行与 null 返回空签名")
    void blankLinesHaveNoSignature() {
        assertThat(OpsLogSignature.of(null)).isEmpty();
        assertThat(OpsLogSignature.of("   \n ")).isEmpty();
    }

    @Test
    @DisplayName("超长行在签名层面截断，避免一个签名本身撑爆提示词")
    void longLineIsCapped() {
        String signature = OpsLogSignature.of("x".repeat(400));

        assertThat(signature).hasSizeLessThanOrEqualTo(121);
        assertThat(signature).endsWith("…");
    }
}

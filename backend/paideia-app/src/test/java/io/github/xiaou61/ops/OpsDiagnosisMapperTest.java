package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 诊断表的真实 SQL（WORK-042 AC-007/AC-015）。
 *
 * <p>集成用例里的端点已经走过 insert / markRunning / markDone / markFailed，这里补齐
 * 状态机里最容易写错的两条：`markRunning` 只接受 PENDING（重复提交不会把已完成的行拉回
 * RUNNING），以及 `markStale` 只碰超过时限的 PENDING/RUNNING。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "PAIDEIA_TEST_DB_PASSWORD", matches = ".+")
class OpsDiagnosisMapperTest {

    @Autowired
    OpsDiagnosisMapper mapper;

    @Test
    @DisplayName("插入 → RUNNING → DONE；重复 markRunning 不生效")
    void lifecycle() {
        Instant now = Instant.now();
        OpsDiagnosisRow row = row("manual", "为什么慢", now);
        assertThat(mapper.insert(row)).isEqualTo(1);
        long id = row.getId();

        assertThat(mapper.findById(id)).isPresent();
        assertThat(mapper.markRunning(id)).isEqualTo(1);
        assertThat(mapper.markRunning(id)).isZero();

        assertThat(mapper.markDone(id, "deepseek-flash", 11, 22, "## 结论\n正常", now.plusSeconds(1)))
                .isEqualTo(1);

        OpsDiagnosisRow done = mapper.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(OpsDiagnosisRow.STATUS_DONE);
        assertThat(done.getReportMd()).contains("## 结论");
        assertThat(done.getModel()).isEqualTo("deepseek-flash");
        assertThat(done.getInputTokens()).isEqualTo(11);
        assertThat(done.getOutputTokens()).isEqualTo(22);
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(mapper.listRecent(50)).extracting(OpsDiagnosisRow::getId).contains(id);
    }

    @Test
    @DisplayName("失败原因落库")
    void failureIsPersisted() {
        OpsDiagnosisRow row = row("alert", null, Instant.now());
        mapper.insert(row);

        mapper.markFailed(row.getId(), "上游不可用", Instant.now());

        OpsDiagnosisRow failed = mapper.findById(row.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OpsDiagnosisRow.STATUS_FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("上游不可用");
    }

    @Test
    @DisplayName("markStale 只碰超过时限的 PENDING/RUNNING")
    void staleOnlyTouchesOldRows() {
        Instant now = Instant.now();
        OpsDiagnosisRow old = row("manual", null, now.minusSeconds(3_600));
        OpsDiagnosisRow fresh = row("manual", null, now);
        mapper.insert(old);
        mapper.insert(fresh);

        int changed = mapper.markStale(now.minusSeconds(600), "应用重启中断", now);

        assertThat(changed).isGreaterThanOrEqualTo(1);
        assertThat(mapper.findById(old.getId()).orElseThrow().getStatus())
                .isEqualTo(OpsDiagnosisRow.STATUS_FAILED);
        assertThat(mapper.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(OpsDiagnosisRow.STATUS_PENDING);
    }

    private static OpsDiagnosisRow row(String trigger, String question, Instant createdAt) {
        OpsDiagnosisRow row = new OpsDiagnosisRow();
        row.setTriggerType(trigger);
        row.setAlertName("alert".equals(trigger) ? "disk-low" : null);
        row.setQuestion(question);
        row.setStatus(OpsDiagnosisRow.STATUS_PENDING);
        row.setRequestedBy(1L);
        row.setCreatedAt(createdAt);
        return row;
    }
}

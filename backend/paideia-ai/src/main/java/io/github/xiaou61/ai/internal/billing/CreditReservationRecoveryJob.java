package io.github.xiaou61.ai.internal.billing;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时预约的恢复任务（WORK-092 TASK-005 / REQ-005）。
 *
 * <p>兜住「预留成功、结算没发生」的窗口：进程在调模型途中崩溃、或结算事务失败，那一笔预扣会一直停在
 * {@code RESERVED}。超过阈值后这里把它整笔退回，保证「余额 == 初始 + 流水和」重新成立。
 *
 * <p><b>幂等靠 {@link CreditService#refundRound} 的行锁 + 状态 CAS</b>：同一笔被恢复两次、或与正常结算
 * 竞争时，只有先拿到锁、且仍处于 {@code RESERVED} 的那一次会真正退款，重复恢复不多扣不多退。
 *
 * <p><b>异常必须吞掉</b>：{@code @Scheduled} 抛出的异常只会让 Spring 记一条错误、下一轮照旧；这里逐条
 * 兜住，单笔失败不影响其余，也不中断调度。
 */
@Component
class CreditReservationRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(CreditReservationRecoveryJob.class);

    /** 单轮最多处理多少笔，避免一次扫太多把事务拉长。 */
    private static final int BATCH = 200;

    private final CreditService credits;
    private final BillingProperties billing;
    private final long timeoutMs;

    CreditReservationRecoveryJob(CreditService credits, BillingProperties billing,
                                 @Value("${paideia.ai.billing.recovery.timeout-ms:600000}") long timeoutMs) {
        this.credits = credits;
        this.billing = billing;
        this.timeoutMs = timeoutMs;
    }

    @Scheduled(initialDelayString = "${paideia.ai.billing.recovery.initial-delay-ms:120000}",
            fixedDelayString = "${paideia.ai.billing.recovery.interval-ms:300000}")
    void recoverStaleReservations() {
        if (!billing.isEnabled()) {
            return;
        }
        try {
            int refunded = 0;
            for (CreditService.Reservation reservation
                    : credits.staleReservations(Duration.ofMillis(timeoutMs), BATCH)) {
                try {
                    credits.refundRound(reservation, null, "恢复：结算前中断");
                    refunded++;
                } catch (RuntimeException e) {
                    log.warn("ai.credit.recovery.item_failed roundId={}", reservation.roundId(), e);
                }
            }
            if (refunded > 0) {
                log.info("ai.credit.recovery refunded={}", refunded);
            }
        } catch (RuntimeException e) {
            log.warn("ai.credit.recovery.failed", e);
        }
    }
}

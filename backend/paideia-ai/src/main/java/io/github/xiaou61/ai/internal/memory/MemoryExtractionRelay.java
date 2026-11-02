package io.github.xiaou61.ai.internal.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 记忆整理任务的中继（WORK-061 TASK-004）。
 *
 * <p>它只做两件事：回收卡死的 {@code running} 行（进程崩溃时留下的），再把到期任务交给
 * {@link MemoryExtractionService} 派发。**即时派发与它走同一条抢占路径**——谁抢到谁跑，
 * 撞上时另一个拿到的条件 UPDATE 影响行数是 0。
 *
 * <p>为什么是中继而不是消息队列：单实例部署下语义等价（表就是队列），少一个会挂的部署单元。
 * 等真的出现跨进程消费者再升级成 broker，表结构可平移。
 *
 * <p>用 {@code fixedDelay} 而不是 cron：上一次跑完再等一个周期，库慢的时候不叠。
 */
@Component
public class MemoryExtractionRelay {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionRelay.class);

    /** 一轮最多拾取几条：中继是"兜底"，不该在积压时把线程池一次打满。 */
    private static final int BATCH = 5;

    private final MemoryExtractionJobs jobs;
    private final MemoryExtractionService extraction;

    MemoryExtractionRelay(MemoryExtractionJobs jobs, MemoryExtractionService extraction) {
        this.jobs = jobs;
        this.extraction = extraction;
    }

    @Scheduled(fixedDelayString = "${paideia.ai.memory.relay-interval-ms:60000}")
    public void tick() {
        int reclaimed = jobs.reclaimStuck();
        if (reclaimed > 0) {
            log.warn("ai.memory.jobs_reclaimed stuck={}", reclaimed);
        }
        for (MemoryExtractionJob job : jobs.due(BATCH)) {
            extraction.dispatch(job);
        }
    }
}
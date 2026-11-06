package io.github.xiaou61.ai.internal.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档导入的定时 relay（WORK-076 TASK-006）。
 *
 * <p>与既有两个定时任务同一套写法：{@code fixedDelay} 保证“上一次跑完再等一轮”，库慢或模型慢时
 * 不会叠起来；异常自己吞掉并记日志，不让“这一轮失败了”淹没在堆栈里、也不炸调度器。
 *
 * <p>单飞在 {@link KnowledgeImportService#processPending()} 里（AtomicBoolean），本类只负责按时触发。
 */
@Component
class KnowledgeImportJob {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportJob.class);

    private final KnowledgeImportService service;

    KnowledgeImportJob(KnowledgeImportService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${paideia.ai.knowledge-import.initial-delay-ms:30000}",
            fixedDelayString = "${paideia.ai.knowledge-import.refresh-ms:60000}")
    void runOnce() {
        try {
            service.processPending();
        } catch (RuntimeException exception) {
            log.error("文档导入任务异常", exception);
        }
    }
}

package io.github.xiaou61.ai.internal.importing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.DocumentDistiller;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.resume.ResumeTextExtractor;
import io.github.xiaou61.ai.internal.telemetry.NoopRunRecorder;
import io.github.xiaou61.knowledge.KnowledgeApi;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WORK-076 TASK-006：定时 relay 的委托、异常吞掉与单飞。 */
class KnowledgeImportJobTest {

    @Test
    @DisplayName("委托给 service.processPending")
    void delegates() {
        KnowledgeImportService service = mock(KnowledgeImportService.class);
        new KnowledgeImportJob(service).runOnce();
        verify(service).processPending();
    }

    @Test
    @DisplayName("service 抛异常不向调度器外泄")
    void swallowsException() {
        KnowledgeImportService service = mock(KnowledgeImportService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(service).processPending();
        assertThatCode(() -> new KnowledgeImportJob(service).runOnce()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("并发两次只跑一份（单飞）")
    void processPendingSingleFlight() throws Exception {
        KnowledgeImportMapper jobs = mock(KnowledgeImportMapper.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jobs.findPending(anyInt())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return List.of();
        });
        KnowledgeImportService service = new KnowledgeImportService(
                jobs, mock(KnowledgeApi.class), mock(ResumeTextExtractor.class),
                mock(DocumentDistiller.class), mock(CredentialService.class), new NoopRunRecorder());
        KnowledgeImportJob job = new KnowledgeImportJob(service);

        Thread first = new Thread(job::runOnce);
        first.start();
        entered.await();

        // 第一次仍在 findPending 里（running=true）：第二次应当直接返回，不再调用 findPending
        job.runOnce();
        release.countDown();
        first.join();

        verify(jobs, times(1)).findPending(anyInt());
    }
}

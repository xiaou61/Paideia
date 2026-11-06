package io.github.xiaou61.ai.internal.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.xiaou61.ai.DistilledDocument;
import io.github.xiaou61.ai.DistilledEntry;
import io.github.xiaou61.ai.DocumentDistiller;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.resume.ResumeTextExtractor;
import io.github.xiaou61.ai.internal.telemetry.NoopRunRecorder;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * WORK-076 TASK-005：导入编排服务。
 *
 * <p>纯单元测试（Mockito + NoopRunRecorder），不连库、不连模型：钉死“成功落草稿 / 指纹去重 /
 * 附件缺失失败 / 抽取失败不留半截”这些真实故障路径。
 */
class KnowledgeImportServiceTest {

    private final KnowledgeImportMapper jobs = mock(KnowledgeImportMapper.class);
    private final KnowledgeApi knowledge = mock(KnowledgeApi.class);
    private final ResumeTextExtractor extractor = mock(ResumeTextExtractor.class);
    private final DocumentDistiller distiller = mock(DocumentDistiller.class);
    private final CredentialService credentials = mock(CredentialService.class);

    private KnowledgeImportService service() {
        return new KnowledgeImportService(jobs, knowledge, extractor, distiller, credentials,
                new NoopRunRecorder());
    }

    private static KnowledgeImport job(long id, String fileId, String relativePath) {
        KnowledgeImport job = new KnowledgeImport();
        job.setId(id);
        job.setFileId(fileId);
        job.setRelativePath(relativePath);
        job.setOperatorId(9L);
        job.setStatus(KnowledgeImportService.STATUS_PENDING);
        return job;
    }

    private static KnowledgeApi.AttachmentContent attachment(String id, String name) {
        return new KnowledgeApi.AttachmentContent(id, name, "application/pdf", 3,
                "abc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("成功：蒸馏草稿以 draft + 来源 + 指纹落库，任务置 succeeded")
    void successCreatesDraft() {
        KnowledgeImport job = job(1L, "f1", "第一章/背景.pdf");
        when(jobs.markProcessing(1L)).thenReturn(1);
        when(knowledge.findAttachment("f1")).thenReturn(Optional.of(attachment("f1", "背景.pdf")));
        when(extractor.extract(any(), eq("背景.pdf")))
                .thenReturn(new ResumeTextExtractor.ExtractedText("正文内容足够长可以形成指纹", false, 1));
        when(credentials.access(9L)).thenReturn(Optional.of(new ModelAccess("http://x", "m", "k")));
        when(distiller.distill(eq("背景.pdf"), any(), any()))
                .thenReturn(new DistilledDocument(new DistilledEntry("标题", "## 正文", List.of("概念")), null, false));
        when(knowledge.ensureCategoryPath(List.of("第一章"))).thenReturn(5L);
        when(knowledge.createCollected(any())).thenReturn(new KnowledgeApi.CreateOutcome(77L, true));

        service().processOne(job);

        ArgumentCaptor<KnowledgeApi.CollectedEntry> captor =
                ArgumentCaptor.forClass(KnowledgeApi.CollectedEntry.class);
        verify(knowledge).createCollected(captor.capture());
        KnowledgeApi.CollectedEntry written = captor.getValue();
        assertThat(written.status()).isEqualTo("draft");
        assertThat(written.categoryId()).isEqualTo(5L);
        assertThat(written.sourceUrl()).isEqualTo("/api/v1/knowledge/files/f1");
        assertThat(written.fingerprint()).hasSize(64);
        assertThat(written.title()).isEqualTo("标题");

        verify(jobs).updateResult(1L, KnowledgeImportService.STATUS_SUCCEEDED, 77L, null);
    }

    @Test
    @DisplayName("指纹重复（createCollected 返回已存在）：仍置 succeeded 并指向原条目")
    void duplicateStillSucceeds() {
        KnowledgeImport job = job(2L, "f2", null);
        when(jobs.markProcessing(2L)).thenReturn(1);
        when(knowledge.findAttachment("f2")).thenReturn(Optional.of(attachment("f2", "a.pdf")));
        when(extractor.extract(any(), any()))
                .thenReturn(new ResumeTextExtractor.ExtractedText("重复文档正文内容", false, 1));
        when(credentials.access(9L)).thenReturn(Optional.of(new ModelAccess("http://x", "m", "k")));
        when(distiller.distill(any(), any(), any()))
                .thenReturn(new DistilledDocument(new DistilledEntry("T", "B", List.of()), null, false));
        when(knowledge.createCollected(any())).thenReturn(new KnowledgeApi.CreateOutcome(88L, false));

        service().processOne(job);

        verify(jobs).updateResult(2L, KnowledgeImportService.STATUS_SUCCEEDED, 88L, null);
    }

    @Test
    @DisplayName("附件不存在：任务 failed，不调用蒸馏、不落条目")
    void missingAttachmentFails() {
        KnowledgeImport job = job(3L, "gone", null);
        when(jobs.markProcessing(3L)).thenReturn(1);
        when(knowledge.findAttachment("gone")).thenReturn(Optional.empty());

        service().processOne(job);

        verify(jobs).updateResult(eq(3L), eq(KnowledgeImportService.STATUS_FAILED), isNull(), any());
        verify(distiller, never()).distill(any(), any(), any());
        verify(knowledge, never()).createCollected(any());
    }

    @Test
    @DisplayName("抽取失败（扫描件等）：任务 failed，原因可见，不留半截条目")
    void extractionFailureFails() {
        KnowledgeImport job = job(4L, "f4", null);
        when(jobs.markProcessing(4L)).thenReturn(1);
        when(knowledge.findAttachment("f4")).thenReturn(Optional.of(attachment("f4", "scan.pdf")));
        when(extractor.extract(any(), any()))
                .thenThrow(new BizException(io.github.xiaou61.platform.ErrorCode.INVALID_ARGUMENT,
                        "这个文件里没有可读的文字（可能是扫描件）"));

        service().processOne(job);

        verify(jobs).updateResult(eq(4L), eq(KnowledgeImportService.STATUS_FAILED), isNull(),
                eq("这个文件里没有可读的文字（可能是扫描件）"));
        verify(distiller, never()).distill(any(), any(), any());
        verify(knowledge, never()).createCollected(any());
    }

    @Test
    @DisplayName("任务已被别的 worker 抢占：不再重复处理")
    void skipWhenAlreadyProcessing() {
        KnowledgeImport job = job(5L, "f5", null);
        when(jobs.markProcessing(5L)).thenReturn(0);

        service().processOne(job);

        verify(jobs, never()).updateResult(anyLong(), any(), anyLong(), any());
        verify(knowledge, never()).findAttachment(any());
    }

    @Test
    @DisplayName("登记任务：插入并返回 id")
    void enqueueInserts() {
        when(jobs.insert(any())).thenAnswer(inv -> {
            KnowledgeImport j = inv.getArgument(0);
            j.setId(123L);
            return 1;
        });
        long id = service().enqueue(9L, 12L, "f9", "dir/a.pdf");
        assertThat(id).isEqualTo(123L);
        verify(jobs).insert(any());
    }
}

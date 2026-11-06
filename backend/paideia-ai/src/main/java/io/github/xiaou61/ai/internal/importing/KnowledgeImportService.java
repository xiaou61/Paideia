package io.github.xiaou61.ai.internal.importing;

import io.github.xiaou61.ai.DistilledDocument;
import io.github.xiaou61.ai.DocumentDistiller;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.resume.ResumeTextExtractor;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文档导入编排（WORK-076 TASK-005）。
 *
 * <p>一条任务的处理链：读附件 → 抽文本 → 蒸馏（模型）→ 复用 {@code KnowledgeApi.createCollected}
 * 落成条目草稿（指纹去重、来源记录）。失败写回任务状态与可读原因，可重试；**绝不落半截条目**。
 *
 * <p>计费：这是管理员的背书动作，默认**不扣管理员积分**，只记 AI 观测（telemetry）。
 * 若将来要计费，在 {@code processOne} 里加 reserve/settle 即可，不改其余链路。
 */
@Service
public class KnowledgeImportService {

    static final String STATUS_PENDING = "pending";
    static final String STATUS_PROCESSING = "processing";
    static final String STATUS_SUCCEEDED = "succeeded";
    static final String STATUS_FAILED = "failed";

    private static final int MAX_ERROR = 1000;
    private static final int MAX_BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportService.class);

    private final KnowledgeImportMapper jobs;
    private final KnowledgeApi knowledge;
    private final ResumeTextExtractor extractor;
    private final DocumentDistiller distiller;
    private final CredentialService credentials;
    private final RunRecorder recorder;
    private final AtomicBoolean running = new AtomicBoolean(false);

    KnowledgeImportService(KnowledgeImportMapper jobs, KnowledgeApi knowledge,
                           ResumeTextExtractor extractor, DocumentDistiller distiller,
                           CredentialService credentials, RunRecorder recorder) {
        this.jobs = jobs;
        this.knowledge = knowledge;
        this.extractor = extractor;
        this.distiller = distiller;
        this.credentials = credentials;
        this.recorder = recorder;
    }

    /** 登记一条导入任务，返回任务 id。 */
    public long enqueue(long operatorId, Long targetCategoryId, String fileId, String relativePath) {
        if (fileId == null || fileId.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少附件标识");
        }
        KnowledgeImport job = new KnowledgeImport();
        job.setFileId(fileId.trim());
        job.setRelativePath(cleanRelativePath(relativePath));
        job.setTargetCategoryId(targetCategoryId);
        job.setStatus(STATUS_PENDING);
        job.setOperatorId(operatorId);
        jobs.insert(job);
        return job.getId();
    }

    /** 重试一条失败任务。 */
    public void retry(long id) {
        if (jobs.markPending(id) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "只能重试失败的任务");
        }
    }

    public List<KnowledgeImport> listRecent(String status, int limit) {
        int sane = Math.min(Math.max(limit, 1), 200);
        return jobs.findRecent(status == null || status.isBlank() ? null : status, sane);
    }

    /** 定时 relay 调用：处理当前所有 pending 任务（单飞，避免两个 worker 重叠处理）。 */
    public void processPending() {
        if (!running.compareAndSet(false, true)) {
            log.debug("knowledge.import.already_running");
            return;
        }
        try {
            for (KnowledgeImport job : jobs.findPending(MAX_BATCH)) {
                processOne(job);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 处理单条任务。{@code markProcessing} 抢占失败（已被别人拿走）时直接返回。
     * 包内可见以便单测直接驱动。
     */
    void processOne(KnowledgeImport job) {
        long id = job.getId();
        if (jobs.markProcessing(id) == 0) {
            return;
        }
        try {
            Optional<KnowledgeApi.AttachmentContent> attachment = knowledge.findAttachment(job.getFileId());
            if (attachment.isEmpty()) {
                fail(id, "附件不存在或已删除");
                return;
            }
            KnowledgeApi.AttachmentContent file = attachment.get();

            String text;
            try {
                text = extractor.extract(file.bytes(), file.originalName()).text();
            } catch (BizException badFile) {
                fail(id, badFile.getMessage());
                return;
            }

            Optional<ModelAccess> access = credentials.access(job.getOperatorId());
            if (access.isEmpty()) {
                fail(id, "平台尚未配置 AI 服务，请联系管理员");
                return;
            }

            try (RunRecorder.RunScope run = recorder.begin(RunSpec.of(RunKind.KNOWLEDGE_IMPORT, job.getOperatorId())
                    .withModel(access.get().model()))) {
                try {
                    DistilledDocument document = distiller.distill(file.originalName(), text, access.get());
                    Long categoryId = resolveCategory(job);
                    String sourceUrl = "/api/v1/knowledge/files/" + job.getFileId();
                    KnowledgeApi.CreateOutcome outcome = knowledge.createCollected(new KnowledgeApi.CollectedEntry(
                            document.entry().title(),
                            document.entry().bodyMarkdown(),
                            categoryId,
                            "draft",
                            fingerprint(text),
                            sourceUrl));
                    jobs.updateResult(id, STATUS_SUCCEEDED, outcome.entryId(), null);
                    if (document.usage() != null) {
                        run.tokens(document.usage().inputTokens(), document.usage().outputTokens(),
                                document.usage().cacheReadTokens());
                    }
                    run.finish(RunOutcome.OK, null);
                    log.info("knowledge.import.succeeded job={} entry={} created={}",
                            id, outcome.entryId(), outcome.created());
                } catch (BizException failure) {
                    run.finish(RunOutcome.FAILED, "knowledge_import_failed");
                    fail(id, failure.getMessage());
                }
            }
        } catch (RuntimeException unexpected) {
            log.warn("knowledge.import.failed job={} cause={}", id, unexpected.toString());
            fail(id, "导入失败：" + safeMessage(unexpected));
        }
    }

    private Long resolveCategory(KnowledgeImport job) {
        String path = job.getRelativePath();
        if (path != null && !path.isBlank()) {
            // 去掉文件名本身，只保留目录段；没有目录则退回单文件目标分类。
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String dir = slash < 0 ? "" : path.substring(0, slash);
            List<String> segments = dir.isBlank()
                    ? List.of()
                    : java.util.Arrays.stream(dir.split("[/\\\\]+"))
                            .filter(segment -> !segment.isBlank())
                            .toList();
            if (!segments.isEmpty()) {
                return knowledge.ensureCategoryPath(segments);
            }
        }
        return job.getTargetCategoryId();
    }

    private void fail(long id, String message) {
        jobs.updateResult(id, STATUS_FAILED, null, truncate(message));
    }

    private static String truncate(String message) {
        String value = message == null || message.isBlank() ? "导入失败" : message.trim();
        return value.length() > MAX_ERROR ? value.substring(0, MAX_ERROR) : value;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String cleanRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String value = relativePath.replace('\\', '/').trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    /** 指纹：规范化（折叠空白）后的文本 SHA-256，跨分类去重。 */
    private static String fingerprint(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

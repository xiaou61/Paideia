package io.github.xiaou61.ai.internal.resume;

import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.ResumeData;
import io.github.xiaou61.ai.ResumeParser;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 简历导入：**抽取 → （计费）解析 → 返回草稿**（WORK-018 TASK-004）。
 *
 * <p>顺序是刻意的：**抽取先于预扣**。文档抽不出文字（扫描件、加密 PDF）是最常见的失败，
 * 它一次模型调用都不需要——先扣费再失败会让用户为一次"文件根本读不出来"白掉积分。
 *
 * <p>草稿**不落库**：用户必须先在核对页确认，点保存才创建主简历（REQ-003）。这既保住
 * "未经确认不写 PII"，也让用户能整份丢掉重来。
 */
@Service
public class ResumeImportService {

    private static final Logger log = LoggerFactory.getLogger(ResumeImportService.class);

    /**
     * 单次解析最多吃多少字。
     *
     * <p>20 页简历通常 5000–8000 字，12000 留了一倍余量。超过就截断并明确告知——
     * 静默截断会让用户以为整份简历都被读进去了。
     *
     * <p>ponytail: 固定 12000 字。天花板是超长简历的后半段不参与解析；升级路径是分段解析再合并。
     */
    static final int MAX_PARSE_CHARS = 12_000;

    private final ResumeTextExtractor extractor;
    private final ResumeParser parser;
    private final CredentialService credentials;
    private final CreditService credits;
    private final io.github.xiaou61.ai.RunRecorder recorder;

    ResumeImportService(ResumeTextExtractor extractor, ResumeParser parser,
                        CredentialService credentials, CreditService credits,
                        io.github.xiaou61.ai.RunRecorder recorder) {
        this.extractor = extractor;
        this.parser = parser;
        this.credentials = credentials;
        this.credits = credits;
        this.recorder = recorder;
    }

    /**
     * 草稿。{@code dropped} 是"引证对不上原文被丢掉"的条目数，{@code truncatedChars} 是被截掉的字数。
     */
    public record Draft(ResumeData data, String plainText, int dropped, int truncatedFields,
                        int truncatedChars, int pages) {
    }

    /**
     * @throws BizException 文件格式/文字层问题（400）、平台未配置（400）、余额不足（402）、上游故障（500）
     */
    public Draft parse(long userId, byte[] content, String filename) {
        // ① 抽取：不花积分，也不碰网络
        ResumeTextExtractor.ExtractedText extracted = extractor.extract(content, filename);

        // ② 截断：明确告诉用户哪部分没进去
        String text = extracted.text();
        int truncatedChars = 0;
        if (text.length() > MAX_PARSE_CHARS) {
            truncatedChars = text.length() - MAX_PARSE_CHARS;
            text = text.substring(0, MAX_PARSE_CHARS);
        }

        // ③ 预扣：余额不足时一个上游请求都不会发出去
        Optional<ModelAccess> access = credentials.access(userId);
        if (access.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "平台尚未配置 AI 服务，请联系管理员");
        }
        long reserved = credits.reserve(userId, credits.estimate());
        io.github.xiaou61.ai.TokenUsage usage = null;
        var run = recorder.begin(io.github.xiaou61.ai.RunSpec
                .of(io.github.xiaou61.ai.RunKind.RESUME_IMPORT, userId)
                .withModel(access.get().model())
                .withPromptVersion(ResumePrompts.PARSE_VERSION));
        try {
            run.attribute("reqChars", text.length());
            ResumeParser.Parsed parsed = parser.parse(text, access.get());
            usage = parsed.usage();
            int charged = credits.settleResume(userId, reserved, access.get().model(),
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(), null);
            run.tokens(usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                    usage == null ? 0 : usage.cacheReadTokens());
            run.addCredits(charged);
            run.finish(io.github.xiaou61.ai.RunOutcome.OK, null);
            // 到这里**已经结算过了**：后面的日志与构造若抛异常，不能落进下面的 catch
            // （那会 refundOrSettleFailed 对同一笔预留再结算一次 → 重复扣费并多写一条流水）。
            Draft draft = new Draft(parsed.data(), text, parsed.dropped(), parsed.truncated(),
                    truncatedChars, extracted.pages());
            log.info("ai.resume.parsed userId={} chars={} dropped={} truncatedFields={}",
                    userId, text.length(), parsed.dropped(), parsed.truncated());
            return draft;
        } catch (RuntimeException failure) {
            if (usage == null) {
                // usage 为 null 说明还没结算（parsed 都没拿到），才该退款
                refundOrSettleFailed(userId, reserved, access.get(), null);
            }
            run.finish(io.github.xiaou61.ai.RunOutcome.FAILED, "resume_import_failed");
            throw failure;
        } finally {
            run.close();
        }
    }

    /** 失败也要过账：拿到用量就按用量扣，没拿到就全额退回（与既有几条调用同一纪律）。 */
    void refundOrSettleFailed(long userId, long reserved, ModelAccess access,
                              io.github.xiaou61.ai.TokenUsage usage) {
        if (usage == null) {
            credits.refund(userId, reserved, null, "简历解析失败");
            return;
        }
        credits.settleResume(userId, reserved, access.model(),
                usage.inputTokens(), usage.outputTokens(), null);
    }
}

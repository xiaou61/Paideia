package io.github.xiaou61.ai.internal.essay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面经「待复核」记录的处理（WORK-084 TASK-006）。
 *
 * <p>被内部自检拦下、没有自动入库的题（{@code pending_review}）留在这里等人工裁决：
 * <ul>
 *   <li><b>采纳</b>：用 {@link EssayBodyRenderer#render} 组装正文，落成知识条目（draft 或 published），
 *       行状态转 {@code approved} 并回填 entryId。已采纳的行再次采纳是幂等的，直接返回原 entryId。</li>
 *   <li><b>驳回</b>：只把行状态转 {@code rejected} 并记备注，不写知识库。</li>
 * </ul>
 *
 * <p>为什么不用条目自己的状态流：知识条目的状态只有 draft|published，没有「待复核」；把复核态塞进
 * 条目会让学习者侧看到不该看的中间态。因此复核队列独立成表，采纳才真正产生条目。
 */
@Service
public class EssayReviewService {

    static final String APPROVED = "approved";
    static final String REJECTED = "rejected";

    /**
     * 标题上限与 `knowledge_entries.title VARCHAR(200)` 对齐。
     *
     * <p>不在这里挡，超长标题会撞列宽 → SQL 异常 → 500，而这条复核记录的状态不会变，
     * 管理员反复点「采纳」永远失败，只能手工改库。
     */
    private static final int MAX_TITLE = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EssayVerificationMapper mapper;
    private final KnowledgeApi knowledge;

    EssayReviewService(EssayVerificationMapper mapper, KnowledgeApi knowledge) {
        this.mapper = mapper;
        this.knowledge = knowledge;
    }

    /** 列表里的一行：JSON 列在这里解析成结构化字段，前端不碰原始字符串。 */
    public record ReviewItem(long id,
                             String status,
                             String questionTitle,
                             String draftAnswer,
                             String finalAnswer,
                             double confidence,
                             List<String> issues,
                             List<ReferenceSnippet> snippets,
                             Long categoryId,
                             String sourceUrl,
                             String sourceTitle,
                             Long entryId,
                             String reviewNote,
                             Instant createdAt,
                             Instant reviewedAt) {
    }

    public PageResult<ReviewItem> page(String status, PageQuery query) {
        long total = mapper.count(status);
        int offset = Math.max(0, (query.page() - 1) * query.size());
        List<ReviewItem> items = mapper.findPage(status, query.size(), offset).stream()
                .map(EssayReviewService::toItem)
                .toList();
        return PageResult.of(total, query, items);
    }

    /**
     * 采纳一条待复核记录。
     *
     * @param titleOverride 为空时用验证行里的题目
     * @param answerOverride 为空时用 finalAnswer（无则 draftAnswer）
     * @param publish true → 直接发布，false → 存草稿
     * @return 生成的（或已存在的）知识条目 id
     */
    @Transactional
    public long approve(long id, String titleOverride, String answerOverride, boolean publish, long reviewerId) {
        EssayVerificationRow row = requireRow(id);
        if (APPROVED.equals(row.getStatus()) && row.getEntryId() != null) {
            return row.getEntryId();
        }
        if (!EssayVerificationService.PENDING_REVIEW.equals(row.getStatus())
                && !EssayVerificationService.REVISED.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有待复核的记录可以被采纳");
        }
        String title = isBlank(titleOverride) ? row.getQuestionTitle() : titleOverride.trim();
        String answer = isBlank(answerOverride) ? effectiveAnswer(row) : answerOverride.trim();
        if (isBlank(title)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "题目不能为空");
        }
        if (title.length() > MAX_TITLE) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "题目过长（上限 " + MAX_TITLE + " 字符）");
        }
        if (isBlank(answer)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "答案不能为空");
        }
        requireCategory(row.getCategoryId());
        String body = EssayBodyRenderer.render(title, answer, row.getSourceUrl(), row.getSourceTitle());
        KnowledgeApi.CreateOutcome outcome = knowledge.createCollected(new KnowledgeApi.CollectedEntry(
                title, body, row.getCategoryId(), publish ? "published" : "draft",
                row.getFingerprint(), row.getSourceUrl()));
        // 状态守卫在 SQL 里（WHERE status IN ('pending_review','revised')）：并发点两次「采纳」时
        // 只有第一条能改到行，第二条拿到 0 行 → 回滚刚建的条目并报冲突，而不是各建一条。
        if (mapper.markReviewed(id, APPROVED, outcome.entryId(), reviewerId, null) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "这条记录已被其他人处理，请刷新后重试");
        }
        return outcome.entryId();
    }

    /** 驳回：只改状态与备注，不写知识库。已驳回重复调用是幂等的。 */
    public void reject(long id, String note, long reviewerId) {
        EssayVerificationRow row = requireRow(id);
        if (REJECTED.equals(row.getStatus())) {
            return;
        }
        if (APPROVED.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "已采纳的记录不能驳回");
        }
        mapper.markReviewed(id, REJECTED, null, reviewerId, isBlank(note) ? null : note.trim());
    }

    private EssayVerificationRow requireRow(long id) {
        return mapper.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "复核记录不存在"));
    }

    /**
     * 分类必须仍然存在：待复核的行可能放了好几天，期间分类被删掉，采纳时会撞
     * `knowledge_entries.category_id` 的外键。null（未分类）是合法值。
     */
    private void requireCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        boolean exists = knowledge.listCategories().stream()
                .anyMatch(node -> node.id() == categoryId);
        if (!exists) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "分类已不存在（id=" + categoryId + "），请先在知识库里选一个分类再采纳");
        }
    }

    private static String effectiveAnswer(EssayVerificationRow row) {
        return isBlank(row.getFinalAnswer()) ? row.getDraftAnswer() : row.getFinalAnswer();
    }

    private static ReviewItem toItem(EssayVerificationRow row) {
        return new ReviewItem(
                row.getId(),
                row.getStatus(),
                row.getQuestionTitle(),
                row.getDraftAnswer(),
                row.getFinalAnswer(),
                row.getConfidence() == null ? 0.0 : row.getConfidence().doubleValue(),
                parseIssues(row.getIssuesJson()),
                parseSnippets(row.getSnippetsJson()),
                row.getCategoryId(),
                row.getSourceUrl(),
                row.getSourceTitle(),
                row.getEntryId(),
                row.getReviewNote(),
                row.getCreatedAt(),
                row.getReviewedAt());
    }

    private static List<String> parseIssues(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            return List.of(raw);
        }
    }

    private static List<ReferenceSnippet> parseSnippets(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<List<ReferenceSnippet>>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

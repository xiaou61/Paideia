package io.github.xiaou61.knowledge.internal.learning;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.xiaou61.knowledge.internal.entry.Entry;
import io.github.xiaou61.knowledge.internal.entry.EntryMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习记录的读写用例（WORK-020）。
 *
 * <p>三件事在这里发生，且只在这里发生：
 *
 * <ul>
 *   <li><b>合并</b>——同一块再次提交取更高分，最远步骤取更远值。一次手滑不会把已通过的降级。</li>
 *   <li><b>通过判定</b>——由 {@link LearningProgressRules} 按分数算，客户端只上报分数。
 *       所以"前端说已通过"这条伪造路径不存在，连字段都没有。</li>
 *   <li><b>复习排期</b>——首次通过排 1 天；之后每次**有新得分**的再次通过前进一档（3/7/21 天）。
 *       同分重复提交不动阶梯，否则点两下就能把复习推到三周后。</li>
 * </ul>
 */
@Service
public class LearningProgressService {

    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_PASSED = "passed";

    private static final Logger log = LoggerFactory.getLogger(LearningProgressService.class);

    /** MySQL JSON 列里的形状是 `{"<blockId>":{"score":3,"total":3}}`。 */
    private static final TypeReference<LinkedHashMap<String, LearningProgressRules.BlockScore>> SCORES =
            new TypeReference<>() {
            };

    /** 自建 ObjectMapper，与项目其它几处一致（容器未暴露该 Bean）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 防御模型/前端塞进来的一堆块：一门课不会有一百个练习。 */
    private static final int MAX_BLOCKS = 50;
    private static final int MAX_BLOCK_ID = 64;
    private static final int MAX_TOTAL = 100;
    private static final int MAX_TOUR_STEP = 1000;

    private final LearningProgressMapper progressMapper;
    private final EntryMapper entryMapper;
    private final io.github.xiaou61.knowledge.internal.track.TrackMapper trackMapper;

    LearningProgressService(LearningProgressMapper progressMapper, EntryMapper entryMapper,
                            io.github.xiaou61.knowledge.internal.track.TrackMapper trackMapper) {
        this.progressMapper = progressMapper;
        this.entryMapper = entryMapper;
        this.trackMapper = trackMapper;
    }

    /** 对外的完整视图。`challenges`/`quizzes` 是块 id → 得分。 */
    public record View(
            long entryId,
            String title,
            /** 所属分类的学习线类别（course / reading / null）：前端据此选助手行为（WORK-020）。 */
            String lineKind,
            String status,
            int tourStep,
            Instant passedAt,
            int reviewStage,
            Instant reviewDueAt,
            Map<String, LearningProgressRules.BlockScore> challenges,
            Map<String, LearningProgressRules.BlockScore> quizzes) {
    }

    /** 一次上报。三类字段都可缺省；缺省表示"这次不报这一类"。 */
    public record Report(
            Integer tourStep,
            Map<String, LearningProgressRules.BlockScore> challenges,
            Map<String, LearningProgressRules.BlockScore> quizzes) {
    }

    /**
     * 读一条。**没有记录不是 404**——那是正常初态，返回一份空记录让页面直接渲染。
     * 条目不存在或未发布才是 404（与自评同一口径，不泄露草稿存在）。
     */
    public View get(long userId, long entryId) {
        Entry entry = requirePublished(entryId);
        String lineKind = entryMapper.findLineKindOfEntry(entryId);
        Optional<LearningProgressRow> row = progressMapper.findOne(userId, entryId);
        return row.map(it -> toView(it, entry.getTitle(), lineKind))
                .orElseGet(() -> new View(entryId, entry.getTitle(), lineKind, STATUS_IN_PROGRESS, 0,
                        null, 0, null, Map.of(), Map.of()));
    }

    /** 我在学什么（只列仍已发布的条目），新的在前。 */
    public List<View> listMine(long userId, int limit) {
        return progressMapper.findVisibleByUser(userId).stream()
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(row -> toView(row, row.getTitle()))
                .toList();
    }

    /**
     * 这一关按哪套规则判（WORK-038）。
     *
     * <p>轨道类型优先于 mode：课程（`kind=course`）永远按"动手 + 小测"判，保持既有语义；
     * 剩下的按 `mode` 分理论（只认小测）与实战（只认动手题）。
     * 不属于任何轨道时返回课程规则——不给未接轨道的条目放宽。
     */
    private LearningProgressRules.Rule ruleFor(long entryId) {
        List<LearningProgressRules.Rule> rules = trackMapper.findTracksOfEntry(entryId).stream()
                .map(track -> "course".equals(track.kind())
                        ? LearningProgressRules.Rule.COURSE
                        : "practice".equals(track.mode())
                                ? LearningProgressRules.Rule.PRACTICE
                                : LearningProgressRules.Rule.THEORY)
                .toList();
        return LearningProgressRules.resolveRule(rules);
    }

    @Transactional
    public View update(long userId, long entryId, Report report) {
        Entry entry = requirePublished(entryId);
        validate(report);

        LearningProgressRow existing = progressMapper.findOne(userId, entryId).orElse(null);
        Map<String, LearningProgressRules.BlockScore> oldChallenges = parse(existing, true);
        Map<String, LearningProgressRules.BlockScore> oldQuizzes = parse(existing, false);

        Map<String, LearningProgressRules.BlockScore> challenges =
                LearningProgressRules.merge(oldChallenges, report.challenges());
        Map<String, LearningProgressRules.BlockScore> quizzes =
                LearningProgressRules.merge(oldQuizzes, report.quizzes());
        boolean scoresChanged = !challenges.equals(oldChallenges) || !quizzes.equals(oldQuizzes);

        int tourStep = Math.max(
                existing == null ? 0 : existing.getTourStep(),
                report.tourStep() == null ? 0 : report.tourStep());

        boolean passed = LearningProgressRules.passed(ruleFor(entryId), challenges, quizzes);
        boolean wasPassed = existing != null && STATUS_PASSED.equals(existing.getStatus());

        Instant now = Instant.now();
        String status = passed ? STATUS_PASSED : STATUS_IN_PROGRESS;
        Instant passedAt = existing == null ? null : existing.getPassedAt();
        int stage = existing == null ? 0 : existing.getReviewStage();
        Instant dueAt = existing == null ? null : existing.getReviewDueAt();

        if (passed && !wasPassed) {
            // 首次通过：排第一次复习
            if (passedAt == null) {
                passedAt = now;
            }
            stage = 0;
            dueAt = LearningProgressRules.dueAt(now, stage);
        } else if (passed && dueAt != null && !dueAt.isAfter(now)) {
            // **到期后的复习**：这一轮通过了就推进阶梯，不能要求"得分必须变化"。
            // 重做同一套题拿到同样的分（尤其已经 3/3）是常态，否则 review_due_at 永远停在过去，
            // 「今天该复习 N 站」只能靠 reset 才降得下去。
            stage = LearningProgressRules.nextStage(stage);
            dueAt = LearningProgressRules.dueAt(now, stage);
        } else if (passed && scoresChanged) {
            // 未到期但内容变了（作者加题等）：保持原有语义推进一档
            stage = LearningProgressRules.nextStage(stage);
            dueAt = LearningProgressRules.dueAt(now, stage);
        } else if (!passed) {
            // 内容变难（例如作者加了题）会让旧的分数不再达标：退回未通过，不排复习。
            // passedAt 保留为历史，界面只看 status。
            dueAt = null;
        }

        LearningProgressRow row = new LearningProgressRow();
        row.setId(existing == null ? null : existing.getId());
        row.setUserId(userId);
        row.setEntryId(entryId);
        row.setTourStep(tourStep);
        row.setChallengesJson(write(challenges));
        row.setQuizzesJson(write(quizzes));
        row.setStatus(status);
        row.setPassedAt(passedAt);
        row.setReviewStage(stage);
        row.setReviewDueAt(dueAt);
        progressMapper.upsert(row);
        return toView(row, entry.getTitle(), entryMapper.findLineKindOfEntry(entryId));
    }

    /** 重置本站。幂等：本来没有记录也返回成功。 */
    @Transactional
    public void reset(long userId, long entryId) {
        progressMapper.delete(userId, entryId);
    }

    private View toView(LearningProgressRow row, String title) {
        return toView(row, title, entryMapper.findLineKindOfEntry(row.getEntryId()));
    }

    private View toView(LearningProgressRow row, String title, String lineKind) {
        return new View(
                row.getEntryId(),
                title,
                lineKind,
                row.getStatus() == null ? STATUS_IN_PROGRESS : row.getStatus(),
                row.getTourStep(),
                row.getPassedAt(),
                row.getReviewStage(),
                row.getReviewDueAt(),
                parse(row, true),
                parse(row, false));
    }

    private Entry requirePublished(long entryId) {
        return entryMapper.findPublishedById(entryId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "条目不存在"));
    }

    /**
     * 读 JSON。**坏数据当作空**而不是抛错：一条脏记录不该让整页打不开，
     * 而丢掉的是"最高分"提示，不是用户内容。
     */
    private static Map<String, LearningProgressRules.BlockScore> parse(LearningProgressRow row, boolean challenges) {
        if (row == null) {
            return Map.of();
        }
        String raw = challenges ? row.getChallengesJson() : row.getQuizzesJson();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, LearningProgressRules.BlockScore> parsed = JSON.readValue(raw, SCORES);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception failure) {
            log.warn("learning.progress.bad-json entry={} kind={}", row.getEntryId(),
                    challenges ? "challenges" : "quizzes");
            return Map.of();
        }
    }

    private static String write(Map<String, LearningProgressRules.BlockScore> scores) {
        try {
            return JSON.writeValueAsString(scores);
        } catch (Exception failure) {
            // 只可能来自循环引用一类不可能出现的形状；抛出去会让本次提交整笔失败
            throw new BizException(ErrorCode.INTERNAL, "学习记录写入失败");
        }
    }

    private static void validate(Report report) {
        if (report == null) {
            return;
        }
        if (report.tourStep() != null && (report.tourStep() < 0 || report.tourStep() > MAX_TOUR_STEP)) {
            throw invalid("最远步骤超出范围");
        }
        validateBlocks(report.challenges(), "挑战");
        validateBlocks(report.quizzes(), "小测");
    }

    private static void validateBlocks(Map<String, LearningProgressRules.BlockScore> blocks, String label) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw invalid(label + "记录条数超过上限");
        }
        blocks.forEach((id, score) -> {
            if (id == null || id.isBlank() || id.length() > MAX_BLOCK_ID) {
                throw invalid(label + "的块标识不合法");
            }
            if (score == null || score.total() < 1 || score.total() > MAX_TOTAL
                    || score.score() < 0 || score.score() > score.total()) {
                throw invalid(label + "的得分不合法");
            }
        });
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_ARGUMENT, message);
    }
}

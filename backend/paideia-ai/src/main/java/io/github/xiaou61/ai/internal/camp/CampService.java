package io.github.xiaou61.ai.internal.camp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.ai.ModelAccess;
import io.github.xiaou61.ai.RunKind;
import io.github.xiaou61.ai.RunOutcome;
import io.github.xiaou61.ai.RunRecorder;
import io.github.xiaou61.ai.RunSpec;
import io.github.xiaou61.ai.TokenUsage;
import io.github.xiaou61.ai.internal.billing.CreditService;
import io.github.xiaou61.ai.internal.credential.CredentialService;
import io.github.xiaou61.ai.internal.resume.ResumeDocumentService;
import io.github.xiaou61.knowledge.KnowledgeApi;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 训练营的编排（WORK-029）。
 *
 * <p>一次营的生命周期：
 * <pre>
 * 开营（计费 #1）→ 交诊断卷（免费）→ 出计划（免费，纯函数）→ 复测（计费 #2）→ 并排对比（免费）
 * </pre>
 *
 * <p>四条设计取舍：
 * <ol>
 *   <li><b>模型只出题</b>：判定、聚合、排序、排期全是代码。这不是为了省钱，而是三件代码能做
 *       而模型做不到的事——可复现、可测试、不会编造内容。</li>
 *   <li><b>判定只在服务端</b>：交卷的请求体形状里根本没有 {@code correct}，多传的字段被
 *       Jackson 忽略，伪造的判定不进入任何分支。</li>
 *   <li><b>答案不出仓库</b>：{@code ItemView} 与 {@code ResultView} 是两个不同的类型，
 *       不是同一个类型少填两个字段。「交卷前泄露答案」在类型层面就走不通。</li>
 *   <li><b>失败不落半条数据</b>：出题与校验全在写库之前完成；没有效题就没有营。</li>
 * </ol>
 */
@Service
public class CampService {

    private static final Logger log = LoggerFactory.getLogger(CampService.class);

    /** 每个用户最多几个营。限制 AI 花费与表行数，也避免刷接口刷出账。 */
    static final int MAX_ACTIVE_CAMPS = 3;
    /** 列表上限。营是按天走的，历史上不会太多，先给足。 */
    static final int LIST_LIMIT = 20;
    /** 每天的时间预算：3 天营 90 分钟、7 天营 60 分钟。不提供可调项。 */
    static final int BUDGET_THREE_DAYS = 90;
    static final int BUDGET_SEVEN_DAYS = 60;
    /** 有效题不到目标的这个比例时提醒"题数不足"，但仍建营。 */
    static final double SHORT_OF_TARGET_RATIO = 0.8;
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String FORM_DIAGNOSTIC = "diagnostic";
    static final String FORM_RETEST = "retest";

    private final CampMapper camps;
    private final ResumeDocumentService documents;
    private final CampItemGenerator generator;
    private final CampItemParser parser;
    private final KnowledgeApi knowledge;
    private final CredentialService credentials;
    private final CreditService credits;
    private final RunRecorder recorder;

    CampService(CampMapper camps, ResumeDocumentService documents, CampItemGenerator generator,
                CampItemParser parser, KnowledgeApi knowledge, CredentialService credentials,
                CreditService credits, RunRecorder recorder) {
        this.camps = camps;
        this.documents = documents;
        this.generator = generator;
        this.parser = parser;
        this.knowledge = knowledge;
        this.credentials = credentials;
        this.credits = credits;
        this.recorder = recorder;
    }

    // ── 视图 ────────────────────────────────────────────────────────

    public record ProgramSummary(long id, int days, int itemCount, int droppedCount,
                                 String resumeTitle, Instant createdAt,
                                 boolean diagnosticSubmitted, boolean retestSubmitted,
                                 boolean hasPlan) {
        static ProgramSummary of(CampMapper.ProgramRow row) {
            return new ProgramSummary(row.getId(), row.getDays(), row.getItemCount(), row.getDroppedCount(),
                    row.getResumeTitle(), row.getCreatedAt(),
                    row.getDiagnosticSubmittedAt() != null, row.getRetestSubmittedAt() != null,
                    row.getActivePlanId() != null);
        }
    }

    /**
     * 一题。**没有 answerIndex、没有 explanation**——这是本项的安全边界，
     * 类型里没有的字段不可能被序列化出去。
     */
    public record ItemView(long id, int seq, String topic, String difficulty, String stem,
                           List<String> options) {
    }

    public record TopicView(String topic, int correct, int total, Map<String, TopicScore.Difficulty> byDifficulty) {
        static TopicView of(TopicScore.TopicResult result) {
            return new TopicView(result.topic(), result.correct(), result.total(), result.byDifficulty());
        }
    }

    /** 交卷后 / 结果页。`previous` 只在 form=retest 且有诊断结果时非空。 */
    public record ResultView(List<TopicView> topics, List<String> weak, List<String> strong,
                             CampComparer.Comparison previous, CampComparer.Verdict verdict) {
    }

    public record TaskView(long id, int day, String kind, String topic, String title, int minutes,
                           String why, Long entryId, boolean done) {
    }

    public record DayView(int day, List<TaskView> tasks) {
    }

    /** {@code dayPlans} 与 {@code days}（天数）分开两个名字：同名会让 record 的访问器撞车。 */
    public record PlanView(long id, int days, int budgetMinutes, List<DayView> dayPlans,
                           List<TaskView> unscheduled, List<String> weak) {
    }

    /** 开营结果。`shortOfTarget` 为真时界面要写明实际题数。 */
    public record Created(long id, int days, int itemCount, int droppedCount, boolean shortOfTarget) {
    }

    // ── 开营（计费） ────────────────────────────────────────────────

    /**
     * 开营并出诊断卷。
     *
     * <p>校验链顺序刻意如此：能免费判的错先判（天数、简历、画像、营数、凭据），
     * **最后才预扣**。余额不足时一次上游请求都没发出去。
     */
    @Transactional
    public Created create(long userId, long resumeDocumentId, int days) {
        if (days != 3 && days != 7) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "营的天数只能是 3 天或 7 天");
        }
        ResumeDocumentService.Detail resume = documents.get(userId, resumeDocumentId);
        SkillDigest.Digest digest = SkillDigest.digest(resume.data());
        if (digest.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "这份简历里还没有可考核的技能。先补全技能与项目经历，或换一份简历。");
        }
        if (camps.countPrograms(userId) >= MAX_ACTIVE_CAMPS) {
            throw new BizException(ErrorCode.CONFLICT,
                    "同时最多 " + MAX_ACTIVE_CAMPS + " 个训练营，请先删掉一个再开新的");
        }
        ModelAccess access = requireAccess();
        int targetCount = targetCount(days);

        long reserved = credits.reserve(userId, credits.estimate());
        TokenUsage usage = null;
        var run = recorder.begin(RunSpec.of(RunKind.CAMP_GENERATE, userId)
                .withModel(access.model())
                .withPromptVersion(CampPrompts.GENERATE_VERSION));
        try {
            Map<String, Long> concepts = conceptsByName();
            Generation result = generate(() -> generator.diagnostic(
                    digest.promptBlock(), conceptBlock(concepts), targetCount, access), concepts);
            usage = result.usage();
            ItemValidator.Validated validated = result.validated();
            if (validated.accepted().isEmpty()) {
                throw CampGenerationException.noValidItem();
            }
            long programId = persistProgram(userId, resume, days, validated);
            settle(userId, reserved, access, usage, programId);
            run.tokens(usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(), 0);
            run.finish(RunOutcome.OK, null);
            boolean shortOfTarget = validated.accepted().size() < targetCount * SHORT_OF_TARGET_RATIO;
            log.info("camp.created userId={} programId={} days={} items={} dropped={}",
                    userId, programId, days, validated.accepted().size(), validated.dropped().size());
            if (!validated.dropped().isEmpty()) {
                log.info("camp.items_dropped programId={} reasons={}", programId,
                        validated.dropped().stream().map(v -> v.reason().name()).toList());
            }
            return new Created(programId, days, validated.accepted().size(),
                    validated.dropped().size(), shortOfTarget);
        } catch (RuntimeException failure) {
            refundFailed(userId, reserved, access, usage, "训练营出卷失败");
            run.finish(RunOutcome.FAILED, "camp_generate_failed");
            throw failure;
        } finally {
            run.close();
        }
    }

    // ── 读 ──────────────────────────────────────────────────────────

    public List<ProgramSummary> list(long userId) {
        return camps.listPrograms(userId, LIST_LIMIT).stream()
                .map(ProgramSummary::of)
                .toList();
    }

    public ProgramSummary get(long userId, long programId) {
        return ProgramSummary.of(requireProgram(userId, programId));
    }

    /** 该卷的题目。**不含答案与解析**。 */
    public List<ItemView> items(long userId, long programId, String form) {
        requireProgram(userId, programId);
        String normalized = requireForm(form);
        return camps.listItems(programId, userId, normalized).stream()
                .map(row -> new ItemView(row.getId(), row.getSeq(), row.getTopic(), row.getDifficulty(),
                        row.getStem(), parseOptions(row.getOptionsJson())))
                .toList();
    }

    @Transactional
    public void delete(long userId, long programId) {
        requireProgram(userId, programId);
        // 子表在前：顺序与注销级联一致，两处都改的时候容易对上
        camps.deleteTasks(programId, userId);
        camps.deletePlans(programId, userId);
        camps.deleteAttempts(programId, userId);
        camps.deleteItems(programId, userId);
        camps.deleteProgram(programId, userId);
        log.info("camp.deleted userId={} programId={}", userId, programId);
    }

    // ── 交卷（免费） ────────────────────────────────────────────────

    /** 一条作答输入。**形状里没有 correct**，客户端伪造不进任何分支。 */
    public record AnswerInput(Long itemId, Integer choice) {
    }

    /**
     * 交卷并返回聚合结果。
     *
     * <p>同一卷二次提交返回 409：作答表有唯一键兜底，但先在这里给出可读的原因。
     */
    @Transactional
    public ResultView submit(long userId, long programId, String form, List<AnswerInput> answers) {
        CampMapper.ProgramRow program = requireProgram(userId, programId);
        String normalized = requireForm(form);
        boolean diagnostic = FORM_DIAGNOSTIC.equals(normalized);
        if (diagnostic && program.getDiagnosticSubmittedAt() != null) {
            throw new BizException(ErrorCode.CONFLICT, "诊断卷已经交过了");
        }
        if (!diagnostic && program.getRetestSubmittedAt() != null) {
            throw new BizException(ErrorCode.CONFLICT, "这一份复测卷已经交过了，要再测请重新生成");
        }

        List<CampMapper.ItemRow> items = camps.listItems(programId, userId, normalized);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "这一卷还没有生成题目");
        }
        Map<Long, Integer> choices = validateAnswers(answers, items);

        Instant now = Instant.now();
        List<TopicScore.ScoredItem> scored = new ArrayList<>(items.size());
        for (CampMapper.ItemRow item : items) {
            int choice = choices.get(item.getId());
            boolean correct = choice == item.getAnswerIndex();
            CampMapper.AttemptRow row = new CampMapper.AttemptRow();
            row.setProgramId(programId);
            row.setUserId(userId);
            row.setItemId(item.getId());
            row.setChoice(choice);
            row.setCorrect(correct);
            row.setCreatedAt(now);
            camps.insertAttempt(row);
            scored.add(new TopicScore.ScoredItem(item.getTopic(), item.getDifficulty(), correct));
        }
        if (diagnostic) {
            camps.markDiagnosticSubmitted(programId, userId, now);
        } else {
            camps.markRetestSubmitted(programId, userId, now);
        }
        log.info("camp.submitted userId={} programId={} form={} items={}", userId, programId, normalized,
                items.size());
        return resultView(program, normalized, TopicScore.aggregate(scored));
    }

    /** 结果页。未交卷时返回 400（"还没交卷"不是"结果为空"）。 */
    public ResultView result(long userId, long programId, String form) {
        CampMapper.ProgramRow program = requireProgram(userId, programId);
        String normalized = requireForm(form);
        if (submittedAt(program, normalized) == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "这一卷还没有交卷，没有结果可看");
        }
        return resultView(program, normalized, scores(programId, userId, normalized));
    }

    // ── 计划（免费，纯函数） ────────────────────────────────────────

    /**
     * 生成计划。
     *
     * <p>旧计划行不删：{@code camp_tasks.done_at} 是那一次的事实，删了就丢历史。
     * 只把 {@code active_plan_id} 指向新计划。
     */
    @Transactional
    public PlanView buildPlan(long userId, long programId) {
        CampMapper.ProgramRow program = requireProgram(userId, programId);
        if (program.getDiagnosticSubmittedAt() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "先做完诊断卷才能出计划");
        }
        Map<String, TopicScore.TopicResult> scores = scores(programId, userId, FORM_DIAGNOSTIC);
        if (scores.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "诊断卷还没有作答记录");
        }

        ResumeDocumentService.Detail resume = documents.get(userId, program.getResumeDocumentId());
        SkillDigest.Digest digest = SkillDigest.digest(resume.data());
        List<PlanBuilder.WeakTopic> weak = weakTopics(programId, userId, scores, digest);
        List<PlanBuilder.DueReview> due = knowledge.dueReviews(userId, 20).stream()
                .map(item -> new PlanBuilder.DueReview(item.conceptId(), item.entryId(), item.title()))
                .toList();

        Set<Long> conceptIds = new LinkedHashSet<>();
        weak.forEach(topic -> {
            if (topic.conceptId() != null) {
                conceptIds.add(topic.conceptId());
            }
        });
        Map<Long, List<PlanBuilder.Carrier>> carriers = new LinkedHashMap<>();
        knowledge.conceptCarriers(conceptIds).forEach((conceptId, rows) ->
                carriers.put(conceptId, rows.stream()
                        .map(row -> new PlanBuilder.Carrier(row.entryId(), row.title(), row.bodyChars(),
                                row.hasBlocks()))
                        .toList()));

        PlanBuilder.Plan plan = PlanBuilder.build(weak, carriers, due, program.getDays(), budgetOf(program.getDays()));
        CampMapper.PlanRow planRow = persistPlan(userId, program, plan, weak);
        long planId = planRow.getId();
        camps.setActivePlan(programId, userId, planId, Instant.now());
        log.info("camp.plan_built userId={} programId={} planId={} weak={} tasks={} unscheduled={}",
                userId, programId, planId, weak.size(),
                plan.days().stream().mapToInt(day -> day.tasks().size()).sum(), plan.unscheduled().size());
        return planView(userId, planRow);
    }

    /**
     * 读当前计划。
     *
     * <p>读时 join 已发布条目：join 不到（条目被下架或删了）就降级为缺口项，
     * {@code entryId} 置空、标题改写。**不返回失效 id**——那会让界面给出一个 404 链接。
     */
    public PlanView currentPlan(long userId, long programId) {
        CampMapper.ProgramRow program = requireProgram(userId, programId);
        if (program.getActivePlanId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "还没有生成计划");
        }
        CampMapper.PlanRow row = camps.findPlan(program.getActivePlanId(), userId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划不存在");
        }
        return assemblePlanView(row, camps.listTasks(row.getId(), userId), readWeak(row.getWeakJson()));
    }

    /**
     * 把一堆任务行组装成视图，**两处（写完立即返回、之后 GET）共用同一段**。
     *
     * <p>曾在这里分成两条路：写入后直接返回的那条把"已发布集合"传成了空集，于是新出的计划里
     * 每一项都被当成已下架（`2026-10-21` 由 `CampMasteryIntegrationTest` 抓到）。
     * 合成一处之后这个错只可能犯一次。
     */
    private PlanView assemblePlanView(CampMapper.PlanRow row, List<CampMapper.TaskRow> tasks,
                                      List<String> weak) {
        List<Long> entryIds = tasks.stream()
                .map(CampMapper.TaskRow::getEntryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> alive = publishedEntryIds(entryIds);

        List<DayView> days = new ArrayList<>();
        for (int day = 1; day <= row.getDays(); day++) {
            List<TaskView> forDay = new ArrayList<>();
            for (CampMapper.TaskRow task : tasks) {
                if (task.getDayNo() == day) {
                    forDay.add(taskView(task, alive));
                }
            }
            days.add(new DayView(day, List.copyOf(forDay)));
        }
        List<TaskView> unscheduled = tasks.stream()
                .filter(task -> task.getDayNo() <= 0)
                .map(task -> taskView(task, alive))
                .toList();
        return new PlanView(row.getId(), row.getDays(), row.getBudgetMinutes(), List.copyOf(days),
                unscheduled, weak);
    }

    /** 勾选/取消一项任务。幂等：重复勾选只是把 done_at 写同一个值。 */
    @Transactional
    public boolean markTask(long userId, long programId, long taskId, boolean done) {
        requireProgram(userId, programId);
        int hit = camps.markTask(taskId, programId, userId, done ? Instant.now() : null);
        if (hit == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return done;
    }

    // ── 复测（计费） ────────────────────────────────────────────────

    /**
     * 生成复测卷：同主题、同题量、同难度分布，且避开诊断卷做过的题。
     */
    @Transactional
    public Created retest(long userId, long programId) {
        CampMapper.ProgramRow program = requireProgram(userId, programId);
        if (program.getDiagnosticSubmittedAt() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "先做完诊断卷才能复测");
        }
        List<CampMapper.ItemRow> diagnostic = camps.listItems(programId, userId, FORM_DIAGNOSTIC);
        if (diagnostic.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "诊断卷没有题目");
        }
        if (camps.listItems(programId, userId, FORM_RETEST).size() > 0
                && program.getRetestSubmittedAt() == null) {
            throw new BizException(ErrorCode.CONFLICT, "已经有一份没交的复测卷，先把它做完");
        }

        ResumeDocumentService.Detail resume = documents.get(userId, program.getResumeDocumentId());
        SkillDigest.Digest digest = SkillDigest.digest(resume.data());
        ModelAccess access = requireAccess();
        String quota = quotaBlock(diagnostic);
        List<String> previousStems = diagnostic.stream().map(CampMapper.ItemRow::getStem).toList();

        long reserved = credits.reserve(userId, credits.estimate());
        TokenUsage usage = null;
        var run = recorder.begin(RunSpec.of(RunKind.CAMP_RETEST, userId)
                .withModel(access.model())
                .withPromptVersion(CampPrompts.RETEST_VERSION));
        try {
            Map<String, Long> concepts = conceptsByName();
            Generation result = generate(() -> generator.retest(digest.promptBlock(),
                    conceptBlock(concepts), quota, previousStems, access), concepts);
            usage = result.usage();
            ItemValidator.Validated validated = result.validated();
            if (validated.accepted().isEmpty()) {
                throw CampGenerationException.noValidItem();
            }
            int seq = 0;
            Set<String> diagnosticFingerprints = diagnostic.stream()
                    .map(CampMapper.ItemRow::getFingerprint).collect(java.util.stream.Collectors.toSet());
            List<ItemValidator.Accepted> kept = validated.accepted().stream()
                    .filter(item -> !diagnosticFingerprints.contains(item.fingerprint()))
                    .toList();
            if (kept.isEmpty()) {
                throw CampGenerationException.noValidItem();
            }
            for (ItemValidator.Accepted item : kept) {
                insertItem(userId, programId, FORM_RETEST, seq++, item);
            }
            settle(userId, reserved, access, usage, programId);
            run.tokens(usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(), 0);
            run.finish(RunOutcome.OK, null);
            log.info("camp.retest_generated userId={} programId={} items={}", userId, programId, kept.size());
            return new Created(programId, program.getDays(), kept.size(), validated.dropped().size(),
                    kept.size() < diagnostic.size() * SHORT_OF_TARGET_RATIO);
        } catch (RuntimeException failure) {
            refundFailed(userId, reserved, access, usage, "训练营复测失败");
            run.finish(RunOutcome.FAILED, "camp_retest_failed");
            throw failure;
        } finally {
            run.close();
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Candidate {
        CampItemGenerator.Generated run();
    }

    /** 一次生成（可能含一次修复）的结果。 */
    private record Generation(ItemValidator.Validated validated, TokenUsage usage) {
    }

    /**
     * 生成 → 校验 → （有违规项时）一次修复重试 → 再校验。
     *
     * <p>两条纪律：
     * <ol>
     *   <li><b>空返回不重试</b>：它通常意味着 {@code max_tokens} 被思考吃满，再问一次同样被吃满，
     *       只会把一次失败变成两次花费（用户被扣两次）。直接抛，让调用方退款。</li>
     *   <li><b>只修复一轮</b>：修复也需要一次上游调用，无限重试等于无限花费。修完仍不合规就
     *       按"有效题不足"处理——那是产品上可以接受的结果，界面会写明实际题数。</li>
     * </ol>
     */
    private Generation generate(Candidate candidate, Map<String, Long> concepts) {
        CampItemGenerator.Generated first = candidate.run();
        ItemValidator.Validated validated = ItemValidator.validate(first.items(), concepts);
        TokenUsage usage = first.usage();
        if (validated.dropped().isEmpty()) {
            return new Generation(validated, usage);
        }
        List<String> violations = validated.dropped().stream()
                .map(item -> "第 " + (item.index() + 1) + " 题：" + item.reason().name() + "（" + item.detail() + "）")
                .toList();
        CampItemGenerator.Generated repaired;
        try {
            repaired = generator.repair(first.rawText(), violations, requireAccess());
        } catch (CampGenerationException repairFailure) {
            // 修复本身失败就把**第一轮的有效题**留下：丢掉它们等于这次调用白花
            log.warn("camp.repair_failed reason={} kept={}", repairFailure.kind(), validated.accepted().size());
            return new Generation(validated, usage);
        }
        ItemValidator.Validated second = ItemValidator.validate(repaired.items(), concepts);
        // 两轮取并集（按指纹去重）：修复可能删掉好题，但那不该让我们丢掉第一轮已经合格的
        List<ItemValidator.RawItem> merged = new ArrayList<>();
        validated.accepted().forEach(item -> merged.add(item.item()));
        second.accepted().forEach(item -> merged.add(item.item()));
        ItemValidator.Validated combined = ItemValidator.validate(merged, concepts);
        // 修复是**另一次独立的上游调用**：两轮的 token 都要算，否则用户为两次真实调用只付一次钱，
        // 观测里的用量也偏低。
        return new Generation(combined, addUsage(usage, repaired.usage()));
    }

    private static TokenUsage addUsage(TokenUsage first, TokenUsage second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new TokenUsage(first.inputTokens() + second.inputTokens(),
                first.outputTokens() + second.outputTokens(),
                first.totalTokens() + second.totalTokens(),
                first.cacheReadTokens() + second.cacheReadTokens());
    }

    private long persistProgram(long userId, ResumeDocumentService.Detail resume, int days,
                                ItemValidator.Validated validated) {
        Instant now = Instant.now();
        CampMapper.ProgramRow row = new CampMapper.ProgramRow();
        row.setUserId(userId);
        row.setResumeDocumentId(resume.id());
        row.setResumeTitle(resume.title());
        row.setDays(days);
        row.setItemCount(validated.accepted().size());
        row.setDroppedCount(validated.dropped().size());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        camps.insertProgram(row);

        int seq = 0;
        for (ItemValidator.Accepted item : validated.accepted()) {
            insertItem(userId, row.getId(), FORM_DIAGNOSTIC, seq++, item);
        }
        return row.getId();
    }

    private void insertItem(long userId, long programId, String form, int seq,
                            ItemValidator.Accepted item) {
        CampMapper.ItemRow row = new CampMapper.ItemRow();
        row.setProgramId(programId);
        row.setUserId(userId);
        row.setForm(form);
        row.setSeq(seq + 1);
        row.setTopic(trim(item.item().topic()));
        row.setConceptId(item.conceptId());
        row.setDifficulty(item.item().difficulty());
        row.setStem(trim(item.item().stem()));
        row.setOptionsJson(writeJson(item.item().options()));
        row.setAnswerIndex(item.item().answerIndex());
        row.setExplanation(trim(item.item().explanation()));
        row.setFingerprint(item.fingerprint());
        row.setCreatedAt(Instant.now());
        camps.insertItem(row);
    }

    private CampMapper.PlanRow persistPlan(long userId, CampMapper.ProgramRow program,
                                           PlanBuilder.Plan plan, List<PlanBuilder.WeakTopic> weak) {
        Instant now = Instant.now();
        CampMapper.PlanRow row = new CampMapper.PlanRow();
        row.setProgramId(program.getId());
        row.setUserId(userId);
        row.setDays(program.getDays());
        row.setBudgetMinutes(budgetOf(program.getDays()));
        row.setWeakJson(writeJson(weak));
        row.setUnscheduledJson(writeJson(plan.unscheduled()));
        row.setCreatedAt(now);
        camps.insertPlan(row);

        for (PlanBuilder.DayTasks day : plan.days()) {
            for (PlanBuilder.Task task : day.tasks()) {
                insertTask(userId, row.getId(), program.getId(), task.day(), task, now);
            }
        }
        for (PlanBuilder.Task task : plan.unscheduled()) {
            insertTask(userId, row.getId(), program.getId(), 0, task, now);
        }
        return row;
    }

    private void insertTask(long userId, long planId, long programId, int day, PlanBuilder.Task task,
                            Instant now) {
        CampMapper.TaskRow row = new CampMapper.TaskRow();
        row.setPlanId(planId);
        row.setProgramId(programId);
        row.setUserId(userId);
        row.setDayNo(day);
        row.setSeq(task.seq());
        row.setKind(task.kind());
        row.setEntryId(task.entryId());
        row.setConceptId(task.conceptId());
        row.setTopic(trim(task.topic()));
        row.setTitle(trim(task.title()));
        row.setMinutes(task.minutes());
        row.setWhy(trim(task.why()));
        row.setCreatedAt(now);
        camps.insertTask(row);
    }

    /** 写完计划后立即返回的视图。**与 GET 那条走同一个组装**，避免两处行为不一致。 */
    private PlanView planView(long userId, CampMapper.PlanRow planRow) {
        return assemblePlanView(planRow, camps.listTasks(planRow.getId(), userId),
                readWeak(planRow.getWeakJson()));
    }

    /** 读计划时的降级：条目不在已发布集合里 → 缺口项。 */
    private TaskView taskView(CampMapper.TaskRow row, Set<Long> published) {
        boolean alive = row.getEntryId() == null || published.contains(row.getEntryId());
        String kind = alive ? row.getKind() : PlanBuilder.KIND_GAP;
        Long entryId = alive ? row.getEntryId() : null;
        String title = alive ? row.getTitle() : "这项内容已下架";
        String why = alive ? row.getWhy() : "原先推荐的内容已经下架，可以让助手讲一讲这个主题";
        return new TaskView(row.getId(), row.getDayNo(), kind, row.getTopic(), title, row.getMinutes(),
                why, entryId, row.getDoneAt() != null);
    }

    /**
     * 哪些条目仍然已发布。
     *
     * <p>shortcut: 逐条判是 N 次查询（N 是一份计划的任务数，实测十几条），量级够小；
     * 真要一次查完得给 KnowledgeApi 加批量方法，等某个用户的计划超过百项再说。
     */
    private Set<Long> publishedEntryIds(Collection<Long> entryIds) {
        if (entryIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> alive = new LinkedHashSet<>();
        for (long entryId : entryIds) {
            if (knowledge.findPublishedContent(entryId).isPresent()) {
                alive.add(entryId);
            }
        }
        return alive;
    }

    /**
     * 弱项列表。
     *
     * <p>参数顺序是 {@code (programId, userId)}——**与调用点必须一致**。曾把两处写反：
     * 两个参数都是 {@code long}，编译器不报错，于是概念查不到、计划永远全量降级成缺口项，
     * 而"下架后降级"那条测试因为本来就拿不到载体而**假通过**（2026-10-21 由
     * {@code CampMasteryIntegrationTest.planTaskLinksToExistingProgressPath} 抓到）。
     */
    private List<PlanBuilder.WeakTopic> weakTopics(long programId, long userId,
                                                   Map<String, TopicScore.TopicResult> scores,
                                                   SkillDigest.Digest digest) {
        Map<String, Long> conceptByTopic = topicConcepts(programId, userId, FORM_DIAGNOSTIC);
        Map<Long, List<Long>> prerequisites = prerequisites();

        List<PlanBuilder.WeakTopic> weak = new ArrayList<>();
        for (TopicScore.TopicResult result : TopicScore.ascendingByRatio(scores)) {
            if (!WeaknessRules.classify(result).weak()) {
                continue;
            }
            Long conceptId = conceptByTopic.get(result.topic());
            weak.add(new PlanBuilder.WeakTopic(result.topic(), conceptId,
                    result.ratio(), digest.coverage(result.topic()),
                    result.correct(), result.total(),
                    conceptId == null ? List.of() : prerequisites.getOrDefault(conceptId, List.of())));
        }
        return weak;
    }

    /** 概念的前置边：conceptId → 它的前置概念 id。 */
    private Map<Long, List<Long>> prerequisites() {
        return knowledge.conceptPrerequisites();
    }

    /** 主题 → 概念 id：取该主题下题目里出现最多的非空 concept_id。 */
    private Map<String, Long> topicConcepts(long programId, long userId, String form) {
        Map<String, Map<Long, Integer>> counts = new LinkedHashMap<>();
        for (CampMapper.ItemRow row : camps.listItems(programId, userId, form)) {
            if (row.getConceptId() == null) {
                continue;
            }
            counts.computeIfAbsent(row.getTopic(), key -> new LinkedHashMap<>())
                    .merge(row.getConceptId(), 1, Integer::sum);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        counts.forEach((topic, byConcept) -> byConcept.entrySet().stream()
                .max(Map.Entry.<Long, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .ifPresent(best -> result.put(topic, best.getKey())));
        return result;
    }

    private Map<String, Long> conceptsByName() {
        Map<String, Long> byName = new LinkedHashMap<>();
        for (KnowledgeApi.ConceptRef concept : knowledge.concepts()) {
            byName.put(concept.name().toLowerCase(Locale.ROOT), concept.id());
        }
        return byName;
    }

    private static String conceptBlock(Map<String, Long> concepts) {
        if (concepts.isEmpty()) {
            return "（管理员还没有建概念，题目不用挂概念，topic 自己起名即可）";
        }
        List<String> names = new ArrayList<>(concepts.keySet());
        if (names.size() > 50) {
            names = names.subList(0, 50);
        }
        return String.join("、", names);
    }

    private Map<String, TopicScore.TopicResult> scores(long programId, long userId, String form) {
        List<TopicScore.ScoredItem> scored = camps.listScored(programId, userId, form).stream()
                .map(row -> new TopicScore.ScoredItem(row.topic(), row.difficulty(), row.correct()))
                .toList();
        return TopicScore.aggregate(scored);
    }

    private ResultView resultView(CampMapper.ProgramRow program, String form,
                                  Map<String, TopicScore.TopicResult> scores) {
        List<TopicView> topics = scores.values().stream().map(TopicView::of).toList();
        List<String> weak = new ArrayList<>();
        List<String> strong = new ArrayList<>();
        for (TopicScore.TopicResult result : scores.values()) {
            if (WeaknessRules.classify(result).weak()) {
                weak.add(result.topic());
            } else if (WeaknessRules.strong(result)) {
                strong.add(result.topic());
            }
        }
        if (!FORM_RETEST.equals(form) || program.getDiagnosticSubmittedAt() == null) {
            return new ResultView(topics, List.copyOf(weak), List.copyOf(strong), null, null);
        }
        Map<String, TopicScore.TopicResult> diagnostic =
                scores(program.getId(), program.getUserId(), FORM_DIAGNOSTIC);
        CampComparer.Comparison comparison = CampComparer.compare(diagnostic, scores);
        return new ResultView(topics, List.copyOf(weak), List.copyOf(strong), comparison,
                CampComparer.summary(comparison));
    }

    private static Map<Long, Integer> validateAnswers(List<AnswerInput> answers,
                                                      List<CampMapper.ItemRow> items) {
        if (answers == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少作答内容");
        }
        Map<Long, Integer> choices = new LinkedHashMap<>();
        for (AnswerInput answer : answers) {
            if (answer == null || answer.itemId() == null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "作答项缺少题目 id");
            }
            Integer choice = answer.choice();
            if (choice == null || choice < 0 || choice >= ItemValidator.OPTION_COUNT) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "选项下标必须在 0..3 之间");
            }
            if (choices.put(answer.itemId(), choice) != null) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "同一题提交了多次");
            }
        }
        for (CampMapper.ItemRow item : items) {
            if (!choices.containsKey(item.getId())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "还有题没有作答，不能交卷");
            }
        }
        if (choices.size() != items.size()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "提交了不属于这一卷的题目");
        }
        return choices;
    }

    private CampMapper.ProgramRow requireProgram(long userId, long programId) {
        CampMapper.ProgramRow row = camps.findProgram(programId, userId);
        if (row == null) {
            // 不存在与不是你的都返回 404：区分开就等于泄露"某个营存在"
            throw new BizException(ErrorCode.NOT_FOUND, "训练营不存在");
        }
        return row;
    }

    private static String requireForm(String form) {
        if (FORM_DIAGNOSTIC.equals(form) || FORM_RETEST.equals(form)) {
            return form;
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "form 只能是 diagnostic 或 retest");
    }

    private static Instant submittedAt(CampMapper.ProgramRow program, String form) {
        return FORM_DIAGNOSTIC.equals(form) ? program.getDiagnosticSubmittedAt()
                : program.getRetestSubmittedAt();
    }

    private ModelAccess requireAccess() {
        Optional<ModelAccess> access = credentials.access(0);
        if (access.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "平台尚未配置 AI 服务，请联系管理员");
        }
        return access.get();
    }

    private void settle(long userId, long reserved, ModelAccess access, TokenUsage usage, long refId) {
        if (usage == null) {
            credits.refund(userId, reserved, refId, "本次生成未返回用量");
            return;
        }
        credits.settleCamp(userId, reserved, access.model(), usage.inputTokens(), usage.outputTokens(), refId);
    }

    /**
     * 失败路径：已产生的用量按实际结算，没产生的全额退。
     *
     * <p>为什么不是一律全退：上游已经算过 token 了，全退等于平台替用户垫钱；
     * 而"没用上就不扣"这条也不能破——所以按 {@code usage == null} 分流。
     */
    private void refundFailed(long userId, long reserved, ModelAccess access, TokenUsage usage, String note) {
        if (usage == null) {
            credits.refund(userId, reserved, null, note);
        } else {
            credits.settleCamp(userId, reserved, access.model(), usage.inputTokens(),
                    usage.outputTokens(), null);
        }
    }

    private static int targetCount(int days) {
        return days == 3 ? 12 : 18;
    }

    private static int budgetOf(int days) {
        return days == 3 ? BUDGET_THREE_DAYS : BUDGET_SEVEN_DAYS;
    }

    private static String quotaBlock(List<CampMapper.ItemRow> diagnostic) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (CampMapper.ItemRow row : diagnostic) {
            counts.computeIfAbsent(row.getTopic(), key -> new LinkedHashMap<>())
                    .merge(row.getDifficulty(), 1, Integer::sum);
        }
        StringBuilder builder = new StringBuilder();
        counts.forEach((topic, tiers) -> {
            int total = tiers.values().stream().mapToInt(Integer::intValue).sum();
            builder.append("- ").append(topic).append("：").append(total).append(" 道（");
            builder.append(tiers.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue() + " 道")
                    .reduce((left, right) -> left + "、" + right).orElse(""));
            builder.append("）\n");
        });
        return builder.toString();
    }

    private static List<String> parseOptions(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> readWeak(String json) {
        try {
            List<PlanBuilder.WeakTopic> weak = JSON.readValue(json,
                    new TypeReference<List<PlanBuilder.WeakTopic>>() {
                    });
            return weak.stream().map(PlanBuilder.WeakTopic::topic).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.INTERNAL, "无法序列化计划内容");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

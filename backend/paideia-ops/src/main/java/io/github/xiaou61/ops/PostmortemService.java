package io.github.xiaou61.ops;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运维复盘（WORK-086）。
 *
 * <p>它把一次诊断的 Markdown 报告转成**可编辑的复盘草稿**，再靠人过一道闸门：
 * {@code DRAFT → APPROVED} 才允许进知识召回，{@code APPROVED} 被再次编辑就退回 {@code DRAFT}。
 * 这条单向门是本节的重点——模型产出直接进知识库，等于让幻觉变成下次诊断的"历史事实"。
 *
 * <p>草稿只做**搬运**，不做判断：报告里的 `## 可能原因` 进"根因"、`## 建议动作` 进"处置"，
 * 时段与日志在生成那一刻就冻结在 `timeline_md`，之后 LogQL 里的老数据被保留期清掉也不影响复盘
 * 自证。
 */
@Service
class PostmortemService {

    private static final Logger log = LoggerFactory.getLogger(PostmortemService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZONE);

    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 60000;
    static final int MAX_FIELD = 20000;
    static final int MAX_TIMELINE_LINES = 60;
    /** 与 ops_postmortems.severity VARCHAR(16) 一致。 */
    static final int MAX_SEVERITY = 16;
    /** 与 ops_postmortems.alert_name VARCHAR(128) 一致。 */
    static final int MAX_ALERT_NAME = 128;

    /** 报告里章节标题的两种层级（模型用 `##`，人工补写时常写 `###`）。 */
    private static final Pattern HEADING = Pattern.compile("^#{2,3}\\s+(.+?)\\s*$");

    private final PostmortemMapper postmortems;
    private final OpsDiagnosisMapper diagnoses;
    private final OpsIncidentService incidents;

    @org.springframework.beans.factory.annotation.Autowired
    PostmortemService(PostmortemMapper postmortems, OpsDiagnosisMapper diagnoses,
                       OpsIncidentService incidents) {
        this.postmortems = postmortems;
        this.diagnoses = diagnoses;
        this.incidents = incidents;
    }

    // ---------------------------------------------------------------- 创建

    /** 人工从诊断起草一份复盘。 */
    long createDraft(long diagnosisId, Long actorUserId) {
        return createDraft(diagnosisId, actorUserId, PostmortemRow.ORIGIN_MANUAL);
    }

    /**
     * 从一条诊断起草复盘。诊断必须已经 {@code DONE}——失败或进行中的报告没有可复盘的结论。
     *
     * @param origin {@link PostmortemRow#ORIGIN_MANUAL} 或 {@link PostmortemRow#ORIGIN_AUTO}
     */
    private long createDraft(long diagnosisId, Long actorUserId, String origin) {
        OpsDiagnosisRow diagnosis = diagnoses.findById(diagnosisId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "诊断记录不存在"));
        if (!OpsDiagnosisRow.STATUS_DONE.equals(diagnosis.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有已完成的诊断可以起草复盘");
        }
        String report = diagnosis.getReportMd() == null ? "" : diagnosis.getReportMd();
        PostmortemRow row = new PostmortemRow();
        row.setDiagnosisId(diagnosis.getId());
        row.setIncidentId(diagnosis.getIncidentId());
        row.setAlertName(diagnosis.getAlertName());
        row.setSeverity(severityOf(diagnosis.getIncidentId()));
        row.setTitle(titleOf(diagnosis.getAlertName(), Instant.now()));
        row.setRootCause(limit(section(report, "可能原因"), MAX_FIELD));
        row.setImpact("");
        row.setTimelineMd(limit(timeline(diagnosis.getIncidentId(), diagnosis), MAX_FIELD));
        row.setActionsMd(limit(section(report, "建议动作"), MAX_FIELD));
        row.setEvidenceRefs(evidenceRefs(diagnosis));
        row.setOrigin(origin == null ? PostmortemRow.ORIGIN_MANUAL : origin);
        row.setStatus(PostmortemRow.STATUS_DRAFT);
        row.setCreatedBy(actorUserId);
        Instant now = Instant.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setBodyMd(renderBody(row));
        postmortems.insert(row);
        log.info("created postmortem #{} from diagnosis #{} ({})", row.getId(), diagnosisId, row.getOrigin());
        return row.getId();
    }

    /**
     * 诊断完成后自动起草（{@link PostmortemRow#ORIGIN_AUTO}）。
     *
     * <p>同一事件已有 DRAFT 或 APPROVED 时**不重复起草**：人写的草稿才是主线，自动那版再生成一次
     * 只会覆盖或并列，让队列变噪音。返回空表示"不需要草稿"。
     */
    Optional<Long> createDraftIfAbsent(long diagnosisId) {
        OpsDiagnosisRow diagnosis = diagnoses.findById(diagnosisId).orElse(null);
        if (diagnosis == null || !OpsDiagnosisRow.STATUS_DONE.equals(diagnosis.getStatus())) {
            return Optional.empty();
        }
        Long incidentId = diagnosis.getIncidentId();
        if (incidentId != null && postmortems.findLiveByIncident(incidentId).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(createDraft(diagnosisId, null, PostmortemRow.ORIGIN_AUTO));
    }

    // ---------------------------------------------------------------- 编辑与状态

    /** 编辑草稿。已 APPROVED 的复盘被改动后**退回 DRAFT**，必须重新确认才回到知识库。 */
    void update(long id, PostmortemDraft draft, long actorUserId) {
        PostmortemRow row = postmortems.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "复盘不存在"));
        if (PostmortemRow.STATUS_ARCHIVED.equals(row.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "已归档的复盘不能编辑");
        }
        String previousStatus = row.getStatus();
        if (draft.title() != null) {
            row.setTitle(limit(draft.title().strip(), MAX_TITLE));
        }
        if (draft.severity() != null) {
            // 列宽 VARCHAR(16)：不截断的话超长值在严格模式下整条 UPDATE 失败，用户刚编辑的内容全丢
            row.setSeverity(limit(draft.severity().strip(), MAX_SEVERITY));
        }
        if (draft.alertName() != null) {
            row.setAlertName(limit(draft.alertName().strip(), MAX_ALERT_NAME));
        }
        if (draft.rootCause() != null) {
            row.setRootCause(limit(OpsLogRedactor.redactDocument(draft.rootCause()), MAX_FIELD));
        }
        if (draft.impact() != null) {
            row.setImpact(limit(OpsLogRedactor.redactDocument(draft.impact()), MAX_FIELD));
        }
        if (draft.timelineMd() != null) {
            row.setTimelineMd(limit(OpsLogRedactor.redactDocument(draft.timelineMd()), MAX_FIELD));
        }
        if (draft.actionsMd() != null) {
            row.setActionsMd(limit(OpsLogRedactor.redactDocument(draft.actionsMd()), MAX_FIELD));
        }
        if (draft.evidenceRefs() != null) {
            row.setEvidenceRefs(limit(draft.evidenceRefs().strip(), 500));
        }
        if (draft.bodyMd() != null && !draft.bodyMd().isBlank()) {
            row.setBodyMd(limit(OpsLogRedactor.redactDocument(draft.bodyMd()), MAX_BODY));
        } else {
            row.setBodyMd(renderBody(row));
        }
        if (PostmortemRow.STATUS_APPROVED.equals(row.getStatus())) {
            row.setStatus(PostmortemRow.STATUS_DRAFT);
            row.setApprovedBy(null);
            row.setApprovedAt(null);
        }
        row.setUpdatedAt(Instant.now());
        if (postmortems.updateFields(row, id, previousStatus) == 0) {
            // 乐观锁未命中：读完之后别人改了状态（归档/确认），本次编辑作废
            throw new BizException(ErrorCode.CONFLICT, "复盘已被其他人修改，请刷新后重试");
        }
        log.info("updated postmortem #{} by user {}, status={}", id, actorUserId, row.getStatus());
    }

    /**
     * 人确认后放行。同一事件旧的 APPROVED 被置为 ARCHIVED——一个事件只有一份生效复盘，
     * 否则下次召回时同一件事会以两种说法同时出现。
     *
     * <p>两条 UPDATE 必须同事务：并发确认同一事件的两份复盘时，分开提交会出现
     * "两份都 APPROVED"或"互相归档后一份 APPROVED 都没有"，破坏上面那条约束。
     */
    @Transactional
    void approve(long id, long actorUserId) {
        PostmortemRow row = postmortems.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "复盘不存在"));
        Instant now = Instant.now();
        if (postmortems.markApproved(id, actorUserId, now) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "只有草稿或已归档的复盘可以确认");
        }
        if (row.getIncidentId() != null) {
            int retired = postmortems.archiveOthersOfIncident(row.getIncidentId(), id, now);
            if (retired > 0) {
                log.info("postmortem #{} approved, retired {} older version(s) of incident #{}",
                        id, retired, row.getIncidentId());
            }
        }
    }

    void archive(long id, long actorUserId) {
        if (postmortems.markArchived(id, Instant.now()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "只有已确认的复盘可以归档");
        }
        log.info("postmortem #{} archived by user {}", id, actorUserId);
    }

    // ---------------------------------------------------------------- 查询

    PostmortemDetail get(long id) {
        PostmortemRow row = postmortems.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "复盘不存在"));
        return PostmortemDetail.of(row);
    }

    Page list(String status, int limit, int offset) {
        String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        if (normalized != null && !java.util.Set.of(PostmortemRow.STATUS_DRAFT,
                PostmortemRow.STATUS_APPROVED, PostmortemRow.STATUS_ARCHIVED).contains(normalized)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "status 只能是 DRAFT、APPROVED 或 ARCHIVED");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        List<PostmortemDetail> items = postmortems.list(normalized, safeLimit, safeOffset).stream()
                .map(PostmortemDetail::of)
                .toList();
        return new Page(items, postmortems.count(normalized), safeLimit, safeOffset);
    }

    // ---------------------------------------------------------------- 渲染

    /** 固定章节顺序：根因 → 影响 → 时间线 → 处置 → 证据。缺的章节省略，不写空标题。 */
    static String renderBody(PostmortemRow row) {
        StringBuilder out = new StringBuilder();
        String title = row.getTitle() == null || row.getTitle().isBlank() ? "运维事件复盘" : row.getTitle();
        out.append("# ").append(title).append("\n\n");
        List<String> meta = new ArrayList<>();
        if (row.getAlertName() != null && !row.getAlertName().isBlank()) {
            meta.add("- 告警：" + row.getAlertName());
        }
        if (row.getSeverity() != null && !row.getSeverity().isBlank()) {
            meta.add("- 严重度：" + row.getSeverity());
        }
        if (row.getIncidentId() != null) {
            meta.add("- 事件：#" + row.getIncidentId());
        }
        if (row.getDiagnosisId() != null) {
            meta.add("- 诊断：#" + row.getDiagnosisId());
        }
        if (!meta.isEmpty()) {
            out.append(String.join("\n", meta)).append("\n\n");
        }
        appendSection(out, "根因", row.getRootCause());
        appendSection(out, "影响", row.getImpact());
        appendSection(out, "时间线", row.getTimelineMd());
        appendSection(out, "处置与待办", row.getActionsMd());
        if (row.getEvidenceRefs() != null && !row.getEvidenceRefs().isBlank()) {
            out.append("## 证据\n\n").append(row.getEvidenceRefs().strip()).append("\n\n");
        }
        return out.toString().strip() + "\n";
    }

    private static void appendSection(StringBuilder out, String heading, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        out.append("## ").append(heading).append("\n\n").append(body.strip()).append("\n\n");
    }

    /**
     * 取报告里某个二级/三级章节的正文，到下一个标题为止。
     *
     * @return 章节正文；章节不存在时返回空串（草稿宁可留空让人填，也不要塞占位废话）
     */
    static String section(String markdown, String heading) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.split("\n", -1);
        StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            Matcher matcher = HEADING.matcher(line.strip());
            if (matcher.matches()) {
                if (inside) {
                    break;
                }
                inside = matcher.group(1).strip().equals(heading);
                continue;
            }
            if (inside) {
                body.append(line).append('\n');
            }
        }
        return body.toString().strip();
    }

    /**
     * 事件时间线。有事件时用事件表（含人工备注），没有事件时退化为"诊断发起 → 诊断完成"两条。
     */
    private String timeline(Long incidentId, OpsDiagnosisRow diagnosis) {
        StringBuilder out = new StringBuilder();
        if (incidentId != null) {
            try {
                OpsIncidentService.Detail detail = incidents.detail(incidentId);
                List<OpsIncidentService.EventView> events = detail.events();
                if (events != null && !events.isEmpty()) {
                    int shown = 0;
                    for (OpsIncidentService.EventView event : events) {
                        if (shown >= MAX_TIMELINE_LINES) {
                            out.append("- …（另有事件已省略）\n");
                            break;
                        }
                        out.append("- ").append(SECOND.format(event.createdAt()))
                                .append(' ').append(safe(event.message())).append('\n');
                        shown++;
                    }
                    return out.toString();
                }
            } catch (RuntimeException e) {
                log.warn("postmortem timeline: incident #{} unreadable: {}", incidentId, e.toString());
            }
        }
        if (diagnosis.getCreatedAt() != null) {
            out.append("- ").append(SECOND.format(diagnosis.getCreatedAt())).append(" 诊断发起\n");
        }
        if (diagnosis.getFinishedAt() != null) {
            out.append("- ").append(SECOND.format(diagnosis.getFinishedAt())).append(" 诊断完成\n");
        }
        return out.toString();
    }

    private String evidenceRefs(OpsDiagnosisRow diagnosis) {
        List<String> refs = new ArrayList<>();
        refs.add("diagnosis:" + diagnosis.getId());
        if (diagnosis.getIncidentId() != null) {
            refs.add("incident:" + diagnosis.getIncidentId());
        }
        if (diagnosis.getCreatedAt() != null) {
            refs.add("报告生成于 " + MINUTE.format(diagnosis.getCreatedAt()));
        }
        return String.join("，", refs);
    }

    private String severityOf(Long incidentId) {
        if (incidentId == null) {
            return null;
        }
        try {
            String severity = incidents.detail(incidentId).incident().severity();
            return severity == null ? null : severity.strip();
        } catch (RuntimeException e) {
            log.warn("postmortem severity: incident #{} unreadable: {}", incidentId, e.toString());
            return null;
        }
    }

    private static String titleOf(String alertName, Instant at) {
        String base = alertName == null || alertName.isBlank() ? "运维事件" : alertName.strip();
        String title = base + " 复盘（" + MINUTE.format(at) + "）";
        return title.length() > MAX_TITLE ? base.substring(0, Math.min(base.length(), MAX_TITLE - 20)) + " 复盘" : title;
    }

    private static String safe(String text) {
        return text == null ? "" : OpsLogRedactor.redactDocument(text).strip();
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- 传输对象

    /** 可编辑字段。null 表示"这次不改这一项"。 */
    record PostmortemDraft(String title, String severity, String alertName, String rootCause,
                           String impact, String timelineMd, String actionsMd, String evidenceRefs,
                           String bodyMd) {
    }

    record PostmortemDetail(long id, Long incidentId, Long diagnosisId, String alertName, String title,
                  String severity, String status, String rootCause, String impact, String timelineMd,
                  String actionsMd, String bodyMd, String evidenceRefs, String origin,
                  Long createdBy, Long approvedBy, Instant approvedAt, Instant createdAt,
                  Instant updatedAt) {

        static PostmortemDetail of(PostmortemRow row) {
            return new PostmortemDetail(row.getId(), row.getIncidentId(), row.getDiagnosisId(), row.getAlertName(),
                    row.getTitle(), row.getSeverity(), row.getStatus(), row.getRootCause(), row.getImpact(),
                    row.getTimelineMd(), row.getActionsMd(), row.getBodyMd(), row.getEvidenceRefs(),
                    row.getOrigin(), row.getCreatedBy(), row.getApprovedBy(), row.getApprovedAt(),
                    row.getCreatedAt(), row.getUpdatedAt());
        }

        boolean editable() {
            return !PostmortemRow.STATUS_ARCHIVED.equals(status);
        }

        boolean approved() {
            return PostmortemRow.STATUS_APPROVED.equals(status);
        }
    }

    record Page(List<PostmortemDetail> items, long total, int pageSize, int offset) {
    }
}

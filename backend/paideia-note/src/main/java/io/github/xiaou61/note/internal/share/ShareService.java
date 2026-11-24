package io.github.xiaou61.note.internal.share;

import io.github.xiaou61.note.internal.note.NoteService;
import io.github.xiaou61.note.internal.note.NoteMapper;
import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只读分享的用例。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>只有本人能分享自己的笔记</b>：分享前先按 `user_id` 把笔记读一遍，读不到就没有分享。</li>
 *   <li><b>share_id 是 UUID</b>：它是免鉴权端点的唯一凭据，可枚举就等于把所有人的分享摊开。</li>
 *   <li><b>失效一律当不存在</b>：过期、撤回、下架都返回空 {@link Optional}，调用方给同一个 404——
 *       区分开等于告诉访问者"这条分享曾经存在"。</li>
 * </ul>
 */
@Service
public class ShareService {

    /** 私有库分享的固定有效期（30 天）；公开库分享永久有效（见 {@link #PERMANENT_EXPIRY}）。 */
    private static final Duration TTL = Duration.ofDays(30);

    /** 公开知识库的分享链接到期时间：取 MySQL DATETIME 的上限年份，等价于"永久"。 */
    private static final Instant PERMANENT_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    /** 举报理由的长度上限：与列宽一致，超长在写入前报错。 */
    private static final int MAX_REASON_CHARS = 500;

    private final NoteShareMapper shares;
    private final NoteReportMapper reports;
    private final NoteService notes;
    private final NoteMapper noteMapper;
    private final NoteBookService books;

    ShareService(NoteShareMapper shares, NoteReportMapper reports, NoteService notes,
                 NoteMapper noteMapper, NoteBookService books) {
        this.shares = shares;
        this.reports = reports;
        this.notes = notes;
        this.noteMapper = noteMapper;
        this.books = books;
    }

    /**
     * 免鉴权只读产物。单篇与整库统一成一个形状：{@code type} 区分（{@code note}/{@code book}）。
     *
     * <p>单篇：{@code title/body/entryId} 有值、{@code documents=null}；
     * 整库：{@code title=库名}、{@code documents} 为嵌套树、{@code body=null}。
     * 无论哪种都**不含**作者、时间、内部 id 或任何学习痕迹。
     */
    public record SharedView(String type, String title, String body, Long entryId,
                             List<SharedBookNode> documents) {
    }

    /** 整库分享的嵌套节点：目录/文档都可能有 children。不含内部 id。 */
    public record SharedBookNode(String kind, String title, String body, List<SharedBookNode> children) {
    }

    @Transactional
    public String share(long userId, long noteId) {
        // 先确认这条笔记是本人的：读不到就没有分享可谈（此时抛 404）
        notes.read(userId, noteId);
        String shareId = UUID.randomUUID().toString();
        shares.insert(shareId, noteId, userId, Instant.now().plus(TTL));
        return shareId;
    }

    /** 整库分享：先确认库是本人的（否则 404），再生成 id。 */
    @Transactional
    public String shareBook(long userId, long bookId) {
        String visibility = books.requireOwnedVisibility(userId, bookId);
        Instant expiresAt = "public".equals(visibility) ? PERMANENT_EXPIRY : Instant.now().plus(TTL);
        String shareId = UUID.randomUUID().toString();
        shares.insertBook(shareId, bookId, userId, expiresAt);
        return shareId;
    }

    /** 某条笔记当前有效的分享 id（本人）。界面据此告诉用户"现在撤的是一条已发出的链接"。 */
    public java.util.List<String> activeShares(long userId, long noteId) {
        // 先确认笔记是本人的：不是本人的笔记连"它有没有被分享过"都不该答
        notes.read(userId, noteId);
        return shares.findActiveForNote(noteId, userId, Instant.now());
    }

    /** 某个知识库当前有效的分享 id（本人）。 */
    public List<String> activeBookShares(long userId, long bookId) {
        books.requireOwned(userId, bookId);
        return shares.findActiveForBook(bookId, userId, Instant.now());
    }

    /** 撤回。删不到（不是本人的、已失效）就报 404——同样不确认它是否存在。 */
    @Transactional
    public void revoke(long userId, String shareId) {
        if (shares.revoke(shareId, userId, Instant.now()) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "分享不存在或已失效");
        }
    }

    /**
     * 读一条有效分享。**免鉴权路径**：这里没有 userId 可校验，安全性全部来自"id 不可枚举"。
     *
     * <p>过期与撤回都在 SQL 里判，所以这里拿到的要么是有效内容、要么什么也没有。
     */
    public Optional<SharedView> read(String shareId) {
        if (shareId == null || shareId.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<SharedView> note = shares.findValid(shareId, now)
                .map(n -> new SharedView("note", n.getTitle(),
                        n.getBody() == null ? "" : n.getBody(), n.getEntryId(), null));
        if (note.isPresent()) {
            return note;
        }
        return shares.findValidBook(shareId, now)
                .map(book -> new SharedView("book", book.getName(), null, null,
                        buildTree(noteMapper.listShareDocs(book.getUserId(), book.getBookId()))));
    }

    /** 把扁平行组装成嵌套树；根是 parentId 为空的节点。顺序沿用 SQL 的 sort_order。 */
    private static List<SharedBookNode> buildTree(List<NoteMapper.ShareDocRow> rows) {
        Map<Long, List<NoteMapper.ShareDocRow>> byParent = new LinkedHashMap<>();
        for (NoteMapper.ShareDocRow row : rows) {
            byParent.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(row);
        }
        return buildChildren(null, byParent);
    }

    private static List<SharedBookNode> buildChildren(Long parentId, Map<Long, List<NoteMapper.ShareDocRow>> byParent) {
        List<NoteMapper.ShareDocRow> children = byParent.get(parentId);
        if (children == null) {
            return List.of();
        }
        List<SharedBookNode> nodes = new ArrayList<>(children.size());
        for (NoteMapper.ShareDocRow row : children) {
            nodes.add(new SharedBookNode(row.getKind(), row.getTitle(),
                    row.getBody() == null ? "" : row.getBody(),
                    buildChildren(row.getId(), byParent)));
        }
        return nodes;
    }

    /**
     * 管理员下架（TASK-013 用）。与撤回同一个字段，区别只是不看归属。
     *
     * @return 是否真的改动了（已经是失效状态则返回 false）
     */
    @Transactional
    public boolean revokeByAdmin(String shareId) {
        return shares.revokeByAdmin(shareId, Instant.now()) > 0;
    }

    /** 管理端列表行。 */
    public record ReportView(long id, String shareId, String noteTitle, String reason, String status,
                             Instant createdAt) {
    }

    /**
     * 提交举报。
     *
     * <p>举报一条**不存在**的分享返回 404：那说明请求有问题，而不是"举报成功"。
     * 但**已失效**的分享仍可举报——链接可能刚从别处打开就已经被撤回了，用户看到的仍是那份内容。
     */
    @Transactional
    public void report(long userId, String shareId, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "举报理由不能为空");
        }
        if (trimmed.length() > MAX_REASON_CHARS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "举报理由不能超过 " + MAX_REASON_CHARS + " 个字符");
        }
        if (reports.countShare(shareId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "分享不存在");
        }
        reports.upsert(shareId, userId, trimmed);
    }

    /** 管理端：举报列表。`status` 为空表示全部；待处理的排在最前。 */
    public java.util.List<ReportView> listReports(String status) {
        return reports.list(status, 100).stream()
                .map(row -> new ReportView(row.getId(), row.getShareId(), row.getTitle(),
                        row.getReason(), row.getStatus(), row.getCreatedAt()))
                .toList();
    }

    /**
     * 管理端下架：把被举报的那条分享撤下来，并把举报标为已处理。
     *
     * <p>两步在同一个事务里：只下架不标状态会让管理员反复看到同一条，只标状态不下架等于
     * 什么都没做。分享已经被撤过也算成功（幂等）——管理员的目的达到了。
     */
    @Transactional
    public boolean takedown(long reportId) {
        NoteReportMapper.ReportRow report = reports.findById(reportId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "举报不存在"));
        revokeByAdmin(report.getShareId());
        reports.markTakenDown(reportId, Instant.now());
        return true;
    }
}

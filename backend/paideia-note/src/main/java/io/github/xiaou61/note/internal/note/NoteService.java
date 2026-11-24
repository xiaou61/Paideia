package io.github.xiaou61.note.internal.note;

import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.note.internal.link.NoteLinkService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.SearchQuery;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 笔记（目录 / 文档）的用例。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>user_id 只从调用方（令牌）来</b>，这里不接受任何"代表别人"的参数。</li>
 *   <li><b>读不到一律当不存在</b>：拿不到就返回，调用方给 404——不区分"没有"与"不是你的"。</li>
 *   <li><b>长度校验在写入前</b>：它是信任边界（写入方包含助手与导入），超长要报错而不是让
 *       数据库报一句用户看不懂的话。</li>
 * </ul>
 *
 * <p>WORK-087 起这些记录挂在一棵「知识库 → 目录 → 文档」的树上：{@code book_id / parent_id /
 * kind / sort_order / deleted_at} 与正文同表。删目录是**软删整棵子树**（{@code deleted_at}）。
 */
@Service
public class NoteService {

    /** 与迁移里的列宽一致。 */
    private static final int MAX_TITLE_CHARS = 200;
    /** 旧的扁平写入路径（助手 append / 兼容端点）沿用历史上限。 */
    private static final int MAX_BODY_CHARS = 20_000;
    /** 树里的正式文档正文上限：允许写长文。 */
    private static final int MAX_DOC_BODY_CHARS = 200_000;
    /** 列表一次最多给多少条。ponytail: 固定值，等真有人攒到那么多再做分页。 */
    private static final int LIST_LIMIT = 200;
    /** 单个知识库的节点上限，防一次导入把库塞爆。 */
    private static final int MAX_NODES_PER_BOOK = 5_000;
    /** 目录嵌套深度上限，防移动成环之外的病态深树。 */
    private static final int MAX_TREE_DEPTH = 32;
    /** 学习单元标识长度上限，与迁移里的列宽一致。 */
    private static final int MAX_PAIR_KEY_CHARS = 64;
    /** 分类标签长度上限，与迁移里的列宽一致。 */
    private static final int MAX_CATEGORY_CHARS = 64;
    /** 难度评级取值。 */
    private static final java.util.Set<String> LEVELS = java.util.Set.of("入门", "进阶", "高级");

    private final NoteMapper mapper;
    private final NoteBookService books;
    private final NoteLinkService links;

    NoteService(NoteMapper mapper, NoteBookService books, NoteLinkService links) {
        this.mapper = mapper;
        this.books = books;
        this.links = links;
    }

    /** 对外形状。**不含 userId**——界面不需要，带上只是多一个会漏出去的字段。 */
    public record NoteView(Long id, Long bookId, String kind, Long parentId, int sortOrder,
                           Long entryId, String title, String body, Instant createdAt, Instant updatedAt,
                           String docType, String pairKey, String category, String level) {
    }

    /** 列表项：不带正文，带一段截断的摘要（列表页用）。 */
    public record NoteSummary(Long id, Long entryId, String title, String excerpt, Instant updatedAt) {
    }

    /** 树节点：不带正文，供前端组装目录树。 */
    public record NoteNode(Long id, String kind, Long parentId, int sortOrder, String title,
                           String excerpt, Instant updatedAt, String docType, String pairKey,
                           String category, String level) {
    }

    // ---------------------------------------------------------------- 旧扁平写入（兼容）

    @Transactional
    public NoteView create(long userId, Long entryId, String title, String body) {
        long bookId = books.ensureDefaultBook(userId);
        Note note = new Note();
        note.setUserId(userId);
        note.setBookId(bookId);
        note.setKind("doc");
        note.setParentId(null);
        note.setSortOrder(0);
        note.setEntryId(entryId);
        note.setTitle(requireText(title, "标题", MAX_TITLE_CHARS));
        note.setBody(requireBody(body, MAX_BODY_CHARS));
        mapper.insert(note);
        links.rebuild(userId, note.getId(), bookId, note.getBody());
        return read(userId, note.getId());
    }

    public List<NoteSummary> list(long userId) {
        return mapper.listByUser(userId, LIST_LIMIT).stream()
                .map(note -> new NoteSummary(note.getId(), note.getEntryId(), note.getTitle(), note.getExcerpt(), note.getUpdatedAt()))
                .toList();
    }

    /** 本人文档检索；空白关键词返回空（不返回全库）。 */
    public List<NoteSummary> search(long userId, String keyword) {
        String normalized = SearchQuery.normalize(keyword);
        if (normalized == null) {
            return List.of();
        }
        String booleanText = SearchQuery.booleanMode(normalized);
        boolean fulltext = booleanText != null;
        String likePattern = SearchQuery.likePattern(normalized);
        return mapper.search(userId, booleanText, fulltext, likePattern, LIST_LIMIT).stream()
                .map(n -> new NoteSummary(n.getId(), n.getEntryId(), n.getTitle(), n.getExcerpt(), n.getUpdatedAt()))
                .toList();
    }

    public NoteView read(long userId, long id) {
        return toView(findOrThrow(userId, id));
    }

    @Transactional
    public NoteView update(long userId, long id, Long entryId, String title, String body) {
        Note note = findOrThrow(userId, id);
        note.setEntryId(entryId);
        note.setTitle(requireText(title, "标题", MAX_TITLE_CHARS));
        note.setBody(requireBody(body, MAX_BODY_CHARS));
        mapper.update(note);
        links.rebuild(userId, id, note.getBookId(), note.getBody());
        return read(userId, id);
    }

    @Transactional
    public void delete(long userId, long id) {
        softDeleteNode(userId, id);
    }

    /** 供别处（如注销流程）判断笔记是否存在而不用抛异常。 */
    public Optional<NoteView> find(long userId, long id) {
        return mapper.findByIdAndUser(id, userId).map(NoteService::toView);
    }

    // ---------------------------------------------------------------- 知识库文档树

    /** 一个知识库的全部存活节点（平铺，由前端按 parentId 组装层级）。 */
    public List<NoteNode> tree(long userId, long bookId) {
        books.requireOwned(userId, bookId);
        return mapper.listByBook(userId, bookId).stream()
                .map(n -> new NoteNode(n.getId(), n.getKind(), n.getParentId(), n.getSortOrder(),
                        n.getTitle(), null, n.getUpdatedAt(), n.getDocType(), n.getPairKey(),
                        n.getCategory(), n.getLevel()))
                .toList();
    }

    /** 新建目录或文档。kind 只认 folder/doc。 */
    public NoteView createNode(long userId, long bookId, Long parentId, String kind, String title, String body) {
        return createNode(userId, bookId, parentId, kind, title, body, null, null, null, null);
    }

    /** 新建目录或文档，可带文档类型与学习单元标识（普通文档两者都为 null）。 */
    @Transactional
    public NoteView createNode(long userId, long bookId, Long parentId, String kind, String title, String body,
                               String docType, String pairKey) {
        return createNode(userId, bookId, parentId, kind, title, body, docType, pairKey, null, null);
    }

    /** 新建目录或文档，附带文档类型、学习单元标识、分类与难度（均可为 null）。 */
    @Transactional
    public NoteView createNode(long userId, long bookId, Long parentId, String kind, String title, String body,
                               String docType, String pairKey, String category, String level) {
        books.requireOwned(userId, bookId);
        String k = normalizeKind(kind);
        String dt = normalizeDocType(docType);
        String pk = normalizePairKey(pairKey);
        String ca = normalizeCategory(category);
        String lv = normalizeLevel(level);
        if ("folder".equals(k) && (dt != null || pk != null || ca != null || lv != null)) {
            throw invalid("目录不能设置文档类型、配对标识、分类或难度");
        }
        List<Note> nodes = mapper.listByBook(userId, bookId);
        if (nodes.size() >= MAX_NODES_PER_BOOK) {
            throw invalid("知识库节点数已达上限（" + MAX_NODES_PER_BOOK + "）");
        }
        Map<Long, Note> byId = byId(nodes);
        if (parentId != null) {
            Note parent = byId.get(parentId);
            if (parent == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "父节点不存在");
            }
            if (!"folder".equals(parent.getKind())) {
                throw invalid("只能在目录下创建内容");
            }
            if (depthOf(byId, parentId) + 1 > MAX_TREE_DEPTH) {
                throw invalid("目录层级过深（上限 " + MAX_TREE_DEPTH + " 层）");
            }
        }
        Note note = new Note();
        note.setUserId(userId);
        note.setBookId(bookId);
        note.setKind(k);
        note.setDocType(dt);
        note.setPairKey(pk);
        note.setCategory(ca);
        note.setLevel(lv);
        note.setParentId(parentId);
        note.setSortOrder(nextSortOrder(nodes, parentId));
        note.setTitle(requireText(title, "标题", MAX_TITLE_CHARS));
        note.setBody("folder".equals(k) ? "" : requireBody(body, MAX_DOC_BODY_CHARS));
        mapper.insert(note);
        if ("doc".equals(k)) {
            links.rebuild(userId, note.getId(), bookId, note.getBody());
        }
        return read(userId, note.getId());
    }

    public NoteView createFolder(long userId, long bookId, Long parentId, String name) {
        return createNode(userId, bookId, parentId, "folder", name, "");
    }

    /** 更新文档正文/标题（不碰层级）。 */
    public NoteView updateDoc(long userId, long id, Long entryId, String title, String body) {
        Note existing = findOrThrow(userId, id);
        return updateDoc(userId, id, entryId, title, body, existing.getDocType(), existing.getPairKey(),
                existing.getCategory(), existing.getLevel());
    }

    /** 更新文档；docType/pairKey 为 null 时保留原值（兼容只传标题正文的重命名/保存调用）。 */
    @Transactional
    public NoteView updateDoc(long userId, long id, Long entryId, String title, String body,
                              String docType, String pairKey) {
        Note existing = findOrThrow(userId, id);
        return updateDoc(userId, id, entryId, title, body, docType, pairKey,
                existing.getCategory(), existing.getLevel());
    }

    /** 更新文档；docType/pairKey/category/level 为 null 时保留原值。 */
    @Transactional
    public NoteView updateDoc(long userId, long id, Long entryId, String title, String body,
                              String docType, String pairKey, String category, String level) {
        Note note = findOrThrow(userId, id);
        if ("folder".equals(note.getKind())
                && (docType != null || pairKey != null || category != null || level != null)) {
            throw invalid("目录不能设置文档类型、配对标识、分类或难度");
        }
        note.setEntryId(entryId);
        note.setTitle(requireText(title, "标题", MAX_TITLE_CHARS));
        note.setBody(requireBody(body, MAX_DOC_BODY_CHARS));
        if (docType != null) {
            note.setDocType(normalizeDocType(docType));
        }
        if (pairKey != null) {
            note.setPairKey(normalizePairKey(pairKey));
        }
        if (category != null) {
            note.setCategory(normalizeCategory(category));
        }
        if (level != null) {
            note.setLevel(normalizeLevel(level));
        }
        mapper.update(note);
        links.rebuild(userId, id, note.getBookId(), note.getBody());
        return read(userId, id);
    }

    /**
     * 移动到某个目录下（{@code targetParentId=null} 表示移到库根）。
     *
     * <p>拒绝：移动到自身、移动到自己的子孙（成环）、跨库、把目录挂到文档下、移动后超深。
     */
    @Transactional
    public void move(long userId, long id, Long targetParentId, int sortOrder) {
        Note note = findOrThrow(userId, id);
        long bookId = note.getBookId();
        List<Note> nodes = mapper.listByBook(userId, bookId);
        Map<Long, Note> byId = byId(nodes);
        if (targetParentId != null) {
            if (targetParentId == id) {
                throw invalid("不能移动到自身");
            }
            Note target = byId.get(targetParentId);
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "目标目录不存在");
            }
            if (!"folder".equals(target.getKind())) {
                throw invalid("只能移动到目录下");
            }
            if (isSelfOrDescendant(byId, targetParentId, id)) {
                throw invalid("不能移动到自己的子孙节点下");
            }
            int newDeepest = depthOf(byId, targetParentId) + subtreeHeight(nodes, id);
            if (newDeepest > MAX_TREE_DEPTH) {
                throw invalid("移动后层级过深（上限 " + MAX_TREE_DEPTH + " 层）");
            }
        }
        mapper.move(id, userId, targetParentId, sortOrder);
    }

    /** 软删一个节点及其整棵子树。 */
    @Transactional
    public void softDeleteNode(long userId, long id) {
        Note note = findOrThrow(userId, id);
        List<Note> nodes = mapper.listByBook(userId, note.getBookId());
        for (Long nodeId : subtreeIds(nodes, id)) {
            mapper.softDelete(nodeId, userId);
            links.deleteForSource(userId, nodeId);
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private Note findOrThrow(long userId, long id) {
        return mapper.findByIdAndUser(id, userId).orElseThrow(NoteService::notFound);
    }

    private static NoteView toView(Note note) {
        return new NoteView(note.getId(), note.getBookId(), note.getKind(), note.getParentId(),
                note.getSortOrder(), note.getEntryId(), note.getTitle(),
                note.getBody() == null ? "" : note.getBody(), note.getCreatedAt(), note.getUpdatedAt(),
                note.getDocType(), note.getPairKey(), note.getCategory(), note.getLevel());
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "doc";
        }
        String k = kind.trim();
        if (!"doc".equals(k) && !"folder".equals(k)) {
            throw invalid("节点类型只能是 doc 或 folder");
        }
        return k;
    }

    /** 文档类型：null/空=普通文档；否则只能是 tutorial / gap_fill。 */
    private static String normalizeDocType(String docType) {
        if (docType == null || docType.isBlank()) {
            return null;
        }
        String value = docType.trim();
        if (!"tutorial".equals(value) && !"gap_fill".equals(value)) {
            throw invalid("文档类型只能是 tutorial 或 gap_fill");
        }
        return value;
    }

    /** 学习单元标识：null/空=无；否则去空白后不超过 64 字符。 */
    private static String normalizePairKey(String pairKey) {
        if (pairKey == null || pairKey.isBlank()) {
            return null;
        }
        String value = pairKey.trim();
        if (value.length() > MAX_PAIR_KEY_CHARS) {
            throw invalid("配对标识不能超过 " + MAX_PAIR_KEY_CHARS + " 个字符");
        }
        return value;
    }

    /** 分类标签：null/空=无；否则去空白后不超过 64 字符。 */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String value = category.trim();
        if (value.length() > MAX_CATEGORY_CHARS) {
            throw invalid("分类不能超过 " + MAX_CATEGORY_CHARS + " 个字符");
        }
        return value;
    }

    /** 难度：null/空=无；否则只能是 入门 / 进阶 / 高级。 */
    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String value = level.trim();
        if (!LEVELS.contains(value)) {
            throw invalid("难度只能是 入门、进阶 或 高级");
        }
        return value;
    }

    private static Map<Long, Note> byId(List<Note> nodes) {
        Map<Long, Note> map = new HashMap<>();
        for (Note n : nodes) {
            map.put(n.getId(), n);
        }
        return map;
    }

    /** 节点深度：库根节点为 1。 */
    private static int depthOf(Map<Long, Note> byId, long id) {
        int depth = 1;
        Long cur = id;
        Set<Long> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            Note n = byId.get(cur);
            if (n == null || n.getParentId() == null) {
                break;
            }
            depth++;
            cur = n.getParentId();
        }
        return depth;
    }

    /** candidate 是否等于 ancestor 或是它的子孙（沿 parent 链上溯）。 */
    private static boolean isSelfOrDescendant(Map<Long, Note> byId, long candidate, long ancestor) {
        Long cur = candidate;
        Set<Long> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            if (cur == ancestor) {
                return true;
            }
            Note n = byId.get(cur);
            cur = n == null ? null : n.getParentId();
        }
        return false;
    }

    private static int subtreeHeight(List<Note> nodes, long rootId) {
        Map<Long, List<Long>> children = childrenMap(nodes);
        return height(rootId, children);
    }

    private static int height(long id, Map<Long, List<Long>> children) {
        List<Long> kids = children.get(id);
        if (kids == null || kids.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (Long kid : kids) {
            max = Math.max(max, height(kid, children));
        }
        return max + 1;
    }

    private static Map<Long, List<Long>> childrenMap(List<Note> nodes) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (Note n : nodes) {
            children.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n.getId());
        }
        return children;
    }

    private static List<Long> subtreeIds(List<Note> nodes, long rootId) {
        Map<Long, List<Long>> children = childrenMap(nodes);
        List<Long> out = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            out.add(cur);
            List<Long> kids = children.get(cur);
            if (kids != null) {
                queue.addAll(kids);
            }
        }
        return out;
    }

    private static int nextSortOrder(List<Note> nodes, Long parentId) {
        int max = -1;
        for (Note n : nodes) {
            if (Objects.equals(n.getParentId(), parentId)) {
                max = Math.max(max, n.getSortOrder());
            }
        }
        return max + 1;
    }

    private static String requireText(String raw, String label, int maxChars) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + "不能为空");
        }
        if (trimmed.length() > maxChars) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + "不能超过 " + maxChars + " 个字符");
        }
        return trimmed;
    }

    /** 正文允许为空串（先建个空壳再写内容），但**不允许超长**。 */
    private static String requireBody(String raw, int maxChars) {
        String body = raw == null ? "" : raw;
        if (body.length() > maxChars) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "正文不能超过 " + maxChars + " 个字符");
        }
        return body;
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "笔记不存在");
    }
}

package io.github.xiaou61.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库模块的对外契约。
 *
 * <p><b>只放真实会被别的模块调用的方法。</b>第一个消费者是 AI 精简（WORK-005）：它需要
 * 按 id 取条目正文来生成精简卡片。在此之前这个接口一直是空的——不是遗漏，是按 WORK-004
 * 定下的纪律"不预先设计猜测出来的方法"。第二个消费者是助手运行时（WORK-006）的**只读工具**：
 * 按关键词检索、取分类树、读某个学习者自己的自评标记。三个方法同样是那时才加的。
 *
 * <p>新增方法时仍守这条：签名只用包根的类型，实现放 {@code internal}，不把内部实体暴露出去。
 * 值类型用**嵌套 record** 而不是各开一个文件——它们是这个契约的一部分。
 */
public interface KnowledgeApi {

    /**
     * 按 id 取**已发布**条目的标题与正文。
     *
     * <p>只给已发布：草稿在学习者侧本来就看不到，让别的模块读到它既没有产品意义，又多一个
     * 越权读取面。不存在与未发布都返回空 {@link Optional}，调用方**无法区分**这两者——
     * 刻意如此，区分开就等于泄露"某个草稿存在"。
     */
    Optional<EntryContent> findPublishedContent(long entryId);

    /**
     * 按关键词检索**已发布**条目。
     *
     * <p>同样只给已发布。空关键词返回最近的若干条（助手问"有哪些内容"时不该得到空结果）；
     * 中文单字在 ngram 索引上召回为零，内部按 {@code SearchQuery} 的判定退回 LIKE。
     */
    List<EntryBrief> searchPublished(String keyword, int limit);

    /**
     * 全部已发布条目的标题与正文（WORK-072）。
     *
     * <p>只服务本地 RAG 索引：调用方必须只索引 public 内容，不能拿它遍历草稿。
     * 默认空实现让测试桩与其它实现不必关心这个可选能力。
     */
    default List<EntryContent> publishedContents() {
        return List.of();
    }

    /** 分类的全量扁平列表（带 parentId，层级由调用方自己拼）。只读。 */
    List<CategoryNode> listCategories();

    /**
     * 某个学习者**自己的**自评标记。
     *
     * <p>**必须由调用方传入 userId**，本方法不做别的归属校验——传别人的 id 就会读到别人的标记。
     * 所以助手那侧把它绑在构造期，不让模型有机会提供这个值。
     */
    List<MarkedEntry> findMarks(long userId, int limit);

    /**
     * 某个学习者在**某一站**的学习记录摘要（WORK-020），供导师提示词注入。
     *
     * <p>只给一行可读文本：状态、各块得分、是否到期待复习。**必须由调用方传入 userId**，
     * 与 {@link #findMarks} 同一纪律——传别人的 id 就读到别人的学情，所以助手那侧把它
     * 绑在构造期，不让模型有机会提供。
     *
     * <p>没有记录时返回空串（不是 null）：导师提示词里不需要额外分支。
     */
    default String learningSummary(long userId, long entryId) {
        // 默认空实现：本接口是"只在真有消费者时才加方法"的契约，但学情是**可选注入**——
        // 测试桩与将来的其它实现不该被迫写一个它们不关心的空方法。
        return "";
    }

    /**
     * 写入一条**自动采集**的条目（WORK-025）。
     *
     * <p>与 {@link #findPublishedContent} 方向相反：那条只读、只给已发布；这条只写、只服务抓取任务。
     * 做成独立方法而不是复用管理端写用例，是因为自动采集有两个管理端没有的约束：
     * <ol>
     *   <li><b>按指纹去重</b>：同一道题会在不同面经里反复出现，重复插入等于每天制造重复内容。
     *       指纹命中时返回 {@code created=false}，不写库。</li>
     *   <li><b>状态由调用方定</b>：抓来的内容先进草稿还是直接发布是产品决定，不在这里写死。</li>
     * </ol>
     *
     * <p>**不做鉴权**：这是模块间调用，不是 HTTP 入口。
     *
     * <p>默认实现抛 {@link UnsupportedOperationException}：与 {@link #learningSummary} 同为
     * "可选能力"——只在真有抓取任务的部署里被调用。给默认实现是为了不让测试桩与其它实现
     * 被迫写一个它们不关心的空方法；真被调用到说明装配错了，应当立刻炸而不是静默成功。
     */
    default CreateOutcome createCollected(CollectedEntry input) {
        throw new UnsupportedOperationException("本实现不支持自动采集写入");
    }

    // ── 文档入库（WORK-076） ───────────────────────────────────────────

    /**
     * 按 id 读取附件字节，供其它模块（文档解析）使用。
     *
     * <p>这是**模块内端口**，不是 HTTP 入口——对外的附件读取仍是既有的免鉴权
     * {@code GET /files/{id}}（id 不可枚举的取舍不变）。返回空表示附件不存在或文件缺失。
     */
    default Optional<AttachmentContent> findAttachment(String fileId) {
        return Optional.empty();
    }

    /**
     * 按相对路径分段创建/复用分类，返回叶子分类 id。
     *
     * <p>用于“上传文件夹保留目录树”：逐段向下查找同名分类，不存在则创建（slug 由路径确定性生成）。
     * {@code segments} 由调用方清洗（去空段、去 {@code .}/{@code ..}、去路径分隔符、限长）；
     * 空列表返回 {@code null}（表示“未分类”）。
     */
    default Long ensureCategoryPath(List<String> segments) {
        throw new UnsupportedOperationException("本实现不支持按路径建分类");
    }

    // ── 训练营（WORK-029） ────────────────────────────────────────────

    /**
     * 概念的全量清单（id + 名称）。训练营出题时用它把模型的 concept 名解析成 id。
     *
     * <p>全量而不是按需：概念数量级是数百，一次取全比逐条查便宜（与
     * {@code ConceptGraphService} 同一取舍）。
     */
    default List<ConceptRef> concepts() {
        return List.of();
    }

    /**
     * 概念 → 载体条目，**只含已发布**。
     *
     * <p>{@code hasBlocks} 表示正文里有 {@code quiz} / {@code challenge} 围栏块——
     * 计划据此把"读"升级成"做"并多给 10 分钟。
     *
     * <p>不传 userId：条目是公共内容，与本人的学习进度无关。
     */
    default Map<Long, List<EntryCarrier>> conceptCarriers(Collection<Long> conceptIds) {
        return Map.of();
    }

    /**
     * 某人到期该复习的条目（{@code review_due_at <= now}）。
     *
     * <p><b>必须由调用方传入 userId</b>，与 {@link #findMarks} 同一纪律——传别人的 id 就会读到
     * 别人的复习安排。训练营那侧把它绑在服务层，不让请求体有机会提供。
     */
    default List<DueEntry> dueReviews(long userId, int limit) {
        return List.of();
    }

    /**
     * 概念的前置关系：概念 id → 它的前置概念 id 列表。
     *
     * <p>只取 {@code prerequisite} 这一种边（{@code related} 是无向的，不构成先后）。
     * 训练营排期用它保证"前置弱的先学"（REQ-019）。
     *
     * <p>全量返回：边数量级与概念同阶，按需查反而多一次往返。
     */
    default Map<Long, List<Long>> conceptPrerequisites() {
        return Map.of();
    }

    /** 概念的最小识别信息。 */
    record ConceptRef(long id, String name) {
    }

    /**
     * 一个概念的载体条目。
     *
     * @param bodyChars 正文字符数，用于估算阅读时长
     * @param hasBlocks 正文是否含 quiz / challenge 围栏块
     */
    record EntryCarrier(long entryId, String title, int bodyChars, boolean hasBlocks) {
    }

    /** 到期复习的一条。`conceptId` 可能为空（条目没挂概念）。 */
    record DueEntry(Long conceptId, long entryId, String title) {
    }

    /** 附件字节（WORK-076）：供文档解析读取，不暴露给 HTTP 层。 */
    record AttachmentContent(String id, String originalName, String contentType, long sizeBytes, byte[] bytes) {
    }

    /** 一条自动采集条目的写入输入。 */
    record CollectedEntry(
            String title,
            String body,
            long categoryId,
            /** draft | published */
            String status,
            /** 题目指纹：规范化后的题目文本，用于跨帖跨天去重 */
            String fingerprint,
            /** 原始来源链接（原帖地址）；平台自产内容为 null */
            String sourceUrl) {
    }

    /** 写入结果：新建了还是已存在（已存在时给出原 id）。 */
    record CreateOutcome(long entryId, boolean created) {
    }

    /** 检索结果行：只要 id 与标题；正文由 {@link #findPublishedContent} 另取。 */
    record EntryBrief(long id, String title) {
    }

    /** 分类节点。`slug` 供按稳定标识查找（WORK-025 的抓取要按 slug 定位分类）。 */
    record CategoryNode(long id, String name, String slug, Long parentId) {
    }

    /** 「我标记过的条目」。`level` 懂／还不懂的取值透传，语义不在这里定义。 */
    record MarkedEntry(long entryId, String title, String level) {
    }
}

package io.github.xiaou61.note.internal.note;

import java.time.Instant;

/**
 * 一条笔记。
 *
 * <p>可变类 + {@code useGeneratedKeys}，与项目其它实体一致。
 */
class Note {

    private Long id;
    private Long userId;
    /** 可挂靠的知识条目；不是每条笔记都挂。 */
    private Long entryId;
    private String title;
    private String body;
    private Instant createdAt;
    private Instant updatedAt;

    /** 只有列表查询会填：正文前 200 字。写入路径不带它。 */
    private String excerpt;

    /** 所属知识库；迁移回填后必有值。 */
    private Long bookId;
    /** 节点类型：目录(folder) 还是文档(doc)。 */
    private String kind = "doc";
    /** 父节点（NULL=库根）。 */
    private Long parentId;
    /** 同级排序。 */
    private int sortOrder;
    /** 软删标记（NULL=存活）；删除目录时整棵子树一起置。 */
    private Instant deletedAt;

    /** 文档类型：tutorial / gap_fill；NULL=普通文档。目录恒为 NULL。 */
    private String docType;
    /** 学习单元标识；同一知识库内同 pair_key 的两篇（教程+查漏补缺）为一对。 */
    private String pairKey;
    /** 分类标签（自由文本，≤64）；NULL=未分类。 */
    private String category;
    /** 难度评级：入门 / 进阶 / 高级；NULL=未评级。 */
    private String level;

    /** 所属知识库名称：仅列表/搜索投影附带，写入路径不带它。 */
    private String bookName;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getUserId() {
        return userId;
    }

    void setUserId(Long userId) {
        this.userId = userId;
    }

    Long getEntryId() {
        return entryId;
    }

    void setEntryId(Long entryId) {
        this.entryId = entryId;
    }

    String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    String getBody() {
        return body;
    }

    void setBody(String body) {
        this.body = body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    Long getBookId() {
        return bookId;
    }

    void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    String getKind() {
        return kind;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    Long getParentId() {
        return parentId;
    }

    void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    int getSortOrder() {
        return sortOrder;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    String getDocType() {
        return docType;
    }

    void setDocType(String docType) {
        this.docType = docType;
    }

    String getPairKey() {
        return pairKey;
    }

    void setPairKey(String pairKey) {
        this.pairKey = pairKey;
    }

    String getCategory() {
        return category;
    }

    void setCategory(String category) {
        this.category = category;
    }

    String getLevel() {
        return level;
    }

    void setLevel(String level) {
        this.level = level;
    }

    String getBookName() {
        return bookName;
    }

    void setBookName(String bookName) {
        this.bookName = bookName;
    }
}

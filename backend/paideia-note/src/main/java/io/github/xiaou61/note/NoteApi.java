package io.github.xiaou61.note;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 笔记模块的对外契约（WORK-019）。
 *
 * <p><b>为什么要开这个接口</b>：MCP 工具要能读写笔记，而工具实现不能放在本模块的 `internal`
 * 之外被别的模块引用，也不该让 `paideia-mcp` 直接碰 `NoteService`。所以按项目一贯做法：
 * 第一个真实消费者出现时，把**只被需要的那几个方法**提到包根。
 *
 * <p><b>只暴露四件事</b>：列、读、建、追加。不做"整篇覆盖更新"——外部助手用追加就够了，
 * 而整篇覆盖是最容易让用户手写内容被模型冲掉的操作，不值得为了对称而暴露。
 *
 * <p>归属一律由 `userId` 参数给出，各方法的 SQL 都带 `WHERE user_id = ?`；拿不到
 * （不存在／不是本人的）**一律返回空或抛同一条业务异常**，不区分原因。
 */
public interface NoteApi {

    /** 最近的笔记（不含正文，带截断摘要）。 */
    List<NoteBrief> list(long userId, int limit);

    /** 按 id 读，只限本人。 */
    Optional<NoteContent> find(long userId, long noteId);

    /** 新建一条，返回新 id。 */
    long create(long userId, String title, String body);

    /**
     * 在正文末尾追加一段。
     *
     * @return 追加后的全文；条目不存在或不是本人的返回空
     */
    Optional<NoteContent> append(long userId, long noteId, String text);

    /** 本人的知识库列表（不含正文）。 */
    List<BookBrief> listBooks(long userId);

    /** 新建一个知识库，返回新 id。 */
    long createBook(long userId, String name, String description);

    record NoteBrief(long id, String title, String excerpt, Instant updatedAt) {
    }

    record NoteContent(long id, String title, String body, Instant updatedAt) {
    }

    record BookBrief(long id, String name, int sortOrder, Instant updatedAt) {
    }
}

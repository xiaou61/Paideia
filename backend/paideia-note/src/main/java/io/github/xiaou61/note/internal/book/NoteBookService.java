package io.github.xiaou61.note.internal.book;

import io.github.xiaou61.note.internal.note.NoteMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库的用例。
 *
 * <p>与笔记一致的三条纪律：user_id 只从令牌来、读不到当不存在、长度校验在写入前。
 * 删库是软删：库标记 + 库内全部节点一起软删（{@code deleted_at}），注销时由监听器硬删。
 */
@Service
public class NoteBookService {

    private static final int MAX_BOOKS_PER_USER = 100;
    private static final int MAX_NAME_CHARS = 200;
    private static final int MAX_DESC_CHARS = 1_000;
    /** 旧扁平写入路径兜底用的默认库名；与迁移回填的名字一致。 */
    private static final String DEFAULT_BOOK_NAME = "我的笔记";

    private final NoteBookMapper books;
    private final NoteMapper notes;

    NoteBookService(NoteBookMapper books, NoteMapper notes) {
        this.books = books;
        this.notes = notes;
    }

    public record BookView(Long id, String name, String description, int sortOrder,
                           Instant createdAt, Instant updatedAt) {
    }

    public List<BookView> list(long userId) {
        return books.listByUser(userId).stream().map(NoteBookService::toView).toList();
    }

    @Transactional
    public BookView create(long userId, String name, String description) {
        String n = requireName(name);
        String d = requireDesc(description);
        if (books.countByUser(userId) >= MAX_BOOKS_PER_USER) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "知识库数量已达上限（" + MAX_BOOKS_PER_USER + "）");
        }
        NoteBook book = new NoteBook();
        book.setUserId(userId);
        book.setName(n);
        book.setDescription(d);
        book.setSortOrder(0);
        books.insert(book);
        return toView(books.findByIdAndUser(book.getId(), userId).orElseThrow(NoteBookService::notFound));
    }

    @Transactional
    public BookView update(long userId, long id, String name, String description, Integer sortOrder) {
        NoteBook book = books.findByIdAndUser(id, userId).orElseThrow(NoteBookService::notFound);
        if (name != null) {
            book.setName(requireName(name));
        }
        book.setDescription(requireDesc(description));
        if (sortOrder != null) {
            book.setSortOrder(sortOrder);
        }
        books.update(book);
        return toView(books.findByIdAndUser(id, userId).orElseThrow(NoteBookService::notFound));
    }

    /** 软删知识库：库标记 + 库内全部节点一起软删。 */
    @Transactional
    public void delete(long userId, long id) {
        if (books.softDelete(id, userId) == 0) {
            throw notFound();
        }
        notes.softDeleteByBook(userId, id);
    }

    /** 校验"这个库是我的"；不存在/不是本人的一律 404。 */
    public void requireOwned(long userId, long bookId) {
        if (books.findByIdAndUser(bookId, userId).isEmpty()) {
            throw notFound();
        }
    }

    /** 给旧的扁平写入路径兜底：没有库就建一个「我的笔记」，返回其 id。 */
    @Transactional
    public long ensureDefaultBook(long userId) {
        List<NoteBook> existing = books.listByUser(userId);
        for (NoteBook b : existing) {
            if (DEFAULT_BOOK_NAME.equals(b.getName())) {
                return b.getId();
            }
        }
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        NoteBook book = new NoteBook();
        book.setUserId(userId);
        book.setName(DEFAULT_BOOK_NAME);
        book.setDescription(null);
        book.setSortOrder(0);
        books.insert(book);
        return book.getId();
    }

    private static BookView toView(NoteBook book) {
        return new BookView(book.getId(), book.getName(), book.getDescription(), book.getSortOrder(),
                book.getCreatedAt(), book.getUpdatedAt());
    }

    private static String requireName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "知识库名称不能为空");
        }
        if (trimmed.length() > MAX_NAME_CHARS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "知识库名称不能超过 " + MAX_NAME_CHARS + " 个字符");
        }
        return trimmed;
    }

    private static String requireDesc(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() > MAX_DESC_CHARS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "知识库描述不能超过 " + MAX_DESC_CHARS + " 个字符");
        }
        return trimmed;
    }

    private static BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
    }
}

package io.github.xiaou61.note.internal.api;

import io.github.xiaou61.note.NoteApi;
import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.note.internal.note.NoteService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link NoteApi} 的实现：把调用转给既有的 {@link NoteService}。
 *
 * <p>**不复制一份查询**：笔记的写入校验（标题长度、正文上限）、归属过滤与"读不到一律当不存在"
 * 都在 `NoteService` 里。这里只是把内部形状换成包根契约的形状——多加一层是为了让跨模块调用
 * 只看到契约，而不是再造一套笔记逻辑。
 */
@Component
public class NoteApiImplementation implements NoteApi {

    /** 一次最多列多少条；与 `NoteService` 的列表上限一致，这里只是把模型给的值夹住。 */
    private static final int MAX_LIST = 50;

    private final NoteService notes;
    private final NoteBookService books;

    NoteApiImplementation(NoteService notes, NoteBookService books) {
        this.notes = notes;
        this.books = books;
    }

    @Override
    public List<NoteBrief> list(long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIST));
        return notes.list(userId).stream()
                .limit(capped)
                .map(summary -> new NoteBrief(summary.id(), summary.title(), summary.excerpt(), summary.updatedAt()))
                .toList();
    }

    @Override
    public Optional<NoteContent> find(long userId, long noteId) {
        return notes.find(userId, noteId)
                .map(view -> new NoteContent(view.id(), view.title(), view.body(), view.updatedAt()));
    }

    @Override
    public long create(long userId, String title, String body) {
        return notes.create(userId, null, title, body == null ? "" : body).id();
    }

    @Override
    public Optional<NoteContent> append(long userId, long noteId, String text) {
        Optional<NoteService.NoteView> existing = notes.find(userId, noteId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        NoteService.NoteView note = existing.get();
        String body = note.body() == null || note.body().isEmpty()
                ? text
                : note.body() + "\n\n" + text;
        NoteService.NoteView updated = notes.update(userId, noteId, note.entryId(), note.title(), body);
        return Optional.of(new NoteContent(updated.id(), updated.title(), updated.body(), updated.updatedAt()));
    }

    @Override
    public List<BookBrief> listBooks(long userId) {
        return books.list(userId).stream()
                .map(book -> new BookBrief(book.id(), book.name(), book.sortOrder(), book.updatedAt()))
                .toList();
    }

    @Override
    public long createBook(long userId, String name, String description) {
        return books.create(userId, name, description).id();
    }
}

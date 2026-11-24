package io.github.xiaou61.note.internal.api;

import io.github.xiaou61.note.NoteApi;
import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.note.internal.link.NoteLinkService;
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
    private final NoteLinkService links;

    NoteApiImplementation(NoteService notes, NoteBookService books, NoteLinkService links) {
        this.notes = notes;
        this.books = books;
        this.links = links;
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
    public List<NoteBrief> search(long userId, String keyword, int limit) {
        int capped = Math.max(1, Math.min(limit <= 0 ? MAX_LIST : limit, MAX_LIST));
        return notes.search(userId, keyword).stream()
                .limit(capped)
                .map(s -> new NoteBrief(s.id(), s.title(), s.excerpt(), s.updatedAt()))
                .toList();
    }

    @Override
    public long createBook(long userId, String name, String description) {
        return books.create(userId, name, description).id();
    }

    @Override
    public List<NoteNode> tree(long userId, long bookId) {
        return notes.tree(userId, bookId).stream()
                .map(node -> new NoteNode(node.id(), node.kind(), node.parentId(), node.sortOrder(),
                        node.title(), node.excerpt(), node.updatedAt()))
                .toList();
    }

    @Override
    public Links links(long userId, long noteId) {
        if (notes.find(userId, noteId).isEmpty()) {
            return new Links(List.of(), List.of());
        }
        NoteLinkService.Links found = links.links(userId, noteId);
        return new Links(
                found.outbound().stream().map(r -> new LinkRef(r.noteId(), r.title())).toList(),
                found.inbound().stream().map(r -> new LinkRef(r.noteId(), r.title())).toList());
    }

    @Override
    public long createFolder(long userId, long bookId, Long parentId, String title) {
        return notes.createFolder(userId, bookId, parentId, title).id();
    }

    @Override
    public long createDoc(long userId, long bookId, Long parentId, String title, String body) {
        return notes.createNode(userId, bookId, parentId, "doc", title, body == null ? "" : body).id();
    }

    @Override
    public void move(long userId, long noteId, Long targetParentId, int sortOrder) {
        notes.move(userId, noteId, targetParentId, sortOrder);
    }

    @Override
    public void deleteNode(long userId, long noteId) {
        notes.delete(userId, noteId);
    }
}

package io.github.xiaou61.note.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.xiaou61.note.NoteApi;
import io.github.xiaou61.platform.ToolContext;
import io.github.xiaou61.platform.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 笔记工具：归属只来自上下文、写工具确实标成写、追加不覆盖。
 */
class NoteToolsTest {

    private static final ToolContext CALLER = ToolContext.internal(7L, "USER");

    /** 内存假实现：记录收到的 userId，并模拟"只能是本人的"这条 SQL 过滤。 */
    private static final class FakeNotes implements NoteApi {

        private final Map<Long, NoteContent> rows = new HashMap<>();
        private final List<Long> seenUserIds = new ArrayList<>();
        private long nextId = 1;

        @Override
        public List<NoteBrief> list(long userId, int limit) {
            seenUserIds.add(userId);
            return rows.values().stream()
                    .limit(limit)
                    .map(note -> new NoteBrief(note.id(), note.title(), note.body(), note.updatedAt()))
                    .toList();
        }

        @Override
        public Optional<NoteContent> find(long userId, long noteId) {
            seenUserIds.add(userId);
            return Optional.ofNullable(rows.get(noteId));
        }

        @Override
        public long create(long userId, String title, String body) {
            seenUserIds.add(userId);
            long id = nextId++;
            rows.put(id, new NoteContent(id, title, body, Instant.EPOCH));
            return id;
        }

        @Override
        public Optional<NoteContent> append(long userId, long noteId, String text) {
            seenUserIds.add(userId);
            NoteContent existing = rows.get(noteId);
            if (existing == null) {
                return Optional.empty();
            }
            String merged = existing.body() == null || existing.body().isEmpty()
                    ? text : existing.body() + "\n\n" + text;
            NoteContent updated = new NoteContent(noteId, existing.title(), merged, Instant.EPOCH);
            rows.put(noteId, updated);
            return Optional.of(updated);
        }

        @Override
        public List<NoteBrief> search(long userId, String keyword, int limit) {
            seenUserIds.add(userId);
            return rows.values().stream()
                    .limit(limit)
                    .map(note -> new NoteBrief(note.id(), note.title(), note.body(), note.updatedAt()))
                    .toList();
        }

        @Override
        public List<BookBrief> listBooks(long userId) {
            seenUserIds.add(userId);
            return List.of();
        }

        @Override
        public long createBook(long userId, String name, String description) {
            seenUserIds.add(userId);
            return nextId++;
        }
    }

    private final FakeNotes notes = new FakeNotes();

    @Test
    void createWritesForContextUserAndReturnsId() {
        ToolResult result = new NoteTools.CreateNote(notes)
                .call(CALLER, Map.of("title", "今天的收获", "body", "记住了 ngram", "userId", 999L));

        assertThat(result.error()).isFalse();
        assertThat(result.structured()).containsEntry("noteId", 1L);
        assertThat(notes.seenUserIds).containsExactly(7L);
    }

    @Test
    void createWithoutTitleFailsReadably() {
        ToolResult result = new NoteTools.CreateNote(notes).call(CALLER, Map.of("body", "只有正文"));
        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("标题");
        assertThat(notes.seenUserIds).isEmpty();
    }

    @Test
    void appendKeepsExistingContent() {
        long id = notes.create(7L, "标题", "第一段");

        ToolResult result = new NoteTools.AppendNote(notes).call(CALLER, Map.of("noteId", id, "text", "第二段"));

        assertThat(result.error()).isFalse();
        assertThat(notes.find(7L, id).orElseThrow().body()).isEqualTo("第一段\n\n第二段");
    }

    @Test
    void appendOnMissingNoteReportsNotFound() {
        ToolResult result = new NoteTools.AppendNote(notes).call(CALLER, Map.of("noteId", 42, "text", "x"));
        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("没有找到");
    }

    @Test
    void listClampsLimit() {
        for (int index = 0; index < 60; index++) {
            notes.create(7L, "标题" + index, "");
        }
        new NoteTools.ListNotes(notes).call(CALLER, Map.of("limit", 999));
        // 夹到 50：假实现把 limit 直接用在 stream().limit() 上，所以列表长度能反映它
        ToolResult result = new NoteTools.ListNotes(notes).call(CALLER, Map.of("limit", 999));
        assertThat(result.text().split("\n")).hasSize(50);
    }

    @Test
    void writeToolsAreMarkedAsWrites() {
        assertThat(new NoteTools.CreateNote(notes).descriptor().readOnly()).isFalse();
        assertThat(new NoteTools.AppendNote(notes).descriptor().readOnly()).isFalse();
        assertThat(new NoteTools.ListNotes(notes).descriptor().readOnly()).isTrue();
        assertThat(new NoteTools.GetNote(notes).descriptor().readOnly()).isTrue();
    }
}

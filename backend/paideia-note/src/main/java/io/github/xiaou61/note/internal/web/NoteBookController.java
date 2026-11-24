package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.note.internal.note.NoteService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库端点。全部是**本人范围**：没有"读别人知识库"的路径。
 *
 * <p>树查询也放在这里（{@code GET /{id}/tree}），因为树是"某个库的内容"，
 * 归属校验与库管理共用同一条 {@link NoteBookService#requireOwned}。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/note-books")
class NoteBookController {

    record SaveRequest(String name, String description, Integer sortOrder) {
    }

    private final NoteBookService books;
    private final NoteService notes;

    NoteBookController(NoteBookService books, NoteService notes) {
        this.books = books;
        this.notes = notes;
    }

    @GetMapping
    ApiResponse<List<NoteBookService.BookView>> list() {
        return ApiResponse.ok(books.list(requireUserId()));
    }

    @PostMapping
    ApiResponse<NoteBookService.BookView> create(@RequestBody SaveRequest request) {
        return ApiResponse.ok(books.create(requireUserId(), request.name(), request.description()));
    }

    @PutMapping("/{id}")
    ApiResponse<NoteBookService.BookView> update(@PathVariable("id") long id, @RequestBody SaveRequest request) {
        return ApiResponse.ok(books.update(requireUserId(), id, request.name(), request.description(), request.sortOrder()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Boolean> delete(@PathVariable("id") long id) {
        books.delete(requireUserId(), id);
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/tree")
    ApiResponse<List<NoteService.NoteNode>> tree(@PathVariable("id") long id) {
        return ApiResponse.ok(notes.tree(requireUserId(), id));
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}

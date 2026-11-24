package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.note.NoteService;
import io.github.xiaou61.note.internal.link.NoteLinkService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记端点。全部是**本人范围**：没有"读别人笔记"的路径，也没有管理员查看的路径
 * （只有用户主动分享出去的那份才会被别人看到，那走的是免鉴权的公开端点）。
 *
 * <p>{@code entryId} 可选：笔记可以挂靠某个知识条目，也可以不挂（随手记的东西）。
 *
 * <p>{@code bookId} 可选：不带就沿用旧的扁平写入（落进默认库「我的笔记」）；
 * 带 {@code bookId} 就按知识库文档树建节点（{@code kind=doc|folder}，可带 {@code parentId}）。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/notes")
class NoteController {

    record SaveRequest(Long bookId, Long parentId, String kind, Long entryId, String title, String body) {
    }

    record MoveRequest(Long parentId, Integer sortOrder) {
    }

    private final NoteService notes;
    private final NoteLinkService linkService;

    NoteController(NoteService notes, NoteLinkService linkService) {
        this.notes = notes;
        this.linkService = linkService;
    }

    @GetMapping
    ApiResponse<List<NoteService.NoteSummary>> list() {
        return ApiResponse.ok(notes.list(requireUserId()));
    }

    @GetMapping("/search")
    ApiResponse<List<NoteService.NoteSummary>> search(
            @RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(notes.search(requireUserId(), q));
    }

    @GetMapping("/{id}")
    ApiResponse<NoteService.NoteView> read(@PathVariable("id") long id) {
        return ApiResponse.ok(notes.read(requireUserId(), id));
    }

    @GetMapping("/{id}/links")
    ApiResponse<NoteLinkService.Links> links(@PathVariable("id") long id) {
        long userId = requireUserId();
        notes.read(userId, id);
        return ApiResponse.ok(linkService.links(userId, id));
    }

    @PostMapping
    ApiResponse<NoteService.NoteView> create(@RequestBody SaveRequest request) {
        long userId = requireUserId();
        if (request.bookId() == null) {
            return ApiResponse.ok(notes.create(userId, request.entryId(), request.title(), request.body()));
        }
        return ApiResponse.ok(notes.createNode(userId, request.bookId(), request.parentId(),
                request.kind(), request.title(), request.body()));
    }

    @PutMapping("/{id}")
    ApiResponse<NoteService.NoteView> update(@PathVariable("id") long id, @RequestBody SaveRequest request) {
        return ApiResponse.ok(notes.updateDoc(requireUserId(), id, request.entryId(), request.title(), request.body()));
    }

    @PutMapping("/{id}/move")
    ApiResponse<Boolean> move(@PathVariable("id") long id, @RequestBody MoveRequest request) {
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        notes.move(requireUserId(), id, request.parentId(), sortOrder);
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    ApiResponse<Boolean> delete(@PathVariable("id") long id) {
        notes.delete(requireUserId(), id);
        return ApiResponse.ok(true);
    }

    private static long requireUserId() {
        return CurrentUser.userId();
    }
}

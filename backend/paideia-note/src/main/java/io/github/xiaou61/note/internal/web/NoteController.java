package io.github.xiaou61.note.internal.web;

import io.github.xiaou61.note.internal.note.NoteService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
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
 * 笔记端点。全部是**本人范围**：没有"读别人笔记"的路径，也没有管理员查看的路径
 * （只有用户主动分享出去的那份才会被别人看到，那走的是 TASK-011 的另一条免鉴权端点）。
 *
 * <p>`entryId` 可选：笔记可以挂靠某个知识条目，也可以不挂（随手记的东西）。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/notes")
class NoteController {

    record SaveRequest(Long entryId, String title, String body) {
    }

    private final NoteService notes;

    NoteController(NoteService notes) {
        this.notes = notes;
    }

    @GetMapping
    ApiResponse<List<NoteService.NoteSummary>> list() {
        return ApiResponse.ok(notes.list(requireUserId()));
    }

    @GetMapping("/{id}")
    ApiResponse<NoteService.NoteView> read(@PathVariable("id") long id) {
        return ApiResponse.ok(notes.read(requireUserId(), id));
    }

    @PostMapping
    ApiResponse<NoteService.NoteView> create(@RequestBody SaveRequest request) {
        return ApiResponse.ok(notes.create(requireUserId(), request.entryId(), request.title(), request.body()));
    }

    @PutMapping("/{id}")
    ApiResponse<NoteService.NoteView> update(@PathVariable("id") long id, @RequestBody SaveRequest request) {
        return ApiResponse.ok(notes.update(requireUserId(), id, request.entryId(), request.title(), request.body()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Boolean> delete(@PathVariable("id") long id) {
        notes.delete(requireUserId(), id);
        return ApiResponse.ok(true);
    }

    /**
     * 令牌主体 → 账号 id。
     *
     * <p>与知识库、AI 两个模块里那两个同形：本项目的模块各自持有自己那几行身份解析，
     * 而不互相依赖（依赖别人的 internal 被 Modulith 禁止）。三处都是六行，暂时不值得为此
     * 往 security 里加一个公共入口——那会牵动另外两个已有调用点。
     */
    private static long requireUserId() {
        return CurrentUser.userId();
    }
}

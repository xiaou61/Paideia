package io.github.xiaou61.ai.internal.web;

import io.github.xiaou61.ai.internal.memory.MemoryCandidateService;
import io.github.xiaou61.ai.internal.memory.MemoryStore;
import io.github.xiaou61.ai.internal.settings.AiSettingsStore;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆的查看与逐条删除（用户裁决 13）。
 *
 * <p>两个动作都是**本人范围**：没有"看别人的记忆"的端点，也没有分享开关——记忆严格私有
 * （用户裁决 3）。这条不是靠调用方自觉，而是这个控制器上就没有那样的路径。
 *
 * <p>删除路径里带 `source`：两路的 id 各自独立，只说 id 会删错表。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/ai/memories")
class AiMemoryController {

    /**
     * 界面形状。`source` 要回给前端，否则删的时候没法说明删的是哪一路。
     *
     * <p>`origin` / `reason` / `conversationId` 是 WORK-013 加的"它为什么在这里"：面板要能显示哪条
     * 是自动整理的、出自哪段对话、当时为什么这么判。**它是"谁写的"，与 `source`（存在哪一路）
     * 是两件事**，别合并成一个字段。
     */
    record MemoryView(String source, long id, String text, Instant createdAt,
                      String origin, String reason, Long conversationId) {
    }

    /**
     * 写入请求。
     *
     * <p>`type` 必给：两路存在不同的表里，只给一段文本无法判断该进哪一路。
     * `kind`／`refId` 只有结构化那路用得上。
     */
    record WriteRequest(String type, String text, String kind, Long refId) {
    }

    /** 清空的响应：清掉多少条 + 清空之后（关掉了总闸的）三个开关，界面据此一次把状态改对。 */
    record ClearResult(int cleared, AiSettingsStore.Settings settings) {
    }

    private final MemoryStore memories;
    private final MemoryCandidateService candidates;
    private final AiSettingsStore settings;

    AiMemoryController(MemoryStore memories, MemoryCandidateService candidates, AiSettingsStore settings) {
        this.memories = memories;
        this.candidates = candidates;
        this.settings = settings;
    }

    /**
     * 本人记忆的一页（旧 → 新按时间倒序，两路合并）。
     *
     * <p>`limit`/`offset` 是 WORK-013 的分页：面板"加载更多"用。上限由 {@code MemoryStore} 兜住，
     * 传大也没用——"一次能看多少"不该由请求方决定。
     */
    @GetMapping
    ApiResponse<List<MemoryView>> list(@RequestParam(value = "limit", required = false) Integer limit,
                                       @RequestParam(value = "offset", required = false) Integer offset) {
        return ApiResponse.ok(memories.list(AiRequester.require(),
                        limit == null ? 0 : limit, offset == null ? 0 : offset).stream()
                .map(item -> new MemoryView(item.source().name().toLowerCase(java.util.Locale.ROOT),
                        item.id(), item.text(), item.createdAt(),
                        item.origin(), item.reason(), item.conversationId()))
                .toList());
    }

    /**
     * 写入一条记忆。**这是记忆唯一的写入口**。
     *
     * <p>为什么单独成一个端点而不是让模型直接写：用户裁决 7 是"自主只建议不动作"，
     * 所以模型产出的只能是**草案**，由用户点击后调到这里来。模型那条路径不存在——
     * 这是"写操作必须用户确认"在接口层的落点，不是靠前端自觉。
     *
     * <p>归属取调用者：请求体里**没有** userId 字段，传不了别人的。
     */
    @PostMapping
    ApiResponse<Long> create(@RequestBody WriteRequest request) {
        long userId = AiRequester.require();
        // 总开关关着时**拒绝并说清原因**（WORK-013 REQ-007）：静默写进去等于"看起来关了其实还在记"，
        // 那是最坏的一种"关掉了"。界面本来就把按钮禁掉了，这条是接口层的第二道闸。
        if (!settings.get(userId).memoryEnabled()) {
            throw new BizException(ErrorCode.MEMORY_DISABLED,
                    "个性化记忆已关闭，这条不会被记住。可以在「记忆」里打开。");
        }
        if (request == null || request.type() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "类型只能是 structured 或 text");
        }
        return switch (request.type().toLowerCase(java.util.Locale.ROOT)) {
            case "structured" -> ApiResponse.ok(
                    memories.rememberStructured(userId, request.kind(), request.refId(), request.text()));
            case "text" -> ApiResponse.ok(memories.rememberText(userId, request.text()));
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "类型只能是 structured 或 text");
        };
    }

    /**
     * **清空全部记忆并关闭**（WORK-013 REQ-009）。
     *
     * <p>为什么清空与关闭是**同一个端点**：分成两次调用会出现"清空了但没关上"的半状态——用户以为
     * 自己已经关掉了，之后聊几句又攒出新记忆。合成一个动作，"我不要再被记"这件事才是一次操作。
     *
     * <p>与它相对的是"只关不删"：那走 {@code PUT /settings}，一条记忆都不动。两个动作的文案在界面上
     * 必须分开写清。
     */
    @DeleteMapping
    ApiResponse<ClearResult> clear() {
        long userId = AiRequester.require();
        int cleared = memories.clearAll(userId);
        return ApiResponse.ok(new ClearResult(cleared, settings.disableMemory(userId)));
    }

    @DeleteMapping("/{source}/{id}")
    ApiResponse<Boolean> delete(@PathVariable("source") String source, @PathVariable("id") long id) {
        long userId = AiRequester.require();
        MemoryStore.MemoryItem.Source parsed = parseSource(source);
        // 删不到（不是本人的、或本来就不存在）都返回 false，**不区分**——区分开就等于
        // 确认"某个 id 存在"。前端据此刷新列表即可。
        return ApiResponse.ok(memories.delete(userId, parsed, id));
    }

    /**
     * 待确认的候选（WORK-050 REQ-008）。
     *
     * <p>被敏感信息过滤掉的那些**只回长度与原因**，正文永远是 null——把原文回给前端
     * 等于它重新出现在浏览器里，而它本来就是因为不该留才被拦下的。
     */
    @GetMapping("/candidates")
    ApiResponse<List<MemoryCandidateService.Candidate>> pendingCandidates(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return ApiResponse.ok(candidates.pending(AiRequester.require(),
                limit == null ? 20 : limit, offset == null ? 0 : offset));
    }

    /** 接受一条候选：它会成为一条正式记忆。总开关关着时拒绝（与其它写入口同一条闸）。 */
    @PostMapping("/candidates/{id}/accept")
    ApiResponse<Void> acceptCandidate(@PathVariable("id") long id) {
        candidates.accept(AiRequester.require(), id);
        return ApiResponse.ok(null);
    }

    /** 拒绝一条候选：它不会成为记忆。 */
    @PostMapping("/candidates/{id}/reject")
    ApiResponse<Void> rejectCandidate(@PathVariable("id") long id) {
        candidates.reject(AiRequester.require(), id);
        return ApiResponse.ok(null);
    }

    private static MemoryStore.MemoryItem.Source parseSource(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)) {
            case "structured" -> MemoryStore.MemoryItem.Source.STRUCTURED;
            case "text" -> MemoryStore.MemoryItem.Source.TEXT;
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "来源只能是 structured 或 text");
        };
    }
}

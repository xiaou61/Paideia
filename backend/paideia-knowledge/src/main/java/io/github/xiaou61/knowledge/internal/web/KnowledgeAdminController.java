package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.admin.CategoryAdminService;
import io.github.xiaou61.knowledge.internal.admin.ConceptAdminService;
import io.github.xiaou61.knowledge.internal.admin.EntryAdminService;
import io.github.xiaou61.knowledge.internal.admin.RelationAdminService;
import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.category.CategoryTree;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * 知识库的管理端写端点。
 *
 * <p><b>整个 {@code /api/v1/knowledge/admin/**} 前缀由 {@code SecurityConfiguration} 的
 * 一条 {@code hasRole('ADMIN')} 规则保护</b>，所以这里不需要逐方法加权限注解——
 * 而且路径规则默认覆盖将来新增的接口，方法注解的失败模式是"漏加一个就多一个越权写入点"。
 *
 * <p><b>绝不接受客户端传入的用户标识</b>：本控制器的所有操作都不需要用户 id，
 * 唯一用到身份的地方是日志里的操作者（取自 {@code CurrentUser}）。
 */
@ApiAccess(ApiAccess.Access.ADMIN)
@RestController
@RequestMapping("/api/v1/knowledge/admin")
class KnowledgeAdminController {

    private final CategoryService categoryService;
    private final CategoryAdminService categoryAdminService;
    private final EntryAdminService entryAdminService;
    private final RelationAdminService relationAdminService;
    private final ConceptAdminService conceptAdminService;
    private final io.github.xiaou61.knowledge.internal.track.TrackMapper trackMapper;

    KnowledgeAdminController(CategoryService categoryService,
                             CategoryAdminService categoryAdminService,
                             EntryAdminService entryAdminService,
                             RelationAdminService relationAdminService,
                             ConceptAdminService conceptAdminService,
                          io.github.xiaou61.knowledge.internal.track.TrackMapper trackMapper) {
        this.categoryService = categoryService;
        this.categoryAdminService = categoryAdminService;
        this.entryAdminService = entryAdminService;
        this.relationAdminService = relationAdminService;
        this.conceptAdminService = conceptAdminService;
        this.trackMapper = trackMapper;
    }

    /**
     * 导入脚本写轨道与关卡用（WORK-024）。
     *
     * <p>**只有这一个写入口**，而且它只做 upsert：轨道与关卡是内容的一部分，
     * 随 markdown 一起进版本库，不由人在管理端手点。管理端维护界面本项不做。
     */
    @PostMapping("/tracks")
    ApiResponse<Void> upsertTrack(@Valid @RequestBody TrackRequest body) {
        Instant now = Instant.now();
        trackMapper.upsertTrack(body.slug(), body.name(), body.summary(), body.kind(),
                body.sortOrder() == null ? 100 : body.sortOrder(),
                body.mode() == null || body.mode().isBlank() ? "theory" : body.mode(),
                body.theoryTrackSlug() == null || body.theoryTrackSlug().isBlank()
                        ? null : body.theoryTrackSlug(),
                now);
        io.github.xiaou61.knowledge.internal.track.TrackMapper.Track track =
                trackMapper.findBySlug(body.slug());
        if (track == null) {
            return ApiResponse.ok(null);
        }
        if (body.stages() != null) {
            // 整体替换：先清空再重写。只做 upsert 会在重排时按 seq 互相覆盖、末尾被挤掉。
            trackMapper.deleteStages(track.id());
            for (StageRequest stage : body.stages()) {
                trackMapper.upsertStage(track.id(), stage.entryId(), stage.seq(),
                        stage.chapter() == null ? "" : stage.chapter(),
                        stage.requiresEntryId(),
                        stage.level() == null ? "beginner" : stage.level(), now);
            }
        }
        return ApiResponse.ok(null);
    }

    record TrackRequest(
            @NotBlank @Size(max = 64) String slug,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 500) String summary,
            /** course / reading */
            @Size(max = 16) String kind,
            Integer sortOrder,
            /** theory / practice；省略按 theory（WORK-038） */
            @Size(max = 16) String mode,
            /** 实战轨道指向的理论轨道 slug；只用于提示，不构成限制 */
            @Size(max = 64) String theoryTrackSlug,
            List<StageRequest> stages) {
    }

    record StageRequest(long entryId, int seq, @Size(max = 64) String chapter,
                        Long requiresEntryId,
                        /** beginner / intermediate / advanced */
                        @Size(max = 16) String level) {
    }

    @GetMapping("/categories")
    ApiResponse<List<CategoryTree.Node>> categories() {
        // 管理端看**全部**：学习轨道下的分类也要能维护（WORK-024）
        return ApiResponse.ok(categoryService.fullTree());
    }

    @PostMapping("/categories")
    ApiResponse<Long> createCategory(@Valid @RequestBody CategoryRequest body) {
        return ApiResponse.ok(categoryAdminService
                .create(body.parentId(), body.name(), body.slug(), body.sortOrder(), body.lineKind())
                .getId());
    }

    @PutMapping("/categories/{id}")
    ApiResponse<Void> updateCategory(@PathVariable("id") long id, @Valid @RequestBody CategoryRequest body) {
        categoryAdminService.update(id, body.parentId(), body.name(), body.slug(), body.sortOrder(),
                body.lineKind());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/categories/{id}")
    ApiResponse<Void> deleteCategory(@PathVariable("id") long id) {
        categoryAdminService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 条目列表，**含草稿**；{@code status} 可选，传 draft 或 published 时只看那一种。 */
    @GetMapping("/entries")
    ApiResponse<PageResult<EntryAdminService.AdminEntrySummary>> entries(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(entryAdminService.page(status, PageQuery.of(page, size, null)));
    }

    /** 条目详情，**含草稿**——草稿预览用它，因此返回的是一份不过滤状态的数据。 */
    @GetMapping("/entries/{id}")
    ApiResponse<EntryAdminService.AdminEntryDetail> entry(@PathVariable("id") long id) {
        return ApiResponse.ok(entryAdminService.detail(id));
    }

    @PostMapping("/entries")
    ApiResponse<Long> createEntry(@Valid @RequestBody EntryRequest body) {
        return ApiResponse.ok(entryAdminService.create(
                body.title(), body.body(), body.categoryId(), body.status(), body.sortOrder()));
    }

    /** 更新条目，含状态。**发布就是一次把 status 置为 published 的 PUT**，没有单独的发布端点。 */
    @PutMapping("/entries/{id}")
    ApiResponse<Void> updateEntry(@PathVariable("id") long id, @Valid @RequestBody EntryRequest body) {
        entryAdminService.update(id, body.title(), body.body(), body.categoryId(), body.status(),
                body.sortOrder());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/entries/{id}")
    ApiResponse<Void> deleteEntry(@PathVariable("id") long id) {
        entryAdminService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/relations")
    ApiResponse<Long> createRelation(@Valid @RequestBody RelationRequest body) {
        return ApiResponse.ok(relationAdminService.create(
                body.fromEntryId(), body.toEntryId(), body.relationType()));
    }

    @DeleteMapping("/relations/{id}")
    ApiResponse<Void> deleteRelation(@PathVariable("id") long id) {
        relationAdminService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 概念列表（WORK-021），含载体条目数与关系数。 */
    @GetMapping("/concepts")
    ApiResponse<List<ConceptAdminService.ConceptSummary>> concepts() {
        return ApiResponse.ok(conceptAdminService.list());
    }

    @PostMapping("/concepts")
    ApiResponse<Long> createConcept(@Valid @RequestBody ConceptRequest body) {
        return ApiResponse.ok(conceptAdminService.create(body.categoryId(), body.name(), body.summary()));
    }

    @PutMapping("/concepts/{id}")
    ApiResponse<Void> updateConcept(@PathVariable("id") long id, @Valid @RequestBody ConceptRequest body) {
        conceptAdminService.update(id, body.categoryId(), body.name(), body.summary());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/concepts/{id}")
    ApiResponse<Void> deleteConcept(@PathVariable("id") long id) {
        conceptAdminService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 把条目挂到概念上。重复关联也算成功。 */
    @PostMapping("/concepts/{id}/entries")
    ApiResponse<Void> linkConceptEntry(@PathVariable("id") long id, @Valid @RequestBody ConceptEntryRequest body) {
        conceptAdminService.linkEntry(id, body.entryId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/concepts/{id}/entries/{entryId}")
    ApiResponse<Void> unlinkConceptEntry(@PathVariable("id") long id, @PathVariable("entryId") long entryId) {
        conceptAdminService.unlinkEntry(id, entryId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/concept-edges")
    ApiResponse<Long> createConceptEdge(@Valid @RequestBody ConceptEdgeRequest body) {
        return ApiResponse.ok(conceptAdminService.createEdge(
                body.fromConceptId(), body.toConceptId(), body.relationType()));
    }

    @DeleteMapping("/concept-edges/{id}")
    ApiResponse<Void> deleteConceptEdge(@PathVariable("id") long id) {
        conceptAdminService.deleteEdge(id);
        return ApiResponse.ok(null);
    }

    record ConceptRequest(
            Long categoryId,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 500) String summary) {
    }

    record ConceptEntryRequest(@NotNull Long entryId) {
    }

    record ConceptEdgeRequest(
            @NotNull Long fromConceptId,
            @NotNull Long toConceptId,
            @NotBlank @Size(max = 32) String relationType) {
    }

    record CategoryRequest(
            Long parentId,
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 64) String slug,
            Integer sortOrder,
            /** course / reading / 空。空表示普通分类（WORK-020）。 */
            @Size(max = 16) String lineKind) {
    }

    record EntryRequest(
            @NotBlank @Size(max = 200) String title,
            String body,
            Long categoryId,
            @NotBlank String status,
            /** 学习线里的站序；空 = 未声明，退回 (published_at, id)（WORK-020）。 */
            Integer sortOrder) {
    }

    record RelationRequest(Long fromEntryId, Long toEntryId, @NotBlank String relationType) {
    }
}

package io.github.xiaou61.sandbox.internal.web;

import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.sandbox.internal.dataset.SqlAiService;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetMapper;
import io.github.xiaou61.sandbox.internal.dataset.SqlDatasetUploadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据集管理（WORK-035 T-09 / T-10）。
 *
 * <p>权限口径与沙箱一致：挂在 `/api/v1/sql-datasets/**`，只需"已登录"。归属一律取自令牌，
 * 请求体里没有 userId——**这是私人数据集不被越权的第一道**（第二道是 `SqlDatasets.visible`）。
 *
 * <p>管理端动作（下架、恢复）另外校验 ADMIN，见各自的注释。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/sql-datasets")
class SqlDatasetController {

    private final SqlDatasetUploadService uploads;
    private final SqlAiService ai;
    private final SqlDatasetMapper mapper;
    private final io.github.xiaou61.sandbox.internal.run.SqlDatasets datasets;
    private final io.github.xiaou61.sandbox.internal.dataset.SqlDatasetCatalogService catalog;

    SqlDatasetController(SqlDatasetUploadService uploads, SqlAiService ai, SqlDatasetMapper mapper,
                         io.github.xiaou61.sandbox.internal.run.SqlDatasets datasets,
                         io.github.xiaou61.sandbox.internal.dataset.SqlDatasetCatalogService catalog) {
        this.uploads = uploads;
        this.ai = ai;
        this.mapper = mapper;
        this.datasets = datasets;
        this.catalog = catalog;
    }

    /**
     * 上传一个数据集。
     *
     * <p>用 JSON 而不是 multipart：前端已经把文件读成文本了（CodeMirror 里也能直接写），
     * multipart 会多一层解析，而这个"文件"本来就是一串文本。
     */
    @PostMapping
    ApiResponse<CreatedView> upload(@Valid @RequestBody UploadRequest body) {
        long userId = currentUserId();
        String key = uploads.upload(userId, body.name(), body.sql(), body.visibility());
        return ApiResponse.ok(new CreatedView(key));
    }

    /** 我的私人数据集（含未发布的草稿）。 */
    @GetMapping("/mine")
    ApiResponse<List<SqlDatasetMapper.Summary>> mine() {
        long userId = currentUserId();
        List<SqlDatasetMapper.Summary> rows = mapper.listVisible(userId).stream()
                .filter(row -> "private".equals(row.source()) && row.ownerUserId() != null
                        && row.ownerUserId() == userId)
                .toList();
        return ApiResponse.ok(rows);
    }

    /**
     * 数据集目录（WORK-060）：内置公开集 + 我的私人集 + 已发布社区集。
     *
     * <p>只返回展示需要的字段；不返回 schema、SQL 与数据行。
     */
    @GetMapping("/catalog")
    ApiResponse<List<io.github.xiaou61.sandbox.internal.dataset.SqlDatasetCatalogService.CatalogItem>> catalog() {
        return ApiResponse.ok(catalog.catalog(currentUserId()));
    }

    /** 删除自己的私人数据集。 */
    @DeleteMapping("/{key}")
    ApiResponse<Void> delete(@PathVariable String key) {
        long userId = currentUserId();
        int removed = mapper.deleteOwned(key, userId);
        if (removed == 0) {
            // 不是自己的、或不存在——同一句文案，不给出可枚举的差异
            throw new BizException(ErrorCode.NOT_FOUND, "没有这个数据集");
        }
        return ApiResponse.ok(null);
    }

    /**
     * 发布为社区数据集（T-10）。
     *
     * <p>保留旧端点：它等价于 `visibility=public`。新前端用下面的通用端点，
     * 两者共用同一段判定，避免「发布」与「切公开」两套规则漂移。
     */
    @PostMapping("/{key}/publish")
    ApiResponse<Void> publish(@PathVariable String key) {
        setVisibility(currentUserId(), key, true);
        return ApiResponse.ok(null);
    }

    /**
     * 作者切换公开/私有（WORK-060）。
     *
     * <p>**管理员下架优先**：`taken_down` 的数据集作者不能自行改回公开或私有——
     * 否则下架就变成一次「作者点两下就能绕过」的动作。作者仍可以删除它。
     */
    @PostMapping("/{key}/visibility")
    ApiResponse<Void> visibility(@PathVariable String key, @Valid @RequestBody VisibilityRequest body) {
        boolean isPublic;
        if ("public".equals(body.visibility())) {
            isPublic = true;
        } else if ("private".equals(body.visibility())) {
            isPublic = false;
        } else {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "visibility 只能是 private 或 public");
        }
        setVisibility(currentUserId(), key, isPublic);
        return ApiResponse.ok(null);
    }

    private void setVisibility(long userId, String key, boolean isPublic) {
        // 内置集不在这张表里：先给明确文案，而不是让它落到"没有这个数据集"
        if (datasets.isBuiltin(key)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内置数据集不能修改可见性");
        }
        SqlDatasetMapper.Row row = mapper.findByKey(key)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有这个数据集"));
        // 不是自己的、或不存在——同一句文案，不给出可枚举的差异
        if (row.ownerUserId() == null || row.ownerUserId() != userId) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有这个数据集");
        }
        if ("taken_down".equals(row.status())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "已被管理员下架，不能修改可见性");
        }
        java.time.Instant now = java.time.Instant.now();
        if (isPublic) {
            if ("private".equals(row.source())) {
                mapper.promoteToCommunity(key, now);
            } else if ("community".equals(row.source()) && !"published".equals(row.status())) {
                mapper.updateStatus(key, "published", now);
            }
        } else if ("community".equals(row.source())) {
            mapper.demoteToPrivate(key, now);
        }
    }

    /**
     * 举报一个社区数据集（T-10）。
     *
     * <p>任何人都能举报，包括作者自己（虽然没什么意义，但拒绝它只会多一个特例）。
     * 同一人对同一集重复举报只留一条。
     */
    @PostMapping("/{key}/report")
    ApiResponse<Void> report(@PathVariable String key, @Valid @RequestBody ReportRequest body) {
        long userId = currentUserId();
        SqlDatasetMapper.Row row = mapper.findByKey(key)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有这个数据集"));
        if (!"community".equals(row.source())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "只有社区数据集可以被举报");
        }
        mapper.upsertReport(key, userId, body.reason(), java.time.Instant.now());
        return ApiResponse.ok(null);
    }

    /**
     * 在数据集上现场出一道题（WORK-035 T-12）。
     *
     * <p>**参考解会在这个数据集上真跑一遍**，返回的期望结果集来自实际执行——
     * 不是模型自己说的。题不落库，用完即弃。
     */
    @PostMapping("/{key}/question")
    ApiResponse<SqlAiService.GeneratedQuestion> generateQuestion(
            @PathVariable String key, @RequestBody(required = false) QuestionRequest body) {
        long userId = currentUserId();
        String goal = body == null ? null : body.goal();
        return ApiResponse.ok(ai.generateQuestion(userId, key, goal));
    }

    /**
     * 让 AI 造一个数据集并保存（WORK-035 T-13）。
     *
     * <p>生成的 SQL 走**与手动上传完全相同的检查**（白名单 + 干跑）——
     * 不给 AI 开一条更宽松的路。
     */
    @PostMapping("/generate")
    ApiResponse<CreatedView> generateDataset(@RequestBody(required = false) GenerateRequest body) {
        long userId = currentUserId();
        String topic = body == null ? null : body.topic();
        String visibility = body == null ? null : body.visibility();
        return ApiResponse.ok(new CreatedView(ai.generateDataset(userId, topic, visibility)));
    }

    record CreatedView(String key) {
    }

    record QuestionRequest(@Size(max = 200) String goal) {
    }

    record GenerateRequest(@Size(max = 200) String topic, String visibility) {
    }

    record UploadRequest(@NotBlank @Size(max = 100) String name,
                         @NotBlank @Size(max = 600_000) String sql,
                         String visibility) {
    }

    record VisibilityRequest(@NotBlank String visibility) {
    }

    record ReportRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** 令牌主体 → 账号 id。与 SandboxController 同一口径（同一个 `CurrentUser` 工具）。 */
    private static long currentUserId() {
        try {
            return Long.parseLong(io.github.xiaou61.security.CurrentUser.subjectId());
        } catch (NumberFormatException cause) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "令牌主体不是有效的账号标识");
        }
    }
}

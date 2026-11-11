package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.category.CategoryTree;
import io.github.xiaou61.knowledge.internal.entry.EntryService;
import io.github.xiaou61.knowledge.internal.entry.EntryViewService;
import io.github.xiaou61.knowledge.internal.entry.SearchLogService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库的只读端点。
 *
 * <p><b>路径纪律</b>：本控制器挂在 {@code /api/v1/knowledge}，**不含 `admin` 段**，
 * 因此只需"已登录"（由 {@code SecurityConfiguration} 的 `anyRequest().authenticated()` 兜住）。
 * 管理员的写端点挂在 {@code /api/v1/knowledge/admin/**}，由一条更具体的角色规则拦住——
 * 两条规则的顺序在安全配置里有讲究，见那里的注释。
 *
 * <p>与写端点分文件而不是分方法：这样"哪些是写接口"在文件层面一眼可见，
 * 与安全规则的路径前缀一一对应，不会出现"某个写接口混在只读控制器里忘了保护"。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/knowledge")
class KnowledgeReadController {

    /** 一次最多查多少个分类的条数：首页只用到 4 个，50 是给将来留的余量。 */
    private static final int MAX_COUNT_IDS = 50;

    private final CategoryService categoryService;
    private final EntryService entryService;
    private final SearchLogService searchLog;
    private final EntryViewService entryViews;

    KnowledgeReadController(CategoryService categoryService, EntryService entryService,
                            SearchLogService searchLog, EntryViewService entryViews) {
        this.categoryService = categoryService;
        this.entryService = entryService;
        this.searchLog = searchLog;
        this.entryViews = entryViews;
    }

    /**
     * 分类树。
     *
     * <p>刻意返回**全部分类**，包括还没有条目的：导航里少一个分类会让管理员以为分类没建成；
     * 空分类点进去是空列表，这不是问题。
     */
    @GetMapping("/categories")
    ApiResponse<List<CategoryTree.Node>> categories() {
        return ApiResponse.ok(categoryService.tree());
    }

    /**
     * 已发布条目的分页列表。
     *
     * @param categoryId 传父分类时**包含其后代分类**的条目
     * @param q          检索串；长度不足 ngram 分词长度时自动退回 LIKE
     */
    @GetMapping("/entries")
    ApiResponse<PageResult<EntryService.EntrySummary>> entries(
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        PageResult<EntryService.EntrySummary> result =
                entryService.listPublished(categoryId, q, PageQuery.of(page, size, null));
        // 只有带 q 的请求才是"搜索"；记一条供数据大屏算内容缺口（WORK-045）。
        // 失败不影响返回（服务里吞异常），也不额外查一次库——total 就是命中数。
        if (q != null && !q.isBlank()) {
            searchLog.record(CurrentUserId.orNull(), q, result.total());
        }
        return ApiResponse.ok(result);
    }

    /**
     * 若干分类下的已发布条目数（2026-11-09）。
     *
     * <p>给首页「面经」四个方向显示条数：一次请求拿完，避免四个入口各打一次列表接口。
     * 只统计已发布——草稿数不该出现在学习者的首页上。
     *
     * @param ids 逗号分隔的分类 id（Spring 直接绑成 List&lt;Long&gt;）
     */
    @GetMapping("/categories/counts")
    ApiResponse<List<CategoryCountView>> categoryCounts(@RequestParam(name = "ids") List<Long> ids) {
        if (ids.size() > MAX_COUNT_IDS) {
            // 这不是批量接口的入口：传一百个 id 说明调用方用错了，直接拒绝比慢慢查更好
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "一次最多查询 " + MAX_COUNT_IDS + " 个分类的条数");
        }
        Map<Long, Long> counts = entryService.publishedCounts(ids);
        // 按入参顺序返回、缺的补 0：调用方按下发顺序渲染入口，缺项不补会让它对不上
        List<CategoryCountView> views = new ArrayList<>(ids.size());
        for (Long id : new LinkedHashSet<>(ids)) {
            views.add(new CategoryCountView(id, counts.getOrDefault(id, 0L)));
        }
        return ApiResponse.ok(views);
    }

    /** 一个分类的已发布条目数。放在这里而不是另开文件：它唯一的调用方就是上面那个端点。 */
    record CategoryCountView(long id, long count) {
    }

    /**
     * 当前用户看过的条目（2026-11-11）。
     *
     * <p>给知识库列表把「已读」标出来，并在左栏显示「已读 x / y」。数据由打开条目时的
     * 浏览上报累积（{@code POST /entries/{id}/view}，WORK-021），这里只读。
     *
     * <p>未登录（读路径不该 401）时返回空列表：列表页照常渲染，只是没有已读标记。
     */
    @GetMapping("/entry-views")
    ApiResponse<List<EntryViewService.ViewedEntry>> entryViews() {
        Long userId = CurrentUserId.orNull();
        if (userId == null) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(entryViews.listViewed(userId));
    }

    /** 条目详情：正文、分类、同分类内的上一篇／下一篇、反向链接、以及**自己的**自评标记。 */
    @GetMapping("/entries/{id}")
    ApiResponse<EntryService.EntryDetail> entry(@PathVariable("id") long id) {
        // 读路径用 orNull 而不是 require：读不出身份只说明拿不到"我的标记"，
        // 不该让整页详情失败
        return ApiResponse.ok(entryService.detail(id, CurrentUserId.orNull()));
    }
}

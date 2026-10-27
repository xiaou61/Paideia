package io.github.xiaou61.knowledge.internal.web;

import io.github.xiaou61.knowledge.internal.category.CategoryService;
import io.github.xiaou61.knowledge.internal.category.CategoryTree;
import io.github.xiaou61.knowledge.internal.entry.EntryService;
import io.github.xiaou61.knowledge.internal.entry.SearchLogService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.PageQuery;
import io.github.xiaou61.platform.PageResult;
import java.util.List;
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

    private final CategoryService categoryService;
    private final EntryService entryService;
    private final SearchLogService searchLog;

    KnowledgeReadController(CategoryService categoryService, EntryService entryService,
                            SearchLogService searchLog) {
        this.categoryService = categoryService;
        this.entryService = entryService;
        this.searchLog = searchLog;
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

    /** 条目详情：正文、分类、同分类内的上一篇／下一篇、反向链接、以及**自己的**自评标记。 */
    @GetMapping("/entries/{id}")
    ApiResponse<EntryService.EntryDetail> entry(@PathVariable("id") long id) {
        // 读路径用 orNull 而不是 require：读不出身份只说明拿不到"我的标记"，
        // 不该让整页详情失败
        return ApiResponse.ok(entryService.detail(id, CurrentUserId.orNull()));
    }
}

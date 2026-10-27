package io.github.xiaou61.ai.internal.digest.web;

import io.github.xiaou61.ai.internal.digest.DigestClickMapper;
import io.github.xiaou61.ai.internal.digest.DigestCollectionService;
import io.github.xiaou61.ai.internal.digest.DigestMapper;
import io.github.xiaou61.ai.internal.digest.DigestProperties;
import io.github.xiaou61.ai.internal.digest.DigestWeeklyService;
import io.github.xiaou61.platform.ApiAccess;
import io.github.xiaou61.platform.ApiResponse;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import io.github.xiaou61.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习者端的速递读接口（WORK-027）。
 *
 * <p>三个端点都要登录，与知识库同门禁。内容本身是公开新闻，但"哪天看过什么"是隐私，
 * 所以不做匿名访问。
 */
@ApiAccess(ApiAccess.Access.AUTHENTICATED)
@RestController
@RequestMapping("/api/v1/digest")
class DigestController {

    private static final Logger log = LoggerFactory.getLogger(DigestController.class);

    private final DigestCollectionService collection;
    private final DigestMapper mapper;
    private final DigestProperties properties;
    private final DigestClickMapper clicks;

    DigestController(DigestCollectionService collection, DigestMapper mapper, DigestProperties properties,
                     DigestClickMapper clicks) {
        this.collection = collection;
        this.mapper = mapper;
        this.properties = properties;
        this.clicks = clicks;
    }

    /** 一条速递给前端的样子。 */
    record ItemView(long id, String groupKey, String sourceKey, String sourceName, String title,
                    String summary, String url, Integer heat, Integer starsDay, Integer starsWeek,
                    String meta, String language, Long publishedAt, String summaryState) {
    }

    /** 某日的全部条目，按 groupKey 分组。不传 date 取业务时区的今天。 */
    @GetMapping
    ApiResponse<Map<String, List<ItemView>>> byDate(@RequestParam(value = "date", required = false) String date) {
        LocalDate edition = parseDate(date);
        Map<String, List<ItemView>> grouped = new java.util.LinkedHashMap<>();
        collection.groupedByDate(edition).forEach((group, rows) ->
                grouped.put(group, rows.stream().map(DigestController::toView).toList()));
        return ApiResponse.ok(grouped);
    }

    /** 有内容的日期，新到旧。给日期选择器。 */
    @GetMapping("/dates")
    ApiResponse<List<LocalDate>> dates(@RequestParam(value = "limit", required = false) Integer limit) {
        int size = limit == null ? 30 : Math.max(1, Math.min(limit, 90));
        return ApiResponse.ok(mapper.recentDates(size));
    }

    /**
     * 某周周报。不传 week 取上一周；没有则返回 null（前端显示「本周周报尚未生成」）。
     *
     * <p>**返回 null 而不是 404**：AC-013 要求周报缺失时页签给出提示而不是报错。
     */
    @GetMapping("/weekly")
    ApiResponse<WeeklyView> weekly(@RequestParam(value = "week", required = false) String week) {
        LocalDate weekStart = week == null || week.isBlank()
                ? DigestWeeklyService.lastWeekStart(properties.zoneId())
                : parseDate(week);
        DigestMapper.WeeklyRow row = mapper.findWeekly(weekStart);
        return ApiResponse.ok(row == null ? null
                : new WeeklyView(row.weekStart(), row.body(), row.itemCount()));
    }

    record WeeklyView(LocalDate weekStart, String body, int itemCount) {
    }

    /**
     * 记一次速递点击（WORK-045）。
     *
     * <p><b>恒返回 200</b>，即使条目不存在或写库失败。理由：用户点的是外链，前端拿到任何
     * 非 2xx 都不该影响跳转，而这条埋点对用户本身没有价值——报错只是噪音。
     * 失败在服务端记 WARN，供排查"为什么点击数是 0"。
     *
     * <p>归属取自令牌，路径里没有用户标识（与浏览上报同一纪律）。
     */
    @PostMapping("/items/{id}/click")
    ApiResponse<Void> recordClick(@PathVariable("id") long id) {
        try {
            long userId = Long.parseLong(CurrentUser.subjectId());
            clicks.insert(userId, id, Instant.now());
        } catch (RuntimeException failure) {
            log.warn("digest.click.record-failed itemId={} cause={}", id, failure.toString());
        }
        return ApiResponse.ok(null);
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return properties.today();
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException exception) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "日期格式应为 YYYY-MM-DD");
        }
    }

    private static ItemView toView(DigestMapper.Row row) {
        return new ItemView(row.id(), row.groupKey(), row.sourceKey(), row.sourceName(), row.title(),
                row.summary(), row.url(), row.heat(), row.starsDay(), row.starsWeek(), row.meta(),
                row.language(), row.publishedAt(), row.summaryState());
    }
}

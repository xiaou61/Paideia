package io.github.xiaou61.ai.internal.essay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 牛客面经列表的 {@link EssaySource} 实现（WORK-025）。
 *
 * <p><b>这条通路是实测选出来的，不是猜的</b>（2026-10-20，细节见
 * `.agent/changes/WORK-025-八股文专区/research.md`）：
 * <ul>
 *   <li>走 `POST /api/sparta/job-experience/experience/job/list`，一次 20 条，
 *       **`momentData.content` 直接是完整正文**——不必再逐条抓详情页。</li>
 *   <li>`pageSize` 上限是 20，填 50/100 会返回「系统未知错误」。</li>
 *   <li>匿名可读，不需要 Cookie；实测连续 6 次请求不限流。</li>
 *   <li>`contentType=74` 是面经帖，`250` 是另一种形态（无标题、无时间、无正文），跳过。</li>
 * </ul>
 *
 * <p>**这是内部接口**，没有文档、没有版本承诺。它一变，这里就抛异常并由定时任务记日志——
 * 端口化的意义就是那时只换这一个类。
 *
 * <p><b>合集 id 由调用方传入</b>（2026-11-06）：牛客按岗位分合集，Java=639、前端=644、
 * 测试=680、数据=684（2026-11-06 实测）。**无效的 id 不会报错**，上游会退回一个默认列表并照样
 * 返回 200/total=2000——所以配错的表现是"抓到了、但和别的源重复"，不是"抓不到"。
 * 见 {@code docs} 里的实测记录；要防它只能靠多源去重（指纹按 contentId 算，跨源也拦得住）。
 */
@Component
class NowcoderEssaySource implements EssaySource {

    private static final Logger log = LoggerFactory.getLogger(NowcoderEssaySource.class);

    private static final String ENDPOINT =
            "https://gw-c.nowcoder.com/api/sparta/job-experience/experience/job/list";

    /** 上游硬上限，见类注释。 */
    private static final int PAGE_SIZE = 20;

    /** 面经帖的 contentType。 */
    private static final int TYPE_EXPERIENCE = 74;

    private final EssayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http;

    NowcoderEssaySource(EssayProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<FetchedPost> fetchSince(long jobId, long sinceMillis, int maxPages) {
        List<FetchedPost> collected = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            JsonNode records = fetchPage(jobId, page);
            if (records == null || records.isEmpty()) {
                break;
            }
            boolean reachedBoundary = false;
            for (JsonNode record : records) {
                FetchedPost post = toPost(record);
                if (post == null) {
                    continue;
                }
                if (post.publishedAt() < sinceMillis) {
                    // 列表按时间倒序，一旦越过下界，后面的都更早——停止翻页。
                    reachedBoundary = true;
                    break;
                }
                collected.add(post);
            }
            if (reachedBoundary) {
                break;
            }
        }
        return collected;
    }

    private JsonNode fetchPage(long jobId, int page) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("page", page);
        payload.put("pageSize", PAGE_SIZE);
        payload.put("jobId", jobId);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
        } catch (Exception exception) {
            throw failure("构造请求失败", exception);
        }

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw failure("上游返回 HTTP " + response.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                throw failure("上游返回失败：" + root.path("msg").asText("未知"), null);
            }
            return root.path("data").path("records");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("抓取被中断", exception);
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("抓取失败：" + exception.getClass().getSimpleName(), exception);
        }
    }

    /** 把一条列表记录转成帖子；不是面经帖或缺正文的返回 null（跳过，不算失败）。 */
    private FetchedPost toPost(JsonNode record) {
        if (record.path("contentType").asInt(-1) != TYPE_EXPERIENCE) {
            return null;
        }
        JsonNode moment = record.path("momentData");
        String content = moment.path("content").asText("");
        String title = moment.path("title").asText("");
        long createdAt = moment.path("createdAt").asLong(0);
        if (content.isBlank() || createdAt <= 0) {
            return null;
        }
        String id = record.path("contentId").asText("");
        String uuid = moment.path("uuid").asText("");
        String url = uuid.isBlank()
                ? "https://www.nowcoder.com/discuss/" + id
                : "https://www.nowcoder.com/feed/main/detail/" + uuid;
        return new FetchedPost(id, title, content, url, createdAt);
    }

    private static BizException failure(String message, Throwable cause) {
        BizException failure = new BizException(ErrorCode.INTERNAL, message);
        if (cause != null) {
            failure.initCause(cause);
        }
        log.warn("面经抓取失败：{}", message);
        return failure;
    }
}

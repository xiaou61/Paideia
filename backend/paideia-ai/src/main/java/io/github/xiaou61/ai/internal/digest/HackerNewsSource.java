package io.github.xiaou61.ai.internal.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hacker News 首页前 10（WORK-027）。
 *
 * <p>走 Algolia 的公开搜索接口（`search?tags=front_page`），不需要 token、不限流到会挡人的程度。
 *
 * <p>**用 `points` 当 heat**：它是源自身的信号，排序与展示都用它，不另算热度。
 */
@Component
class HackerNewsSource {

    private static final String ENDPOINT =
            "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=10";

    private static final String USER_AGENT = "PaideiaDigest/1.0";

    static final String GROUP = "hackernews";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 抓前 10。失败抛出，由编排层单源 try/catch。 */
    List<FetchedItem> fetch(DigestHttp http, long nowMillis) {
        String body = http.getText(ENDPOINT, USER_AGENT);
        try {
            JsonNode hits = objectMapper.readTree(body).path("hits");
            List<FetchedItem> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                String title = hit.path("title").asText("");
                String url = hit.path("url").asText("");
                // 没有外链的讨论帖（Ask HN 之类）用 HN 自己的讨论页
                if (url.isBlank()) {
                    long objectId = hit.path("objectID").asLong(0);
                    if (objectId > 0) {
                        url = "https://news.ycombinator.com/item?id=" + objectId;
                    }
                }
                if (url.isBlank() || title.isBlank()) {
                    continue;
                }
                int points = hit.path("points").asInt(0);
                int comments = hit.path("num_comments").asInt(0);
                result.add(new FetchedItem(
                        title,
                        url,
                        "",
                        points,
                        null,
                        comments > 0 ? comments + " 条评论" : null,
                        parseCreatedAt(hit, nowMillis),
                        "hackernews",
                        "Hacker News",
                        GROUP));
            }
            return result;
        } catch (java.io.IOException exception) {
            throw new DigestSource.DigestSourceException("HN 响应解析失败", exception);
        }
    }

    /** `created_at_i` 是 epoch 秒；缺失时退回抓取时刻。 */
    private static long parseCreatedAt(JsonNode hit, long nowMillis) {
        long seconds = hit.path("created_at_i").asLong(0);
        return seconds > 0 ? seconds * 1000 : nowMillis;
    }
}

package io.github.xiaou61.ai.internal.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GitHub 仓库的每日 star 增量（WORK-041）。
 *
 * <p>端点：`GET /repos/{owner}/{repo}/stargazers/history?per_page=2`，2026-09 上线。
 * 一次给最多 30 周，这里只要最近两周（本周 + 上周），足够算「昨日」与「近 7 日」。
 *
 * <p><b>必须记住的坑：`week` 标签是周日，但 `days` 从周一开始。</b>
 * 2026-10-26 用三个创建日期已知的仓库核对过：`15 + 267 + 860 = 1142` 精确等于总 star。
 * 映射写成 `dayDate = week + (index + 1) 天`；按「周日开头」读会整体错一天。
 *
 * <p>返回的是「按创建日统计、当前仍存在的 star」，取消 star 会让历史某天的计数变小，
 * 但单日值不会为负。缺失的日期按 0 处理。
 */
@Component
class GithubStarHistory {

    private static final Logger log = LoggerFactory.getLogger(GithubStarHistory.class);

    private static final String ENDPOINT =
            "https://api.github.com/repos/%s/stargazers/history?per_page=2";

    private static final String USER_AGENT = "PaideiaDigest/1.0";

    private static final String API_HOST = "api.github.com";

    private static final int MAX_REDIRECTS = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 一个仓库的两个窗口：`dayStars` 是最近完整 UTC 日，`weekStars` 是最近 7 个完整日。 */
    record Stats(int dayStars, int weekStars) {
    }

    /**
     * 拉一个仓库的历史。
     *
     * @param referenceDay 最近一个完整 UTC 日
     * @return 失败返回 {@code null}，由调用方回退到页面的 period stars
     */
    Stats fetch(String repo, String token, DigestHttp http, LocalDate referenceDay) {
        String url = ENDPOINT.formatted(repo);
        try {
            String body = http.getTextFollowingSameHostRedirects(
                    url, USER_AGENT, headers(token), API_HOST, MAX_REDIRECTS);
            return parse(body, referenceDay);
        } catch (DigestSource.DigestSourceException exception) {
            log.warn("GitHub star 历史失败（{}）：{}", repo, exception.getMessage());
            return null;
        } catch (RuntimeException exception) {
            log.warn("GitHub star 历史解析失败（{}）：{}", repo, exception.getClass().getSimpleName());
            return null;
        }
    }

    /** 解析响应并映射日期。包级可见，单测直接喂 fixture。 */
    Stats parse(String body, LocalDate referenceDay) {
        Map<LocalDate, Integer> days = new HashMap<>();
        try {
            JsonNode weeks = objectMapper.readTree(body);
            for (JsonNode week : weeks) {
                LocalDate sunday = Instant.ofEpochSecond(week.path("week").asLong())
                        .atZone(ZoneOffset.UTC).toLocalDate();
                JsonNode values = week.path("days");
                for (int index = 0; index < 7; index++) {
                    // week 是周日，days[0] 是周一
                    days.put(sunday.plusDays(index + 1), values.path(index).asInt(0));
                }
            }
        } catch (java.io.IOException exception) {
            throw new DigestSource.DigestSourceException("star 历史响应解析失败", exception);
        }
        int day = days.getOrDefault(referenceDay, 0);
        int week = 0;
        for (int index = 0; index < 7; index++) {
            week += days.getOrDefault(referenceDay.minusDays(index), 0);
        }
        return new Stats(day, week);
    }

    private static Map<String, String> headers(String token) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("accept", "application/vnd.github+json");
        headers.put("x-github-api-version", "2026-03-10");
        if (token != null && !token.isBlank()) {
            headers.put("authorization", "Bearer " + token);
        }
        return headers;
    }
}

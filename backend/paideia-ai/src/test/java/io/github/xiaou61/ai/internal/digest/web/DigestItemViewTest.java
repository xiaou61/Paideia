package io.github.xiaou61.ai.internal.digest.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 速递读接口的字段契约（WORK-041）：前端靠 `starsDay/starsWeek` 切换日榜/周榜，
 * 字段名一旦被改掉，前端会静默拿到 `null`。
 */
class DigestItemViewTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("GitHub 条目序列化出 starsDay/starsWeek，且 heat 仍等于 d1")
    void githubStarsAreSerialized() throws Exception {
        DigestController.ItemView view = new DigestController.ItemView(
                1L, "github", "github-trending", "GitHub 热榜", "owner/repo", "摘要",
                "https://github.com/owner/repo", 250, 250, 850, "12345★", "Python",
                1_800_000_000_000L, "ready");

        String json = objectMapper.writeValueAsString(view);

        assertThat(json).contains("\"heat\":250", "\"starsDay\":250", "\"starsWeek\":850");
    }

    @Test
    @DisplayName("非 GitHub 条目的两字段为 null，旧前端忽略即可")
    void nonGithubStarsAreNull() throws Exception {
        DigestController.ItemView view = new DigestController.ItemView(
                2L, "news", "qbitai", "量子位", "标题", null, "https://example.com/a",
                null, null, null, null, null, null, "degraded");

        String json = objectMapper.writeValueAsString(view);

        assertThat(json).contains("\"starsDay\":null", "\"starsWeek\":null");
    }
}

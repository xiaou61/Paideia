package io.github.xiaou61.ai.internal.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本闸的分组轮转（WORK-027 TASK-005）。
 *
 * <p>**2026-10-21 实跑踩到的缺陷**：IT 之家一家 60 条，直接取前 60 条会把配额全占掉，
 * GitHub / 社区 / HN 三组一条都进不来——入库 60 条全是一个源。这组用例钉住"每组都有份"。
 */
class DigestTakeByGroupTest {

    private static FetchedItem item(String group, String url) {
        return new FetchedItem("t", url, "", null, null, null, 0L, "s-" + group, "源" + group, group);
    }

    private static Map<String, FetchedItem> index(List<FetchedItem> items) {
        Map<String, FetchedItem> byFingerprint = new LinkedHashMap<>();
        for (FetchedItem item : items) {
            byFingerprint.put(DigestFingerprint.of(item.url()), item);
        }
        return byFingerprint;
    }

    @Test
    @DisplayName("**单个源不能垄断配额**：一家 100 条时其它组仍进得来")
    void doesNotLetOneGroupMonopolize() {
        List<FetchedItem> all = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add(item("news", "https://ithome.com/" + i));
        }
        all.add(item("github", "https://github.com/a/b"));
        all.add(item("community", "https://juejin.cn/post/1"));
        all.add(item("hackernews", "https://news.ycombinator.com/item?id=1"));

        Map<String, FetchedItem> index = index(all);
        List<String> selected = DigestCollectionService.takeByGroup(
                new ArrayList<>(index.keySet()), index, 60);

        List<String> groups = selected.stream().map(fp -> index.get(fp).groupKey()).toList();
        assertThat(groups).as("配额的 60 条里必须包含其它组").contains("github", "community", "hackernews");
        assertThat(selected).hasSize(60);
    }

    @Test
    @DisplayName("候选不满上限时全取，不丢")
    void takesEverythingUnderLimit() {
        List<FetchedItem> all = List.of(
                item("news", "https://a.com/1"), item("github", "https://github.com/x/y"),
                item("hackernews", "https://hn.com/1"));
        Map<String, FetchedItem> index = index(all);

        List<String> selected = DigestCollectionService.takeByGroup(
                new ArrayList<>(index.keySet()), index, 60);

        assertThat(selected).hasSize(3);
    }

    @Test
    @DisplayName("轮转保持组内相对顺序（组内不重排）")
    void preservesInGroupOrder() {
        List<FetchedItem> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(item("news", "https://n.com/" + i));
        }
        Map<String, FetchedItem> index = index(all);
        List<String> fresh = new ArrayList<>(index.keySet());

        List<String> selected = DigestCollectionService.takeByGroup(fresh, index, 3);

        assertThat(selected).containsExactlyElementsOf(fresh.subList(0, 3));
    }

    @Test
    @DisplayName("上限为 0 时不取任何东西（边界不越界）")
    void zeroLimitTakesNothing() {
        List<FetchedItem> all = List.of(item("news", "https://a.com/1"));
        Map<String, FetchedItem> index = index(all);

        assertThat(DigestCollectionService.takeByGroup(new ArrayList<>(index.keySet()), index, 0))
                .isEmpty();
    }
}

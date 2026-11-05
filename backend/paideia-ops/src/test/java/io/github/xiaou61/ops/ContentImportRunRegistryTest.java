package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 内容导入汇总的环形缓冲 + 最近哈希/时间（WORK-074 C3/C4）。纯内存，无 Spring/DB。 */
class ContentImportRunRegistryTest {

    @Test
    void keepsMostRecentFirstAndCapsAtMax() {
        ContentImportRunRegistry registry = new ContentImportRunRegistry();
        for (int i = 0; i < ContentImportRunRegistry.MAX + 5; i++) {
            registry.record("dir" + i, "hash" + i, i, 0, 0);
        }
        assertThat(registry.size()).isEqualTo(ContentImportRunRegistry.MAX);
        assertThat(registry.recent().get(0).source()).isEqualTo("dir" + (ContentImportRunRegistry.MAX + 4));
        assertThat(registry.recent().get(0).created()).isEqualTo(ContentImportRunRegistry.MAX + 4);
        assertThat(registry.lastHash()).isEqualTo("hash" + (ContentImportRunRegistry.MAX + 4));
        assertThat(registry.lastAt()).isNotNull();
    }

    @Test
    void recordsSummaryFields() {
        ContentImportRunRegistry registry = new ContentImportRunRegistry();
        registry.record("content/courses/python", "abc123", 3, 1, 2);
        ContentImportRun run = registry.recent().get(0);
        assertThat(run.source()).isEqualTo("content/courses/python");
        assertThat(run.sourceHash()).isEqualTo("abc123");
        assertThat(run.created()).isEqualTo(3);
        assertThat(run.updated()).isEqualTo(1);
        assertThat(run.relations()).isEqualTo(2);
        assertThat(run.at()).isNotNull();
    }

    @Test
    void emptyRegistryHasNoLastValues() {
        ContentImportRunRegistry registry = new ContentImportRunRegistry();
        assertThat(registry.lastAt()).isNull();
        assertThat(registry.lastHash()).isNull();
    }
}

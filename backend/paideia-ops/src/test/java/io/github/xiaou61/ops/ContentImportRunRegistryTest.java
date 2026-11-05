package io.github.xiaou61.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 内容导入汇总的环形缓冲（WORK-074 C3）。纯内存，无 Spring/DB。 */
class ContentImportRunRegistryTest {

    @Test
    void keepsMostRecentFirstAndCapsAtMax() {
        ContentImportRunRegistry registry = new ContentImportRunRegistry();
        for (int i = 0; i < ContentImportRunRegistry.MAX + 5; i++) {
            registry.record("dir" + i, i, 0, 0);
        }
        assertThat(registry.size()).isEqualTo(ContentImportRunRegistry.MAX);
        assertThat(registry.recent().get(0).source()).isEqualTo("dir" + (ContentImportRunRegistry.MAX + 4));
        assertThat(registry.recent().get(0).created()).isEqualTo(ContentImportRunRegistry.MAX + 4);
    }

    @Test
    void recordsSummaryFields() {
        ContentImportRunRegistry registry = new ContentImportRunRegistry();
        registry.record("content/courses/python", 3, 1, 2);
        ContentImportRun run = registry.recent().get(0);
        assertThat(run.source()).isEqualTo("content/courses/python");
        assertThat(run.created()).isEqualTo(3);
        assertThat(run.updated()).isEqualTo(1);
        assertThat(run.relations()).isEqualTo(2);
        assertThat(run.at()).isNotNull();
    }
}

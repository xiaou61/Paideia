package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 最近若干次内容导入运行的汇总（WORK-074 C3/C4）。
 *
 * <p>进程内环形、重启清空——够回答"最近导入发生了什么"，并给"距上次导入多久"这个时效指标提供数据源。
 * 真正的漂移判定在仓库侧（`scripts/check-content-drift.mjs` 用 `sourceHash` 比对），因为部署机看不到
 * 仓库内容。
 */
@Component
class ContentImportRunRegistry {

    static final int MAX = 20;

    private final Deque<ContentImportRun> runs = new ArrayDeque<>();

    synchronized void record(String source, String sourceHash, int created, int updated, int relations) {
        runs.addFirst(new ContentImportRun(source, sourceHash, created, updated, relations, Instant.now()));
        while (runs.size() > MAX) {
            runs.removeLast();
        }
    }

    synchronized List<ContentImportRun> recent() {
        return new ArrayList<>(runs);
    }

    synchronized int size() {
        return runs.size();
    }

    synchronized Instant lastAt() {
        return runs.isEmpty() ? null : runs.peekFirst().at();
    }

    synchronized String lastHash() {
        return runs.isEmpty() ? null : runs.peekFirst().sourceHash();
    }
}

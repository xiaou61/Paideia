package io.github.xiaou61.ops;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 最近若干次内容导入运行的汇总（WORK-074 C3）。
 *
 * <p><b>为什么是进程内环形</b>：内容是"改 md → 跑导入脚本"，一次运行就是一条业务事实，量极小
 * （一天几次）。为它开一张表 + 迁移不划算；而"最近发生了什么"是管理端真正要看的。
 * 长期趋势（漂移）交给 Prometheus 指标，那里才需要跨重启。
 */
@Component
class ContentImportRunRegistry {

    static final int MAX = 20;

    private final Deque<ContentImportRun> runs = new ArrayDeque<>();

    synchronized void record(String source, int created, int updated, int relations) {
        runs.addFirst(new ContentImportRun(source, created, updated, relations, Instant.now()));
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
}

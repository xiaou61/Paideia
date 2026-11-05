package io.github.xiaou61.ops;

import java.time.Instant;

/**
 * 一次内容导入的汇总（WORK-074 C3）。
 *
 * <p>由 `scripts/import-course.mjs` 导入结束后 POST 上报；管理端可查"最近导入成功/跳过/冲突"。
 * 进程内、重启清空——够回答"最近导入发生了什么"，长期趋势由 Prometheus 侧的漂移指标承担。
 */
record ContentImportRun(String source, int created, int updated, int relations, Instant at) {
}

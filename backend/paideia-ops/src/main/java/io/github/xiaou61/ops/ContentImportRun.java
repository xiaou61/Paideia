package io.github.xiaou61.ops;

import java.time.Instant;

/**
 * 一次内容导入的汇总（WORK-074 C3/C4）。
 *
 * <p>由 `scripts/import-course.mjs` 导入结束后 POST 上报；`sourceHash` 是内容目录的确定性哈希，
 * 供仓库侧 `scripts/check-content-drift.mjs` 比对"源改了但没导入"。
 */
record ContentImportRun(String source, String sourceHash, int created, int updated, int relations, Instant at) {
}

package io.github.xiaou61.ai.internal.essay;

import java.util.List;

/**
 * 面经来源端口（WORK-025）。
 *
 * <p><b>为什么是端口</b>：牛客的接口是内部接口（`/api/sparta/`），随时可能改路径、加签名或加风控。
 * 写死在服务层意味着换源要动业务代码；收在端口后面，换源只加一个实现。
 *
 * <p><b>为什么 jobId 是参数</b>（2026-11-06）：牛客的合集按岗位分（Java / 前端 / 测试 / 数据各一个
 * id），一个方向一个源。合集 id 让调用方决定，实现只管"按这个合集抓"——实现里不读配置，
 * 才能在同一个进程里跑多个方向。
 *
 * <p>失败语义：网络失败、上游改了响应结构、被限流，一律抛
 * {@link io.github.xiaou61.platform.BizException}。调用方（定时任务）**必须接住并记日志**，
 * 不能让一次抓取失败把整个任务炸掉。
 */
public interface EssaySource {

    /**
     * 抓取指定合集里不早于 {@code sinceMillis} 的帖子，按时间倒序。
     *
     * @param jobId       合集 id（牛客 `job-experience` 的岗位标签）
     * @param sinceMillis 下界（含）。实现应**翻页直到越过这个时间点**就停，不做全量扫描
     * @param maxPages    最多翻几页。硬上限，防止上游分页异常时无限翻
     */
    List<FetchedPost> fetchSince(long jobId, long sinceMillis, int maxPages);
}

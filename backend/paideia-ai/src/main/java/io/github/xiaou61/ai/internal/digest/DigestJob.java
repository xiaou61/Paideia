package io.github.xiaou61.ai.internal.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每日速递的定时任务（WORK-027）。
 *
 * <p>**2026-10-22 改的四处**（线上实测都会让用户白天看到「这一天还没有速递」）：
 * <ol>
 *   <li>调度从 `fixedDelay=24h` 换成按业务时区的固定 cron：前者的锚点是"上次跑完"，
 *       每次部署补跑一轮就把锚点推后，最终漂到 19:04——而"今天"在 08:00 就翻篇了；</li>
 *   <li>{@code edition_date} 改用业务时区的「今天」，不再用 UTC；</li>
 *   <li>启动时补跑一次，部署当天不必等到第二天；</li>
 *   <li>补跑前先看当天有没有内容，避免反复部署把上游抓十几次。</li>
 * </ol>
 *
 * <p>**异常必须吞掉**：`@Scheduled` 抛出的异常只会让 Spring 记一条错误，下一轮照旧；
 * 但让它抛出去会让"今天抓失败了"这件事淹没在堆栈里。
 *
 * <p>**刻意不加 `@Component`**：只在开关打开时由 {@link DigestConfiguration} 注册。
 */
class DigestJob {

    private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

    /** 启动补跑的等待：避开应用启动时与其它初始化抢资源。 */
    private static final long STARTUP_DELAY_MS = 60 * 1000L;

    private final DigestCollectionService service;
    private final DigestProperties properties;

    DigestJob(DigestCollectionService service, DigestProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /** 每天 {@code paideia.ai.digest.cron} 时刻采集（按 {@code zone} 解释，默认北京 08:00）。 */
    @Scheduled(cron = "${paideia.ai.digest.cron:0 0 8 * * *}",
            zone = "${paideia.ai.digest.zone:Asia/Shanghai}")
    void collectDaily() {
        run("定时");
    }

    /**
     * 启动补跑：只在"今天还没有内容"时真跑一次。
     *
     * <p>**必须带这个判断**：换成 cron 之后重启不再自动补当天内容，而"部署完要等一晚才有东西"
     * 正是原先让人以为坏了的那件事；反过来，每次重启都无条件跑一遍会让每次都抓十来个外部源。
     */
    @Scheduled(initialDelay = STARTUP_DELAY_MS, fixedDelay = Long.MAX_VALUE)
    void catchUpOnStartup() {
        if (service.hasContentFor(properties.today())) {
            log.info("速递启动补跑跳过：{} 已有内容", properties.today());
            return;
        }
        run("启动补跑");
    }

    private void run(String trigger) {
        try {
            DigestCollectionService.RunResult result = service.runOnce();
            if (result.skipReason() != null) {
                log.info("速递{}跳过：{}", trigger, result.skipReason());
            }
        } catch (RuntimeException exception) {
            log.error("速递{}任务异常", trigger, exception);
        }
    }
}

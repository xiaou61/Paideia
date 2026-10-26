package io.github.xiaou61.ai.internal.digest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 每日技术速递的配置（WORK-027）。
 *
 * <p>**默认关闭**，与 essay / decision 同一条纪律：它要访问多个外部站点、每天花一次 AI 调用，
 * 打开是部署时的显式决定。
 *
 * <p><b>源清单在配置里而不是代码里</b>：加一个 RSS 源不该改代码。这是与 WORK-025 不同的一处
 * ——牛客是一个特殊 API，速递的 RSS 源是同质的。
 */
@ConfigurationProperties(prefix = "paideia.ai.digest")
public class DigestProperties {

    /** 是否启用每日抓取。关闭时不注册定时任务。 */
    private boolean enabled = false;

    /**
     * GitHub token。**强烈建议配置**：star 历史端点按 core 限额计费，带 token 5000/时，
     * 25 个候选一天一次毫无压力；不带时匿名 60/时，候选池必须收缩。
     * **值只进被忽略的本地配置文件**，仓库里只留环境变量占位。
     */
    private String githubToken = "";

    /**
     * GitHub trending 页面基地址。生产机直连 `github.com` 会超时，配置成本机反向代理地址
     * （例如 `http://127.0.0.1:8082/trending`）；默认值只给本地与测试用。
     */
    private String githubTrendingUrl = "https://github.com/trending";

    /** 单次运行最多入库条数。**这是成本闸**：决定喂给 AI 的候选池上限。 */
    private int maxItemsPerRun = 60;

    /** 每周最多取多少条日报素材做周报。超了按天分段摘要。 */
    private int weeklyMaxItems = 150;

    /**
     * 业务时区。**「今天」的日期口径以它为准，不用 UTC**。
     *
     * <p>2026-10-22 线上实测的缺陷：`edition_date` 与接口的「今天」都取 UTC 日期，而用户在
     * UTC+8——北京时间每天 08:00 日期就翻篇，可采集要到当晚才跑，于是**每天有约 11 小时
     * 打开页面看到「这一天还没有速递」**，而昨天的满屏内容就在旁边一个按钮的距离。
     */
    private String zone = "Asia/Shanghai";

    /**
     * 采集时刻（Spring cron，按 {@link #zone} 解释）。默认每天 08:00。
     *
     * <p>**08:00 北京 = 00:00 UTC**：GitHub 的 star 历史按 UTC 自然日分桶，08:00 跑才能让
     * 「昨天」是一个完整桶。07:00 跑会差一小时（约 4%），虽然对所有仓库一致，但口径不干净。
     *
     * <p>**必须是固定时刻，不能用 fixedDelay**：后者的锚点是「上次跑完」，
     * 部署重启时补跑一轮就把锚点推到那一刻，之后每天顺延漂移（线上实测漂到了 19:04，
     * 正好落在「日期已翻篇、内容还没到」的那段空窗里）。
     */
    private String cron = "0 0 8 * * *";

    /** 抓多久以内的内容（小时）。30 覆盖「昨天至今」并留容错。 */
    private int lookbackHours = 30;

    /** 单次 HTTP 抓取超时（秒）。 */
    private int timeoutSeconds = 20;

    /** AI 调用超时（秒）。一次调用要处理几十条，给足。 */
    /**
     * **每一批** AI 调用的超时（秒）。
     *
     * <p>**2026-10-21 线上实测**：原先是"整轮 180 秒"，但 60 条会拆成 3 批串行，
     * 第 1 批用完预算后第 2、3 批直接 `InterruptedException`——分批的意义就没了。
     * 改成按批计：每批独立 120 秒，三批互不影响。
     */
    private int aiTimeoutSeconds = 120;

    /** 抓来的内容直出（不设草稿）。用户裁决 D-07。 */
    private boolean publishDirectly = true;

    /** RSS/Atom 源清单。 */
    private List<RssSource> rss = new ArrayList<>();

    /** 一个 RSS 源。 */
    public static class RssSource {

        /** 源标识（小写），如 qbitai。 */
        private String key = "";

        /** 显示名，如「量子位」。 */
        private String name = "";

        /** feed 地址。 */
        private String url = "";

        /** 前端分组：news | github | community | hackernews。 */
        private String group = "news";

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String githubToken) {
        this.githubToken = githubToken;
    }

    public String getGithubTrendingUrl() {
        return githubTrendingUrl;
    }

    public void setGithubTrendingUrl(String githubTrendingUrl) {
        this.githubTrendingUrl = githubTrendingUrl;
    }

    public int getMaxItemsPerRun() {
        return maxItemsPerRun;
    }

    public void setMaxItemsPerRun(int maxItemsPerRun) {
        this.maxItemsPerRun = maxItemsPerRun;
    }

    public int getWeeklyMaxItems() {
        return weeklyMaxItems;
    }

    public void setWeeklyMaxItems(int weeklyMaxItems) {
        this.weeklyMaxItems = weeklyMaxItems;
    }

    public int getLookbackHours() {
        return lookbackHours;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    /** 业务时区。解析失败时退回系统默认而不是抛异常——一个配置笔误不该让整个应用起不来。 */
    public java.time.ZoneId zoneId() {
        try {
            return java.time.ZoneId.of(zone);
        } catch (RuntimeException invalid) {
            return java.time.ZoneId.systemDefault();
        }
    }

    /** 业务意义上的「今天」。**所有日期口径（入库、查库、接口）都必须走这里**。 */
    public LocalDate today() {
        return LocalDate.now(zoneId());
    }

    public void setLookbackHours(int lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getAiTimeoutSeconds() {
        return aiTimeoutSeconds;
    }

    public void setAiTimeoutSeconds(int aiTimeoutSeconds) {
        this.aiTimeoutSeconds = aiTimeoutSeconds;
    }

    public boolean isPublishDirectly() {
        return publishDirectly;
    }

    public void setPublishDirectly(boolean publishDirectly) {
        this.publishDirectly = publishDirectly;
    }

    public List<RssSource> getRss() {
        return rss;
    }

    public void setRss(List<RssSource> rss) {
        this.rss = rss;
    }
}

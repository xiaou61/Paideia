package io.github.xiaou61.ai.internal.essay;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 八股文专区的配置（WORK-025）。
 *
 * <p>**默认关闭**，与沙箱、决策模型同一条纪律：它要访问外部站点并在失败时消耗 AI 解析额度，
 * 打开是部署时的显式决定。
 *
 * <p>`publishDirectly` 默认 false：无人值守 + 自动公开意味着内容质量完全靠提示词，
 * 先进草稿让人过一眼更稳。这是需求里 Q4 的取值。
 *
 * <p><b>多方向（2026-11-06）</b>：{@link #sources} 是「合集 → 分类」的清单，一个源一个方向。
 * 方向由**源**决定而不是让分类器猜——牛客的合集本身就按岗位分（前端 / 测试 / 数据各一个 jobId），
 * 而决策模型的主题标签只有 java / database / career / other 四个，判不出这三个方向。
 */
@ConfigurationProperties(prefix = "paideia.ai.essay")
public class EssayProperties {

    /** 是否启用每日抓取。关闭时不注册定时任务。 */
    private boolean enabled = false;

    /**
     * 单源模式下的合集 id：639 = Java 工程师（实测每天 3~7 条新帖）。
     *
     * <p>**只在 {@link #sources} 为空时生效**，保留它是为了让没配新键的部署配置照旧能跑。
     */
    private long jobId = 639;

    /**
     * 多方向源清单。为空时退化成「单个源 = {jobId, categorySlug}」。
     *
     * <p>每个源自带目标分类：抓完直接落该分类，不经过模型判主题。分类**不由本服务创建**——
     * 不存在的源整源跳过并记日志（{@code CategoryTree} 不过滤空节点，空分类会出现在导航里）。
     */
    private List<SourceSpec> sources = List.of();

    /** 一个源：抓哪个合集、落到哪个分类。 */
    public static class SourceSpec {

        /** 牛客合集 id。 */
        private long jobId;

        /** 目标分类 slug。 */
        private String categorySlug;

        public SourceSpec() {
        }

        public SourceSpec(long jobId, String categorySlug) {
            this.jobId = jobId;
            this.categorySlug = categorySlug;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }

        public String getCategorySlug() {
            return categorySlug;
        }

        public void setCategorySlug(String categorySlug) {
            this.categorySlug = categorySlug;
        }
    }

    /**
     * 实际要跑哪些源：配了 {@code sources} 用它，否则用 {@code jobId} + {@code categorySlug}。
     *
     * <p>方法而不是"在构造时算好"：配置类的字段由 Spring 绑定后才有效。
     */
    public List<SourceSpec> effectiveSources() {
        if (sources != null && !sources.isEmpty()) {
            return List.copyOf(sources);
        }
        return List.of(new SourceSpec(jobId, categorySlug));
    }

    /** 抓取回溯多久：默认 2 天，覆盖"昨天"并留一天容错（定时任务漏跑一次不会丢内容）。 */
    private int lookbackDays = 2;

    /** 每次最多翻几页（20 条/页）。日增量 3~7 条，2~3 页足够。 */
    private int maxPages = 3;

    /** 单次抓取请求超时（秒）。 */
    private int timeoutSeconds = 20;

    /** 抓来的内容进哪个分类的 slug。不存在时任务会跳过并记录（不自动建分类）。 */
    private String categorySlug = "essay-java";

    /**
     * 按判定主题分流：topic → 分类 slug（WORK-070）。
     *
     * <p>**默认空**：为空时不额外问主题，行为与改动前逐条一致。配了以后，入库前先看模型判出的
     * topic；没配到这个 topic、或目标分类不存在，都回退 {@link #categorySlug}。
     *
     * <p>目标分类**不由本服务创建**——{@code CategoryTree} 不过滤空节点，空分类会直接出现在
     * 学习者导航里。建分类是人的决定。
     */
    private Map<String, String> categorySlugs = Map.of();

    /**
     * 抓来直接发布还是先进草稿。
     *
     * <p>默认草稿：无人值守的自动入库，先让人过一眼比直接对外更稳。
     */
    private boolean publishDirectly = false;

    /** 每天最多解析多少道题（**全局**，跨所有源合计）。**这是成本闸**：超了就停下，第二天继续。 */
    private int maxQuestionsPerRun = 40;

    /**
     * **每个源**单次最多处理多少个帖子（防止上游异常返回一大批时把额度吃光）。
     *
     * <p>多源之后从"全局上限"改成"每源上限"：全局上限会被发帖量最大的源吃满，
     * 其它方向一条都进不来。总量仍由 {@link #maxQuestionsPerRun} 兜住。
     */
    private int maxPostsPerRun = 6;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getJobId() {
        return jobId;
    }

    public void setJobId(long jobId) {
        this.jobId = jobId;
    }

    public int getLookbackDays() {
        return lookbackDays;
    }

    public void setLookbackDays(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getCategorySlug() {
        return categorySlug;
    }

    public void setCategorySlug(String categorySlug) {
        this.categorySlug = categorySlug;
    }

    public Map<String, String> getCategorySlugs() {
        return categorySlugs;
    }

    public void setCategorySlugs(Map<String, String> categorySlugs) {
        this.categorySlugs = categorySlugs;
    }

    public boolean isPublishDirectly() {
        return publishDirectly;
    }

    public void setPublishDirectly(boolean publishDirectly) {
        this.publishDirectly = publishDirectly;
    }

    public int getMaxQuestionsPerRun() {
        return maxQuestionsPerRun;
    }

    public void setMaxQuestionsPerRun(int maxQuestionsPerRun) {
        this.maxQuestionsPerRun = maxQuestionsPerRun;
    }

    public int getMaxPostsPerRun() {
        return maxPostsPerRun;
    }

    public void setMaxPostsPerRun(int maxPostsPerRun) {
        this.maxPostsPerRun = maxPostsPerRun;
    }

    public List<SourceSpec> getSources() {
        return sources;
    }

    public void setSources(List<SourceSpec> sources) {
        this.sources = sources;
    }
}

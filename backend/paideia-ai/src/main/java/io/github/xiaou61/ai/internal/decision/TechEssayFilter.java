package io.github.xiaou61.ai.internal.decision;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 面经帖的技术内容过滤（WORK-026，为 WORK-025 的每日抓取准备）。
 *
 * <p>抓来的面经里混着大量非技术帖——实测同一天 14 条里只有 5 条真在讲题，其余是
 * 「终于找到实习了！」「如何提高工程能力」这类。不过滤就会为每条都花一次 AI 解析。
 *
 * <p><b>两层判断，规则的在前</b>：
 * <ol>
 *   <li>规则：正文里命中的**不同**技术词种类数达到阈值就算技术帖。不用出现次数——一条帖子
 *       提十几次「Java」但通篇讲求职经历，按频次会被骗过去。</li>
 *   <li>模型：规则判不准（命中数刚好在阈值附近）时才问决策模型。模型不可用就按规则结论走。</li>
 * </ol>
 *
 * <p>顺序是刻意的：规则零成本、零延迟、不会挂，先让它把大量明显的帖子分掉；只有处在边界上的
 * 才值得花 200ms 问一次模型。反过来（先问模型）等于给每一条都付一次延迟。
 *
 * <p>**一个例外**（WORK-070）：调用方要按主题分流时（{@code needTopic}），规则已判定的技术帖
 * 也会被问一次——只取它判出的主题，判定结论仍按规则。不这么做的话，命中 ≥5 种的帖子根本不会
 * 带 topic，分流等于没有。
 *
 * <p><b>词表覆盖四个方向</b>（2026-11-06）：Java 后端、前端、测试 / 测开、数据 / 大数据。
 * 只收一个方向的词时，新方向会被规则静默判成"非技术帖"（命中 0 种），表现为"接口通了但没有内容"。
 */
@Component
public class TechEssayFilter {

    /**
     * 判定为技术帖所需命中的**不同**技术词种类数。
     *
     * <p>3 是起点不是定论：实测标注过的样例里，技术面经命中 4~16 种、纯信息帖命中 1 种，
     * 中间地带有但不宽。真要调就改这个常量并重跑标注集，别凭感觉改配置。
     */
    static final int MIN_DISTINCT_TERMS = 3;

    /**
     * 英文技术词：套 {@code \b} 卡词边界，免得 {@code java} 命中 {@code javascript} 这类前缀。
     *
     * <p>按方向分组：语言 / 后端、前端、测试、数据。加词时**别加** {@code less}/{@code test}
     * 这种日常英文词——它们会在普通句子里命中，把求职吐槽判成技术帖。
     */
    private static final String ASCII_TERMS =
            "java|jvm|gc|spring|mybatis|hibernate|hashmap|concurrenthashmap|arraylist|linkedlist"
                    + "|threadlocal|aqs|reentrantlock|synchronized|volatile|juc|threadpool|cpu|cache"
                    + "|mysql|innodb|sql|redis|mongodb|kafka|rabbitmq|rocketmq|elasticsearch|zookeeper"
                    + "|nginx|docker|kubernetes|linux|git|http|https|tcp|udp|rest|grpc|mq"
                    + "|b\\+tree|index|transaction|isolation|acid|mvcc|lock|deadlock"
                    // 前端
                    + "|javascript|typescript|react|vue|angular|svelte|nextjs|nuxt|nestjs|node|nodejs"
                    + "|html|css|sass|scss|tailwind|webpack|vite|rollup|esbuild|babel|npm|pnpm|yarn"
                    + "|dom|websocket|http2|cors|csrf|xss|ssr|csr|spa|pwa|redux|pinia|vuex|hooks"
                    // 测试 / 测开
                    + "|junit|testng|mockito|pytest|selenium|appium|cypress|playwright|postman|jmeter|locust"
                    // 数据 / 大数据
                    + "|hive|spark|flink|hadoop|hdfs|hbase|etl|olap|clickhouse|doris|iceberg|hudi"
                    + "|airflow|dolphinscheduler|presto|trino|parquet|orc|avro|pandas|numpy";

    /**
     * 中文技术词：**绝对不能套 {@code \b}**。
     *
     * <p>这是个真实存在过的缺陷（WORK-026 起就这样，2026-11-06 修）：Java 正则的 {@code \b}
     * 依赖 {@code \w}，而 {@code \w} 默认只含 ASCII，中文不算词字符——所以「MySQL索引失效」
     * 里那个「索引」两侧都不是词边界，**永远匹配不上**，那条帖子的命中数只有 1。表现是中文词
     * 在词表里形同虚设，全靠英文词兜着。测试方向的帖子几乎只有中文词（等价类、边界值、冒烟），
     * 不修这条就等于没加测试方向。
     */
    private static final String CHINESE_TERMS =
            // 通用 / 后端
            "微服务|分布式|索引|事务|隔离级别|锁|线程|进程|内存模型|垃圾回收|集合|多态|继承"
                    + "|重载|重写|泛型|反射|注解|序列化|设计模式|单例|算法|数据结构|链表|哈希|二叉树"
                    + "|数据库|缓存|消息队列|负载均衡|并发|异步|回调|接口|抽象类|时间复杂度|死锁"
                    + "|虚拟内存|页面置换|文件系统|操作系统"
                    // 前端
                    + "|前端|浏览器|跨域|事件循环|事件委托|闭包|原型链|作用域|微任务|宏任务"
                    + "|重排|重绘|首屏|白屏|防抖|节流|懒加载|组件|响应式|状态管理|小程序|工程化"
                    + "|渲染|骨架屏|虚拟"
                    // 测试 / 测开
                    + "|测试用例|用例|等价类|边界值|回归测试|冒烟|自动化测试|单元测试|集成测试"
                    + "|接口测试|性能测试|压力测试|压测|白盒|黑盒|覆盖率|断言|缺陷|测试开发"
                    // 数据 / 大数据
                    + "|数仓|数据仓库|数据开发|数据倾斜|维度建模|事实表|维度表|数据湖|湖仓"
                    + "|数据质量|数据血缘|大数据|数据工程|数据平台|实时计算|离线计算|分库分表";

    /** 英文段与中文段的并集；两段各管自己的边界语义。 */
    private static final Pattern TECH_TERMS = Pattern.compile(
            "(?i)(?:\\b(?:" + ASCII_TERMS + ")\\b|" + CHINESE_TERMS + ")");

    private final ContentClassifier classifier;

    public TechEssayFilter(ContentClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 一次判定的结果。
     *
     * @param technical      是否值得进入 AI 解析
     * @param topic          模型判出的主题；没问、或模型没判出来时为空串
     * @param borderline     是否落在规则判不准的边界地带（1~阈值+1 种技术词）
     * @param askedModel     是否真的调用了模型
     * @param changedByModel 模型结论与规则兜底结论是否不同
     */
    public record Verdict(boolean technical, String topic, boolean borderline,
                          boolean askedModel, boolean changedByModel) {

        static final Verdict NOT_TECHNICAL = new Verdict(false, "", false, false, false);
    }

    /**
     * @param text      面经正文
     * @param needTopic 配了按主题分流（WORK-070）时为 true。那时**连规则已判定的技术帖也要问一次
     *                  主题**——否则绝大多数帖子（命中 ≥5 种）根本不会带 topic，分流形同虚设。
     *                  默认为 false，保持"明显的帖子零模型调用"。
     */
    public Verdict judge(String text, boolean needTopic) {
        if (text == null || text.isBlank()) {
            return Verdict.NOT_TECHNICAL;
        }
        // 规则先判：命中数离阈值足够远时直接下结论，不问模型。
        int hits = countDistinctTerms(text);
        if (hits >= MIN_DISTINCT_TERMS + 2) {
            if (!needTopic) {
                return new Verdict(true, "", false, false, false);
            }
            return new Verdict(true, topicOf(classifier.classify(text)), false, true, false);
        }
        if (hits == 0) {
            return Verdict.NOT_TECHNICAL;
        }
        // 边界地带（1~阈值+1）交给模型；模型没给出判断就按规则走。
        boolean byRule = hits >= MIN_DISTINCT_TERMS;
        Optional<ContentClassification> judged = classifier.classify(text);
        return new Verdict(
                judged.map(ContentClassification::technical).orElse(byRule),
                topicOf(judged),
                true,
                true,
                judged.map(item -> item.technical() != byRule).orElse(false));
    }

    private static String topicOf(Optional<ContentClassification> judged) {
        return judged.map(ContentClassification::topic).orElse("");
    }

    /** 命中多少个**不同**的技术词（大小写不敏感，每个词只数一次）。 */
    int countDistinctTerms(String text) {
        Matcher matcher = TECH_TERMS.matcher(text);
        Set<String> seen = new java.util.HashSet<>();
        while (matcher.find()) {
            // 用 group() 而不是 group(1)：词表拆成「英文段 + 中文段」两个非捕获分支，
            // 编号组不再对应单个词。
            seen.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return seen.size();
    }
}

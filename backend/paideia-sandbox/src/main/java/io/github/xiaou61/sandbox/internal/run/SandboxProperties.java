package io.github.xiaou61.sandbox.internal.run;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置（WORK-015）。
 *
 * <p><b>默认关闭</b>：执行用户代码是唯一一类"跑别人的程序"的能力，默认开等于把风险塞给每一个部署。
 * 打开只需要把 {@code paideia.sandbox.enabled} 设为 true，并且本机有 Docker。
 *
 * <p><b>镜像按 digest</b>：`repo:tag` 会漂移，而漂移意味着"今天验过的隔离参数明天可能配一个不同的
 * 基础镜像"。实施时把 digest 填进来（`name@sha256:...`），文档里给出拉取命令。
 *
 * <p>所有上限都是**硬上限**：它们是安全边界，不是性能调优项。改大之前先想清楚最坏情况。
 */
@ConfigurationProperties(prefix = "paideia.sandbox")
public class SandboxProperties {

    /** 总开关。关掉之后接口返回 503，前端不显示「运行」。 */
    private boolean enabled = false;

    /**
     * 容器运行时。留空 = Docker 默认（runc）。
     *
     * <p>设为 `runsc` 即切到 gVisor——这是**升级档**：加固容器挡不住内核漏洞导致的逃逸，
     * gVisor 把系统调用转发到用户态内核，能挡掉大部分提权路径。切换只改这一行，不必改代码。
     */
    private String runtime = "";

    /** 每个镜像的引用，按语言。**按 digest 钉住**。 */
    private String javaImage = "eclipse-temurin:21-jdk-alpine";
    private String pythonImage = "python:3.12-alpine";

    /**
     * MySQL 实训镜像（WORK-032）。**datadir 在构建期已初始化**——实测运行时初始化要 17.9 秒，
     * 而一次练习的 SQL 本身只跑几十毫秒。留空表示该语言不可用。
     */
    private String sqlImage = "";

    /**
     * SQL 专用上限。
     *
     * <p>`/tmp` 的 tmpfs 里那份 78MB datadir **也计入容器内存**，加上 mysqld 的约 350MB 常驻，
     * 512m 会让 mysqld 在 InnoDB 初始化结束后被 OOM 静默杀掉（日志里一行错误都没有）。
     * 2026-10-28 实测：512m 起不来、1g 正常。--memory-swap 必须同时给，否则 swap 会兜住 OOM。
     */
    private String sqlMemory = "1g";
    private String sqlCpus = "1";
    private int sqlPidsLimit = 128;
    private long sqlTimeoutMs = 20_000;

    /**
     * SQL 常驻容器池的大小（WORK-052）。**0 = 关闭池化**，全部走一次性容器。
     *
     * <p>池化把一次执行从 3.5–4.2 秒降到约 120 毫秒：省掉起容器、拷 datadir 与起 mysqld。
     * 代价是隔离从"一人一个容器"降为"共享 mysqld、按库+账号隔离"。
     *
     * <p>每个池容器常驻约 400MB，开之前先看宿主 `available` 有多少。
     */
    private int sqlPoolSize = 2;

    /** 取不到池容器时最多等多久（毫秒）。超时即回落一次性容器——排队会让 p99 变得不可预测。 */
    private long sqlPoolAcquireTimeoutMs = 1500;
    /**
     * SQL 的日配额（WORK-035 T-17 从 20 提到 500）。
     *
     * <p>**它的角色是熔断器，不是预算**：SQL 跑在自己的服务器上、不花 AI 积分，
     * 所以"每天 20 次"没有成本依据，只是让人紧张。真正的保护是 {@code RunSlot} 的每人并发 1。
     * 500 次按每次 3 秒算是 25 分钟不间断地跑，正常人碰不到；真有人写脚本刷，至少有个天花板。
     */
    private int sqlDailyLimit = 500;

    /** 墙钟上限（毫秒）。超时即 `destroyForcibly`。 */
    private long timeoutMs = 10_000;

    /**
     * Spring 项目的镜像（WORK-055）。**留空 = 不启用**：`languages()` 就不会暴露 spring，
     * 前端也就不会渲染运行按钮——渲染一个必然失败的按钮比不渲染更糟。
     */
    private String springImage;

    /** Spring 项目的墙钟上限：全量 `mvn test` 实测 15–27 秒，给 3 倍余量。 */
    private long springTimeoutMs = 90_000;

    /** Spring 容器的内存上限：`@SpringBootTest` 起内嵌 Tomcat 实测 400–600MB。 */
    private String springMemory = "1g";

    /**
     * Spring 容器的 CPU：Maven 编译 + 起 Spring 上下文都是 CPU 密集。
     * 实测 0.5 核时一次跑 55 秒（上下文 22 秒），2 核约 15 秒——档位不变会踩到 90 秒上限。
     */
    private String springCpus = "2";

    /**
     * Linux 判题练习场的镜像（WORK-056）。**留空 = 不启用**，与 sql/spring 同一条口径。
     *
     * <p>选 debian 而不是 alpine：判题要用 GNU 的 `find -printf`（BusyBox 没有），
     * 而课程里讲的 `sed -i`、`ps` 列、`grep -P` 也都是 GNU 行为——镜像与教材不一致时，
     * 学习者照着课敲会得出"课错了"的结论。
     */
    private String shellImage;

    /** 练习容器的内存：跑的是 grep/awk/小脚本，256m 足够。 */
    private String shellMemory = "256m";

    /** 练习容器的 CPU：与 java/python 同档。 */
    private String shellCpus = "0.5";

    /** 练习容器的进程数上限：仍要防 fork 炸弹，但不给 Maven 那种余量。 */
    private int shellPidsLimit = 64;

    /**
     * 练习的墙钟上限。一次提交要跑「学习者脚本 + judge」两段，比单文件语言多一步；
     * 首批题目都是纯状态操作（毫秒级），15 秒只是防死循环的上限，不是预算。
     */
    private long shellTimeoutMs = 15_000;

    /**
     * 练习的日配额。**熔断器不是预算**：它不花 AI 积分，真正的保护是每人并发 1。
     * 200 次按每题重试 10 次算，够把 6 道题练完再从头来一遍。
     */
    private int shellDailyLimit = 200;

    /** 代码与 stdin 的长度上限（字符）。 */
    private int maxCodeChars = 20_000;
    private int maxStdinChars = 4_000;

    // ── 项目模式（WORK-038）────────────────────────────────────────────────
    // 三个上限一起把"提交一次"的规模钉死：数量、单文件、总量。超了直接拒绝，
    // 不做截断——截断后的代码编译不过，报错会指向用户看不懂的地方。
    /** 一次最多提交几个文件。 */
    private int maxProjectFiles = 20;
    /** 单个文件的字符上限。 */
    private int maxProjectFileChars = 64_000;
    /** 所有文件加起来的字符上限。 */
    private int maxProjectTotalChars = 256_000;

    /** stdout / stderr 各自截断到这个字节数。 */
    private int maxOutputBytes = 65_536;

    /** 全局并发：同时最多几个容器。超出的直接拒绝，不排队。 */
    private int maxConcurrency = 2;

    /** 每人每天的运行次数。**被拒绝的不计入**，否则重试会把用户锁死。 */
    private int dailyLimit = 50;

    /** 容器资源上限。 */
    private String memory = "256m";
    private String cpus = "0.5";
    private int pidsLimit = 64;

    /**
     * 支持的围栏语言标识。
     *
     * <p>**按镜像是否配置过筛**：`sql-image` 留空时 sql 不出现，前端就不渲染运行按钮。
     * 渲染一个必然失败的按钮比不渲染更糟——用户会以为是自己点错了（与 `run-challenge.tsx`
     * 的降级口径一致）。
     */
    public Set<String> languages() {
        Set<String> available = new java.util.LinkedHashSet<>();
        if (javaImage != null && !javaImage.isBlank()) {
            available.add("java");
        }
        if (pythonImage != null && !pythonImage.isBlank()) {
            available.add("python");
        }
        if (sqlImage != null && !sqlImage.isBlank()) {
            available.add("sql");
        }
        if (springImage != null && !springImage.isBlank()) {
            available.add("spring");
        }
        if (shellImage != null && !shellImage.isBlank()) {
            available.add("shell");
        }
        return Set.copyOf(available);
    }

    /** 语言 → 镜像。未知语言返回 null（调用方负责拒绝）。 */
    public String imageFor(String language) {
        if (language == null) {
            return null;
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> blankToNull(javaImage);
            case "python" -> blankToNull(pythonImage);
            case "sql" -> blankToNull(sqlImage);
            // Spring 项目（WORK-055）：JDK + Maven + 离线仓库 + 骨架，只能按项目模式跑
            case "spring" -> blankToNull(springImage);
            // Linux 判题练习场（WORK-056）：也走项目模式的文件分帧，但入口是判题脚本
            case "shell" -> blankToNull(shellImage);
            default -> null;
        };
    }

    /** 该语言的墙钟上限。SQL 单独一档：启动 mysqld 本身就要 3 秒上下。 */
    public long timeoutMsFor(String language) {
        if (isSql(language)) {
            return sqlTimeoutMs;
        }
        // 实测：Spring 项目全量 mvn test 1 核 27 秒、2 核 15 秒（见 .agent/notes/spring-project-sandbox-spike.md），
        // 90 秒给到 3 倍余量；普通 java/python 仍是 10 秒。
        if ("spring".equalsIgnoreCase(language)) {
            return springTimeoutMs;
        }
        if (isShell(language)) {
            return shellTimeoutMs;
        }
        return timeoutMs;
    }

    /** 该语言的内存上限。 */
    public String memoryFor(String language) {
        if (isSql(language)) {
            return sqlMemory;
        }
        // Spring 起上下文实测 400–600MB（@SpringBootTest + 内嵌 Tomcat），512m 会 OOM
        if ("spring".equalsIgnoreCase(language)) {
            return springMemory;
        }
        return isShell(language) ? shellMemory : memory;
    }

    /** 该语言的 CPU 上限。 */
    public String cpusFor(String language) {
        if (isSql(language)) {
            return sqlCpus;
        }
        if ("spring".equalsIgnoreCase(language)) {
            return springCpus;
        }
        return isShell(language) ? shellCpus : cpus;
    }

    /** 该语言的进程数上限。 */
    public int pidsLimitFor(String language) {
        if (isSql(language)) {
            return sqlPidsLimit;
        }
        return isShell(language) ? shellPidsLimit : pidsLimit;
    }

    /** 该语言的每日运行次数。 */
    public int dailyLimitFor(String language) {
        if (isSql(language)) {
            return sqlDailyLimit;
        }
        return isShell(language) ? shellDailyLimit : dailyLimit;
    }

    private static boolean isSql(String language) {
        return language != null && "sql".equalsIgnoreCase(language.trim());
    }

    private static boolean isShell(String language) {
        return language != null && "shell".equalsIgnoreCase(language.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public boolean isSupported(String language) {
        return language != null && languages().contains(language.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public String getJavaImage() {
        return javaImage;
    }

    public void setJavaImage(String javaImage) {
        this.javaImage = javaImage;
    }

    public String getPythonImage() {
        return pythonImage;
    }

    public void setPythonImage(String pythonImage) {
        this.pythonImage = pythonImage;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getSpringImage() {
        return springImage;
    }

    public void setSpringImage(String springImage) {
        this.springImage = springImage;
    }

    public long getSpringTimeoutMs() {
        return springTimeoutMs;
    }

    public void setSpringTimeoutMs(long springTimeoutMs) {
        this.springTimeoutMs = springTimeoutMs;
    }

    public String getSpringMemory() {
        return springMemory;
    }

    public void setSpringMemory(String springMemory) {
        this.springMemory = springMemory;
    }

    public String getSpringCpus() {
        return springCpus;
    }

    public void setSpringCpus(String springCpus) {
        this.springCpus = springCpus;
    }

    public String getShellImage() {
        return shellImage;
    }

    public void setShellImage(String shellImage) {
        this.shellImage = shellImage;
    }

    public String getShellMemory() {
        return shellMemory;
    }

    public void setShellMemory(String shellMemory) {
        this.shellMemory = shellMemory;
    }

    public String getShellCpus() {
        return shellCpus;
    }

    public void setShellCpus(String shellCpus) {
        this.shellCpus = shellCpus;
    }

    public int getShellPidsLimit() {
        return shellPidsLimit;
    }

    public void setShellPidsLimit(int shellPidsLimit) {
        this.shellPidsLimit = shellPidsLimit;
    }

    public long getShellTimeoutMs() {
        return shellTimeoutMs;
    }

    public void setShellTimeoutMs(long shellTimeoutMs) {
        this.shellTimeoutMs = shellTimeoutMs;
    }

    public int getShellDailyLimit() {
        return shellDailyLimit;
    }

    public void setShellDailyLimit(int shellDailyLimit) {
        this.shellDailyLimit = shellDailyLimit;
    }

    public int getMaxCodeChars() {
        return maxCodeChars;
    }

    public int getMaxProjectFiles() {
        return maxProjectFiles;
    }

    public void setMaxProjectFiles(int maxProjectFiles) {
        this.maxProjectFiles = maxProjectFiles;
    }

    public int getMaxProjectFileChars() {
        return maxProjectFileChars;
    }

    public void setMaxProjectFileChars(int maxProjectFileChars) {
        this.maxProjectFileChars = maxProjectFileChars;
    }

    public int getMaxProjectTotalChars() {
        return maxProjectTotalChars;
    }

    public void setMaxProjectTotalChars(int maxProjectTotalChars) {
        this.maxProjectTotalChars = maxProjectTotalChars;
    }

    public void setMaxCodeChars(int maxCodeChars) {
        this.maxCodeChars = maxCodeChars;
    }

    public int getMaxStdinChars() {
        return maxStdinChars;
    }

    public void setMaxStdinChars(int maxStdinChars) {
        this.maxStdinChars = maxStdinChars;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getCpus() {
        return cpus;
    }

    public void setCpus(String cpus) {
        this.cpus = cpus;
    }

    public int getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(int pidsLimit) {
        this.pidsLimit = pidsLimit;
    }

    public String getSqlImage() {
        return sqlImage;
    }

    public void setSqlImage(String sqlImage) {
        this.sqlImage = sqlImage;
    }

    public String getSqlMemory() {
        return sqlMemory;
    }

    public void setSqlMemory(String sqlMemory) {
        this.sqlMemory = sqlMemory;
    }

    public String getSqlCpus() {
        return sqlCpus;
    }

    public void setSqlCpus(String sqlCpus) {
        this.sqlCpus = sqlCpus;
    }

    public int getSqlPidsLimit() {
        return sqlPidsLimit;
    }

    public void setSqlPidsLimit(int sqlPidsLimit) {
        this.sqlPidsLimit = sqlPidsLimit;
    }

    public long getSqlTimeoutMs() {
        return sqlTimeoutMs;
    }

    public int getSqlPoolSize() {
        return sqlPoolSize;
    }

    public void setSqlPoolSize(int sqlPoolSize) {
        this.sqlPoolSize = sqlPoolSize;
    }

    public long getSqlPoolAcquireTimeoutMs() {
        return sqlPoolAcquireTimeoutMs;
    }

    public void setSqlPoolAcquireTimeoutMs(long sqlPoolAcquireTimeoutMs) {
        this.sqlPoolAcquireTimeoutMs = sqlPoolAcquireTimeoutMs;
    }

    public void setSqlTimeoutMs(long sqlTimeoutMs) {
        this.sqlTimeoutMs = sqlTimeoutMs;
    }

    public int getSqlDailyLimit() {
        return sqlDailyLimit;
    }

    public void setSqlDailyLimit(int sqlDailyLimit) {
        this.sqlDailyLimit = sqlDailyLimit;
    }

    /** 供状态端点展示：每种语言的镜像（**只列配置了的**，空串不出现）。 */
    public List<String> images() {
        List<String> configured = new java.util.ArrayList<>();
        if (!javaImage.isBlank()) {
            configured.add(javaImage);
        }
        if (!pythonImage.isBlank()) {
            configured.add(pythonImage);
        }
        if (sqlImage != null && !sqlImage.isBlank()) {
            configured.add(sqlImage);
        }
        return List.copyOf(configured);
    }
}

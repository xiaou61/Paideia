package io.github.xiaou61.ops;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运维面板的阈值与连接参数（WORK-028）。
 *
 * <p>阈值放配置而不是写进面板 JSON：写在 Grafana 里就变成了"只有面板知道"，前端拿不到、
 * 测试测不到，改一次要动 JSON。这里一处定义，摘要接口与面板都以它为准。
 */
@ConfigurationProperties(prefix = "paideia.ops")
public class OpsProperties {

    /** Prometheus 的查询地址。只应指向回环。 */
    private String prometheusUrl = "http://127.0.0.1:9090";

    /** 单次查询超时；一个慢查询不得拖住整页。 */
    private long queryTimeoutMs = 2000;

    /** Grafana 地址。反向代理唯一的目标。 */
    private String grafanaUrl = "http://127.0.0.1:3000";

    /** Loki 查询地址（WORK-042）。只应指向回环。 */
    private String lokiUrl = "http://127.0.0.1:3100";

    /** Tempo 查询地址（WORK-043）。只应指向回环。 */
    private String tempoUrl = "http://127.0.0.1:3200";

    /**
     * Grafana webhook 的共享令牌（WORK-042）。为空时 webhook 一律拒绝，手动诊断不受影响。
     * 真实值来自服务器 600 文件，经 systemd 环境变量注入；仓库里只有占位。
     */
    private String hookToken = "";

    private final Thresholds thresholds = new Thresholds();

    private final Diagnosis diagnosis = new Diagnosis();

    private final Incidents incidents = new Incidents();

    private final Notify notify = new Notify();

    private final Patterns patterns = new Patterns();

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public long getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    public void setQueryTimeoutMs(long queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }

    public String getGrafanaUrl() {
        return grafanaUrl;
    }

    public void setGrafanaUrl(String grafanaUrl) {
        this.grafanaUrl = grafanaUrl;
    }

    public String getLokiUrl() {
        return lokiUrl;
    }

    public void setLokiUrl(String lokiUrl) {
        this.lokiUrl = lokiUrl;
    }

    public String getTempoUrl() {
        return tempoUrl;
    }

    public void setTempoUrl(String tempoUrl) {
        this.tempoUrl = tempoUrl;
    }

    public String getHookToken() {
        return hookToken;
    }

    public void setHookToken(String hookToken) {
        this.hookToken = hookToken == null ? "" : hookToken;
    }

    public Diagnosis getDiagnosis() {
        return diagnosis;
    }

    public Incidents getIncidents() {
        return incidents;
    }

    public Notify getNotify() {
        return notify;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public Patterns getPatterns() {
        return patterns;
    }

    public static class Thresholds {

        /** 磁盘已用比例达到此值报警告。 */
        private double diskUsedWarn = 0.70;

        /** 磁盘已用比例达到此值报异常。 */
        private double diskUsedError = 0.85;

        /** systemd 单元内存占上限的比例达到此值报警告（Laya 常态就在这一档）。 */
        private double unitMemoryWarn = 0.75;

        /** 单元内存占上限的比例达到此值报异常。 */
        private double unitMemoryError = 0.90;

        /** 备份超过这么多小时没成功就算陈旧。 */
        private long backupStaleHours = 36;

        public double getDiskUsedWarn() {
            return diskUsedWarn;
        }

        public void setDiskUsedWarn(double diskUsedWarn) {
            this.diskUsedWarn = diskUsedWarn;
        }

        public double getDiskUsedError() {
            return diskUsedError;
        }

        public void setDiskUsedError(double diskUsedError) {
            this.diskUsedError = diskUsedError;
        }

        public double getUnitMemoryWarn() {
            return unitMemoryWarn;
        }

        public void setUnitMemoryWarn(double unitMemoryWarn) {
            this.unitMemoryWarn = unitMemoryWarn;
        }

        public double getUnitMemoryError() {
            return unitMemoryError;
        }

        public void setUnitMemoryError(double unitMemoryError) {
            this.unitMemoryError = unitMemoryError;
        }

        public long getBackupStaleHours() {
            return backupStaleHours;
        }

        public void setBackupStaleHours(long backupStaleHours) {
            this.backupStaleHours = backupStaleHours;
        }
    }

    /**
     * 诊断的开关与上限（WORK-042）。
     *
     * <p>`user-id` 是**告警触发**时记账的账号；手动触发用触发者自己的 id。0 表示未配置，
     * webhook 只会记一条跳过日志。
     */
    public static class Diagnosis {

        private boolean enabled = true;

        private long userId = 0;

        /** 同一告警名的冷却窗口；窗口内重复触发不建新行。 */
        private long cooldownSeconds = 600;

        private int maxLogLines = 60;

        /** 证据总字符上限（摘要 + 指标 + 告警 + 日志 + 发版）。 */
        private int maxEvidenceChars = 16000;

        private int listLimit = 20;

        /** 同实例复用窗口（WORK-049）：窗口内已有 DONE 诊断就不再新开；0 关闭。 */
        private long relatedWindowSeconds = 600;

        /** 应用重启后是否补一次漏掉的诊断（WORK-049）。 */
        private boolean catchUpEnabled = true;

        /** 日志单元白名单（WORK-049）：配置本身就是白名单，未知单元仍然报错。 */
        private List<String> units = List.of("paideia", "laya");

        /** 没有关联事件时，证据窗口向前的默认跨度（分钟，WORK-086）。 */
        private int evidenceWindowMinutes = OpsEvidenceWindow.DEFAULT_MINUTES;

        /** 证据窗口总长上限（分钟，WORK-086）：事件再长也不把整个事故现场塞进提示词。 */
        private int evidenceWindowMaxMinutes = OpsEvidenceWindow.MAX_MINUTES;

        /** 每个单元在证据里最多取多少行原始日志（WORK-086 三段查询各自的 limit）。 */
        private int logsPerUnit = 30;

        /** 每个单元最多保留多少个日志签名（WORK-086）；错误类签名不受此限。 */
        private int signatureTop = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public long getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(long cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }

        public int getMaxLogLines() {
            return maxLogLines;
        }

        public void setMaxLogLines(int maxLogLines) {
            this.maxLogLines = maxLogLines;
        }

        public int getMaxEvidenceChars() {
            return maxEvidenceChars;
        }

        public void setMaxEvidenceChars(int maxEvidenceChars) {
            this.maxEvidenceChars = maxEvidenceChars;
        }

        public int getListLimit() {
            return listLimit;
        }

        public void setListLimit(int listLimit) {
            this.listLimit = listLimit;
        }

        public long getRelatedWindowSeconds() {
            return relatedWindowSeconds;
        }

        public void setRelatedWindowSeconds(long relatedWindowSeconds) {
            this.relatedWindowSeconds = relatedWindowSeconds;
        }

        public boolean isCatchUpEnabled() {
            return catchUpEnabled;
        }

        public void setCatchUpEnabled(boolean catchUpEnabled) {
            this.catchUpEnabled = catchUpEnabled;
        }

        public List<String> getUnits() {
            return units;
        }

        public void setUnits(List<String> units) {
            this.units = units == null ? List.of() : units;
        }

        public int getEvidenceWindowMinutes() {
            return evidenceWindowMinutes;
        }

        public void setEvidenceWindowMinutes(int evidenceWindowMinutes) {
            this.evidenceWindowMinutes = evidenceWindowMinutes;
        }

        public int getEvidenceWindowMaxMinutes() {
            return evidenceWindowMaxMinutes;
        }

        public void setEvidenceWindowMaxMinutes(int evidenceWindowMaxMinutes) {
            this.evidenceWindowMaxMinutes = evidenceWindowMaxMinutes;
        }

        public int getLogsPerUnit() {
            return logsPerUnit;
        }

        public void setLogsPerUnit(int logsPerUnit) {
            this.logsPerUnit = logsPerUnit;
        }

        public int getSignatureTop() {
            return signatureTop;
        }

        public void setSignatureTop(int signatureTop) {
            this.signatureTop = signatureTop;
        }
    }

    /** 告警事件（WORK-043）。 */
    public static class Incidents {

        /** 一条 webhook 最多处理几个 alert，防止异常载荷放大写入。 */
        private int maxAlertsPerNotification = 5;

        /** critical 事件存在时，同通知的 warning 只记时间线、不触发诊断。 */
        private boolean suppressWarningWhenCritical = true;

        public int getMaxAlertsPerNotification() {
            return maxAlertsPerNotification;
        }

        public void setMaxAlertsPerNotification(int maxAlertsPerNotification) {
            this.maxAlertsPerNotification = maxAlertsPerNotification;
        }

        public boolean isSuppressWarningWhenCritical() {
            return suppressWarningWhenCritical;
        }

        public void setSuppressWarningWhenCritical(boolean suppressWarningWhenCritical) {
            this.suppressWarningWhenCritical = suppressWarningWhenCritical;
        }
    }

    /**
     * 诊断完成后的邮件通知（把"答案"送到运维眼前）。
     *
     * <p>默认 {@code enabled=true} 但 {@code to} 为空——空收件人视为未启用，不会误发。
     * {@code base-url} 用于把邮件里的"完整报告"链接指到管理端公网地址：Grafana 邮件里的
     * 链接拼的是回环 root_url，外部打不开，所以这里必须单独配置一个可访问的基址。
     */
    public static class Notify {

        private boolean enabled = true;

        /** 收件人，逗号分隔；为空则不发。 */
        private String to = "";

        /** 管理端公网基址，如 http://36.212.5.130:8081；为空则邮件不带链接。 */
        private String baseUrl = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to == null ? "" : to;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }
    }

    /**
     * 日志模板聚类与模板级异常检测（WORK-085）。
     *
     * <p>聚类不走应用自研 Drain：直接用 Loki 自带的 pattern ingester（Drain 算法）在采集侧成模板，
     * 应用只做「同步 → 分桶 → 与历史同 dow/hour 基线比对 → 触发诊断」。阈值全部放这里，
     * 面板与告警读同一份，不再各自写死。
     */
    public static class Patterns {

        private boolean enabled = true;

        /** 同步周期（秒）。与 Loki pattern ingester 的 downsample 相比可以粗一些。 */
        private long syncIntervalSeconds = 300;

        /** 检测窗口宽度（分钟），桶按此宽度对齐。 */
        private int windowMinutes = 15;

        /**
         * 每轮同步向 Loki 查询的历史长度（分钟），要显著大于窗口宽度。
         *
         * <p>原因（2026-11-19 实测）：Loki pattern 查询硬编码 {@code minClusterSize = 30}，窗口内
         * 累计不足 30 条模板的 series 会被剪掉。本项目日志量小（约 7 行/分钟），只有拉长窗口
         * 才能让同一模板凑到 30 条。上限受 Loki {@code retain_for}（默认 3h）约束，别超过 180。
         */
        private int lookbackMinutes = 60;

        /** 基线回看周数；同 dow+hour 的历史窗口在这段范围内取样。 */
        private int baselineWeeks = 4;

        /** 频次突增：当前窗口 / 基线均值 达到此倍数。 */
        private double freqSpikeRatio = 3.0;

        /** 频次突增：同时要求 z 分数达到此值，压住低方差噪声。 */
        private double zScoreThreshold = 3.0;

        /** 新模板：窗口内条数达到此值才算「值得说」的新模板。 */
        private int newTemplateMinCount = 10;

        /** 消失：基线均值达到此值，而本窗口一条都没有，才判「服务静默」。 */
        private int disappearedBaselineMin = 20;

        /** 同一模板同类型的触发冷却（秒），防止同一异常每轮同步都发一次诊断。 */
        private long triggerCooldownSeconds = 900;

        /** 单次同步最多处理多少模板，防止第一次接入时把整库拉爆。 */
        private int maxTemplates = 2000;

        /** 桶与模板字典的保留天数。 */
        private int retentionDays = 30;

        /** 端点展示的 Top 模板数。 */
        private int topN = 15;

        /** 拼进证据/展示的模板文本最大长度。 */
        private int maxPatternChars = 2000;

        /**
         * 最小服务拓扑（REQ-008）。不引 CMDB，只把「应用依赖哪些外部组件」写成一行配置，
         * 拼进证据头部，帮助模型判断「某个依赖挂了」而不是「应用自己出错」。空串则不出该行。
         */
        private String topology = "app → MySQL/Redis/RustFS/Laya/Tempo";

        /** 参与聚类的日志单元白名单。 */
        private List<String> units = List.of("paideia", "laya");

        /**
         * 异常超过该分钟数仍未关闭就自动归档（状态置 RESOLVED）。
         *
         * <p>异常是「某一窗口的观测」，不是持续态：诊断已经完成，或根本没人处理，都不该让它
         * 一直以 OPEN 计数——{@code paideia_ops_pattern_anomalies_open} 长期大于 0 会让
         * Grafana 规则永久 firing，重启一次重新触发一次告警（2026-11-19 实测踩到）。
         */
        private int autoResolveMinutes = 120;

        /**
         * 不参与异常判定的模板正则（大小写不敏感，任一条命中即忽略）。
         *
         * <p>健康探针这类「按计划刷 200」的日志既不是故障，停了也不代表服务静默，却会因为
         * 低频场景下「首次出现」和「探针停止」各报一次。默认忽略探针与健康检查成功日志。
         */
        private List<String> ignorePatterns = List.of(
                "(?i)\\bprobe\\b",
                "(?i)\\bhealth(?:check)?\\b.*\\b(?:200|OK)\\b");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSyncIntervalSeconds() {
            return syncIntervalSeconds;
        }

        public void setSyncIntervalSeconds(long syncIntervalSeconds) {
            this.syncIntervalSeconds = syncIntervalSeconds;
        }

        public int getWindowMinutes() {
            return windowMinutes;
        }

        public void setWindowMinutes(int windowMinutes) {
            this.windowMinutes = windowMinutes;
        }

        public int getLookbackMinutes() {
            return lookbackMinutes;
        }

        public void setLookbackMinutes(int lookbackMinutes) {
            this.lookbackMinutes = lookbackMinutes;
        }

        public int getBaselineWeeks() {
            return baselineWeeks;
        }

        public void setBaselineWeeks(int baselineWeeks) {
            this.baselineWeeks = baselineWeeks;
        }

        public double getFreqSpikeRatio() {
            return freqSpikeRatio;
        }

        public void setFreqSpikeRatio(double freqSpikeRatio) {
            this.freqSpikeRatio = freqSpikeRatio;
        }

        public double getZScoreThreshold() {
            return zScoreThreshold;
        }

        public void setZScoreThreshold(double zScoreThreshold) {
            this.zScoreThreshold = zScoreThreshold;
        }

        public int getNewTemplateMinCount() {
            return newTemplateMinCount;
        }

        public void setNewTemplateMinCount(int newTemplateMinCount) {
            this.newTemplateMinCount = newTemplateMinCount;
        }

        public int getDisappearedBaselineMin() {
            return disappearedBaselineMin;
        }

        public void setDisappearedBaselineMin(int disappearedBaselineMin) {
            this.disappearedBaselineMin = disappearedBaselineMin;
        }

        public long getTriggerCooldownSeconds() {
            return triggerCooldownSeconds;
        }

        public void setTriggerCooldownSeconds(long triggerCooldownSeconds) {
            this.triggerCooldownSeconds = triggerCooldownSeconds;
        }

        public int getMaxTemplates() {
            return maxTemplates;
        }

        public void setMaxTemplates(int maxTemplates) {
            this.maxTemplates = maxTemplates;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public int getMaxPatternChars() {
            return maxPatternChars;
        }

        public void setMaxPatternChars(int maxPatternChars) {
            this.maxPatternChars = maxPatternChars;
        }

        public String getTopology() {
            return topology;
        }

        public void setTopology(String topology) {
            this.topology = topology == null ? "" : topology;
        }

        public List<String> getUnits() {
            return units;
        }

        public void setUnits(List<String> units) {
            this.units = units == null ? List.of() : units;
        }

        public int getAutoResolveMinutes() {
            return autoResolveMinutes;
        }

        public void setAutoResolveMinutes(int autoResolveMinutes) {
            this.autoResolveMinutes = autoResolveMinutes;
        }

        public List<String> getIgnorePatterns() {
            return ignorePatterns;
        }

        public void setIgnorePatterns(List<String> ignorePatterns) {
            this.ignorePatterns = ignorePatterns == null ? List.of() : ignorePatterns;
        }
    }
}

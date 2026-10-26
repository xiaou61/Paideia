package io.github.xiaou61.ops;

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

    /**
     * Grafana webhook 的共享令牌（WORK-042）。为空时 webhook 一律拒绝，手动诊断不受影响。
     * 真实值来自服务器 600 文件，经 systemd 环境变量注入；仓库里只有占位。
     */
    private String hookToken = "";

    private final Thresholds thresholds = new Thresholds();

    private final Diagnosis diagnosis = new Diagnosis();

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

    public String getHookToken() {
        return hookToken;
    }

    public void setHookToken(String hookToken) {
        this.hookToken = hookToken == null ? "" : hookToken;
    }

    public Diagnosis getDiagnosis() {
        return diagnosis;
    }

    public Thresholds getThresholds() {
        return thresholds;
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
        private int maxEvidenceChars = 12000;

        private int listLimit = 20;

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
    }
}

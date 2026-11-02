package io.github.xiaou61.ops;

/**
 * 运维摘要里每一项的判定结果（WORK-028 REQ-028）。
 *
 * <p><b>四态而不是三态</b>：{@link #UNKNOWN} 是独立的一档，不能压进"正常"或"异常"。
 * 拿不到数据时画成 0 或绿色，是这个页面最容易犯也最难发现的错——它会让"监控自己挂了"
 * 看起来和"一切正常"一模一样。
 */
public enum OpsStatus {

    OK,
    WARN,
    ERROR,
    UNKNOWN;

    /**
     * 按阈值判定一个数值。
     *
     * <p><b>空值与 NaN 必须先于所有比较返回 {@link #UNKNOWN}</b>：NaN 参与任何比较都是
     * false，漏掉这一支会一路落到 OK——「没有数据」于是变成了「一切正常」。
     */
    public static OpsStatus of(Double value, double warnAt, double errorAt) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return UNKNOWN;
        }
        if (value >= errorAt) {
            return ERROR;
        }
        if (value >= warnAt) {
            return WARN;
        }
        return OK;
    }

    /** 取一个组里最严重的状态；{@link #UNKNOWN} 不压过已知的异常。 */
    public static OpsStatus worst(OpsStatus... statuses) {
        OpsStatus result = OK;
        for (OpsStatus status : statuses) {
            if (status == ERROR) {
                return ERROR;
            }
            if (status == WARN) {
                result = WARN;
            } else if (status == UNKNOWN && result == OK) {
                result = UNKNOWN;
            }
        }
        return result;
    }
}

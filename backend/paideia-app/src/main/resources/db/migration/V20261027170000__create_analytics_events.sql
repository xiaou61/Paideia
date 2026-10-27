-- 数据大屏的两张埋点表（WORK-045）。
--
-- 为什么这两张表值得建（其它"未采集"项都已核实过：要么已有数据只是没聚合，
-- 要么采集成本远超价值）：
--   1. digest_item_clicks 回答「535 条速递有没有人点开过」——digest_items 只有内容，
--      点开是外链跳转，连 knowledge_entry_views 都不会经过，所以这是速递链唯一的消费端证据。
--   2. knowledge_search_log 回答「用户在搜什么、搜不到什么」——`q` 参数此前只进 SQL
--      从不落库；result_count = 0 的搜索是最有价值的内容缺口信号。
--
-- **两张表都没有外键**：digest_items 与 knowledge_entries 会被各自的生命周期清理掉，
-- 点击/搜索记录要独立存活，否则删内容时连带删历史数据，趋势图上会凭空掉一截。

CREATE TABLE digest_item_clicks (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    item_id    BIGINT      NOT NULL,
    clicked_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    -- 按天聚合（数据大屏的速递点击趋势）
    KEY idx_dic_clicked (clicked_at),
    -- 热门速递前 N
    KEY idx_dic_item (item_id, clicked_at),
    -- 保留期清理按用户批量删（与其它埋点表同一纪律）
    KEY idx_dic_user (user_id, clicked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_search_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    -- 截断到 100 字符（应用侧截）；不存 hash 是因为大屏要显示「用户在搜什么」
    keyword      VARCHAR(100) NOT NULL,
    -- 本次搜索的命中数：0 表示内容缺口，是这张表最值钱的一列
    result_count INT          NOT NULL,
    searched_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ksl_searched (searched_at),
    -- 高频词排序
    KEY idx_ksl_keyword (keyword),
    KEY idx_ksl_user (user_id, searched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

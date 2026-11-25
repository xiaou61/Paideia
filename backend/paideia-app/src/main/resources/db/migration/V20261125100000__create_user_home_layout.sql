-- 首页自定义布局（WORK：用户可排序/显隐版块、排序/显隐快捷入口、添加自定义链接）。
-- 每用户一行；layout_json 存完整 JSON（前端拥有 schema 与默认值，服务端只做透明存取）。
CREATE TABLE IF NOT EXISTS user_home_layout (
    user_id     BIGINT      NOT NULL,
    layout_json TEXT        NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

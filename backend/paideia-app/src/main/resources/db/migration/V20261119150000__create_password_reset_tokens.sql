-- 忘记密码 / 重置口令的一次性令牌。
--
-- 与 refresh_tokens 同样的取舍：**库里只存 SHA-256**，明文只出现在发出去的那封邮件链接里。
-- 因此库被读走也换不出可用链接；日志与备份同理。
--
-- used_at 兼作两个语义：已使用（消费成功）与已作废（用户重新申请时把旧的整批作废）。
-- 两种情形对外都是"这枚令牌不能用了"，因此不必再拆一个 status 列。
CREATE TABLE password_reset_tokens (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL,
  token_hash CHAR(64)        NOT NULL,
  expires_at DATETIME(3)     NOT NULL,
  used_at    DATETIME(3)     NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_password_reset_token_hash (token_hash),
  KEY idx_password_reset_user (user_id),
  -- 定期清理按 expires_at 扫，与 refresh_tokens 的理由相同：这张表必须能过期回收
  KEY idx_password_reset_expires (expires_at),
  CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

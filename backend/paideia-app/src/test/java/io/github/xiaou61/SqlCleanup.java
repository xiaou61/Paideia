package io.github.xiaou61;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试共享件（WORK-062）：按 user_id 逐表清理。
 *
 * <p>原来 5 个集成测试各自写"for (表名 : List.of(...)) jdbcTemplate.update(DELETE FROM ...)"。
 * 这里只统一**循环样板**：表清单与顺序仍留在各测试类——**顺序是承重的**（子表在前），
 * 且各测试清理的表集本来就不同，合并成共享常量只会制造耦合。
 *
 * <p>`users` 表不在本方法范围内：它按 `id` 列删，调用方在逐表清理之后单独删。
 */
final class SqlCleanup {

    private SqlCleanup() {
    }

    /** 顺序 = 参数顺序：调用方按子表在前、父表在后的顺序传。 */
    static void deleteByUser(JdbcTemplate jdbcTemplate, Object userId, String... tablesInOrder) {
        for (String table : tablesInOrder) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
    }
}
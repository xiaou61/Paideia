package io.github.xiaou61.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 集成测试用的数据库装配。
 *
 * <p>连接信息只从环境变量读取，仓库里不放任何凭据：
 * {@code PAIDEIA_TEST_DB_URL}、{@code PAIDEIA_TEST_DB_USER}、{@code PAIDEIA_TEST_DB_PASSWORD}。
 * 默认 URL 指向本机 MySQL 的 {@code paideia_test}；服务器上的测试库（地址、库名、账号与
 * 口令都只在被忽略的 {@code backend/config/remote-db.local.env} 里）通过
 * {@code PAIDEIA_TEST_DB_URL} 传进来，源码里不写死任何一项。
 *
 * <p>没有配置口令时调用方应跳过测试并如实报告为未运行，而不是让整个构建红掉。
 *
 * <p>{@code forceConnectionTimeZoneToSession=true} 与 {@code connectionTimeZone=UTC} 必须成对：
 * 服务器 MySQL 的会话时区是 {@code SYSTEM}（+08:00），只声明前者是"驱动按 UTC 解释、会话却按
 * 本地时间写"，建表默认值写进去的时间会整体快 8 小时（2026-10-10 实测）。
 */
final class TestDatabase {

    static final String URL_ENV = "PAIDEIA_TEST_DB_URL";
    static final String USER_ENV = "PAIDEIA_TEST_DB_USER";
    static final String PASSWORD_ENV = "PAIDEIA_TEST_DB_PASSWORD";

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/paideia_test"
                    + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&characterEncoding=utf8";
    private static final String DEFAULT_USER = "paideia";

    private TestDatabase() {
    }

    static String password() {
        return System.getenv(PASSWORD_ENV);
    }

    static boolean isConfigured() {
        String password = password();
        return password != null && !password.isBlank();
    }

    static DataSource dataSource() {
        return new DriverManagerDataSource(url(), user(), password());
    }

    static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/testdata")
                // 关掉校验是必要的：本模块的测试与 paideia-app 的测试共用同一个 paideia_test 库，
                // 而应用那边会按 classpath:db/migration,classpath:db/testdata 迁移。
                // 那个库的历史里因此会有本模块解析不到的 V1 —— Flyway 会判为
                // "已应用但本地找不到" 并让本模块的测试整片失败。
                // 本助手的职责只是"保证夹具表在"，生产 schema 的归属与校验属于应用侧。
                .validateOnMigrate(false)
                .load()
                .migrate();
    }

    private static String url() {
        String value = System.getenv(URL_ENV);
        return value == null || value.isBlank() ? DEFAULT_URL : value;
    }

    private static String user() {
        String value = System.getenv(USER_ENV);
        return value == null || value.isBlank() ? DEFAULT_USER : value;
    }
}

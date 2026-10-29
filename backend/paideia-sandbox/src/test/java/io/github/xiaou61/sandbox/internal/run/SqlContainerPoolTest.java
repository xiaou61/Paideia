package io.github.xiaou61.sandbox.internal.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 容器池（WORK-052）的纯逻辑部分。
 *
 * <p>**真容器路径不在这里测**：本机没有 Docker，起不来容器。真实执行的验证在服务器上做
 * （见验收报告）。这里守的是两件在本地就能守住的事：
 * <ol>
 *   <li>池关掉/不可用时必须<b>回落</b>而不是抛错——这是"池坏了 SQL 练习还能用"的保证；</li>
 *   <li>执行脚本里**必须有**每次一库一号的隔离语句，且口令不走命令行。</li>
 * </ol>
 */
class SqlContainerPoolTest {

    private static SandboxProperties props(int poolSize) {
        SandboxProperties properties = new SandboxProperties();
        properties.setEnabled(true);
        properties.setSqlImage("paideia-sandbox-mysql@sha256:abc");
        properties.setSqlPoolSize(poolSize);
        return properties;
    }

    @Test
    @DisplayName("池大小为 0（关闭）时直接回落，不进任何容器")
    void 关闭时回落() {
        SqlContainerPool pool = new SqlContainerPool(props(0));
        CodeRun.Request request = new CodeRun.Request("sql", "SELECT 1", "-- dataset", "c1");
        Optional<CodeRun.Result> result = pool.tryRun(request);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("沙箱总开关关掉时，池即使配了大小也不能用")
    void 总开关关闭时不启用() {
        SandboxProperties properties = props(2);
        properties.setEnabled(false);
        SqlContainerPool pool = new SqlContainerPool(properties);
        assertThat(pool.tryRun(new CodeRun.Request("sql", "SELECT 1", "", "c1"))).isEmpty();
    }

    @Test
    @DisplayName("执行脚本：每次一库一号，账号只授权本库，用完删库删号")
    void 脚本包含隔离语句() {
        String script = SqlContainerPool.script(false);
        assertThat(script).contains("CREATE DATABASE");
        assertThat(script).contains("CREATE USER");
        assertThat(script).contains("GRANT ALL ON");
        assertThat(script).contains("DROP DATABASE IF EXISTS");
        assertThat(script).contains("DROP USER IF EXISTS");
        // 用户 SQL 必须用受限账号跑，不能用 root——否则池化就等于把所有人的临时库摊开
        assertThat(script).contains("MYSQL_PWD");
    }

    @Test
    @DisplayName("口令不走命令行：不能用 -p 传密码")
    void 口令不走命令行() {
        assertThat(SqlContainerPool.script(false)).doesNotContain("-p\"$PW\"");
        assertThat(SqlContainerPool.script(true)).doesNotContain("-p\"$PW\"");
    }

    @Test
    @DisplayName("计划模式只把第一段包进 EXPLAIN，且仍走同一套隔离")
    void 计划模式脚本() {
        String script = SqlContainerPool.script(true);
        assertThat(script).contains("EXPLAIN FORMAT=TREE");
        assertThat(script).contains("CREATE USER");
        assertThat(SqlContainerPool.script(false)).doesNotContain("EXPLAIN");
    }

    @Test
    @DisplayName("启动脚本里不能出现相邻的后台符号与分号：那是 shell 语法错误")
    void 启动脚本没有语法错() {
        // 2026-10-28 线上真踩过：拼装时把「后台符号」和「分号」连在了一起，
        // 容器起来就退出，于是每次执行都等满 30 秒就绪再回落，比不做池化还慢一倍。
        assertThat(SqlContainerPool.BOOT_SCRIPT).doesNotContain("& ;");
        assertThat(SqlContainerPool.BOOT_SCRIPT).contains("& sleep 100000");
    }

    @Test
    @DisplayName("容器内存默认 1g：512m 会把 mysqld OOM 掉")
    void 默认内存是一g() {
        assertThat(new SandboxProperties().getSqlMemory()).isEqualTo("1g");
    }

    @Test
    @DisplayName("池默认 2 个，且取用超时有默认值")
    void 默认池大小() {
        SandboxProperties properties = new SandboxProperties();
        assertThat(properties.getSqlPoolSize()).isEqualTo(2);
        assertThat(properties.getSqlPoolAcquireTimeoutMs()).isGreaterThan(0);
    }
}

package io.github.xiaou61.sandbox.internal.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 语句白名单（WORK-035 T-09）。
 *
 * <p>**为什么这组用例是安全测试而不是功能测试**：数据集内容会被**别人的运行**反复执行。
 * 一旦里面藏了 `TRIGGER`，它在每次别人跑 SQL 时都执行一次——"一次性上传"变成"持续执行"。
 * 白名单漏一个的后果不是"用户抱怨"，是"任意逻辑在沙箱里长期运行"。
 */
class SqlStatementGuardTest {

    @Test
    void 正常建表与插数通过() {
        List<String> problems = SqlStatementGuard.check("""
                CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(20));
                INSERT INTO customers VALUES (1, 'ann'), (2, 'bob');
                """);
        assertThat(problems).isEmpty();
    }

    @Test
    void 建索引与视图通过() {
        assertThat(SqlStatementGuard.check("CREATE INDEX idx ON t (a);")).isEmpty();
        assertThat(SqlStatementGuard.check("CREATE UNIQUE INDEX idx ON t (a);")).isEmpty();
        assertThat(SqlStatementGuard.check("CREATE VIEW v AS SELECT 1;")).isEmpty();
    }

    /**
     * 触发器是最该挡的一种：它在**别人执行 INSERT 时**跑代码。
     * 沙箱隔离能挡住逃逸，但挡不住"每次运行都执行一遍"。
     */
    @Test
    void 触发器被拒() {
        List<String> problems = SqlStatementGuard.check(
                "CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW SET @a = 1;");
        assertThat(problems).isNotEmpty();
        assertThat(String.join(" ", problems)).contains("TRIGGER");
    }

    @Test
    void 存储过程与函数被拒() {
        assertThat(SqlStatementGuard.check("CREATE PROCEDURE p() SELECT 1;")).isNotEmpty();
        assertThat(SqlStatementGuard.check("CREATE FUNCTION f() RETURNS INT RETURN 1;")).isNotEmpty();
    }

    @Test
    void 事件调度被拒() {
        assertThat(SqlStatementGuard.check("CREATE EVENT e ON SCHEDULE EVERY 1 SECOND DO SELECT 1;"))
                .isNotEmpty();
    }

    /** 读宿主文件是另一条路径：即使不能执行代码，也能把文件内容插进表里再 SELECT 出来。 */
    @Test
    void 文件读写被拒() {
        assertThat(SqlStatementGuard.check("LOAD DATA INFILE '/etc/passwd' INTO TABLE t;")).isNotEmpty();
        assertThat(SqlStatementGuard.check("SELECT LOAD_FILE('/etc/passwd');")).isNotEmpty();
        assertThat(SqlStatementGuard.check("SELECT 1 INTO OUTFILE '/tmp/x';")).isNotEmpty();
    }

    @Test
    void 删除与更新被拒() {
        // 数据集是"快照"，不该包含改数据的语句——它会被反复灌入，每次效果都要一样
        assertThat(SqlStatementGuard.check("DROP TABLE customers;")).isNotEmpty();
        assertThat(SqlStatementGuard.check("DELETE FROM customers;")).isNotEmpty();
        assertThat(SqlStatementGuard.check("UPDATE customers SET name = 'x';")).isNotEmpty();
    }

    /**
     * **注释里出现危险词不该误报**。
     *
     * <p>这正是"先剥注释再判"的理由：不加这一步，一句写得很清楚的注释
     * （"这里不用 TRIGGER"）会让整个上传被拒，而用户完全不知道原因。
     */
    @Test
    void 注释里的危险词不误报() {
        assertThat(SqlStatementGuard.check("""
                -- 这里不用 TRIGGER，因为触发器会让每次插入都执行
                /* 也不用 PROCEDURE */
                CREATE TABLE t (id INT);
                """)).isEmpty();
    }

    /** 反过来：注释不能用来藏真实语句。 */
    @Test
    void 注释不能藏语句() {
        // 注释掉一半，真正的 CREATE TRIGGER 在注释之后
        List<String> problems = SqlStatementGuard.check("""
                /* 看起来无害 */
                CREATE TRIGGER x BEFORE INSERT ON t FOR EACH ROW SET @a = 1;
                """);
        assertThat(problems).isNotEmpty();
    }

    @Test
    void 空内容被拒() {
        assertThat(SqlStatementGuard.check("")).isNotEmpty();
        assertThat(SqlStatementGuard.check("   \n  ")).isNotEmpty();
        assertThat(SqlStatementGuard.check(null)).isNotEmpty();
    }

    @Test
    void 只有注释被拒() {
        assertThat(SqlStatementGuard.check("-- 什么都没有\n/* 真的没有 */")).isNotEmpty();
    }

    /** 大小写与多空白不该绕过白名单。 */
    @Test
    void 大小写与空白不影响判定() {
        assertThat(SqlStatementGuard.check("create   table   t (id int);")).isEmpty();
        assertThat(SqlStatementGuard.check("CrEaTe\n\tTaBlE t (id int);")).isEmpty();
        assertThat(SqlStatementGuard.check("CREATE  TRIGGER x BEFORE INSERT ON t FOR EACH ROW SET @a=1;"))
                .isNotEmpty();
    }

    /**
     * 查询门禁：AI 产出的参考解。
     *
     * <p>**为什么不能直接复用 {@code check}**：参考解就是一条 `SELECT`，而数据集白名单里
     * 根本没有 `SELECT`——套上去会把每一个合法参考解都拒掉，于是调用方只能把返回值丢掉，
     * 门禁又变回空转（2026-11-02 审查抓到的正是这个状态）。
     */
    @Test
    void 参考解只挡危险写法而不挡查询() {
        assertThat(SqlStatementGuard.checkQuery("SELECT name FROM customers ORDER BY name;")).isEmpty();
        assertThat(SqlStatementGuard.checkQuery("select count(*) from customers")).isEmpty();
        assertThat(SqlStatementGuard.checkQuery("WITH t AS (SELECT 1 AS a) SELECT a FROM t;")).isEmpty();
    }

    @Test
    void 参考解仍挡宿主文件与多语句() {
        assertThat(SqlStatementGuard.checkQuery("SELECT 1 INTO OUTFILE '/tmp/x';")).isNotEmpty();
        assertThat(SqlStatementGuard.checkQuery("SELECT LOAD_FILE('/etc/passwd');")).isNotEmpty();
        assertThat(SqlStatementGuard.checkQuery("SELECT 1 FROM t; SELECT 2 FROM t;")).isNotEmpty();
        assertThat(SqlStatementGuard.checkQuery(null)).isNotEmpty();
        assertThat(SqlStatementGuard.checkQuery("   ")).isNotEmpty();
    }
}

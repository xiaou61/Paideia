package io.github.xiaou61.sandbox.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 命令构造的**安全断言**（WORK-015 AC-002 / AC-008）。
 *
 * <p>这一组用例的存在理由：这些参数就是隔离边界。少写一个 `--cap-drop=ALL`，代码仍然能跑、
 * 测试也可能全绿，只是隔离没了——而这种缺失在本地完全观察不到。所以每条加固标志都单独断言，
 * 将来有人"顺手简化一下命令"时会被这里拦住。
 */
class DockerCommandTest {

    @Test
    void explainOnlyAcceptsSingleStatement() {
        // 脚本只给第一段加 EXPLAIN 前缀，第二条会被真实执行 —— 必须在构造时就挡住
        assertThatThrownBy(() -> new CodeRun.Request("sql", "SELECT 1; UPDATE t SET a=1", null, null, true))
                .hasMessageContaining("只能有一条");

        // 结尾分号要去掉：`EXPLAIN ... SELECT ...;` 本身也是语法错误
        assertThat(new CodeRun.Request("sql", "  SELECT 1;  ", null, null, true).code())
                .isEqualTo("SELECT 1");
    }

    private static SandboxProperties props() {
        SandboxProperties properties = new SandboxProperties();
        properties.setJavaImage("eclipse-temurin:21-jdk-alpine");
        properties.setPythonImage("python:3.12-alpine");
        return properties;
    }

    /** 每条都是安全边界：断的是"在不在"，不是"位置对不对"。 */
    private static final List<String> MANDATORY = List.of(
            "--rm",
            "--network=none",
            "--read-only",
            "--user", "65534:65534",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--memory-swap=256m",
            "--pids-limit=64",
            "--stop-timeout=0");

    private static void assertHardened(List<String> command) {
        for (String flag : MANDATORY) {
            assertThat(command)
                    .as("加固参数 %s 必须在命令行里——它缺失时程序照样能跑，只是隔离没了", flag)
                    .contains(flag);
        }
        assertThat(command).contains("--memory=256m", "--cpus=0.5", "--network=none");

        // 绝对不能有的：任何形式的宿主挂载
        assertThat(command)
                .as("不能把宿主目录挂进容器")
                .noneMatch(item -> item.equals("-v") || item.startsWith("--volume") || item.startsWith("--mount"));
    }

    @Test
    void java命令包含全部加固参数且不挂载宿主() {
        List<String> command = DockerCommand.of("java", props(), "/tmp/x.cid");
        assertHardened(command);
        assertThat(command).contains("eclipse-temurin:21-jdk-alpine");
        assertThat(command.get(command.size() - 1)).isEqualTo(DockerCommand.scriptFor("java"));
        assertThat(command).contains("--entrypoint", "sh", "-c");
    }

    @Test
    void python命令同样加固() {
        List<String> command = DockerCommand.of("python", props(), "/tmp/x.cid");
        assertHardened(command);
        assertThat(command).contains("python:3.12-alpine");
        assertThat(command.get(command.size() - 1)).isEqualTo(DockerCommand.scriptFor("python"));
    }

    @Test
    void 配置了runtime才加runtime参数() {
        SandboxProperties plain = props();
        assertThat(DockerCommand.of("java", plain, "/tmp/x.cid")).noneMatch(item -> item.startsWith("--runtime"));

        SandboxProperties gvisor = props();
        gvisor.setRuntime("runsc");
        assertThat(DockerCommand.of("java", gvisor, "/tmp/x.cid")).contains("--runtime=runsc");
    }

    @Test
    void 语言标识大小写不敏感但不支持的语言被拒() {
        assertThat(DockerCommand.of("JAVA", props(), "/tmp/x.cid")).contains(DockerCommand.scriptFor("java"));
        assertThatThrownBy(() -> DockerCommand.of("ruby", props(), "/tmp/x.cid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的语言");
    }

    @Test
    void 语言白名单按镜像可用性给出_未配置sql时只有两种() {
        SandboxProperties properties = props();
        assertThat(properties.languages()).containsExactlyInAnyOrder("java", "python");
        assertThat(properties.isSupported("java")).isTrue();
        assertThat(properties.isSupported("PHP")).isFalse();
        assertThat(properties.imageFor("ruby")).isNull();
    }

    @Test
    void 配置了sql镜像才出现sql() {
        SandboxProperties properties = props();
        properties.setSqlImage("paideia-sandbox-mysql@sha256:abc");
        assertThat(properties.languages()).contains("sql");
        assertThat(properties.imageFor("sql")).isEqualTo("paideia-sandbox-mysql@sha256:abc");
    }

    /**
     * SQL 的隔离参数（WORK-032 AC-001 / AC-004）。
     *
     * <p>与 Java/Python **共用同一套加固**，只有资源上限按语言取值：
     * mysqld 常驻约 348 MB，用 256m 会被 OOM 杀掉；tmpfs 要装下 180 MB 的 datadir 副本。
     * 这两条差异如果被"顺手统一"回去，SQL 会稳定失败，而 Java/Python 的测试仍然全绿。
     */
    @Test
    void sql命令同样加固但资源上限单独一档() {
        SandboxProperties properties = props();
        properties.setSqlImage("paideia-sandbox-mysql@sha256:abc");
        List<String> command = DockerCommand.of("sql", properties, "/tmp/x.cid");

        assertThat(command).contains("--rm", "--network=none", "--read-only",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--stop-timeout=0");
        assertThat(command).contains("--memory=512m", "--memory-swap=512m", "--cpus=1", "--pids-limit=128");
        assertThat(command).contains("/tmp:rw,size=768m,exec");
        assertThat(command).contains("--user", "999:999");
        assertThat(command)
                .as("不能把宿主目录挂进 SQL 容器")
                .noneMatch(item -> item.equals("-v") || item.startsWith("--volume") || item.startsWith("--mount"));
        assertThat(command).contains(DockerCommand.scriptFor("sql"));
    }

    /**
     * 执行计划模式（WORK-035 T-05）。
     *
     * <p>两条都要断：**加固参数不能因为"只是看计划"就放松**（它仍然起一个真容器、仍然读数据集），
     * 以及**脚本确实换成了 EXPLAIN**（否则用户点"看执行计划"会真的把语句执行一遍，
     * 写操作会落数据）。
     */
    @Test
    void sql计划模式仍加固且脚本换成explain() {
        SandboxProperties properties = props();
        properties.setSqlImage("paideia-sandbox-mysql@sha256:abc");
        List<String> command = DockerCommand.of("sql", properties, "/tmp/x.cid", true);

        assertThat(command).contains("--rm", "--network=none", "--read-only",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--stop-timeout=0");
        assertThat(command).contains("--memory=512m", "--user", "999:999");
        assertThat(command).contains(DockerCommand.scriptFor("sql", true));
        assertThat(DockerCommand.scriptFor("sql", true)).contains("EXPLAIN FORMAT=TREE");
        assertThat(DockerCommand.scriptFor("sql", true))
                .as("普通脚本不该带 EXPLAIN，否则所有查询都会被包成计划")
                .isNotEqualTo(DockerCommand.scriptFor("sql"));
    }

    /** explain 只对 SQL 生效：别的语言传 true 也必须跑普通脚本，不能悄悄换掉。 */
    @Test
    void 非sql语言的explain被忽略() {
        assertThat(DockerCommand.of("python", props(), "/tmp/x.cid", true))
                .contains(DockerCommand.scriptFor("python"));
        assertThat(DockerCommand.of("java", props(), "/tmp/x.cid", true))
                .contains(DockerCommand.scriptFor("java"));
    }

    /**
     * 脚本里必须显式收紧两个危险开关（WORK-032）。
     *
     * <p>`secure_file_priv` 默认是 NULL（不限路径）；指向**不存在的目录**会让 mysqld
     * 直接 Aborting（实测）。所以脚本里既要有这个参数，也要先 mkdir。
     */
    @Test
    void sql脚本显式收紧文件读写开关() {
        String script = DockerCommand.scriptFor("sql");
        assertThat(script).contains("--secure-file-priv=");
        assertThat(script).as("必须指向已存在的目录，否则 mysqld 起不来").contains("mkdir -p /tmp/nofiles");
        // 根文件系统只读：pid 与 X 协议 socket 默认写到 /var/run/mysqld，那里不可写。
        // 写不了时 mysqld 不报错也不退出，只是静默卡住——线上表现为每次都 20 秒超时。
        assertThat(script).as("pid 文件必须落到 tmpfs").contains("--pid-file=/tmp/data/mysqld.pid");
        assertThat(script).as("mysqlx 插件要往只读目录写 socket，必须关掉").contains("--mysqlx=0");
        assertThat(script).contains("--local-infile=0");
        assertThat(script).contains("--skip-log-bin");
        assertThat(script).as("必须用 socket 而不是 TCP").contains("--skip-networking");
        assertThat(script).as("datadir 拷贝后必须恢复可写，否则报 ibdata1 must be writable")
                .contains("chmod -R u+w");
        assertThat(script).as("用 --xml 才有结果集边界与显式 NULL").contains("--xml");
    }

    @Test
    void 未配置sql镜像时构造命令被拒绝() {
        SandboxProperties properties = props();
        assertThatThrownBy(() -> DockerCommand.of("sql", properties, "/tmp/x.cid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的语言");
    }

    @Test
    void 内存与cpus可配置且真的进了命令行() {
        SandboxProperties properties = props();
        properties.setMemory("128m");
        properties.setCpus("0.25");
        properties.setPidsLimit(32);
        List<String> command = DockerCommand.of("python", properties, "/tmp/x.cid");
        assertThat(command).contains("--memory=128m", "--memory-swap=128m", "--cpus=0.25", "--pids-limit=32");
    }
}

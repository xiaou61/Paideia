package io.github.xiaou61.sandbox.internal.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 判题结果的翻译（WORK-056）。
 *
 * <p>这一组的重点是**判定只看 judge 的退出码**，以及"拿不到协议块"必须变成
 * `undetermined` 而不是异常或"通过"——那是最容易悄悄错的一处：
 * 容器超时被杀时 stdout 是空的，如果解析器把"没有 `###RC`"当成 0，所有人都会莫名其妙通过。
 */
class ShellRunnerTest {

    /** 记录请求、返回预设结果的假执行器：不启动 Docker。 */
    private static final class FakeRunner implements CodeRunner {
        CodeRun.Request last;
        CodeRun.Result result;

        @Override
        public CodeRun.Result run(CodeRun.Request request) {
            this.last = request;
            return result;
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }
    }

    private static CodeRun.Result ok(String stdout) {
        return new CodeRun.Result(stdout, "", 0, 42, false, false, false);
    }

    private static ShellRunner runner(FakeRunner fake) {
        return new ShellRunner(fake, new SandboxProperties());
    }

    private static String protocol(int runExit, int judgeExit, String out, String judgeErr) {
        return "###RC\n" + runExit + " " + judgeExit + "\n"
                + "###OUT\n" + out + "\n"
                + "###ERR\n\n"
                + "###JUDGE\n\n"
                + "###JUDGEERR\n" + judgeErr + "\n"
                + "###DIFF\n"
                + "> -rw------- 12 ./a.txt\n"
                + "---\n"
                + "< -rw-r--r-- 12 ./a.txt\n";
    }

    @Test
    void judge退出0即通过() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok(protocol(0, 0, "done", ""));

        ShellRunner.Result result = runner(fake).run("chmod 600 a.txt", "test -r a.txt", List.of(), "c1");

        assertThat(result.verdict()).isEqualTo("passed");
        assertThat(result.judgeExit()).isZero();
        assertThat(result.reason()).isNull();
        assertThat(result.stdout()).isEqualTo("done");
    }

    @Test
    void judge非0即未通过且带回它的错误输出() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok(protocol(0, 1, "", "权限应为 600，实际 644"));

        ShellRunner.Result result = runner(fake).run("chmod 644 a.txt", "test \"$(stat -c %a a.txt)\" = 600",
                List.of(), null);

        assertThat(result.verdict()).isEqualTo("failed");
        assertThat(result.judgeExit()).isEqualTo(1);
        assertThat(result.judgeStderr()).contains("权限应为 600");
    }

    @Test
    void 学习者脚本失败不算未通过() {
        // 题目的目的可能就是让他看到"这条命令报错了"；判定交给 judge
        FakeRunner fake = new FakeRunner();
        fake.result = ok(protocol(1, 0, "ls: cannot access 'nope'", ""));

        ShellRunner.Result result = runner(fake).run("ls nope", "true", List.of(), null);

        assertThat(result.verdict()).isEqualTo("passed");
        assertThat(result.runExit()).isEqualTo(1);
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void 缺协议块是未能判定() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok("Killed\n");

        ShellRunner.Result result = runner(fake).run("while true; do :; done", "true", List.of(), null);

        assertThat(result.verdict()).isEqualTo("undetermined");
        assertThat(result.reason()).contains("没有返回结果");
    }

    @Test
    void 超时是未能判定且说清原因() {
        FakeRunner fake = new FakeRunner();
        fake.result = new CodeRun.Result("", "", -1, 15_000, true, false, false);

        ShellRunner.Result result = runner(fake).run("sleep 999", "true", List.of(), null);

        assertThat(result.verdict()).isEqualTo("undetermined");
        assertThat(result.reason()).contains("超时");
    }

    @Test
    void 文件变更从协议块里取() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok(protocol(0, 0, "", ""));

        ShellRunner.Result result = runner(fake).run("chmod 600 a.txt", "true", List.of(), null);

        assertThat(result.diff()).contains("./a.txt");
    }

    @Test
    void 三个文件走项目模式且seed落在work目录下() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok(protocol(0, 0, "", ""));

        runner(fake).run("echo hi", "true",
                List.of(new ShellRunner.Seed("app/secrets.env", "TOKEN=demo")), "case-9");

        assertThat(fake.last.language()).isEqualTo("shell");
        assertThat(fake.last.project()).isTrue();
        assertThat(fake.last.caseId()).isEqualTo("case-9");
        List<String> paths = new ArrayList<>();
        fake.last.files().forEach(file -> paths.add(file.path()));
        assertThat(paths).containsExactly("run.sh", "judge.sh", "work/app/secrets.env");
    }

    @Test
    void 协议块里的数字不合法时按未能判定处理() {
        FakeRunner fake = new FakeRunner();
        fake.result = ok("###RC\nabc def\n###OUT\n");

        ShellRunner.Result result = runner(fake).run("true", "true", List.of(), null);

        assertThat(result.verdict()).isEqualTo("undetermined");
    }

    @Test
    void 解析器忽略协议块之前的杂音() {
        // 容器 stderr 之外不该有杂音，但 docker 自己偶尔会往 stdout 写警告；
        // 解析只认标记行，前面的内容直接丢掉
        ShellRunner.Parsed parsed = ShellRunner.parse("WARNING: something\n" + protocol(0, 0, "ok", ""));

        assertThat(parsed).isNotNull();
        assertThat(parsed.stdout()).isEqualTo("ok");
    }
}

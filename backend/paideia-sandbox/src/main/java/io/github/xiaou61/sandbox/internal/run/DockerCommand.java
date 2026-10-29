package io.github.xiaou61.sandbox.internal.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 构造 `docker run` 命令行（纯函数，WORK-015）。
 *
 * <p><b>为什么单独一个类而不是塞在 runner 里</b>：这些参数是**安全边界**，必须能被逐条断言。
 * 一个"看起来对"的命令行少写一个 `--cap-drop` 就等于没隔离，而那在本地根本测不出来。
 * 所以这里只做字符串拼装，由 {@code DockerCommandTest} 把每一条加固标志钉死。
 *
 * <p>另外三条纪律：
 * <ul>
 *   <li>**没有 `-v` / `--mount`**：代码通过 stdin 进容器，宿主文件系统不出现。</li>
 *   <li>**镜像来自配置，不来自请求**：用户输入永远进不了命令行。</li>
 *   <li>`--read-only` 之后仍要可写 `/tmp`：`javac` 得写 class 文件，所以给一个 64MB 的 tmpfs。</li>
 * </ul>
 */
final class DockerCommand {

    /**
     * 容器内执行脚本：从 stdin 落盘，再编译/运行。
     *
     * <p><b>分帧协议</b>：先读一行「源码字节数」，再读恰好那么多字节作为源码，剩下的才是
     * 程序的标准输入。不能简单 `cat > file` 把代码和 stdin 一起倒进去——`cat` 会一直吃到 EOF，
     * 用户传入的 stdin 会被追加进源码（变成莫名其妙的编译错误），而程序自己拿到的是 EOF。
     *
     * <p>脚本本身是常量，不含用户内容。
     */
    private static final String JAVA_SCRIPT = """
            read -r size
            dd bs=1 count="$size" of=/tmp/Main.java 2>/dev/null
            cd /tmp && javac Main.java && java Main""";
    private static final String PYTHON_SCRIPT = """
            read -r size
            dd bs=1 count="$size" of=/tmp/main.py 2>/dev/null
            cd /tmp && python main.py""";

    /**
     * Spring 项目脚本（WORK-055）。
     *
     * <p>与其它语言的区别只有三处，全部来自尖刺实测（`.agent/notes/spring-project-sandbox-spike.md`）：
     * <ol>
     *   <li>骨架在镜像里（`/opt/skeleton`），用户文件覆盖到 `/tmp/app/src/` 下；</li>
     *   <li>离线跑：`mvn -o` + `-Dmaven.repo.local=/opt/m2/repository`（**必须指到 repository 子目录**，
     *       指到上一级会报 "parent POM absent"）；</li>
     *   <li>`HOME` 与 `MAVEN_CONFIG` 要指到可写目录——基础镜像把它们写死在 `/root` 下，
     *       而容器是非 root、根只读。</li>
     * </ol>
     *
     * <p>判分看退出码（测试不通过时 mvn 非 0）；下面再把 surefire 的汇总与失败用例打到输出里，
     * 让用户知道挂在哪一条——不解析这两段也能判，但没人愿意只看到一句"退出码 1"。
     */
    private static final String SPRING_PROJECT_SCRIPT = """
            read -r count
            i=0
            mkdir -p /tmp/app /tmp/home
            cp -a /opt/skeleton/. /tmp/app/
            while [ "$i" -lt "$count" ]; do
              read -r path
              read -r size
              mkdir -p "/tmp/app/src/$(dirname "$path")"
              dd bs=1 count="$size" of="/tmp/app/src/$path" 2>/dev/null
              i=$((i + 1))
            done
            cd /tmp/app || exit 71
            export HOME=/tmp/home
            export MAVEN_CONFIG=/tmp/home/.m2
            mvn -o -q -B -Dmaven.repo.local=/opt/m2/repository test
            code=$?
            echo
            grep -h "Tests run" target/surefire-reports/*.txt 2>/dev/null || echo "没有产生测试报告（编译失败或依赖缺失）"
            grep -h -A3 "<<< FAILURE" target/surefire-reports/*.txt 2>/dev/null | head -20 || true
            exit $code""";

    /**
     * 录制脚本（WORK-023）：用 sys.settrace 逐步记录行号、局部变量与输出。
     *
     * <p><b>三条安全约束</b>写在脚本里而不是靠"调用方记得"：
     * <ol>
     *   <li>用户代码在**独立命名空间**里 exec，不共享录制器的全局变量；</li>
     *   <li>`builtins` 只保留一份拷贝，且**不把录制器暴露给用户代码**；</li>
     *   <li>步数、每步变量数、值长度、总字节都有硬上限，触顶即停止录制并保留已录部分。</li>
     * </ol>
     *
     * <p>输出走**文件描述符 3**（而不是 stdout 混在程序输出里）：程序自己的 print 与
     * 录制数据必须能分开，否则一个打印 JSON 的程序会让结果解析不出来。
     */
    private static final String PYTHON_TRACE_SCRIPT = """
            read -r size
            dd bs=1 count="$size" of=/tmp/user_main.py 2>/dev/null
            cd /tmp && python /opt/trace.py""";

    /**
     * SQL 脚本（WORK-032）。
     *
     * <p>**为什么 stdout 就是结果**：这里没有"用户程序自己 print"这一层——用户写的就是查询，
     * 查询的输出就是结果。所以不需要像 Python 录制那样借 fd 3：数据集导入的输出全部丢弃，
     * stdout 上只剩查询结果。错误走 stderr，与其它语言一致。
     *
     * <p>分帧：先读数据集长度（来自请求的 `stdin`）、再读用户 SQL 长度。数据集是**受信任内容**，
     * 由服务端按白名单取，不来自请求。
     *
     * <p>三条硬约束写死在脚本里，不依赖配置默认值：
     * <ol>
     *   <li>`--secure-file-priv` 指向**已创建**的空目录——实测指向不存在的路径会让 mysqld
     *       直接 Aborting，而且报错在容器启动之后，排查起来像"镜像坏了"；</li>
     *   <li>`--local-infile=0`：默认已关，仍写死，防将来换镜像时默认值变了；</li>
     *   <li>`--skip-log-bin`：不写 binlog，省磁盘也少一条能读文件内容的路径。</li>
     * </ol>
     *
     * <p>输出用 `--xml` 而不是 `--batch`：`--batch` 下多个结果集顺序拼在一起、**没有任何边界**，
     * 只能靠插标记查询硬切，而标记行与真实数据行长得一样（`--raw` 下无法区分），实测确实会切错。
     * `--xml` 每个结果集自带 `<resultset statement="...">` 起止、`xsi:nil` 显式表达 NULL、
     * `DECIMAL` 保留尾零，三件事一次解决。
     *
     * <p>datadir 从镜像里拷进 tmpfs 再 `chmod u+w`：镜像里的基准 datadir 是只读的（防误写），
     * `cp -a` 会把只读权限一起带过来，于是 mysqld 报 `ibdata1 must be writable`（实测踩过）。
     *
     * <p>**`--pid-file` 与 `--log-error` 必须指到 tmpfs**：根文件系统是 `--read-only`，
     * 而 mysqld 默认往 `/var/run/mysqld/` 写 pid 与 socket 锁。写不了时它**不报错也不退出**，
     * 只是初始化完 InnoDB 之后静默卡住——线上表现为"每次运行都 20 秒超时"，日志里只有
     * 两行 System 记录（实测踩过，排查了两轮）。同理 `--mysqlx=0` 关掉 X 协议插件，
     * 它也要往那个目录写 socket。
     *
     * <p>**必须先建库并 `--database`**：mysqld 启动后没有"当前库"这个概念，直接执行
     * `CREATE TABLE t` 会报 `ERROR 1046 No database selected`（实测踩过）。库名写死在脚本里，
     * 用户的 SQL 里不需要也不应该自己写 `USE`。
     */
    private static final String SQL_SCRIPT = """
            read -r dlen
            dd bs=1 count="$dlen" of=/tmp/dataset.sql 2>/dev/null
            read -r clen
            dd bs=1 count="$clen" of=/tmp/user.sql 2>/dev/null
            mkdir -p /tmp/nofiles
            cp -a /opt/datadir-base /tmp/data && chmod -R u+w /tmp/data
            mysqld --skip-networking --socket=/tmp/m.sock --datadir=/tmp/data --user=mysql \
              --secure-file-priv=/tmp/nofiles --local-infile=0 --skip-log-bin \
              --mysqlx=0 --pid-file=/tmp/data/mysqld.pid --log-error=/tmp/mysqld.log &
            for i in $(seq 1 900); do
              mysqladmin --socket=/tmp/m.sock -uroot ping >/dev/null 2>&1 && break
              sleep 0.02
            done
            mysqladmin --socket=/tmp/m.sock -uroot ping >/dev/null 2>&1 || {
              tail -5 /tmp/mysqld.log >&2; exit 8; }
            mysql --socket=/tmp/m.sock -uroot -e "CREATE DATABASE practice"
            mysql --socket=/tmp/m.sock -uroot practice < /tmp/dataset.sql >/dev/null 2>/tmp/ds.err || {
              cat /tmp/ds.err >&2; exit 9; }
            mysql --socket=/tmp/m.sock -uroot practice --xml \
              --max-allowed-packet=16m < /tmp/user.sql""";

    /**
     * SQL 执行计划脚本（WORK-035 T-05）。
     *
     * <p>与 {@link #SQL_SCRIPT} 只差最后一行：把用户 SQL 包进 `EXPLAIN FORMAT=TREE`。
     *
     * <p>**为什么不复用同一个脚本加个开关**：两条路径的"用户 SQL"在语义上不同——
     * 计划模式不执行语句（写操作不会真的写），普通模式会执行。用一个变量拼命令行会让
     * "到底跑的是哪一种"变成运行时才确定的事，而这个区别对用户是可观察的（`INSERT` 在
     * 计划模式下不落数据）。两个脚本各自完整，读代码时一眼能看清。
     *
     * <p>**为什么不在同一次运行里先跑 EXPLAIN 再跑 SQL**：两个结果集会拼在 stdout 上，
     * 而 `--xml` 的每个 `<resultset>` 都长得一样，前端无法可靠区分"这是计划还是数据"。
     * 一次只跑一种，语义干净。
     */
    private static final String SQL_EXPLAIN_SCRIPT = """
            read -r dlen
            dd bs=1 count="$dlen" of=/tmp/dataset.sql 2>/dev/null
            read -r clen
            dd bs=1 count="$clen" of=/tmp/user.sql 2>/dev/null
            mkdir -p /tmp/nofiles
            cp -a /opt/datadir-base /tmp/data && chmod -R u+w /tmp/data
            mysqld --skip-networking --socket=/tmp/m.sock --datadir=/tmp/data --user=mysql \
              --secure-file-priv=/tmp/nofiles --local-infile=0 --skip-log-bin \
              --mysqlx=0 --pid-file=/tmp/data/mysqld.pid --log-error=/tmp/mysqld.log &
            for i in $(seq 1 900); do
              mysqladmin --socket=/tmp/m.sock -uroot ping >/dev/null 2>&1 && break
              sleep 0.02
            done
            mysqladmin --socket=/tmp/m.sock -uroot ping >/dev/null 2>&1 || {
              tail -5 /tmp/mysqld.log >&2; exit 8; }
            mysql --socket=/tmp/m.sock -uroot -e "CREATE DATABASE practice"
            mysql --socket=/tmp/m.sock -uroot practice < /tmp/dataset.sql >/dev/null 2>/tmp/ds.err || {
              cat /tmp/ds.err >&2; exit 9; }
            { printf 'EXPLAIN FORMAT=TREE '; cat /tmp/user.sql; } \\
              | mysql --socket=/tmp/m.sock -uroot practice --xml --max-allowed-packet=16m""";

    /** mysqld 起不来时的退出码（与用户 SQL 自身失败区分）。 */
    static final int SQL_BOOT_FAILED = 8;

    /** 数据集导入失败时的退出码。 */
    static final int SQL_DATASET_FAILED = 9;

    private DockerCommand() {
    }

    /**
     * @param language 已校验过的语言标识（java / python / sql）
     * @param props    资源与运行时配置
     * @param cidFile  容器 id 落到哪个文件（超时兜底删容器用）
     */
    static List<String> of(String language, SandboxProperties props, String cidFile) {
        return of(language, props, cidFile, false);
    }

    /**
     * @param explain SQL 专用（WORK-035 T-05）：跑执行计划而不是执行语句。
     *                加固参数与普通运行**完全一致**——计划模式仍然启动一个真实容器，
     *                仍然要读数据集，隔离要求没有降低。
     */
    static List<String> of(String language, SandboxProperties props, String cidFile, boolean explain) {
        return of(language, props, cidFile, explain, false);
    }

    /**
     * @param project 项目模式（WORK-038）：从 stdin 读 N 个文件写进 /tmp/work，编译后跑
     *                **固定的测试入口**。加固参数与单文件模式完全一致——多文件只改变"代码怎么进去"，
     *                不改变隔离强度。入口名是常量，请求里的任何字段都进不了命令行。
     */
    static List<String> of(String language, SandboxProperties props, String cidFile, boolean explain,
                           boolean project) {
        String image = props.imageFor(language);
        if (image == null) {
            throw new IllegalArgumentException("不支持的语言：" + language);
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        boolean sql = "sql".equals(normalized);
        // Spring（WORK-055）：Maven 会 fork 一个 JVM 并同时打开上百个 jar。
        // 60 个 fd 时它连 maven-resources-plugin 都加载不了（报"类缺失"，实测），
        // 所以这几个上限单独放宽——仍是**有界值**，不是取消限制。
        boolean spring = "spring".equals(normalized);
        if (project && sql) {
            throw new IllegalArgumentException("SQL 不支持项目模式");
        }

        List<String> command = new ArrayList<>(List.of(
                "docker", "run",
                "--rm",
                "-i",
                // ── 网络：完全断开。任何出网都可能被当成代理、扫描器或矿池客户端 ──
                "--network=none",
                // ── 文件系统：根只读，唯一的可写处是一个有上限的 tmpfs ──
                "--read-only",
                // SQL 的 datadir 副本要装下 180MB 基准 + 系统库写入，64m 远远不够；
                // 768m 给了 4 倍余量，且仍是硬上限。
                // Spring 项目要装骨架副本 + target + mvn 自己的临时文件，64m 不够；实测 256m 够用，给 512m 余量。
                "--tmpfs",
                sql ? "/tmp:rw,size=768m,exec"
                        : "spring".equals(normalized) ? "/tmp:rw,size=512m,exec"
                        : "/tmp:rw,size=64m,exec",
                // ── 身份与权限：非 root、cap 全丢、禁止提权 ──
                // mysqld 必须以 mysql 用户跑（镜像里是 uid 999），其它语言用 nobody。
                "--user", sql ? "999:999" : "65534:65534",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                // ── 资源上限（按语言取值：mysqld 常驻约 348MB，256m 会被 OOM 杀掉）──
                "--memory=" + props.memoryFor(normalized),
                "--memory-swap=" + props.memoryFor(normalized),
                "--cpus=" + props.cpusFor(normalized),
                "--pids-limit=" + (spring ? "256" : String.valueOf(props.pidsLimitFor(normalized))),
                "--ulimit", sql || spring ? "nofile=1024" : "nofile=64",
                "--ulimit", sql ? "nproc=128" : spring ? "nproc=256" : "nproc=64",
                // 停止超时 0：超时后立即 SIGKILL，不给程序自己收尾的机会
                "--stop-timeout=0",
                // 记录容器 id：宿主上的 docker CLI 被强杀后容器仍归 daemon 管，
                // `--rm` 只在容器自己退出时生效。调用方据此在超时分支里补一刀 docker rm -f。
                "--cidfile", cidFile,
                // 时区固定：输出不随部署机漂移
                "-e", "TZ=UTC",
                // 入口：脚本从 stdin 读代码
                "--entrypoint", "sh"
        ));

        // 可选：切到 gVisor。空字符串 = 不传，走 Docker 默认 runtime
        if (props.getRuntime() != null && !props.getRuntime().isBlank()) {
            command.add("--runtime=" + props.getRuntime().trim());
        }

        command.add(image);
        command.add("-c");
        // explain 只对 SQL 有意义：其它语言传进来也不该悄悄换成"普通运行"以外的东西
        command.add(project ? projectScriptFor(normalized) : scriptFor(normalized, explain && sql));
        return List.copyOf(command);
    }

    /**
     * 项目模式的脚本（WORK-038）。
     *
     * <p>分帧与单文件模式同一个思路：第一行是文件数，之后每个文件是「路径一行、字节数一行、
     * 内容恰好那么多字节」。这样用户代码里的换行、空行、甚至以数字开头的一行都不会被误读成控制行。
     *
     * <p>路径已由控制器限制在 `[A-Za-z0-9_./-]` 且不含 `..`，所以能安全地插进 shell 双引号里；
     * 路径里没有空格与引号，`find` 的输出也不会被拆词。
     *
     * <p>运行的是**固定的测试入口**（Java `TestMain`、Python `test_main.py`），不是用户文件——
     * 入口名来自常量，请求里的任何内容都进不了命令行。
     */
    private static String projectScriptFor(String language) {
        if ("spring".equals(language)) {
            // Spring 的脚本自己读帧、自己写 src/ 下，不走 /tmp/work 那套单文件入口
            return SPRING_PROJECT_SCRIPT;
        }
        String entry = "python".equals(language)
                ? "cd /tmp/work && python test_main.py"
                : "cd /tmp/work && javac -d /tmp/work/out $(find . -name '*.java') && java -cp /tmp/work/out TestMain";
        return """
                read -r count
                i=0
                mkdir -p /tmp/work
                while [ "$i" -lt "$count" ]; do
                  read -r path
                  read -r size
                  mkdir -p "/tmp/work/$(dirname "$path")"
                  dd bs=1 count="$size" of="/tmp/work/$path" 2>/dev/null
                  i=$((i+1))
                done
                """ + entry;
    }

    /**
     * 录制命令（WORK-023）。
     *
     * <p>与 {@link #of} **共用同一套加固参数**（无网络、只读根、非 root、cap 全丢、资源上限）：
     * 录制不是"更宽松的运行"，它跑的是同样的用户代码。只有两处不同：
     * <ul>
     *   <li>入口脚本换成 `python /opt/trace.py`；</li>
     *   <li>脚本要读 `/opt/trace.py`，所以那个文件必须由镜像提供——本期做法是把
     *       {@code trace.py} 的内容也经 stdin 送进去，与源码一起分帧，不依赖镜像改造。</li>
     * </ul>
     */
    static List<String> ofTrace(SandboxProperties props, String cidFile, int traceScriptBytes) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-i",
                "--network=none",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=64m,exec",
                "--user", "65534:65534",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--memory=" + props.getMemory(),
                "--memory-swap=" + props.getMemory(),
                "--cpus=" + props.getCpus(),
                "--pids-limit=" + props.getPidsLimit(),
                "--ulimit", "nofile=64",
                "--ulimit", "nproc=64",
                "--stop-timeout=0",
                "--cidfile", cidFile,
                "-e", "TZ=UTC",
                "--entrypoint", "sh"));
        if (props.getRuntime() != null && !props.getRuntime().isBlank()) {
            command.add("--runtime=" + props.getRuntime().trim());
        }
        command.add(props.imageFor("python"));
        command.add("-c");
        // 分帧：先读录制器脚本长度，再读源码长度，最后才是 stdin
        command.add("read -r tracelen && dd bs=1 count=\"$tracelen\" of=/tmp/trace.py 2>/dev/null"
                + " && read -r size && dd bs=1 count=\"$size\" of=/tmp/user_main.py 2>/dev/null"
                + " && cd /tmp && PYTHONPATH=/tmp python /tmp/trace.py");
        return List.copyOf(command);
    }

    /**
     * 容器内的脚本常量（测试与文档共用，避免两处各写一份）。
     */
    static String scriptFor(String language) {
        return scriptFor(language, false);
    }

    /**
     * @param explain SQL 专用：跑执行计划而不是执行语句。其它语言忽略此参数
     *                （它们的"计划"没有对应的 SQL 概念，调用方在入口就该拒掉）。
     */
    static String scriptFor(String language, boolean explain) {
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "java" -> JAVA_SCRIPT;
            case "sql" -> explain ? SQL_EXPLAIN_SCRIPT : SQL_SCRIPT;
            // Spring 只能项目模式：单文件跑一个 Spring 项目没有意义，静默落到 python 更糟
            case "spring" -> throw new IllegalArgumentException("spring 只支持项目模式（需要 files）");
            default -> PYTHON_SCRIPT;
        };
    }
}

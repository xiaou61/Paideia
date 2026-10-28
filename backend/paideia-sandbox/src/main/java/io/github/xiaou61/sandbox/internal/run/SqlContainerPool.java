package io.github.xiaou61.sandbox.internal.run;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SQL 沙箱容器池（WORK-049）。
 *
 * <p><b>为什么要有它</b>：一次性容器每次执行要付三笔固定成本——起容器 1.1 秒、拷 78MB 基准
 * datadir 1.05 秒、mysqld 初始化 InnoDB 约 2 秒。线上实测一次 SQL 要 3.5–4.2 秒，而数据集
 * 只有 1.4 KB，慢的全部是"每次开一台新机器"。池化之后实测 **约 120 毫秒**。
 *
 * <p><b>代价（必须一起读）</b>：隔离从"每次一个干净容器"降为"多用户共享一个 mysqld"。
 * 所以每次执行都建**独立的库 + 独立账号**，账号只授权给那个库，执行完连同库一起删。
 * 容器本身仍保留全部加固参数（无网络、只读根、cap 全丢、CPU/内存/pid 上限），
 * 只是不再一人一个。
 *
 * <p><b>回落是设计的一部分</b>：池没起、池忙、容器不健康、执行异常——一律返回 empty，
 * 由调用方走既有的"起一个干净容器"那条路。SQL 练习不能因为池而不可用。
 */
@Component
class SqlContainerPool {

    private static final Logger log = LoggerFactory.getLogger(SqlContainerPool.class);

    /** 池里一个槽位：一个常驻容器。槽位被取走时容器归调用方独占。 */
    private static final class Slot {
        final int index;
        String container;

        Slot(int index) {
            this.index = index;
        }
    }

    private final SandboxProperties properties;
    private final BlockingQueue<Slot> idle;

    SqlContainerPool(SandboxProperties properties) {
        this.properties = properties;
        int size = Math.max(0, properties.getSqlPoolSize());
        List<Slot> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new Slot(i));
        }
        this.idle = new ArrayBlockingQueue<>(Math.max(1, size));
        this.idle.addAll(slots);
        if (size > 0) {
            log.info("sql.pool.enabled size={} memory={} acquireTimeoutMs={}",
                    size, properties.getSqlMemory(), properties.getSqlPoolAcquireTimeoutMs());
        }
    }

    boolean enabled() {
        return properties.getSqlPoolSize() > 0 && properties.isEnabled();
    }

    /**
     * 在池里执行一次。拿不到槽位、容器起不来、执行异常都返回 empty——调用方回落一次性容器。
     *
     * <p><b>拿不到槽位不排队等</b>：排队会让 p99 变成"未知的几秒"。直接回落到一次性容器，
     * 用户拿到的是慢但确定的 4 秒，而不是可能 30 秒的等待。
     */
    Optional<CodeRun.Result> tryRun(CodeRun.Request request) {
        if (!enabled()) {
            return Optional.empty();
        }
        Slot slot;
        try {
            slot = idle.poll(properties.getSqlPoolAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (slot == null) {
            return Optional.empty();
        }
        boolean reusable = true;
        try {
            ensureContainer(slot);
            return Optional.of(execute(slot, request));
        } catch (RuntimeException failure) {
            // 容器可能被跑挂了（mysqld 崩、OOM、执行超时）；回收它，下一个请求重建
            reusable = false;
            log.warn("sql.pool.run_failed container={} reason={}", slot.container, failure.getMessage());
            return Optional.empty();
        } finally {
            if (!reusable) {
                destroy(slot);
            }
            idle.offer(slot);
        }
    }

    /** 没有常驻容器就用 `docker run -d` 起一个，并等到 mysqld 能 ping 通。 */
    private void ensureContainer(Slot slot) {
        if (slot.container != null && ping(slot.container)) {
            return;
        }
        destroy(slot);
        String name = "paideia-sql-pool-" + slot.index;
        List<String> command = List.of(
                "docker", "run", "-d", "--name", name,
                "--network=none",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=768m,exec",
                "--user", "999:999",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                // 512m 会被 OOM 杀掉：tmpfs 里的 datadir 也计入容器内存，
                // mysqld 在 InnoDB 初始化结束后需要约 350MB 常驻，两者相加超了上限。
                // 2026-10-28 实测：512m 时 mysqld 静默消失、日志无任何错误；1g 正常。
                "--memory=" + properties.getSqlMemory(),
                "--memory-swap=" + properties.getSqlMemory(),
                "--cpus=" + properties.getSqlCpus(),
                "--pids-limit=" + properties.getSqlPidsLimit(),
                "--ulimit", "nofile=1024",
                "--ulimit", "nproc=128",
                "--stop-timeout=0",
                "-e", "TZ=UTC",
                properties.getSqlImage(),
                "sh", "-c", BOOT_SCRIPT);
        runQuietly(command, 30);
        slot.container = name;
        if (!waitReady(name)) {
            destroy(slot);
            throw new SandboxUnavailableException("池容器未就绪：" + name);
        }
        log.info("sql.pool.container_ready name={}", name);
    }

    private boolean waitReady(String name) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ping(name)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException cause) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean ping(String name) {
        try {
            Process process = new ProcessBuilder("docker", "exec", name,
                    "mysqladmin", "--socket=/tmp/m.sock", "-uroot", "ping")
                    .redirectErrorStream(true).start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                process.getInputStream().close();
                return false;
            }
            process.getInputStream().close();
            return true;
        } catch (IOException | InterruptedException cause) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private void destroy(Slot slot) {
        String name = slot.container;
        slot.container = null;
        if (name == null) {
            return;
        }
        runQuietly(List.of("docker", "rm", "-f", name), 10);
        log.info("sql.pool.container_removed name={}", name);
    }

    private void runQuietly(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            process.getInputStream().close();
        } catch (IOException | InterruptedException cause) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("sql.pool.command_failed command={} reason={}", command.get(0), cause.getMessage());
        }
    }

    private CodeRun.Result execute(Slot slot, CodeRun.Request request) {
        long started = System.nanoTime();
        List<String> command = List.of(
                "docker", "exec", "-i", slot.container, "sh", "-c",
                request.explain() ? EXPLAIN_SCRIPT : RUN_SCRIPT);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException cause) {
            throw new SandboxUnavailableException("池容器内执行失败：" + cause.getMessage(), cause);
        }

        // 与一次性路径同一套两段帧：先数据集长度+内容，再用户 SQL 长度+内容
        try (OutputStream stdin = process.getOutputStream()) {
            byte[] dataset = (request.stdin() == null ? "" : request.stdin()).getBytes(StandardCharsets.UTF_8);
            byte[] code = request.code().getBytes(StandardCharsets.UTF_8);
            stdin.write((dataset.length + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.write(dataset);
            stdin.write((code.length + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.write(code);
            stdin.flush();
        } catch (IOException ignored) {
            // 容器已退出时写 stdin 会失败，错误会体现在 stderr / 退出码上
        }

        DockerCodeRunner.BoundedBuffer out = new DockerCodeRunner.BoundedBuffer(properties.getMaxOutputBytes());
        DockerCodeRunner.BoundedBuffer err = new DockerCodeRunner.BoundedBuffer(properties.getMaxOutputBytes());
        Thread outReader = DockerCodeRunner.readInto(process.getInputStream(), out);
        Thread errReader = DockerCodeRunner.readInto(process.getErrorStream(), err);

        boolean finished;
        try {
            finished = process.waitFor(properties.getSqlTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxUnavailableException("执行被中断", cause);
        }
        if (!finished) {
            process.destroyForcibly();
            // 超时的查询可能还在容器里跑：整个容器换掉，别让下一个人接着等
            throw new SandboxUnavailableException("池内执行超时");
        }
        DockerCodeRunner.join(outReader);
        DockerCodeRunner.join(errReader);

        long durationMs = (System.nanoTime() - started) / 1_000_000;
        int exitCode = process.exitValue();
        String stdout = out.text();
        String stderr = err.text();
        SqlResultCodec.Parsed parsed = exitCode == 0
                ? SqlResultCodec.parse(stdout, properties.getMaxOutputBytes() / 8)
                : null;
        return new CodeRun.Result(
                stdout,
                stderr,
                exitCode,
                durationMs,
                false,
                false,
                parsed != null && parsed.overLimit(),
                parsed == null ? List.of() : parsed.tables());
    }

    /**
     * 容器启动脚本：拷一份基准 datadir、起 mysqld，然后留着不退出。
     *
     * <p>`exec` 之后 sleep 保持容器常驻；mysqld 用 `&` 放后台，与一次性脚本一致。
     */
    private static final String BOOT_SCRIPT = String.join("; ",
            "mkdir -p /tmp/nofiles",
            "cp -a /opt/datadir-base /tmp/data",
            "chmod -R u+w /tmp/data",
            "mysqld --skip-networking --socket=/tmp/m.sock --datadir=/tmp/data --user=mysql"
                    + " --secure-file-priv=/tmp/nofiles --local-infile=0 --skip-log-bin"
                    + " --mysqlx=0 --pid-file=/tmp/data/mysqld.pid --log-error=/tmp/mysqld.log &",
            "sleep 100000");

    /**
     * 一次执行：建库 + 建受限账号 → 灌数据集 → 跑用户 SQL → 删库删账号。
     *
     * <p><b>必须建账号</b>：池化之后多个用户共享同一个 mysqld，root 能看见所有人的临时库。
     * 用户 SQL 用只授权给本次库的账号跑，`SHOW DATABASES` 里就只有自己和系统库。
     *
     * <p>口令走 `MYSQL_PWD` 环境变量而不是 `-p`：`-p` 会把口令留在容器内的命令行上。
     */
    private static final String RUN_SCRIPT = script(false);

    private static final String EXPLAIN_SCRIPT = script(true);

    static String script(boolean explain) {
        String userQuery = explain
                ? "{ printf 'EXPLAIN FORMAT=TREE '; cat /tmp/user.sql; } | MYSQL_PWD=\"$PW\" $M -u\"$U\" \"$DB\" --xml --max-allowed-packet=16m"
                : "MYSQL_PWD=\"$PW\" $M -u\"$U\" \"$DB\" --xml --max-allowed-packet=16m < /tmp/user.sql";
        return String.join("; ",
                "read -r dlen",
                "dd bs=1 count=\"$dlen\" of=/tmp/dataset.sql 2>/dev/null",
                "read -r clen",
                "dd bs=1 count=\"$clen\" of=/tmp/user.sql 2>/dev/null",
                "DB=\"p$(date +%s%N)\"",
                "U=\"u$(date +%s%N | cut -c11-18)\"",
                "PW=\"$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \\n')\"",
                "M=\"mysql --socket=/tmp/m.sock\"",
                "$M -uroot -e \"CREATE DATABASE \\`$DB\\`; CREATE USER '$U'@'localhost' IDENTIFIED BY '$PW';"
                        + " GRANT ALL ON \\`$DB\\`.* TO '$U'@'localhost'\"",
                "$M -uroot \"$DB\" < /tmp/dataset.sql >/dev/null 2>/tmp/ds.err || { cat /tmp/ds.err >&2; RC=9; }",
                "[ \"${RC:-0}\" -eq 0 ] && { " + userQuery + "; RC=$?; }",
                "$M -uroot -e \"DROP DATABASE IF EXISTS \\`$DB\\`; DROP USER IF EXISTS '$U'@'localhost'\"",
                "exit ${RC:-9}");
    }
}

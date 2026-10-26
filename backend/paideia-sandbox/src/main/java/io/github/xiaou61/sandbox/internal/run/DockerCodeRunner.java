package io.github.xiaou61.sandbox.internal.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用一次性容器执行代码（WORK-015）。
 *
 * <p><b>为什么是 `docker run` 子进程而不是 runner 服务</b>：当前部署是 systemd + 裸 jar，
 * 宿主已有 Docker；`docker run` 本身就是独立进程，崩掉不影响 JVM。少一个部署单元、少一套
 * 内网鉴权。**已知限制**：应用一旦容器化，这条路就没有 socket 可用，届时必须改成独立 runner
 * （见 design「已知限制」）。
 *
 * <p><b>三条实现纪律</b>：
 * <ul>
 *   <li>**输出必须边跑边读**：不读的话管道写满 64KB 就会把子进程卡死，表现为"莫名超时"。</li>
 *   <li>**超时用 `destroyForcibly`**：先温和再强杀要两段等待，而这里没有"优雅退出"的语义。</li>
 *   <li>**代码只走 stdin**：不进命令行（否则会出现在 `ps` 与 shell 历史里），也不落宿主磁盘。</li>
 * </ul>
 */
@Component
class DockerCodeRunner implements CodeRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeRunner.class);

    private final SandboxProperties properties;
    private final SandboxCapability capability;

    DockerCodeRunner(SandboxProperties properties, SandboxCapability capability) {
        this.properties = properties;
        this.capability = capability;
    }

    @Override
    public boolean ready() {
        return properties.isEnabled() && capability.ready();
    }

    @Override
    public String unavailableReason() {
        if (!properties.isEnabled()) {
            return "代码执行已关闭";
        }
        return capability.reason();
    }

    @Override
    public CodeRun.Result run(CodeRun.Request request) {
        // docker run --cidfile 要求目标路径**不存在**（CLI 会先 stat，已存在就直接拒绝启动、
        // 以 exit 125 失败）。所以这里只生成一个唯一路径，不创建文件；用临时目录保证可写。
        Path cidDir;
        try {
            cidDir = Files.createTempDirectory("paideia-sandbox-");
        } catch (IOException cause) {
            throw new SandboxUnavailableException("无法创建容器 id 目录：" + cause.getMessage(), cause);
        }
        Path cidFile = cidDir.resolve("container.cid");
        try {
            return execute(request, DockerCommand.of(
                    request.language(), properties, cidFile.toString(), request.explain(),
                    request.project()));
        } finally {
            try {
                Files.deleteIfExists(cidFile);
                Files.deleteIfExists(cidDir);
            } catch (IOException ignored) {
                // 清理失败不影响结果
            }
        }
    }

    /**
     * 录制一次逐步执行（WORK-023）。
     *
     * <p>复用同一套加固参数与超时/强删逻辑，只有三处不同：
     * <ol>
     *   <li>分帧多一段：先送录制器脚本，再送源码；</li>
     *   <li>**录制数据从 stderr 读**（stdout 留给程序自己的 print）；</li>
     *   <li>不判定"程序是否正确"——录制不参与通过判定（REQ-013）。</li>
     * </ol>
     *
     * @return 录制器输出的原始行（每行一个 JSON 事件）+ 程序的 stdout/stderr
     */
    @Override
    public CodeRun.TraceResult trace(String code, String stdin, String traceScript) {
        Path cidDir;
        try {
            cidDir = Files.createTempDirectory("paideia-trace-");
        } catch (IOException cause) {
            throw new SandboxUnavailableException("无法创建容器 id 目录：" + cause.getMessage(), cause);
        }
        Path cidFile = cidDir.resolve("container.cid");
        try {
            byte[] script = traceScript.getBytes(StandardCharsets.UTF_8);
            List<String> command = DockerCommand.ofTrace(properties, cidFile.toString(), script.length);
            return executeTrace(code, stdin, script, command);
        } finally {
            try {
                Files.deleteIfExists(cidFile);
                Files.deleteIfExists(cidDir);
            } catch (IOException ignored) {
                // 清理失败不影响结果
            }
        }
    }

    private CodeRun.TraceResult executeTrace(String code, String stdin, byte[] script, List<String> command) {
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException cause) {
            throw new SandboxUnavailableException("启动容器失败：" + cause.getMessage(), cause);
        }

        try (OutputStream out = process.getOutputStream()) {
            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            out.write((script.length + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(script);
            out.write((codeBytes.length + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(codeBytes);
            if (stdin != null && !stdin.isEmpty()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (IOException cause) {
            log.debug("写入录制 stdin 失败（容器可能已退出）：{}", cause.getMessage());
        }

        BoundedBuffer stdout = new BoundedBuffer(properties.getMaxOutputBytes());
        BoundedBuffer stderr = new BoundedBuffer(properties.getMaxOutputBytes());
        Thread outReader = streamReader(process.getInputStream(), stdout);
        Thread errReader = streamReader(process.getErrorStream(), stderr);

        boolean finished;
        try {
            finished = process.waitFor(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxUnavailableException("执行被中断", cause);
        }
        if (!finished) {
            process.destroyForcibly();
            removeContainer(command);
        }
        joinQuietly(outReader);
        joinQuietly(errReader);

        long durationMs = (System.nanoTime() - started) / 1_000_000;
        log.info("sandbox trace finished={} durationMs={} events={}",
                finished, durationMs, stderr.text().lines().count());
        return new CodeRun.TraceResult(stdout.text(), stderr.text(), durationMs, !finished);
    }

    private CodeRun.Result execute(CodeRun.Request request, List<String> command) {
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException cause) {
            throw new SandboxUnavailableException("启动容器失败：" + cause.getMessage(), cause);
        }

        // 分帧写入：第一行是源码字节数，容器先用 dd 精确取走源码，剩下的才是程序的标准输入。
        // 直接顺序写会让 `cat` 把 stdin 也吞进源码文件（见 DockerCommand 的说明）。
        //
        // SQL 是**两段帧**：先数据集长度、再用户工程长度。数据集来自服务端的白名单内容
        // （请求里的 `stdin` 字段承载），用户写的是 `code`。
        //
        // SQL 还要把多条语句交给**同一个 mysql 进程**顺序执行：事务题的 BEGIN/ROLLBACK
        // 只有在同一会话里才是真的。结果集边界由 `--xml` 自己给出（见 SqlResultCodec）。
        try (OutputStream stdin = process.getOutputStream()) {
            boolean sql = isSql(request.language());
            if (request.project()) {
                // 项目模式（WORK-038）：文件数 + 每个文件「路径 / 字节数 / 内容」。
                // 不追加程序 stdin——项目跑的是测试入口，它不读输入。
                stdin.write((request.files().size() + "\n").getBytes(StandardCharsets.UTF_8));
                for (CodeRun.FileEntry file : request.files()) {
                    stdin.write(file.path().getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                    byte[] body = file.content().getBytes(StandardCharsets.UTF_8);
                    stdin.write((body.length + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.write(body);
                }
            } else {
                byte[] code = request.code().getBytes(StandardCharsets.UTF_8);
                if (sql) {
                    byte[] dataset = (request.stdin() == null ? "" : request.stdin()).getBytes(StandardCharsets.UTF_8);
                    stdin.write((dataset.length + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.write(dataset);
                }
                stdin.write((code.length + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.write(code);
                if (!sql && request.stdin() != null && !request.stdin().isEmpty()) {
                    stdin.write(request.stdin().getBytes(StandardCharsets.UTF_8));
                }
            }
            stdin.flush();
        } catch (IOException cause) {
            // 容器提前退出（例如镜像缺失）时写 stdin 会失败；继续读输出，错误会体现在 stderr
            log.debug("写入容器 stdin 失败（容器可能已退出）：{}", cause.getMessage());
        }

        // 两个流同时读：不读的那个会把子进程卡在 write 上
        BoundedBuffer stdout = new BoundedBuffer(properties.getMaxOutputBytes());
        BoundedBuffer stderr = new BoundedBuffer(properties.getMaxOutputBytes());
        Thread outReader = streamReader(process.getInputStream(), stdout);
        Thread errReader = streamReader(process.getErrorStream(), stderr);

        boolean finished;
        try {
            // 项目模式要编译多个文件再跑测试，冷容器里 10 秒常常不够；给到 30 秒下限。
            long timeout = request.project()
                    ? Math.max(properties.timeoutMsFor(request.language()), 30_000L)
                    : properties.timeoutMsFor(request.language());
            finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SandboxUnavailableException("执行被中断", cause);
        }
        if (!finished) {
            // 强杀宿主上的 docker CLI 只是第一步：CLI 被 SIGKILL 后来不及通知 daemon，
            // 容器仍由 daemon 管着（--rm 只在容器自己退出时生效）。不补这一刀，
            // 一个 while(true) 会带着 0.5 CPU / 256MB 一直跑，超时约束形同虚设。
            process.destroyForcibly();
            removeContainer(command);
        }

        joinQuietly(outReader);
        joinQuietly(errReader);
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        int exitCode = finished ? process.exitValue() : -1;
        String out = stdout.text();
        String err = stderr.text();
        boolean oom = !finished && looksLikeOom(exitCode, err, out);
        SqlResultCodec.Parsed parsed = isSql(request.language()) && exitCode == 0
                ? SqlResultCodec.parse(out, properties.getMaxOutputBytes() / 8)
                : new SqlResultCodec.Parsed(java.util.List.of(), false);
        log.info("sandbox run language={} finished={} exit={} durationMs={} outBytes={}",
                request.language(), finished, exitCode, durationMs,
                out.getBytes(StandardCharsets.UTF_8).length);

        return new CodeRun.Result(
                out,
                err,
                exitCode,
                durationMs,
                !finished,
                oom || exitCode == 137 && err.contains("Killed"),
                stdout.truncated() || stderr.truncated() || parsed.overLimit(),
                parsed.tables());
    }

    private static boolean isSql(String language) {
        return language != null && "sql".equalsIgnoreCase(language.trim());
    }

    /**
     * 区分"超时"与"被 OOM 杀掉"。
     *
     * <p>两者都是非 0 退出，但给用户的提示完全不同：一个是"你的循环没停"，另一个是"你的内存用超了"。
     * 容器被 cgroup OOM killer 杀时，退出码通常是 137，且 stderr 里会有 `Killed`；
     * 而普通超时是我们主动 kill，**不会有** Killed 字样。
     */
    private static boolean looksLikeOom(int exitCode, String err, String out) {
        return exitCode == 137 && (err.contains("Killed") || out.contains("Killed"));
    }

    private static Thread streamReader(InputStream stream, BoundedBuffer sink) {
        Thread thread = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    sink.write(chunk, read);
                }
            } catch (IOException ignored) {
                // 进程被杀时读端会异常结束，这是预期路径
            }
        }, "sandbox-stream");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 超时兜底：按 cidfile 里的容器 id 强删。
     *
     * <p>失败也不抛——超时的结论已经确定，删不掉只是留下一个会被后续清理捞走的容器，
     * 不能让清理动作把"超时"这个结果改成 500。
     */
    private static void removeContainer(List<String> command) {
        int index = command.indexOf("--cidfile");
        if (index < 0 || index + 1 >= command.size()) {
            return;
        }
        Path cidFile = Path.of(command.get(index + 1));
        try {
            if (!Files.exists(cidFile)) {
                return;
            }
            String id = Files.readString(cidFile, StandardCharsets.UTF_8).trim();
            if (id.isEmpty()) {
                return;
            }
            new ProcessBuilder("docker", "rm", "-f", id)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
            log.info("超时后已强删容器 {}", id);
        } catch (IOException cause) {
            log.warn("超时后强删容器失败：{}", cause.getMessage());
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
        }
    }

    /** 有上限的输出缓冲：超限后继续读（排空管道）但不再存。 */
    static final class BoundedBuffer {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int limit;
        private boolean truncated;

        BoundedBuffer(int limit) {
            this.limit = limit;
        }

        synchronized void write(byte[] chunk, int length) {
            int room = limit - buffer.size();
            if (room <= 0) {
                truncated = true;
                return;
            }
            if (length > room) {
                buffer.write(chunk, 0, room);
                truncated = true;
                return;
            }
            buffer.write(chunk, 0, length);
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}

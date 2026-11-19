---
title: 信号与 kill：-9 的代价
category: linux-from-zero
order: 9
minutes: 18
level: beginner
status: published
---

## 我在 Windows 上发「请它退出」，它连一句话都没留

我想先确认一件事：让一个进程「收尾再走」，到底走不走得通。本机是 Windows，没有 Linux，于是我退一步，在 Node 里做同一件事——子进程注册一个退出处理器，写一行状态再退；父进程给它发终止信号。

```js
// probe.mjs：自己 spawn 自己。子进程注册 SIGTERM 处理器，父进程发信号
import { spawn } from 'node:child_process'
import fs from 'node:fs'

if (process.argv[2] === 'child') {
  fs.rmSync('worker.state', { force: true })
  process.on('SIGTERM', () => { fs.writeFileSync('worker.state', 'graceful'); process.exit(0) })
  setInterval(() => {}, 1000)
} else {
  const alive = (p) => { try { process.kill(p, 0); return 'alive' } catch { return 'gone' } }
  const child = spawn(process.execPath, [process.argv[1], 'child'], { stdio: 'ignore' })
  setTimeout(() => {
    console.log('pid=' + child.pid + ' before=' + alive(child.pid))
    process.kill(child.pid, 'SIGTERM')
    child.on('exit', (code) => {
      console.log('exit code=' + code)
      console.log('worker.state=' + (fs.existsSync('worker.state') ? fs.readFileSync('worker.state', 'utf8') : '<missing>'))
      setTimeout(() => console.log('after=' + alive(child.pid)), 100)
    })
  }, 900)
}
```

本机真跑，node v24.14.1 / win32，连跑两遍输出形状一致：

```text
$ node probe.mjs
pid=137888 before=alive
exit code=1
worker.state=<missing>
after=gone
```

我原来以为：Node 号称跨平台，`process.kill(pid, 'SIGTERM')` 至少能把信号送进那个处理器。实际是 Windows 上根本没有这一档——Node 的 `process.kill()` 文档写在明处：Windows 没有 POSIX 信号，除 `0` 之外的 signal 参数都会被忽略、进程被无条件终止，底层是 `TerminateProcess`。所以处理器里那句 `fs.writeFileSync('worker.state', 'graceful')` 一次都没执行（`worker.state=<missing>`），`exit code=1` 是 `TerminateProcess` 给的，不是我的收尾代码给的。

这台机器因此只能演示「暴力」那一半。剩下的 Linux 部分我没法实跑，下面每条 Linux 回显都按标准形态写、都标了「未实跑」——那是给你在真机上对照的，不是我的实验记录。

不过这半条实验还是留下了一样在两边都成立的东西：`process.kill(pid, 0)` 不发信号、只探活，`before=alive` 与 `after=gone` 就是它给出的两个答案。Linux 上同一个动作叫 `kill -0 PID`。本文后面每一次判断「它到底走没走」，靠的都是这个探活，而不是「我发过信号了」这件事。

## Linux 上这一步叫 kill，默认那一档不是 -9

```text
$ kill -l            # 未实跑；bash 内建 kill 的排版，列宽随终端宽度变，这里只示意排布
 1) SIGHUP       2) SIGINT       3) SIGQUIT      4) SIGILL       5) SIGTRAP
 6) SIGABRT      7) SIGBUS       8) SIGFPE       9) SIGKILL     10) SIGUSR1
11) SIGSEGV     12) SIGUSR2     13) SIGPIPE     14) SIGALRM     15) SIGTERM
```

`kill 9812` 默认发 `SIGTERM(15)`，不是 `-9`。这个默认值是有意留的余地：先请求，收到请求的程序可以刷盘、关连接、回滚事务再自己退；`SIGKILL(9)` 由内核直接终止，程序连捕获的机会都没有。

真正容易搞错的地方不在这两个编号，而在 `kill` 的返回值：

**`kill` 成功返回 0，只说明信号投递出去了，不代表进程死了。** `kill 9812; echo $?` 打出 0 之后，9812 可能还在跑——信号还排着队（比如进程卡在内核里，见后面那张表）、或者程序干脆把它忽略了。想确认死没死，只能用探活：`kill -0 9812; echo $?`，还在是 0，不在或你没权限都是 1。

**`kill` 也不等。** 它把信号挂上去就返回，剩下的时间不是它的事。这一点和 `systemctl stop` 正好相反，后者是同步的，会一直阻塞到单元停掉或超时——下一节那个发版脚本卡不卡，全看这一行的对面。

| 你敲的（未实跑） | 信号给到谁 | 进程能做什么 | 你拿到什么 |
| --- | --- | --- | --- |
| `kill 9812` | 9812，`SIGTERM` | 捕获后收尾，也可以忽略 | 立刻返回；0 只代表投递成功 |
| `kill -9 9812` | 9812，`SIGKILL` | 什么都做不了 | 立刻返回；死没死要另外查 |
| `kill -0 9812` | 不发信号 | — | 还在=0，不在或没权限=1 |
| `kill -l 15` | — | — | 反查编号，答 `TERM` |
| `kill -- -9812` | 进程组 9812 的每个成员 | 各自按自己的处理器处理 | 立刻返回，这是「带走一棵树」的写法 |
| `systemctl stop paideia` | 单元的整个 cgroup | 先 `SIGTERM`，按 `TimeoutStopSec` 等，再 `SIGKILL` | **同步阻塞**，直到停了或超时 |

`--` 不能省：`kill -9812` 会被解析成「信号 9812」，直接报 `invalid signal specification`。

退出码也常被误读：被 `SIGTERM` 的默认动作终止，`$?` 是 143（128+15）；被 `SIGKILL` 是 137（128+9）。但这只对「没自己接管信号」的程序成立。捕获了 `SIGTERM` 再 `exit 0` 的服务，`$?` 就是 0，拿 143 反推「它没优雅退出」会推错方向。

## 那次发版，停止服务只有一行

`scripts/release.ps1` 是本仓库真实的发版脚本，第 467–469 行就是停机那一步：

```text
# **先停服务再换文件**（见文件头约束 4）：应用在跑时换掉 app.jar 会让懒加载类找不到
t2=$(date +%s)
systemctl stop paideia
```

这段跑在服务器上，我在 Windows 本机没跑过它。文件头约束 4（`scripts/release.ps1:14-16`）自己写了为什么必须停：

```text
4. **远端只开两次 SSH 连接，且先停服务再换文件**（2026-11-02 改，原因见下）。
   Windows 上没法做连接复用（ControlMaster 传不了 socket fd），每次连接 5.7 秒起步；
   而"边跑边换 jar"会让关机路径上的懒加载类抛 NoClassDefFoundError。
```

注意这句里的因果：**关机路径本身要加载类**，所以「停」不是瞬间的事，它有一段真实的收尾时间，还可能因为类是懒加载的而在收尾时炸掉。这就是为什么这一行不是「发个信号就走」，而是一个带超时的等待。

`systemctl stop` 分三段（按 systemd.service 手册）：

1. 给单元里的进程发 `KillSignal`，默认 `SIGTERM`；
2. 等 `TimeoutStopSec`，它不单独设时取 `DefaultTimeoutStopSec`，手册里默认 90 秒（我没在本机核对，这台机器没有 systemd）；
3. 还没停，发 `SIGKILL`，这次停止就不是优雅停机的样子了。

这条链有一个必踩的陷阱：**应用的收尾超时和 `TimeoutStopSec` 相等时，两边同时到点，systemd 会抢在应用收尾完成前 `SIGKILL`，优雅停机等于没配。** 仓库里 `content/reading/ops/03-systemd把jar变成服务.md:135-138` 把这条写成了规矩：`TimeoutStopSec = 应用收尾超时 + 5~10 秒`；第 12 关那张速查表也标了默认值 90（`content/reading/linux-from-zero/12-systemd把程序变成服务.md:131`）。

超时后被强杀的那一刀，在 journal 里长这样（下面这条来自 `content/reading/linux/06-日志与内核消息.md:83`，那篇自己声明了是拼的示意、不是实测日志，我也照这个标注转述）：

```text
paideia.service: Killing process 4987 (java) with signal SIGKILL.
```

看到它，答案不是「进程有 bug」，而是「它没在给的时间里退出来」。往上看几秒，才能分清是它收尾慢、还是它压根没理 `SIGTERM`。

## 有些进程，-9 也拿不走

「杀不掉」不是一种情况，是四种。先看状态，再决定动谁：

| 症状 | 先看什么 | 说明 |
| --- | --- | --- |
| `kill -9` 之后 `ps` 里还在，STAT 是 `D` | `ps -eo pid,stat,wchan:20,comm`，读 `wchan` 列 | 卡在不可中断的内核等待（磁盘、NFS、块设备驱动）。信号是**挂起了**，不是没发出去；这次等待返回之前它不会死 |
| `ps` 里有一条 `Z`，怎么发信号它都不消失 | `ps -eo pid,ppid,stat,comm`，找它的 PPID | 它已经死了，缺的是父进程的 `wait()`。要动的是父进程，不是它 |
| `kill` 报 `Operation not permitted` | `ps -o uid,pid,comm -p <pid>` 对比 `id -u` | 不是同一个 uid；普通用户发不进别人的进程，`sudo` 只是换了发令的人 |
| 发了 `TERM` 毫无反应，进程还在跑 | `kill -0` 探活，再看 `/proc/<pid>/status` 的 `SigIgn` 位图 | 程序可能明确写了「忽略 TERM」，或者处理器卡住了。这时 `-9` 是剩下唯一能做的 |

第一行的措辞值得较真：`D` 不是「`kill -9` 无效」，而是「现在不处理信号」。它和权限、和 `-9` 都无关，加 `sudo` 只是多发一次注定要排队的信号。

## 杀了手里那个客户端，不等于杀了它派出去的进程

这不是比喻。仓库里判题沙箱的这段代码（`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/DockerCodeRunner.java:245-250`）就踩在这条线上：

```java
if (!finished) {
    // 强杀宿主上的 docker CLI 只是第一步：CLI 被 SIGKILL 后来不及通知 daemon，
    // 容器仍由 daemon 管着（--rm 只在容器自己退出时生效）。不补这一刀，
    // 一个 while(true) 会带着 0.5 CPU / 256MB 一直跑，超时约束形同虚设。
    process.destroyForcibly();
    removeContainer(command);
}
```

`destroyForcibly()` 相当于对那个 `docker` CLI 进程来一刀 `kill -9`。但 CLI 死在半路，它还没来得及把「请停掉这个容器」的请求送到 daemon，于是容器照旧在跑——超时了却什么都没停住。所以第 250 行必须补一次 `removeContainer`。

同样的形状在纯 shell 里天天出现：你 `kill` 掉一个脚本，它起的子进程不一定跟着走。信号发给的是单个进程，不会顺手传给它的子进程。要一次带走整组，用负号指定进程组：

```text
$ ps -o pid,pgid,cmd -p 9812     # 未实跑：先看 PGID
$ kill -- -9812                  # 未实跑：给整个进程组的每个成员发 TERM
```

交给 systemd 管就不用自己写这串：`KillMode=control-group`（手册里的默认值）会把整个 cgroup 里的进程一起清掉，`content/reading/linux-from-zero/12-systemd把程序变成服务.md:40` 那句「`systemctl stop` 能把整棵进程树一次清掉」说的就是它。

## 按名字杀，最容易杀到发命令的那个自己

这条不是推的，仓库 `content/courses/spring/20-打包与部署.md:148` 记了一次真实踩坑：

```text
第一条有个本项目真实踩过的坑：**用 `pkill -f '<模式>'` 杀进程时，模式串会匹配到执行它的那条 shell 自己**，
于是你把会话自己杀了、旧进程还在。正确做法是按 PID 杀。
```

原因不神秘：`pkill -f` 匹配的是每个进程的完整命令行（`/proc/<pid>/cmdline`）。`pkill` 本身不会匹配到自己，但**执行它的那条 shell 会**——如果这条命令是从脚本里、或者经 `bash -c 'pkill -f paideia-app'` 发出的，那个 shell 的 cmdline 里就带着同样的字符串，于是它把下达命令的这一层也算了进去。这是匹配规则的直接后果，不是某个 shell 的怪癖。

先看清楚再动手，用 `pgrep -af` 预演：

```text
$ pgrep -af 'paideia-app'        # 未实跑
9812 java -jar /opt/paideia-app/app.jar --spring.profiles.active=prod
$ pkill -f 'paideia-app'
```

真机上按 PID 杀最稳。PID 从哪来？`ss -lntp` 看监听端口的那一行，或者 `pgrep -f` 先拿到号、确认无误再 `kill` 那个号。

## 动手

第一题把「先礼后兵」写完整。

```code-challenge
{"id":"linux-09-kill-escalate","kind":"fill","title":"写一段有礼貌的停机","language":"bash",
 "template":"# 先请求退出，盯着它最多几秒，还不走才强杀\nkill {{0}} $PID\nfor i in 1 2 3 4 5; do kill -0 $PID 2>/dev/null || break; sleep 1; done\nkill -0 $PID 2>/dev/null && kill {{1}} $PID",
 "options":[["-15","-9","-2"],["-9","-15","-1"]],"answers":[0,0],
 "explain":"先 TERM 给程序收尾机会（关连接、刷盘、回滚事务）；`kill -0` 不发信号、只探活，还在才升级到 SIGKILL。循环上界 5 秒也是为了适应判题容器的墙钟上限。"}
```

第二题在容器里真跑：判定要同时看到它留下的痕迹和它真的不在了。

```code-challenge
{
 "id": "linux-09-shell-graceful-stop",
 "kind": "shell",
 "title": "让它自己退出，而不是强杀",
 "seed": [
  {
   "path": "app/run-worker.sh",
   "content": "#!/bin/bash\ntrap 'echo graceful > /tmp/worker.state; exit 0' TERM\nwhile :; do sleep 0.2; done\n"
  }
 ],
 "starter": "# 1. 后台启动 app/run-worker.sh，记下它的 PID\n# 2. 发一次让它自己收尾的信号（不是 -9），然后等它走完\n# 3. 把 /tmp/worker.state 的内容写进 app/result.txt\n",
 "judge": "set -e\ncase $(cat app/result.txt 2>/dev/null || true) in\n  graceful) ;;\n  *) echo 「app/result.txt 里不是 graceful：用 kill -15，别用 -9，它来不及写状态」; exit 1 ;;\nesac\nif pgrep -f run-worker.sh > /dev/null; then echo 「run-worker.sh 还在跑：发了信号还要等它退出」; exit 1; fi",
 "explain": "seed 里那个脚本装了 TERM 处理器：收到 kill -15 会写下 graceful 再自己退出；用 -9 它来不及写，result.txt 就取不到内容。判定还查了进程真的不在，所以「发了信号不等」也会失败。壳层判题只看 judge 的退出码。"
}
```

这个练习的 cgroup 里还藏着一个硬限制：壳层练习的墙钟上限是 15 秒（`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/SandboxProperties.java:119` 的 `shellTimeoutMs = 15_000`），所以别在里面写 `sleep 10` 再升级——练习题跑不到那一步。

## 检查一下

```quiz
{"id":"linux-09-quiz","title":"信号三问","questions":[
 {"question":"`kill 9812` 返回 0 之后，你能确定什么？","options":["进程已经退出","信号投递成功，进程死没死还得另外查","进程一定忽略了信号"],"answer":1,"explain":"kill 成功只代表信号送出去了，它不等、也不看结果。要确认还在不在，用 kill -0 探活。"},
 {"question":"服务自己捕获了 SIGTERM、收尾后 exit 0，`$?` 看到的是多少？","options":["0","143","137"],"answer":0,"explain":"143 是 128+15，只对「被 TERM 的默认动作终止」的进程成立。自己接管信号再正常退出，看到的是它自己的退出码。"},
 {"question":"`kill -9` 之后 `ps` 里进程还在、STAT 是 `D`，说明什么？","options":["权限不够，要加 sudo","它已经死了，在等父进程回收","它卡在不可中断的内核等待，信号挂起还没轮到它处理"],"answer":2,"explain":"D 状态在内核里等 IO，信号要等这次等待返回才轮得到它处理。要查的是它在等的存储，不是进程本身。"}
]}
```

## 还差的

1. 全文所有 Linux 命令回显都是标准形态，一条都没在本机实跑（这台机器是 Windows，连 `kill -l` 的列宽都会随终端变）。真正要你自己对一遍的是退码那组：我没在 Linux 上看过「trap 了 TERM 再 exit 0」的服务退出后 `$?` 到底是 0 还是 143，正文是按「自己给的退出码说了算」推的。
2. `trap ... EXIT` 在收到 `SIGTERM` 时到底跑不跑，我没验证。仓库里 `scripts/test-db.sh:39` 就是 `trap 'rm -rf "$tmp"' EXIT` 这种写法，清的是它自己派生的临时库——中途被 TERM 会不会清掉，我不确定，所以正文的练习只用显式 `trap ... TERM`。`SIGKILL` 下 EXIT trap 一定不执行，这一条由「不可捕获」保证，不用验。
3. `TimeoutStopSec` 的 90 秒是 systemd 手册的默认值，本机没有 systemd，没核对过。项目自己的 unit 也不在仓库里：`deploy/ops` 下只有监控组件的 unit（`paideia-backup.service`、`loki.service` 这些），应用那个在服务器上——所以「这次停 paideia 有没有超过 90 秒」我拿不出日志。
4. 判题容器 15 秒墙钟上限让「等够 10 秒再补一刀」这种写法没法在练习题里完整演示：练习只验到「发了 TERM、它写下状态、进程没了」，超时后升级 SIGKILL 那半段没验。
5. 怎么确认一个进程是不是明确忽略了 `TERM`，我只给了 `/proc/<pid>/status` 的 `SigIgn` 位图这个方向，没在容器里真读过那种十六进制位图，也没给出「第几位对应哪个信号」的对照表——这一点别照抄我。

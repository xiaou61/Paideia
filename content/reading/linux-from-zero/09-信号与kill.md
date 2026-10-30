---
title: 信号与 kill：-9 的代价
category: linux-from-zero
order: 9
minutes: 20
level: beginner
status: published
---

## 这一关在解决什么

`kill` 不是"杀进程"的命令，是**发信号**的命令，而"杀掉"只是其中一个信号的效果。这一关讲清楚：`-15` 和 `-9` 差在哪、`Ctrl+C` 到底发了什么、为什么有的进程怎么都杀不掉。

```sketch
{
  "title": "kill 是发信号，不是杀",
  "rows": [
    [
      { "id": "term", "title": "-15 请它退", "sub": "能收尾、能关连接", "tone": "green" },
      { "id": "kill9", "title": "-9 直接停", "sub": "来不及清理", "tone": "amber" },
      { "id": "stuck", "title": "怎么都杀不掉", "sub": "多半卡在内核态 IO", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "term", "to": "kill9" },
    { "from": "kill9", "to": "stuck" }
  ]
}
```

学完你会知道：`kill -9` 应该是最后手段，而不是第一反应。

## 为什么用信号而不是"命令进程去退出"

因为进程可能正处在任何状态：半截写文件、拿着锁、连着数据库。**直接抹掉它会留下不一致的状态**（半截文件、没释放的锁、没回滚的事务）。

信号给了进程一个"知道了，我来收尾"的机会：进程可以捕获信号，在处理器里保存数据、关闭连接、删临时文件，然后自己退出。`SIGKILL` 就是"不给你这个机会"的那个信号——它由内核直接执行，进程无法捕获、无法忽略。

## 讲解

### 常用信号速查

```text
$ kill -l
 1) SIGHUP   2) SIGINT   3) SIGQUIT   9) SIGKILL  15) SIGTERM  19) SIGSTOP  20) SIGTSTP
```

日常真正会用到的只有六个：

| 编号 | 名字 | 谁发的 | 含义 |
| --- | --- | --- | --- |
| `1` | SIGHUP | 终端关闭 | 挂断。守护进程常把它解释为"重读配置" |
| `2` | SIGINT | `Ctrl+C` | 中断。绝大多数程序默认退出 |
| `3` | SIGQUIT | `Ctrl+\` | 退出并**生成 core dump**（JVM 收到它会打印线程栈） |
| `9` | SIGKILL | 只能 `kill -9` | **强杀**，不可捕获、不可忽略 |
| `15` | SIGTERM | `kill` 的默认 | **请求退出**，可捕获 |
| `20` | SIGTSTP | `Ctrl+Z` | 暂停（第 08 关） |

`kill -l` 里还有 `SIGUSR1`、`SIGUSR2`：这两个没有固定含义，留给程序自己定义（很多服务用 `SIGUSR1` 触发"重开日志文件"）。

### `kill` 的正确用法

```text
$ kill 9812            # 默认发 SIGTERM，等于 kill -15 9812
$ kill -15 9812
$ kill -9 9812         # 强杀
$ kill -l 15           # 反查：15 是什么信号
```

**默认是 `-15` 不是 `-9`**，这是有意的设计：先请求，再暴力。

一个成熟服务的退出流程应该是这样的（Spring Boot、Nginx、MySQL 都是）：

```text
1. 收到 SIGTERM
2. 停止接收新请求（从负载均衡摘掉 / 关监听端口）
3. 等在处理的请求完成（优雅停机，通常有超时）
4. 关闭连接池、刷盘、删临时文件
5. 自己退出（退出码 0）
```

`kill -9` 跳过了 2–4 步。后果按严重程度排：

- 请求被硬切断，客户端看到连接重置；
- 正在写的文件半截（日志、上传的临时文件）；
- 数据库事务没回滚，锁要等超时才释放；
- 临时文件、pid 文件残留，下次启动报"已在运行"。

### 升级流程：先礼后兵

标准做法是**TERM、等、再 KILL**：

```text
$ kill -15 9812
$ for i in $(seq 1 10); do kill -0 9812 2>/dev/null || break; sleep 1; done
$ kill -0 9812 2>/dev/null && kill -9 9812
```

`kill -0 PID` 不发信号，只检查"这个进程还在不在、我有没有权限"——用来等待很方便。

写成一行（脚本里常用）：

```text
$ kill -15 9812; sleep 5; kill -0 9812 2>/dev/null && kill -9 9812
```

`systemctl stop` 内部就是这个流程：先发 TERM，按 `TimeoutStopSec`（默认 90 秒）等，还不走就发 KILL 并把它标成 `failed`。所以**用 systemd 管服务时不要自己写这串**。

### 杀不掉的情况

有三种"杀不掉"，原因完全不同：

**1. 进程处于 `D` 状态（不可中断睡眠）**

它在等内核的 IO 完成，信号要等系统调用返回才能处理。此时连 `-9` 都只能"挂起待处理"，进程要等 IO 返回或超时才真正退出。根因在磁盘/网络存储，不在进程。

```text
$ ps -eo pid,stat,wchan:20,comm | awk '$2 ~ /^D/'
  412 D    nfs_wait        dd
```

`wchan` 列会告诉你在等什么内核函数。

**2. 僵尸进程（`Z`）**

它其实**已经死了**，只剩一个进程表条目，等着父进程调 `wait()` 回收。`kill` 对已死进程无效——要处理的是**它的父进程**（让父进程去 wait，或者杀掉父进程让 init 收养）。

```text
$ ps -eo pid,ppid,stat,comm | awk '$3 ~ /^Z/'
```

**3. 权限不够**

普通用户只能给自己（同一 uid）的进程发信号。给别人的进程发信号要 `sudo`。

还有一个隐藏情况：**信号被忽略了**。程序可以 `signal(SIGTERM, SIG_IGN)` 明确忽略 TERM（有些老程序为了"不被误杀"这么写）。这种时候先试试 `SIGHUP`、`SIGINT`，都不行才 `-9`。

### 按名字杀：`pkill`、`killall`，以及它们的危险

```text
$ pkill -f "paideia.jar"       # 按完整命令行匹配
$ pkill -9 -f "paideia.jar"
$ killall java                 # 按进程名匹配（所有 java 都杀！）
```

`pkill -f` 的匹配是**子串**。`pkill -f java` 会把"命令行里任何位置含 java 的进程"全杀掉——包括 `vim javadoc.md`。

**先看清楚再杀**，用 `pgrep -af` 预演：

```text
$ pgrep -af "paideia.jar"
9812 java -jar /opt/paideia/paideia.jar --spring.profiles.active=prod
$ pkill -f "paideia.jar"
```

还有一个更隐蔽的自伤：`pkill -f "job.sh"` 在**自己就是 `job.sh` 的子进程**时会把自己也匹配上（命令行里含这个字符串），表现为"脚本杀到自己就停了"。加 `pgrep -af` 确认，或把匹配写得更具体。

### 进程组与"杀一整棵树"

启动一个脚本，它又起了几个子进程，`kill` 父进程时子进程不一定跟着走。信号发给**单个进程**，不会自动传给子进程。

要一次干掉整组，用负号表示进程组：

```text
$ ps -o pid,pgid,cmd -p 9812       # 看 PGID
$ kill -- -9812                    # 给整个进程组发 TERM（注意 --）
```

`--` 是必要的：否则 `-9812` 会被当成信号编号。这是 `kill` 里最容易写错的一处。

多数情况不用这么麻烦——`systemd` 服务的 `KillMode=control-group`（默认）会把整个 cgroup 里的进程一起清掉。

## 动手

```code-challenge
{"id":"linux-09-kill-escalate","kind":"fill","title":"写一段有礼貌的停机","language":"bash",
 "template":"# 先请求退出，等 10 秒，还不走才强杀\nkill {{0}} $PID\nsleep 10\nkill -0 $PID 2>/dev/null && kill {{1}} $PID",
 "options":[["-15","-9","-2"],["-9","-15","-1"]],"answers":[0,0],
 "explain":"先 SIGTERM 给程序收尾机会（关连接、刷盘、回滚事务），等它自己走；`kill -0` 只探测不发送，还在才升级到 SIGKILL。"}
```

这一道让一个真进程优雅退出——判定要同时看到它留下的痕迹和它真的不在了。

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
 "starter": "# 1. 后台启动 app/run-worker.sh\n# 2. 先请求它退出（不要 -9），等它自己走完\n# 3. 把 /tmp/worker.state 的内容写进 app/result.txt\n",
 "judge": "set -e\nstate=$(cat app/result.txt 2>/dev/null || true)\n[ \"$state\" = \"graceful\" ] || { echo \"app/result.txt 是 '$state'，期望 graceful：要用 kill -15 而不是 -9\"; exit 1; }\nif pgrep -f run-worker.sh > /dev/null; then echo \"run-worker.sh 还在跑：发了信号还要等它退出\"; exit 1; fi",
 "explain": "那个脚本装了 TERM 处理器：收到 `kill -15` 会写下 graceful 再退出。用 `kill -9` 它来不及写，result.txt 就取不到内容；判定还检查进程真的没了，所以「发了信号不等」也会失败。"
}
```

## 检查一下

```quiz
{"id":"linux-09-quiz","title":"信号三问","questions":[
 {"question":"`kill 9812` 不带参数，发的是哪个信号？","options":["SIGTERM（15）","SIGKILL（9）","SIGINT（2）"],"answer":0,"explain":"kill 的默认信号是 15，允许进程捕获并优雅退出。要用强杀必须显式写 -9。"},
 {"question":"为什么 `-9` 可能造成数据不一致？","options":["进程无法捕获，收尾逻辑不执行","内核会立即回收内存","它会删除文件"],"answer":0,"explain":"SIGKILL 由内核直接终止进程，进程没有机会刷盘、回滚事务、删临时文件或释放锁。"},
 {"question":"进程状态是 `Z`，为什么 kill 无效？","options":["它已经退出，缺的是父进程回收","它被内核保护","它没有 PID"],"answer":0,"explain":"僵尸只剩进程表条目，等父进程 wait 回收。要处理的是父进程，不是僵尸本身。"}
]}
```

## 常见误区

### 误区：杀不掉就是权限问题，加 `sudo kill -9`

**你以为**：`kill -9` 加上 sudo 是终极手段。
**其实**：`D` 状态的进程连 root 的 `-9` 都要等 IO 返回；`Z` 状态杀了也没用。先看 `ps` 的 STAT 列，`D` 就查存储，`Z` 就找父进程，都是权限之外的事。
**怎么验证**：`ps -eo pid,stat,wchan:20,comm` 找 D 状态；`ps -eo pid,ppid,stat,comm | awk '$3~/Z/'` 找僵尸和它们的父进程。

### 误区：`kill -9` 比 `kill -15` "更干净"，因为它一定生效

**你以为**：强杀最干脆。
**其实**：`-9` 是**跳过所有清理**的强制终止。对数据库、消息队列、正在写文件的任务，它留下的是半截状态；对 systemd 服务，它会让单元被标记为 `failed`，即使本次是你手动操作的。
**怎么验证**：拿一个写文件的脚本，`kill -15` 与 `kill -9` 各试一次，对比退出时的日志有没有"收尾完成"那一行。

### 误区：`Ctrl+C` 和 `kill -9` 差不多

**你以为**：都是"停下来"。
**其实**：`Ctrl+C` 发的是 `SIGINT`，程序可以捕获并优雅退出（很多程序会在这里刷盘、关连接）；而且它只发给**前台进程组**，后台任务不受影响。区别很大：`Ctrl+C` 停下 MySQL 客户端是安全的，`kill -9` 停 MySQL 服务端可能要做崩溃恢复。
**怎么验证**：跑 `tail -f app.log`，`Ctrl+C` 立刻回到提示符——`tail` 对 SIGINT 的处理就是干净退出。

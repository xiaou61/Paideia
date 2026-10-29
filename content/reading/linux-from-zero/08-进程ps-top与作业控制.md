---
title: 进程：ps、top 与作业控制
category: linux-from-zero
order: 8
minutes: 24
level: beginner
status: published
---

## 这一关在解决什么

程序"起了但好像没起"、任务"跑一半 ssh 断了就没了"、机器"负载 8 但 CPU 只用了 5%"——这些都要靠"看懂进程"来解决。

这一关给你三件事：**`ps` 的 STAT 列怎么读**、**前台/后台怎么切**、**为什么关掉终端程序就死**。

## 为什么进程是主角

因为 Linux 里**没有"软件"这个实体，只有进程**。你装的是文件，跑起来才是一个 PID。服务停止、端口被占、内存不足，全是进程层面的事。

理解进程还有一层用处：进程是**任务的容器**。一个进程占着内存、拿着文件句柄、听着端口、带着环境变量和当前目录——所以"换个目录起服务就找不到配置"这类问题，本质是"进程的工作目录不是你以为了那个"。

## 讲解

### `ps`：拍一张快照

```text
$ ps aux | head -4
USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
root         1  0.0  0.1 168392 11876 ?        Ss   Oct01   3:12 /sbin/init
root       412  0.0  0.0  22444  6012 ?        Ss   Oct01   0:00 /usr/sbin/cron -f
lzf       9812  2.1  4.3 4210580 703112 pts/0  Sl   14:02   0:47 java -jar paideia.jar
```

各列：

| 列 | 含义 | 该看什么 |
| --- | --- | --- |
| `PID` | 进程号 | 后面 kill、查 `/proc` 都用它 |
| `%CPU` / `%MEM` | 占 CPU / 内存百分比 | `%CPU` 可以超过 100（多核，一核 100） |
| `VSZ` | 虚拟内存大小 | 逻辑上申请了多少，**通常很大且不说明问题** |
| `RSS` | 常驻内存 | **真实占用物理内存的部分**，看这个 |
| `TTY` | 关联的终端 | `?` 表示没有终端（服务、后台任务） |
| `STAT` | 状态 | 下面单独讲 |
| `TIME` | 累计 CPU 时间 | 不是运行时长 |

`ps aux` 和 `ps -ef` 的区别只是风格：前者 BSD 风格（`aux`），后者 System V 风格（`-ef`，会多显示 `PPID`）。查父子关系用 `ps -ef`。

**最常用的其实是找自己关心的那个**：

```text
$ ps -ef | grep -v grep | grep java
$ pgrep -a java            # 更干净：直接列 PID + 命令
$ pgrep -af "paideia.jar"  # -f 匹配完整命令行
```

`ps | grep xxx` 会连 grep 自己那一行也列出来，所以老手都写 `grep -v grep`，或者干脆用 `pgrep`。

### STAT 列：七个字母定生死

`Ss`、`Sl`、`R+`、`D`、`Z` 这些组合，第一位是**状态**，后面的字母是附加信息：

| 状态 | 含义 | 说明 |
| --- | --- | --- |
| `R` | Running / Runnable | 正在跑或在排队等 CPU |
| `S` | Sleeping（可中断） | 在等事件（网络、输入、定时器）。**绝大多数进程长这样，正常** |
| `D` | Uninterruptible sleep | 在等**磁盘 IO**，连信号都不能打断。**大量 `D` 说明存储卡住了** |
| `Z` | Zombie | 已经退出但父进程还没回收。占一个 PID，不占资源 |
| `T` | Stopped | 被 `Ctrl+Z` 或信号暂停 |
| `I` | Idle（内核线程） | 内核线程没事干 |

附加字母：

| 字母 | 含义 |
| --- | --- |
| `s` | 会话首进程（session leader） |
| `l` | 多线程 |
| `+` | 在前台进程组里 |
| `<` / `N` | 高优先级 / 低优先级 |

一个实用判断：**`Z` 多说明父进程有 bug**（它 fork 了子进程却不 `wait`），杀掉父进程僵尸会被 init 收养并清理。**`D` 多说明磁盘或网络存储有问题**，这时候 `kill -9` 也没用（第 09 关）。

### `top`：持续的视图

`ps` 是拍一张照片，`top` 是录像。进去以后常用按键：

| 按键 | 作用 |
| --- | --- |
| `1` | 展开每个 CPU 核 |
| `P` | 按 CPU 排序（默认） |
| `M` | 按内存排序 |
| `c` | 显示完整命令行 |
| `k` | 输入 PID 直接杀掉（慎用） |
| `q` | 退出 |

顶部的 `load average: 0.08, 0.12, 0.09` 是 1、5、15 分钟的**平均可运行任务数**。它不是 CPU 使用率，含义是"平均有多少个任务在等 CPU 或在跑"：

- 4 核机器上 `load` 长期在 4 左右是**满载但可接受**；到 8 就是排队了。
- 判断标准要除以核数：`nproc` 拿到核数，`load/nproc > 1` 就开始排队。

`top` 还有个坑：**第一屏的 CPU 百分比是从开机累计的**，要等一两秒刷新一次才是当前值。按 `Shift+P` 排序后多等两屏再下结论。

### 前后台：`&`、`jobs`、`fg`、`bg`

```text
$ ./long-job.sh &        # 放后台，立刻拿回提示符
[1] 9812
$ jobs
[1]+  Running                 ./long-job.sh &
$ fg %1                 # 调回前台
^Z                      # Ctrl+Z 暂停（注意是暂停，不是结束）
[1]+  Stopped                 ./long-job.sh
$ bg %1                 # 让它在后台继续跑
```

四个概念别混：

- `&`：**一开始就**在后台跑。
- `Ctrl+Z`：把前台进程**暂停**（状态变 `T`）。很多人以为这是"结束"，于是进程挂在那儿不动。
- `bg`：把暂停的进程转到后台**继续**跑。
- `fg`：调回前台。

### 关掉终端就死：SIGHUP 与 `nohup`

ssh 断开时，bash 会给它启动的所有进程发 **SIGHUP**（hang up，挂断）。默认处理就是终止。所以"跑了半小时的导入，拔了网线就没了"。

三种解法，按可靠性排：

```text
$ nohup ./job.sh > job.log 2>&1 &      # 忽略 SIGHUP，输出进文件
$ ./job.sh & disown                    # 起完再从作业表里摘掉
$ tmux new -s work                     # 最稳：会话在服务端，断线不影响
```

`nohup` 的问题是**它只挡 SIGHUP**：进程还绑在那个终端上，输出还是要靠重定向，而且它没有"重新回到现场"的能力。真要长时间跑、还要中途回来看看，用 `tmux`（或 `screen`）：断线后 `tmux attach -t work` 能接回原来的画面。

### 优先级：`nice` 与 `renice`

`nice` 值范围 `-20`（最高优先级）到 `19`（最低），默认 `0`。**只有 root 能调低 nice 值（提高优先级）**，普通用户只能调高（让出 CPU）：

```text
$ nice -n 10 ./backup.sh          # 启动时就低优先级
$ renice 10 -p 9812               # 改已在跑的
$ top -o NI                       # 按 nice 排序看
```

压缩、备份、日志归档这类"不急但要跑很久"的任务，`nice -n 19` 是标准做法，能让它在你干活时不抢 CPU。

### `/proc/<pid>`：进程的档案柜

每个进程在 `/proc` 下有一个以 PID 命名的目录，里面全是它的实时信息：

```text
$ cat /proc/9812/cmdline | tr '\0' ' '; echo
java -jar /opt/paideia/paideia.jar --spring.profiles.active=prod
$ ls -l /proc/9812/cwd
lrwxrwxrwx ... /proc/9812/cwd -> /opt/paideia
$ tr '\0' '\n' < /proc/9812/environ | grep SPRING
$ ls /proc/9812/fd | wc -l        # 它打开了多少个文件句柄
```

四个最有用的：

| 路径 | 内容 |
| --- | --- |
| `cmdline` | 完整命令行（用 NUL 分隔，所以要 `tr`） |
| `cwd` | **工作目录**（软链接）——"它到底在哪个目录跑" |
| `environ` | 环境变量（同样 NUL 分隔） |
| `fd/` | 打开的所有文件描述符——查"哪个文件没关"用 |

`cwd` 这一条能直接解决一类问题：`ps` 显示 `java -jar app.jar`，但配置没读到——`ls -l /proc/<pid>/cwd` 一看，工作目录是 `/`，相对路径当然对不上（第 02 关说过脚本要用绝对路径，原因就在这里）。

## 动手

```code-challenge
{"id":"linux-08-nohup","kind":"fill","title":"让任务在断线后继续跑","language":"bash",
 "template":"# 输出与错误都进 job.log，且忽略挂断信号\nnohup ./import.sh {{0}} {{1}}",
 "options":[["2>&1","2>1","1>&2"],["&","disown","fg"]],"answers":[0,0],
 "explain":"`2>&1` 让错误和正常输出都进 job.log，结尾的 `&` 把它放到后台。只想最稳的话用 tmux，nohup 只挡 SIGHUP 且拿不回现场。"}
```

## 检查一下

```quiz
{"id":"linux-08-quiz","title":"进程三问","questions":[
 {"question":"看一个 Java 进程真实占了多少物理内存，该看哪一列？","options":["RSS","VSZ","TIME"],"answer":0,"explain":"VSZ 是虚拟地址空间（含未真正分配的），RSS 才是当前驻留物理内存的部分。TIME 是累计 CPU 时间。"},
 {"question":"进程状态是 `D`，`kill -9` 也没反应，说明什么？","options":["它在等不可中断的磁盘 IO","它已经死了","它被暂停了"],"answer":0,"explain":"D 状态是内核态等待 IO，信号要等系统调用返回才处理。SSD/磁盘或网络存储卡住时会出现。"},
 {"question":"`Ctrl+Z` 做了什么？","options":["暂停进程并放回作业表","结束进程","把进程转到后台继续跑"],"answer":0,"explain":"Ctrl+Z 发 SIGTSTP，进程状态变 T（Stopped）；要用 bg 才会在后台继续跑，fg 调回前台。"}
]}
```

## 常见误区

### 误区：`load average` 高就是 CPU 不够

**你以为**：负载 8 说明 CPU 被打满了。
**其实**：`load` 统计的是**处于 R 和 D 状态的任务数**。大量 `D` 状态（等磁盘）也会把 load 顶上去，而 CPU 可能只用了一半。判断要先看 `top` 里 `%Cpu(s)` 的 `us`/`sy`/`wa`：`wa` 高说明瓶颈在 IO，加 CPU 没用。
**怎么验证**：`uptime` 看 load，`top` 按 `1` 看每核使用率与 `wa`；`ps -eo stat,pid,comm | grep '^D'` 列出所有 D 状态进程。

### 误区：`ps aux` 里 `%MEM` 加起来就是内存占用

**你以为**：把每个进程的 `%MEM` 加总就是系统内存使用。
**其实**：多个进程会**共享内存**（共享库、`fork` 出来的页、tmpfs），每个进程的 RSS 里都算了一份，加总会重复计算，结果可能远超物理内存。准确的口径是 `free -m` 的 `used`，或者看 `smem` 这类按 PSS 统计的工具。
**怎么验证**：`ps aux | awk '{s+=$6} END {print s/1024" MB"}'` 得到所有 RSS 之和，和 `free -m` 的 used 对比，通常会偏大。

### 误区：`nohup` 能保证任务跑完

**你以为**：加了 `nohup` 就万无一失。
**其实**：`nohup` 只处理"终端挂断"。机器重启、OOM Killer 杀掉它、脚本自己异常退出，`nohup` 都管不了。要真正"跑完"，得是 systemd 服务（第 12 关，带 `Restart=on-failure`），或者至少写个带日志与退出码检查的外壳脚本。
**怎么验证**：`ps -eo pid,nice,stat,comm` 看它还在不在；`journalctl -k | grep -i "killed process"` 能看到 OOM Killer 的记录。

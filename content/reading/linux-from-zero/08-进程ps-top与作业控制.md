---
title: 进程：ps、top 与作业控制
category: linux-from-zero
order: 8
minutes: 18
level: beginner
status: published
---

## 单元是 active，3000 端口上却不是它

```text
# deploy/ops/install-ops.sh:214-221 的原话
#  包自带的 grafana-server.service 必须停用（2026-10-27 实测踩过）：apt 装的 grafana 会
#  enable 它自己的单元，而本脚本用的是自写的 grafana.service。两个单元抢 127.0.0.1:3000，
#  后启动的那个 bind 失败并进入 Restart=on-failure 重启循环（半小时内 66 次），
#  而先启动的那个只是「看着正常」——systemctl restart grafana-server 更是完全改不了真正在跑的那个。
```

这段注释是当初写监控安装脚本的人记下的，不是我编的事故：66 次、3000、2026-10-27 都在那句注释里。我在本机复现不了它（下一节说明为什么），但它把我原来混成一个的判断拆成了两个——`systemctl is-active` 答的是「这个单元的主进程还在不在」，不是「这个端口归谁」。

两个单元抢同一个端口时，后启动的那个 bind 失败，`Restart=on-failure` 加 `RestartSec=5`（`deploy/ops/grafana.service:14-15`）就每 5 秒再试一次，那 66 次是这么攒出来的。而 `systemctl restart grafana-server` 只动它自己那个单元，真正占着端口的进程一点没变：它会一直「看着正常」，直到你去 `ss` 看那行属主。

## 先在本机把「活着」和「在听」拆开

本机是 Windows，没有 Linux。能真跑的就是这一半：进程号先有、监听后有，中间那段时间「进程在」为真、「端口在听」为假。脚本放在 `$env:TEMP\paideia-alive-vs-listen.js`，先 `setInterval` 撑着活着，4 秒后才 `listen`。Node v24.14.1 上的原样输出：

```text
started pid = 83400
  0.1s  alive=True   listening=False
  0.4s  alive=True   listening=False
  ...
  4.1s  alive=True   listening=False
  4.5s  alive=True   listening=True
owner pid = 83400 / started pid = 83400
after kill: listening = False
```

那 4.4 秒里 `Get-Process` 一直查得到它，`netstat -ano` 一直查不到 `:18732`。再起一个进程抢同一个端口，Node 直接抛错：

```text
Error: listen EADDRINUSE: address already in use 127.0.0.1:18732 (code: 'EADDRINUSE', errno: -4091, syscall: 'listen')
```

（这台 PowerShell 7.6.3 里没有 `Get-NetTCPConnection`，报「不是可识别的 cmdlet 名称」，所以用 `netstat -ano`。那个 `-4091` 是 Windows 的编号，Linux 上同一条错误不带它，别拿数字对版本。）

**下面所有 Linux 回显都是标准形态、未实跑。** 本机的 `bash.exe` 只是 WSL 的入口，跑起来回一句「没有已安装的分发版，需要 `wsl --update`」，我不拿它冒充 Linux。进程和信号的基础在 `linux/05`、`linux/07` 里已经铺过，`linux-from-zero/09` 讲信号本身；这一篇只走一条线：**它在不在 → 端口在不在 → 能不能干活**。

## 第一层：进程在不在

```text
$ ps aux | head -4        # 标准形态，未实跑
USER       PID %CPU %MEM    VSZ    RSS TTY STAT START   TIME COMMAND
root         1  0.0  0.1 168392  11876 ?   Ss   Oct01   3:12 /sbin/init
root       412  0.0  0.0  22444   6012 ?   Ss   Oct01   0:00 /usr/sbin/cron -f
paideia   9812  2.1  9.3 4210580 703112 ?  Ssl  14:02   0:47 /usr/bin/java -Xms256m -Xmx768m -jar /opt/paideia-app/app.jar
```

一行一个进程，`PID` 是它此刻的编号。几列的意思：`STAT` 是此刻的状态字母，`TIME` 是累计吃掉的 CPU 时间，`%CPU` 就是 `TIME` 除以它活了多久——所以 `%CPU 2.1` 是**出生以来的平均**，不是此刻。服务刚被压上去的那几秒，这里不会有任何变化。`RSS` 是当前驻留的物理内存，`VSZ` 是虚拟地址空间（含还没真正分配的），两者差得远是常态。

`ps` 答不了的问题，正是后面要分层查的：

| 症状 | 先看哪 |
| --- | --- |
| 「8081 到底谁占着」 | `ps` 没有端口列，去 `ss -ltnp` |
| 「`%CPU` 才 2%，可机器很烫」 | 那是累计平均；`top` 按 `P` 取第二帧 |
| 「它读的是哪个配置」 | `ls -l /proc/<pid>/cwd` 看工作目录 |
| 「它是不是被冻住了」 | `STAT` 里的 `T`，再回 `jobs` 看是哪个作业 |

`ps aux` 和 `ps -ef` 是同一张表的两套列（BSD 风格 / System V 风格），`-ef` 多出 `PPID`——僵尸、以及「谁把谁又拉起来了」都得看 `PPID`。查单个：

```text
$ ps -p 9812 -o pid,ppid,stat,etime,cmd     # 标准形态，未实跑
  PID   PPID STAT     ELAPSED CMD
 9812   1234 Ssl     01:12:31 /usr/bin/java -Xms256m -Xmx768m -jar /opt/paideia-app/app.jar
$ pgrep -af 'app\.jar'                      # 标准形态，未实跑
9812 /usr/bin/java -Xms256m -Xmx768m -jar /opt/paideia-app/app.jar
```

`pgrep -f` 匹配整条命令行，所以它也会匹配到你自己这条 `pgrep -af 'app\.jar'`。问「有没有在跑」用 `pgrep -c`，看数字，别数行。

发行版差异在这里最容易咬人：上面是 procps-ng 的写法。Alpine 里是 BusyBox 的 `ps`，`aux`、`-eo`、`--sort` 不一定在，`STAT` 的字母集也不同。`ps aux` 报错时先 `ps --help` 看是谁的实现，别先怀疑进程。

## STAT 第一位：它就是你该看的那格

`Ssl` 拆开是 `S`（可中断睡眠）+ `s`（会话首进程）+ `l`（多线程）。第一位只取一个字母。

| 你看到 | 意思 | 先看哪 |
| --- | --- | --- |
| `R` | 在 CPU 上，或排队等 CPU | `ps aux --sort=-%cpu` 的前几名 |
| `S` | 在等事件（网络、定时、锁） | 正常态；一片 `S` 而 load 高，接着看 `D` |
| `D` | 卡在内核里不可中断 | `vmstat 1 5` 的 `b` 列，`iostat -x -y 1 5` |
| `Z` | 已退出、父进程没 `wait` | `ps -o ppid= -p <pid>` 找父进程 |
| `T` | 被停住（`Ctrl+Z`、`SIGSTOP`） | `jobs`，见后面作业控制 |
| `I` | 内核线程空闲 | 不用管 |

两件容易讲反的：

- 僵尸（`Z`）**不占 CPU、不占内存，只占一个 PID**。它已经死了，没有东西可杀；要动的是父进程，让它去 `wait`。
- `D` 不是「磁盘忙」的同义词，是**等待内核里某个操作完成、连信号都不响应**。`kill -9` 对 `D` 无效不是权限问题。所以「杀不掉」先看 `STAT`，别先怀疑 `sudo`。

## 第二层：端口在听吗 —— 这不是 ps 能答的

`ps` 证明了 java 在跑。`8081` 归不归它，要去问 socket：

```text
$ ss -ltnp 'sport = :8081'        # 标准形态，未实跑
State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process
LISTEN 0      511          127.0.0.1:8081       0.0.0.0:*    users:(("java",pid=9812,fd=87))
```

| 命令 | 结果 |
| --- | --- |
| `ss -ltnp` | 所有 TCP 监听，加属主进程（要权限才看得到 pid） |
| `ss -ltnp 'sport = :8081'` | 只问 8081；输出为空就是没人听 |
| `ss -ltn` | 同上但不管进程，任何用户都能跑 |
| `ss -tan state established` | 谁连着它、连了多少条 |

两个坑：

- 不带 `sudo` 时 `Process` 那列是空的。空不等于没主，是你没权限看——先提权，别据此下结论。
- `ss` 属于 iproute2。老机器上是 net-tools 的 `netstat -ltnp`；新发行版里 `netstat` **可能根本没装**，报「command not found」不代表网络坏了。Debian 系装它是 `apt install net-tools`，RHEL 系是 `dnf install net-tools`，装包命令别互相抄。

还有一种「看着对」：端口在听，但地址是 `0.0.0.0:8081`（全网可达）而你以为只有本机能连；或者反过来绑在 `127.0.0.1`，同机的 nginx 没事，从外面永远连不上。`Local Address` 那列要看清，不是看有没有 `LISTEN`。

## 第三层：能不能干活 —— 只有应用自己出口

端口在听也只证明 socket 建好了，不证明 Spring 上下文起来了。仓库里的发版脚本就是按应用自己的话判的（`scripts/release.ps1:488-499`，端口默认 8081 见 `:32`，目录 `/opt/paideia-app` 见 `:33`）：

```text
$ curl -fsS -m 2 http://127.0.0.1:8081/actuator/health     # 标准形态，未实跑
{"status":"UP"}
```

脚本的逻辑是 `while [ $i -lt 90 ]` 里每轮 curl，`grep -q '"status":"UP"'` 命中才 `ok=yes`；90 秒还没 UP 就打印 `DEPLOY_FAILED 健康检查 90 秒未就绪` 并给出回滚命令。它查的不是端口，是应用自己说的「我好了」——慢、但比 `ss` 多看了一层。

`systemctl status paideia` 里的 `active (running)` 在这一层上更弱。`content/reading/linux-from-zero/19-上机实战从空机器到无人值守.md:162` 给出的那份 unit 是 `Type=simple`；`Type=simple` 下 systemd 把 `ExecStart` 拉起来就认为服务已启动、不等它就绪，jar 参数错、进程立刻退出也一样先算启动成功（`content/reading/ops/03-systemd把jar变成服务.md:81` 拿 `Type=exec` 对比的就是这件事）。所以 `active` 只回答「主进程存在」。

| 你看到的 | 它能证明 | 还得查什么 |
| --- | --- | --- |
| `ps` 里有 PID | 进程存在 | `ss -ltnp` 看端口属主 |
| `ss` 有 `LISTEN` | socket 建好、属主是谁 | `curl /actuator/health` |
| `curl` 回 UP | 应用自己认为可用 | 它的 `HealthIndicator` 到底检查了什么 |
| `is-active` 是 active | 主进程在（`Type=simple` 下仅此） | 上面三层从头走 |

## load 高、CPU 却不忙

`load average` 是 1/5/15 分钟的指数衰减平均，数的是**可运行任务加上处在 `D` 的任务**——所以它高不等于在算，也可能在等（`linux/07-机器变慢的排查顺序.md:107` 把这层拆开讲过，这里只补它和进程的关系）：

```text
$ cat /proc/loadavg        # 标准形态，未实跑
8.42 6.11 3.20 3/512 9812
```

前三格是 1/5/15 分钟；第四格 `3/512` 是「此刻可运行 / 总任务数」，最后一格是最近分配的 PID。第四格的分子**只数 `R`，不数 `D`**——所以「load 8，这里却只有 3」正是有人在等 IO 的形状。

| 症状 | 先看哪 | 判读 |
| --- | --- | --- |
| load 高、`wa` 也高 | `vmstat 1 5` 的 `b` 列，`iostat -x -y 1 5` | 卡在 IO，加 CPU 没用 |
| load 高、`us`+`sy` 高 | `top` 按 `P`，`ps aux --sort=-%cpu` | 真在算，找具体进程 |
| load 高、`R` 却没有 | `cat /proc/loadavg` 比 1 分钟与 15 分钟 | 早先堆的活正在衰减，过两分钟再看 |
| `D` 一片 | `ps -eo stat,pid,comm` 取 `^D` | 等的是存储、驱动或 NFS |

`top` 两处要按规矩读：顶上那行 `%Cpu(s)` 在 `top -b -n1` 的第一帧是**开机以来的平均**（`linux/07-机器变慢的排查顺序.md:123`），看当前要 `top -b -n2 -d1` 取第二帧或直接 `vmstat 1 5`；单个进程的 `%CPU` 同理，第一帧不可信。

优先级调的是 CPU 份额，不是 IO。仓库里的备份单元写了 `Nice=10`（`deploy/ops/paideia-backup.service:12`），意思是让着在线服务；但备份慢在磁盘上时这个值救不了前台。

```text
$ renice -n 10 -p 9812        # 标准形态，未实跑
9812 (process ID) old priority 0, new priority 10
$ ionice -c3 -p 9812          # 标准形态，未实跑；IO 优先级是另一套
```

`nice` 从 `-20`（最不客气）到 `19`（最客气），只有 root 能把值调低。普通用户 `renice -n -5` 被拒不是 bug。

## Ctrl+Z 之后 jobs 里写的是 Stopped

```text
$ ./import.sh &                 # 标准形态，未实跑
[1] 11020
$ jobs
[1]+  Running                 ./import.sh &
$ fg %1
./import.sh
^Z
[1]+  Stopped                 ./import.sh
$ bg %1
[1]+ ./import.sh &
```

`Stopped` 不是「在后台跑」，是**被 SIGTSTP 冻住了**：不占 CPU，但内存、文件句柄、数据库连接一样没放开。我自己就把它当过「切后台」用，等了一阵没动静才想起那个进程是冻着的。

- `&`：命令直接进后台
- `Ctrl+Z`：冻住（SIGTSTP），占着资源不干活
- `fg %1` / `bg %1`：接回前台 / 让冻住的继续在后台跑
- `jobs` 只列**当前 shell** 的作业；换终端、或者用 `nohup` 起的，这里看不到

关终端会怎样：shell 退出时给它的作业发 `SIGHUP`。`nohup` 做的事就是让程序忽略 `SIGHUP`——**加了 `nohup` 关终端它才在，不加才没**。这个方向很容易写反（`content/reading/ops/03-systemd把jar变成服务.md:34-48` 专门纠过一次）。`disown` 是把作业从 shell 的作业表里摘掉，退出时就不去发那个信号；`setsid` 是另开一个会话、脱离控制终端。要中途回去看画面用 `tmux`，断线后 `tmux attach -t work` 接回来。

这几条都不算「把服务跑起来」：进程崩了没人拉、开机不自启、日志落在 `nohup.out` 里。`deploy/ops/` 下的 `.service` 文件干的就是这件事，见 `content/reading/linux-from-zero/12-systemd把程序变成服务.md`。

## /proc/<pid>：进程自己记着的比 ps 多

`ps` 是把 `/proc` 渲染成表，直接读文件能拿到表里没有的：

| 看什么 | 怎么读 | 注意 |
| --- | --- | --- |
| 启动命令（含参数） | `tr '\0' ' ' < /proc/9812/cmdline` | 以 NUL 分隔，直接 `cat` 是粘在一起的 |
| 工作目录 | `ls -l /proc/9812/cwd` | 相对路径配置读错，十有八九在这里 |
| 打开的文件与端口 | `ls -l /proc/9812/fd` | 一条 `socket:[...]` 就是一条连接 |
| 资源上限 | `grep 'Max open files' /proc/9812/limits` | 对应 unit 里的 `LimitNOFILE=`（`19-上机实战从空机器到无人值守.md:171` 写的是 65535） |
| 启动时的环境 | `tr '\0' '\n' < /proc/9812/environ` | **出生那一刻的快照**，之后改的不在这里 |

最后一行是坑：改完 `/etc/paideia/paideia.env` 不重启，`/proc/<pid>/environ` 和 `ps e` 都还是老值——它们记的是进程出生时的环境，不是文件此刻的样子。

cwd 值得单说：第 19 关那份 unit 写的是 `WorkingDirectory=/opt/paideia`（`19-上机实战从空机器到无人值守.md:165`），而发版脚本把文件放到 `/opt/paideia-app`（`scripts/release.ps1:33`）。这两个不是同一个目录，照抄 unit 就会得到「服务起来了、但读不到同目录的配置」。这种症状别猜，`ls -l /proc/<pid>/cwd` 一行答完。

## 跑一遍

本机只能跑一半（进程在 ≠ 端口在）。三层都要在 Linux 上跑：

```text
$ ps -p <pid> -o pid,stat,etime,cmd                       # 在不在
$ ss -ltnp 'sport = :8081'                                # 听没听、谁在听
$ curl -fsS -m 2 http://127.0.0.1:8081/actuator/health    # 能不能干活
```

## 动手

```code-challenge
{"id":"linux-08-port-owner","kind":"fill","title":"三层各一条命令","language":"bash",
 "template":"# 1) 8081 上是谁在听\n{{0}} -ltnp 'sport = :8081'\n# 2) 按 pid 看状态与命令行\nps -p 9812 -o pid,{{1}},args\n# 3) 它到底在哪个目录跑\nls -l /proc/9812/{{2}}",
 "options":[["ss","ps","top"],["rss","stat","ni"],["cwd","fd","environ"]],"answers":[0,1,0],
 "explain":"ss 才答端口属主，ps 只答进程在不在；STAT 第一位是状态字母，rss 是内存列、ni 是 nice 值；cwd 是工作目录的软链接，fd 是句柄、environ 是启动时的环境快照。"}
```

## 检查一下

```quiz
{"id":"linux-08-quiz","title":"进程三问","questions":[
 {"question":"服务报「8081 已被占用」，`systemctl status` 显示 active，先看哪一条？","options":["`ss -ltnp 'sport = :8081'` 看属主 pid","`systemctl restart` 再来一次","`ps aux` 里 java 那一行"],"answer":0,"explain":"status 只说主进程在。端口归谁只有 socket 那一层答得了；重启改不了真正在听的那个进程（install-ops.sh:214-221 记的就是这回事）。"},
 {"question":"`ps aux` 里某进程 `%CPU` 是 2.1，能推出此刻它只用了 2.1% CPU 吗？","options":["能，这就是当前占用","不能，那是虚拟内存比值","不能，这一列是出生以来的平均值"],"answer":2,"explain":"`%CPU` 与 `TIME` 对应，是生命周期累计平均；此刻的占用要看 top 按 P，且要取第二帧。"},
 {"question":"进程 `STAT` 第一位是 `D`，`kill -9` 没反应，说明什么？","options":["它已经是僵尸了","它在等不可中断的内核操作","它被 SIGTSTP 停住了"],"answer":1,"explain":"D 要等系统调用返回才处理信号，存储、驱动、NFS 卡住时都会这样；僵尸是 Z，被停住是 T。"}
]}
```

## 还差的

- **三层没在同一台 Linux 上连着跑过。** 本机只有 Windows，`ps`/`ss`/`curl` 那些回显是标准形态、未实跑；我只能证明「进程在 ≠ 端口在」这半截（Node v24.14.1 + PowerShell 7.6.3 实测），三层之间的时序没有真实时间线。
- **`/actuator/health` 返回 UP 到底代表什么，我没核。** 它可能只查了数据库连接池；下游某个 HTTP 依赖挂了，它照样 UP。这取决于应用自己的 `HealthIndicator` 配置，仓库里我看的那份是 shell 侧的判断，不是应用侧的实现。
- **不同发行版的 `ps`/`ss` 只写了方向，没逐台验。** procps-ng 与 BusyBox 的列与 `STAT` 字母集差异、Alpine 里 iproute2 是否预装、RHEL 8 以上 `netstat` 的去留，我都只是按文档写的，没有真机对照。
- **`Type=simple` 与 `Type=exec` 的差别我只引了别人的一行。** 服务器上 `paideia` 实际用的 `Type=` 我在仓库里看不到（`19` 那份是教学样例），所以「status 说 active 就万事大吉」这类结论我没法替真实 unit 下。
- **load 和 CPU 的对应关系没有数据。** `/proc/loadavg` 第四格只数 `R`，但内核版本之间在 cgroup 下怎么算，近几年的改法我没有比对过（`linux/07` 那篇的自述也停在「不告诉你在算还是在等」）。

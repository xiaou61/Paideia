---
title: 定时任务：cron 与 systemd timer
category: linux-from-zero
order: 18
minutes: 20
level: beginner
status: published
---

## 一年跑了 63 次，我以为 1 次

```text
$ python3 cron_check.py
base = 2026-11-19T20:00:00 (Thu)
0 0 1 * 1  -> Mon 11-23 00:00 | Mon 11-30 00:00 | Tue 12-01 00:00 | Mon 12-07 00:00 | Mon 12-14 00:00 | Mon 12-21 00:00
  2026 按 cron 语义（日或周）: 63 次
  2026 按「且」: 1 次
```

这是在本机真跑的输出（Windows 11，CPython 3.11.5），脚本在第三节。

`/opt/paideia/backup.sh --full` 是那台机器上的全量备份，跑一次 40 分钟，把磁盘压得很死。我想让它每月只跑一次，还挑在月初、机器最闲的周一。于是 `crontab -e` 写下 `0 0 1 * 1`，保存，`crontab -l` 原样回显，没有报错，也没有警告。

然后 2026 年它会跑 63 次。

这不是 cron 的 bug。crontab(5)（Debian bookworm 的 cron 3.0pl1-162）在 Note 里写得很直白：两个日期字段**都**被限制时，命令在**任一**字段匹配时运行；有一个是 `*` 才是「且」。按这条，`0 0 1 * 1` 表达的是「每月 1 号 + 每个周一」——52 个周一加 12 个 1 号，其中 2026-06-01 两头重合，63 次。真正的「1 号且周一」2026 年只有 1 次。

而且「被限制」的判据比 manpage 那句话更宽。Vixie cron 的 `entry.c` 里只有 `if (ch == '*') e->flags |= DOM_STAR;`，看的是字段的**第一个字符**。所以 `0 3 */2 * 1` 里的 `*/2` 也算不受限，那行是「隔天的 03:00 且是周一」，不是「隔天或周一」。这个绕法连作者自己都嫌怪，上游 vixie/cron 的 `cron.c` 里 `find_jobs()` 上面就摆着这段：

```text
 * the dom/dow situation is odd.  '* * 1,15 * Sun' will run on the
 * first and fifteenth AND every Sunday;  '* * * * Sun' will run *only*
 * on Sundays;  '* * 1,15 * *' will run *only* the 1st and 15th.  this
 * is why we keep 'e->dow_star' and 'e->dom_star'.  yes, it's bizarre.
 * like many bizarre things, it's the standard.
```

真想要「1 号且周一」，把条件塞进脚本，别在五个字段上凑：

```bash
#!/usr/bin/env bash
# /opt/paideia/backup.sh
set -euo pipefail
[ "$(date +%u)" = 1 ] || exit 0                 # 不是周一就直接退，什么都不做
tar -czf "/var/backups/paideia-$(date +%F).tgz" /srv/paideia
```

```text
0 3 1 * * /usr/bin/bash /opt/paideia/backup.sh --full >> /var/log/paideia/backup.log 2>&1
```

每月 1 号 03:00 触发，星期几交给脚本判断。顺带一个好处：`$(date +%F)` 写在脚本里没事，直接写进 crontab 那行会被 cron 吃掉（第五节说为什么）。

## 同一行改一个字符，一天差 12 倍

同一个脚本里还有一条增量同步，本来要「每 5 分钟一次」。同一个枚举器给出的两种写法：

```text
*/5 * * * * -> Thu 11-19 20:00 | Thu 11-19 20:05 | Thu 11-19 20:10 | Thu 11-19 20:15
5   * * * * -> Thu 11-19 20:05 | Thu 11-19 21:05 | Thu 11-19 22:05 | Thu 11-19 23:05
```

`5` 是「每小时的第 5 分钟」，`*/5` 是「从 0 开始每 5 个单位」。一天分别是 24 次和 288 次（也是枚举出来的，不是估的）。差 12 倍，而 `crontab -e` 两个都收、都不吭声。

范围别心算：crontab(5) 写的是「指定的范围包含两端」，所以 `0 9-18 * * 1-5` 的 18 点在范围内，一天是 10 次而不是 9 次。

## 跑一遍：三十行枚举，比背五个字段可靠

`crontab -e` 保存时只在语法层拦很粗的错，不同版本拦的宽度还不一样。上面那几段触发时刻出自这份脚本（本机 CPython 3.11.5，无第三方依赖，存成 `cron_check.py` 就能跑）：

```python
from datetime import datetime, timedelta

B = [(0, 59), (0, 23), (1, 31), (1, 12), (0, 7)]        # 分 时 日 月 周

def field(s, i):
    lo, hi = B[i]; out = set()
    for part in s.split(','):
        step = 1
        if '/' in part:
            part, n = part.split('/'); step = int(n)
        a, b = (lo, hi) if part == '*' else (int(part.split('-')[0]), int(part.split('-')[-1]))
        if not (lo <= a <= b <= hi) or step < 1:
            raise ValueError(f'{s}: 越界或步长为 0（本字段 {lo}-{hi}）')
        out |= set(range(a, b + 1, step))
    return out

def compile5(expr):
    f = expr.split()
    if len(f) != 5:
        raise ValueError(f'{expr}: 要 5 个字段，给了 {len(f)} 个')
    m, h, d, mo, w = (field(x, i) for i, x in enumerate(f))
    return m, h, d, mo, {0 if x == 7 else x for x in w}, f[2][0] == '*' or f[4][0] == '*'

def hit(c, t, star_rule=True):
    m, h, d, mo, w, star = c
    if t.minute not in m or t.hour not in h or t.month not in mo:
        return False
    dom, dow = t.day in d, (t.isoweekday() % 7) in w
    if not star_rule:
        return dom and dow
    return (dom and dow) if star else (dom or dow)

def nexts(expr, start, n):
    c = compile5(expr); t = start.replace(second=0, microsecond=0); out = []
    while len(out) < n:
        if hit(c, t):
            out.append(t)
        t += timedelta(minutes=1)
    return out

def count(expr, year, star_rule=True):
    c = compile5(expr); t = datetime(year, 1, 1); n = 0
    for _ in range(365 * 24 * 60):
        n += hit(c, t, star_rule)
        t += timedelta(minutes=1)
    return n
```

四行坏写法喂进去，是这个反应：

```text
0 3 * * 8 -> 8: 越界或步长为 0（本字段 0-7）
60 * * * * -> 60: 越界或步长为 0（本字段 0-59）
*/0 * * * * -> */0: 越界或步长为 0（本字段 0-59）
0 3 * * -> 0 3 * *: 要 5 个字段，给了 4 个
0 25 * * * -> 25: 越界或步长为 0（本字段 0-23）
```

它不懂的东西也要说清楚：不管补跑、不管夏令时、不管 `@reboot` 这类简写。它只回答「按这五个字段，接下来哪几个时刻会触发」。

## 定时器启动的那个进程，和你终端里那个不是同一个

这台机器是 Windows，没有 cron。但「同一个脚本，终端里跑得好好的，交给调度器就出事」Windows 也有，所以我真跑了一次任务计划做对照。**先说清楚：这不是 cron**，只是同一类问题在另一套实现里的样子。

```text
$ schtasks /create /tn PaideiaCronProbe /tr "cmd /c %TEMP%\paideia-cron-probe.cmd" /sc once /st 23:59 /f
$ schtasks /run /tn PaideiaCronProbe
$ schtasks /query /tn PaideiaCronProbe /v /fo LIST
Folder: \
HostName:                             LAPTOP-5M8H38DP
TaskName:                             \PaideiaCronProbe
Next Run Time:                        2026-11-19 23:59:00
Status:                               Ready
Logon Mode:                           Interactive only
Last Run Time:                        2026-11-19 20:23:23
Last Result:                          0
Author:                               LAPTOP-5M8H38DP\Lenovo
Task To Run:                          cmd /c C:\Users\Lenovo\AppData\Local\Temp\paideia-cron-probe.cmd
Start In:                             N/A
Run As User:                          Lenovo
```

那个 `.cmd` 五条命令：`cd > 文件`，再分别把 `%USERNAME%`、`%CD%`、`%PATH%` 追加进去，最后 `whoami >> 文件`。它落下的是：

```text
C:\Windows\system32
USERNAME=Lenovo
CD=C:\Windows\system32
PATH=E:\vm\bin\;E:\platform-tools;C:\Program Files (x86)\jdk/bin;…（共 73 项，中间省略）
laptop-5m8h38dp\lenovo
```

我原本准备在这里演「调度器的 PATH 会短一截，所以找不到命令」。**猜错了。** 逐项比对这条任务和当前终端的 PATH：任务 73 项（去重 72），终端 81 项（去重 73）；只在任务里出现的 2 项，是两条中文路径被写成了 `?` 的乱码变体（`E:\微信web开发者工具\dll` 变了脸）；只在我终端里的 3 项，除了那两条真身，还有 PowerShell 7 的路径。去重后净差 1 项。

真正变的是工作目录：`Start In` 是 `N/A`，进程拿到的是 `C:\Windows\system32`。脚本里任何相对路径都往系统目录跑。

为什么 PATH 没变？看 `Logon Mode` 和 `Run As User`——这条任务是**以我这个交互用户、在我的会话里**跑的，继承的就是我的环境。换成「不管用户是否登录」，或者以 SYSTEM 跑，PATH 立刻是另一套。所以「环境会不会变」不取决于「它是不是定时任务」，取决于**以谁的身份、在哪个会话里跑**。

Linux 那边是同一件事，只是默认值写在别处，而且这台机器上没法看到：

```text
# 标准形态，未实跑
$ crontab -e
* * * * * env > /tmp/cron-env.txt      # 存盘，等一分钟，再把这行删掉
$ cat /tmp/cron-env.txt
SHELL=/bin/sh
HOME=/home/paideia
LOGNAME=paideia
USER=paideia
PATH=/usr/bin:/bin
```

`PATH` 那行别照抄。crontab(5) 明确写了的只有三样：`SHELL` 设成 `/bin/sh`，`HOME` 和 `LOGNAME` 取自该用户的 passwd 行——它压根没提 PATH。PATH 是实现在 `entry.c` 里补的：`if (!env_get("PATH", e->envp))` 才把 `PATH` 设成编译期常量 `_PATH_DEFPATH`；上游 `pathnames.h` 里那个兜底值是 `/usr/bin:/bin`，但同一个文件在 `__linux` 下会 `#include <paths.h>`，让 libc 来提供这个宏。**这个值在编译时就定死了**，不是运行时从你的 shell 抄过来的。所以：量一次，别背；而且无论它是哪一套，`~/.local/bin`、nvm、pyenv 装的东西都不在里面——cron 的环境里没有与家目录绑定的路径，除非你自己在 crontab 里写。

再加上第 10 关讲过的：cron 不读 `.bashrc`、`.profile`。工作目录也不是脚本所在目录——`do_command.c` 在执行命令前调 `chdir(env_get("HOME", e->envp))`，所以相对路径落在 `$HOME`；manpage 没写这条，源码写了，下文表里「Linux 是任务用户的 `$HOME`」依据的就是这一行。同一个文件里还有一条：任务进程的 stderr 被 `dup2` 到 stdout，两个流通到同一根管道，所以它「有没有输出」其实都落进了那根管道，问题只在另一头有没有人接。

编辑与查看（都是标准形态，未实跑）：

```text
# 标准形态，未实跑
$ crontab -e                  # 改自己的
$ sudo crontab -e -u paideia  # 改 paideia 的
$ crontab -l                  # 看
$ crontab -r                  # 删掉整份 crontab，不确认、不备份
```

`-r` 离 `-e` 只有一个键，敲错就没有回退；动手前先 `crontab -l > ~/crontab.bak`。系统级的位置不一样：`/etc/cron.d/<名字>` 和 `/etc/crontab` 每行多一列用户（`0 3 * * * root /usr/bin/bash ...`），`crontab -e` 写的是用户 crontab，没有那一列。

出问题时按症状分流，别从头 read 一遍配置：

| 症状 | 先看哪 | 为什么先看这里 |
| --- | --- | --- |
| 只有 cron 里报 `command not found` | 加一条 `* * * * * env > /tmp/cron-env.txt`，一分钟后看 | 先把「它看到的环境」量出来，比按 PATH 猜快 |
| 提示找不到某个相对路径的文件 | 脚本里第一个相对路径，再在开头加一句 `pwd` 打回日志 | 工作目录不是脚本所在目录（Windows 这边实测 `C:\Windows\system32`，Linux 是任务用户的 `$HOME`） |
| 日志和邮件里什么都没有 | 命令末尾有没有 `>> ... 2>&1`，以及 crontab 里的 `MAILTO` | 输出交给 MTA；`MAILTO=""` 就什么都不发 |
| 能看到执行记录，脚本却没效果 | `grep CRON /var/log/syslog`（Debian 系装了 rsyslog 时）或 `journalctl -u cron -n 50` | 有记录=触发了，问题在脚本；没记录=没触发，问题在时间或服务 |
| 同一份日志叠了好几遍 | `pgrep -fc backup.sh` | cron 只管触发，不管上一次跑完没有 |
| 触发点差 8 小时 | `timedatectl`，以及容器里有没有 `/etc/localtime` | 用的是守护进程所在机器的时区；镜像没装 tzdata 时按 UTC 走，`0 0 * * *` 落在北京时间 08:00 |

## 四件事，钉进同一条命令

| 写法 | 实际发生什么 |
| --- | --- |
| 命令里的程序不写路径，靠 PATH 找 | 找不到就 `command not found`，而且这条错误只落在 journal 或那封没人收的邮件里 |
| `>> /var/log/x.log 2>&1` | `2>&1` 必须写在 `>>` 之后；反过来只把 stdout 送进文件，stderr 还在原地 |
| `flock -n /tmp/x.lock` 包住整行 | 拿不到锁立刻以 1 退出（flock(1) 原话），不排队、不等；去掉 `-n` 就变成等锁，后面堆一串 |
| 脚本开头 `cd /opt/paideia \|\| exit 1` | 相对路径才有意义；不要指望 crontab 给你设工作目录 |

落地就是第一节结尾那一行：

```text
0 3 1 * * /usr/bin/flock -n /tmp/paideia-full.lock /usr/bin/bash /opt/paideia/backup.sh --full >> /var/log/paideia/backup.log 2>&1
```

还有两个坑不在这一行里，但会咬人：

- **`%` 会被 cron 吃掉。** crontab(5)：命令部分到换行或 `%` 为止，`%` 不转义就变成换行，后面的内容当 stdin 喂给命令。所以在 crontab 行里写 `date +\%F`，或者干脆把 `date` 塞进脚本（第一节就是这么干的）。
- **重定向解决了「看不到」，没解决「看多久」。** 日志没人轮转就一路长，磁盘满的那天整套服务一起挂——logrotate 在第 13 关。

## 同一件事，换成一对 unit

```ini
# /etc/systemd/system/paideia-backup.timer
[Unit]
Description=每日备份

[Timer]
OnCalendar=*-*-* 03:00:00
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/paideia-backup.service
[Unit]
Description=Paideia 备份

[Service]
Type=oneshot
User=paideia
WorkingDirectory=/opt/paideia
ExecStart=/opt/paideia/backup.sh --full
```

两份都是标准形态，未实跑。参数别只看名字猜：

| 参数 | 它实际做了什么 | 容易踩的地方 |
| --- | --- | --- |
| `OnCalendar=*-*-* 03:00:00` | 归一到日历时间 | 写完用 `systemd-analyze calendar` 看它归一成什么，别读自己的想法 |
| `AccuracySec=`（默认 1min） | 允许在触发点之后 1 分钟内随时启动 | 要准点就显式设 `1s`，代价是唤醒更碎 |
| `Persistent=true` | 记下上次触发时间，关机期间错过的在开机后补跑 | 只对 `OnCalendar=` 有效，配 `OnBootSec=` 这类单调时间没意义 |
| `RandomizedDelaySec=300` | 在窗口内再随机延 0–300 秒 | 削峰用；它不改 `Persistent` 的补跑判定 |
| `User=`（写在 `.service` 里） | 以谁的身份跑 | timer 不管身份，写错文件等于白写 |
| `WantedBy=timers.target` | 要 enable 的是 `.timer` | `enable paideia-backup.service` 只让 service 能手动起，timer 没被安排 |

原稿在这里写过「同一 timer 不会重叠触发」，那句只说对了一半。更准确的是：**同一个 unit 还在 active 时再 start 一次是空操作**——所以不会叠，但这一次就没了，不排队，也不会有日志说「跳过」。换成那份 40 分钟的全量备份、每 5 分钟触发一次，你会看到 00:00 起一次、接下来 7 个触发点（00:05 到 00:35）静默消失、00:45 又正常。要验证就数 `Starting` 的条数：

```text
# 标准形态，未实跑
$ journalctl -u paideia-backup.service --since today | grep -c 'Starting'
```

启用之后必须看 `NEXT` 有没有值：

```text
# 标准形态，未实跑
$ sudo systemctl daemon-reload
$ sudo systemctl enable --now paideia-backup.timer
$ systemctl list-timers --all | grep paideia
NEXT                         LEFT       LAST  PASSED  UNIT                  ACTIVATES
Fri 2026-11-20 03:00:12 CST  6h left    -     -       paideia-backup.timer  paideia-backup.service
```

`NEXT` 空着就是没被安排；`ACTIVATES` 指错说明 `.timer` 和 `.service` 不是同名，而 timer 默认只激活与它同名的 service（要改就写 `Unit=`）。

## 动手

```code-challenge
{"id":"linux-18-cron","kind":"fill","title":"把两条备份计划排对","language":"bash",
 "template":"# 每月 1 号 03:00 全量备份，同一时刻只跑一份\n{{0}} 3 1 * * /usr/bin/flock -n /tmp/paideia-full.lock /usr/bin/bash /opt/paideia/backup.sh --full >> /var/log/paideia/backup.log 2>&1\n# 每 5 分钟一次增量同步\n{{1}} * * * * /opt/paideia/incr.sh >> /var/log/paideia/incr.log 2>&1",
 "options":[["0","3","1"],["*/5","5","5/*"]],"answers":[0,0],
 "explain":"第一空是分钟字段：`0` 表示整点那一分钟（配上后面的 `3 1`，就是 1 号 03:00）；写 `3` 是每小时第 3 分钟，写 `1` 是每小时第 1 分钟。第二空要「每 5 分钟」，只有 `*/5` 是从 0 开始按 5 递增（20:00、20:05、20:10…，一天 288 次）；`5` 是每小时的第 5 分钟（20:05、21:05…，一天 24 次）；`5/*` 写反了，解析不了。"}
```

## 检查一下

```quiz
{"id":"linux-18-quiz","title":"定时任务三问","questions":[
 {"question":"crontab 里写 `0 0 1 * 1`，本意是「1 号且周一」，实际会怎样？","options":["每月 1 号那一次，全年就 1 次","每周一那一次，全年 52 次","每月 1 号加上每个周一，2026 年 63 次"],"answer":2,"explain":"两个日期字段都被限制时取「或」。63 是本机枚举出来的，同一份脚本按「且」算只有 1 次。要「且」就把条件写进脚本。"},
 {"question":"同一个脚本在终端里能跑，交给 cron 就报 `command not found`。第一步做什么？","options":["先把命令改成绝对路径再试","量一次它看到的环境：加一条 `* * * * * env > /tmp/cron-env.txt`","给脚本加执行权限"],"answer":1,"explain":"crontab(5) 只写了 SHELL、HOME、LOGNAME 三样，PATH 是实现在 entry.c 里补的编译期常量，各发行版不同。量出来再改，比按 PATH 猜快。"},
 {"question":"一份全量备份要跑 40 分钟，写成 systemd timer 每 5 分钟触发一次，中间那 7 个触发点会怎样？","options":["静默消失：unit 还是 active，再 start 是空操作","排队，备份跑完后立刻补跑 7 次","同时起 7 个进程，一起抢同一份日志文件"],"answer":0,"explain":"systemd 不会为同一个 unit 起第二个实例，也不排队、不记日志，所以在 journal 里只能靠数 Starting 的条数看出来。"}
]}
```

## 还差的

1. **Linux 那一侧的回显全是「标准形态，未实跑」。** 这台机器是 Windows，没有 cron 也没有 systemd。`crontab -l`、`env > /tmp/cron-env.txt` 那几行、`journalctl -u cron`、`systemd-analyze calendar`、`list-timers` 的列宽与取值，都得在一台真 Linux 上对一遍——尤其 PATH 那行，它最容易抄成别人机器上的默认值。
2. **Windows 那两段是实跑，但只代表这台机器。** `Logon Mode: Interactive only`、`Run As User: Lenovo`、任务计划默认给 `C:\Windows\system32` 作工作目录，都是这条探针任务的性质。换成「不管用户是否登录」或 SYSTEM 身份后 PATH 会变，那部分我没测，不写结论。
3. **补跑只写了「会补」，没验过补几次。** `Persistent=true` 在关机两天、错过两次的情况下补一回还是两回，得在你机器上数 `journalctl -u paideia-backup.service` 里 `Starting` 的条数。cron 那边我只到源码这一层：`cron.c` 把迟到分三档——1–5 分钟走 `small`（逐分钟补跑错过的每一分钟）、6 分钟以上走 `medium`（先跑通配任务，再补错过的固定时间任务）、时钟大幅跳变走 `large`（只跑当前这一分钟）。真机上制造休眠后落在哪一档、补了几次，我没测。
4. **夏令时没验。** `cron.c` 对「时间往前跳」和「往后跳」分别走 `medium` 与 `negative` 两条分支（注释里写的就是 DST begins / DST ends），哪一次多跑、哪一次少跑要有夏令时的机器实测。中国时区碰不到，所以我没跑。
5. **备份脚本本身还是占位的。** `/opt/paideia/backup.sh` 在本文里只到「判断周一 + tar 打包」，失败告警和日志轮转都没做。这两件事不做，前面所有努力只是让「没跑」变得**可查**，没让它变得**会自己好**。

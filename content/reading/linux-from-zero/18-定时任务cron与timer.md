---
title: 定时任务：cron 与 systemd timer
category: linux-from-zero
order: 18
minutes: 20
level: beginner
status: published
---

## 这一关在解决什么

"每天凌晨备份一次""每 5 分钟同步一次"——这类需求在 Linux 上有两套做法：**cron**（老牌、简单）和 **systemd timer**（新、带日志与依赖）。

新手最常遇到的两个问题都在 cron 上：**时间写了但没跑**、**跑了但什么都不对**。根因几乎总是同一个：**cron 的环境和你终端的完全不一样**。

## 为什么 cron 让人困惑

因为 cron 启动的进程**不在你的登录会话里**：

- `PATH` 只有 `/usr/bin:/bin`（很多工具在 `/usr/local/bin`，于是 `command not found`）；
- 工作目录是 `$HOME`（相对路径全错）；
- 不读 `.bashrc`、`.profile`（环境变量、别名全没有）；
- stdout/stderr 会被当成邮件发给 `$MAILTO`，绝大多数机器上没有邮件服务，于是**输出去哪了？没有地方**。

理解了这一条，cron 的各种"灵异现象"就都解释得通了。

## 讲解

### cron 的时间格式

```text
*  *  *  *  *  命令
│  │  │  │  └── 星期（0–7，0 和 7 都是周日）
│  │  │  └───── 月（1–12）
│  │  └──────── 日（1–31）
│  └─────────── 时（0–23）
└────────────── 分（0–59）
```

几个例子：

| 表达式 | 含义 |
| --- | --- |
| `*/5 * * * *` | 每 5 分钟 |
| `0 3 * * *` | 每天 03:00 |
| `30 2 * * 1` | 每周一 02:30 |
| `0 0 1 * *` | 每月 1 号 0 点 |
| `0 9-18 * * 1-5` | 工作日 9 点到 18 点，每小时整点 |

三个易错点：

- `*/5` 是"每 5 个单位"，写成 `5` 是"第 5 分钟"（每小时一次）。
- **日和周同时指定时是"或"关系**：`0 0 1 * 1` 表示"1 号**或**每周一"，不是"1 号且周一"。
- 时间用的是**系统时区**。容器里常是 UTC，于是"每天 0 点"实际在北京时间 8 点。

特殊简写：`@reboot`（开机后跑一次）、`@daily`、`@hourly`、`@weekly`、`@monthly`。

### 怎么编辑

```text
$ crontab -e        # 编辑当前用户的任务
$ crontab -l        # 列出
$ crontab -r        # 全部删除（慎用，没有确认）
$ sudo crontab -e -u paideia   # 编辑指定用户的任务
```

系统级任务放在 `/etc/cron.d/` 下的文件里，**格式多一列"以哪个用户运行"**：

```text
# /etc/cron.d/paideia-backup
0 3 * * * paideia /opt/paideia/backup.sh
```

区别值得知道：`crontab -e` 是"这个用户自己的任务"，`/etc/cron.d/` 的是"系统配置"，能进版本管理与部署脚本（不要手改 `/etc/crontab`，那是给发行版用的）。

### 让 cron 任务可靠的四件事

**1. 全部用绝对路径**：命令、脚本、参数、日志文件。

```text
0 3 * * * /usr/bin/bash /opt/paideia/backup.sh >> /var/log/paideia/backup.log 2>&1
```

`/usr/bin/bash` 而不是 `bash`，因为 cron 的 PATH 里可能有也可能没有（不同发行版不同）。用 `command -v bash` 查真实路径。

**2. 显式重定向输出。**

```text
0 3 * * * ... >> /var/log/job.log 2>&1
```

不加的话，输出会变成"邮件"；没邮件服务，静默丢失。**这是"任务跑了但没日志"的唯一原因。**

**3. 脚本自己设好环境和目录**（第 17 关）：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /opt/paideia || exit 1
export PATH="/usr/local/bin:/usr/bin:/bin"
```

如果确实需要完整环境，可以在 crontab 里显式 source：

```text
0 3 * * * . /etc/profile; /opt/paideia/backup.sh
```

但更推荐**什么都不依赖**：显式写 `PATH`、显式写配置路径。

**4. 加锁，防止上一次没跑完又起一次。**

```text
*/5 * * * * /usr/bin/flock -n /tmp/sync.lock /opt/paideia/sync.sh >> /var/log/sync.log 2>&1
```

`flock -n` 拿不到锁就直接退出。备份、同步这类任务一旦重叠，可能同时写同一个文件或把带宽占满。

### 排查：cron 到底跑了没有

```text
$ systemctl status cron        # Debian/Ubuntu；RHEL 是 crond
$ journalctl -u cron -n 50     # 有没有执行记录
$ grep CRON /var/log/syslog | tail -20
```

能看到 `(paideia) CMD (/opt/paideia/backup.sh)` 说明 **cron 触发了**，问题在脚本自己（路径、权限、环境）。看不到这一行说明**没触发**（时间写错、crontab 没保存、服务没在跑）。

这一条分界能把问题砍掉一半，先做它。

### systemd timer：更现代的做法

定时任务也可以写成一对文件：`.timer`（什么时候）+ `.service`（做什么）。

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
ExecStart=/opt/paideia/backup.sh
```

启用：

```text
$ sudo systemctl daemon-reload
$ sudo systemctl enable --now paideia-backup.timer
$ systemctl list-timers --all
$ journalctl -u paideia-backup.service -n 50
```

它比 cron 多出来的东西，恰好就是 cron 的四块短板：

| | cron | systemd timer |
| --- | --- | --- |
| 日志 | 靠重定向，不然丢 | **自动进 journal**，`journalctl -u` 就够 |
| 环境 | 极简且各发行版不同 | 由 unit 显式声明（第 12 关） |
| 错过一次会不会补 | 不会（机器关机就跳过） | `Persistent=true` **开机后补跑** |
| 依赖别的服务 | 做不到 | `After=`、`Requires=` |
| 并发控制 | 要自己 `flock` | 同一 timer 不会重叠触发 |

`OnCalendar` 的语法比 cron 好读：`daily`、`hourly`、`*-*-* 03:00:00`、`Mon..Fri 09:00`、`*:0/5`（每 5 分钟）。验证写法：

```text
$ systemd-analyze calendar "*-*-* 03:00:00"
  Next elapse: ...
```

它会告诉你"下次什么时候触发"，写完一定跑一次，比猜准。

`RandomizedDelaySec` 值得加：几百台机器都用 `0 3 * * *` 会同时打一个共享资源（备份服务器、对象存储），加几百秒抖动就把峰削平了。

### 该选哪个

- 一次性、简单的（清临时文件、每小时同步）→ cron 就够。
- 要日志、要"错过补跑"、要依赖别的服务、需要非 root 用户执行 → **systemd timer**。
- 已经在用 systemd 管服务 → 优先 timer，运维面只有一套。

## 动手

```code-challenge
{"id":"linux-18-cron","kind":"fill","title":"写两条最常用的时间表达式","language":"bash",
 "template":"# 每 5 分钟一次\n{{0}} * * * * /opt/app/sync.sh >> /var/log/sync.log 2>&1\n# 每天 03:00 一次\n{{1}} 3 * * * /opt/app/nightly.sh >> /var/log/nightly.log 2>&1",
 "options":[["*/5","5","5/*"],["0","*","3"]],"answers":[0,0],
 "explain":"`*/5` 是「每 5 分钟」，只写 `5` 是「每小时的第 5 分钟」。分钟字段写 `0` 才是整点。两条都显式重定向输出，否则输出会变成没人收的邮件。"}
```

## 检查一下

```quiz
{"id":"linux-18-quiz","title":"定时任务三问","questions":[
 {"question":"cron 任务里写 `python3 job.py` 报 command not found，根本原因？","options":["cron 的 PATH 很短，不包含实际路径","cron 不能执行 python","脚本没有执行权限"],"answer":0,"explain":"cron 的环境极简，PATH 常只有 /usr/bin:/bin。要写绝对路径，脚本里也显式设 PATH。"},
 {"question":"cron 任务执行了但完全没有输出，为什么？","options":["输出被当成邮件发送，没有邮件服务就丢了","cron 会丢弃所有输出","需要 chmod +x"],"answer":0,"explain":"cron 把 stdout/stderr 交给 MTA；多数机器没有 MTA，于是静默丢失。必须自己重定向到日志文件。"},
 {"question":"systemd timer 相比 cron，哪一点是它独有的？","options":["Persistent=true 能在关机错过后开机补跑","支持每分钟执行","支持多用户"],"answer":0,"explain":"cron 错过就跳过；timer 的 Persistent=true 会记录上次触发时间，开机后补跑一次。"}
]}
```

## 常见误区

### 误区：`.bashrc` 里配的环境，cron 任务能用

**你以为**：我平时能跑，cron 也能跑。
**其实**：cron 不读任何登录脚本（第 10 关），PATH、`JAVA_HOME`、`LANG` 全是默认值。这也解释了"中文日志变成问号"（LANG 没设）与"脚本找不到 java"这两个高频问题。
**怎么验证**：在 crontab 里加一条 `* * * * * env > /tmp/cron-env.txt`，等一分钟再看这个文件——它会让你看清 cron 看到的世界。

### 误区：cron 每分钟跑，任务一定每分钟都在跑

**你以为**：`* * * * *` 表示"每分钟执行一次，直到完成"。
**其实**：cron 是"每分钟**触发**一次"。上一次还在跑，下一次照样启动一个新进程，于是同一个任务叠加几十份。它们抢同一个文件、抢同一把数据库连接，症状是"数据重复""负载莫名升高"。
**怎么验证**：`ps -ef | grep 脚本名` 看同时有几个；给任务加 `flock -n`（本关讲过）就能杜绝。

### 误区：`systemctl enable` 之后 timer 就会按点跑

**你以为**：enable 了 timer 就生效。
**其实**：要 enable 的是 **`.timer` 单元**，不是 `.service`。`systemctl enable paideia-backup.service` 只是让 service 能手动启动，timer 根本没被安排。启用后 `systemctl list-timers` 里必须能看到它，并且有 `NEXT` 时间。
**怎么验证**：`systemctl list-timers --all | grep paideia`；没有就是没启用对单元。

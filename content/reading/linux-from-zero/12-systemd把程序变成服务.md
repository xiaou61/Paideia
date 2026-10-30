---
title: systemd：把程序变成服务
category: linux-from-zero
order: 12
minutes: 28
level: beginner
status: published
---

## 这一关在解决什么

`nohup ./app.sh &` 能跑，但机器一重启就没了、崩了不会自己起来、日志混在某个文件里、`ps` 里也看不出它是谁。**把这些"能跑"升级成"能长期活着"，就是 systemd 的职责。**

```sketch
{
  "title": "从「能跑」到「长期活着」",
  "rows": [
    [
      { "id": "nohup", "title": "nohup 后台跑", "sub": "重启就没了", "tone": "amber" },
      { "id": "unit", "title": "写成 unit", "sub": "崩了自动拉起", "tone": "blue" },
      { "id": "journal", "title": "日志进 journald", "sub": "开机自启、状态可查", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "nohup", "to": "unit" },
    { "from": "unit", "to": "journal" }
  ]
}
```

这一关给你一份能直接改用的 unit 文件，和三个必须知道的行为：改完要 `daemon-reload`、`ExecStart` 必须是绝对路径、单元里读不到你的 shell 环境变量。

## 为什么是 systemd

它取代了老的 SysV init 脚本，主要解决三件事：

- **依赖与并行**：老式脚本靠编号（`S20` 排在 `S21` 前），systemd 声明 `After=`、`Wants=` 之后能并行启动无依赖的服务，开机快得多。
- **cgroup 管理**：每个服务放进自己的 cgroup，能精确知道"这个服务用了多少内存、起过哪些子进程"，`systemctl stop` 能把整棵进程树一次清掉（第 09 关讲的"杀一整组"它默认就做）。
- **统一的日志**：服务的 stdout/stderr 自动进 journal，不用自己在脚本里重定向。

## 讲解

### 单元是什么

systemd 管的一切都是"单元"（unit），一个单元一个文件：

| 后缀 | 管什么 | 例子 |
| --- | --- | --- |
| `.service` | 后台服务 | `paideia.service` |
| `.timer` | 定时任务（替代 cron，第 18 关） | `backup.timer` |
| `.socket` | 按需唤醒（有人连端口才启动服务） | `docker.socket` |
| `.mount` | 挂载点 | `/data.mount` |
| `.target` | 一组单元的集合，类似"运行级别" | `multi-user.target` |

单元文件放哪，决定它归谁管：

| 目录 | 谁放 | 优先级 |
| --- | --- | --- |
| `/etc/systemd/system/` | **管理员自己写的** | 最高 |
| `/usr/lib/systemd/system/` | 包管理器装的（apt 装的服务在这） | 中 |
| `~/.config/systemd/user/` | 用户级服务（`systemctl --user`） | 仅该用户 |

**自己的服务写在 `/etc/systemd/system/`**。要改包管理器装的单元，也在这里放一个同名文件覆盖，不要去改 `/usr/lib` 下的原件——升级时会被覆盖回去。

### 一份能直接用的 unit

假设程序在 `/opt/paideia/paideia.jar`，用专用账号 `paideia` 跑：

```ini
# /etc/systemd/system/paideia.service
[Unit]
Description=Paideia API
Documentation=https://example.com/paideia
After=network-online.target mysql.service
Wants=network-online.target
Requires=mysql.service

[Service]
Type=simple
User=paideia
Group=paideia
WorkingDirectory=/opt/paideia
Environment=LANG=C.UTF-8
EnvironmentFile=-/etc/paideia/paideia.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/paideia/paideia.jar
ExecReload=/bin/kill -HUP $MAINPID
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

逐段看：

**[Unit] 段——关系和元信息**

| 指令 | 含义 |
| --- | --- |
| `Description` | `systemctl status` 里显示的一句话 |
| `After=X` | **启动顺序**：X 起来之后再启动我。只定顺序，不建立依赖 |
| `Wants=X` | **弱依赖**：想启动 X，X 失败我也照跑 |
| `Requires=X` | **强依赖**：X 失败我就不启动；X 停止时我也被停 |
| `Documentation` | 文档地址，`systemctl status` 里可点 |

`After` 和 `Requires` 是两件不同的事，这也是最容易配错的地方：只写 `After=mysql.service` 只是"排在 mysql 后面"，mysql 没起来它照样启动然后连不上库。要"mysql 必须可用"得写 `Requires=` + `After=`（或 `BindsTo=`）。

`network-online.target` 与 `network.target` 也别混：前者等网络**真正可用**（拿到地址），后者只是"网络子系统初始化完了"。依赖外部服务的程序要用 `network-online.target`。

**[Service] 段——怎么跑**

| 指令 | 说明 |
| --- | --- |
| `Type=simple` | 默认值。`ExecStart` 启动的进程就是主进程，不 fork |
| `Type=forking` | 老式守护进程（自己 fork 到后台），要配 `PIDFile=` |
| `Type=oneshot` | 跑完就退出（脚本、初始化任务），常配 `RemainAfterExit=yes` |
| `User` / `Group` | **以谁的身份跑**。不写就是 root（第 05 关讲过为什么不该这样） |
| `WorkingDirectory` | 进程的工作目录——**相对路径的基准**（第 02 关） |
| `Environment=` | 直接给变量，可以写多行 |
| `EnvironmentFile=` | 从文件读 `KEY=VALUE`；路径前加 `-` 表示文件不存在也不报错 |
| `ExecStart` | **必须是绝对路径**，且**不经过 shell** |
| `ExecReload` | `systemctl reload` 时执行什么；`$MAINPID` 是主进程 PID |
| `Restart=` | `no`（默认）/ `on-failure` / `always` |
| `RestartSec` | 重启前等几秒，防止疯狂重启 |
| `TimeoutStopSec` | SIGTERM 之后等多久发 SIGKILL（默认 90） |
| `LimitNOFILE` | 打开文件数上限，服务报 "Too many open files" 就改它 |
| `StandardOutput` | `journal` / `file:` / `null`；默认进 journal |

**[Install] 段——什么时候自启**

`WantedBy=multi-user.target` 表示"多用户模式（也就是正常启动）时把我带上"。`systemctl enable` 就是根据这一段创建软链接；没有这一段，`enable` 会报错。

### `ExecStart` 的两个硬约束

**1. 必须是绝对路径。**

```ini
ExecStart=java -jar app.jar            # ✗ 启动失败：Executable path is not absolute
ExecStart=/usr/bin/java -jar app.jar   # ✓
```

原因：systemd 不读你的 PATH（第 10 关），它只认绝对路径。想用 `which java` 查出来的路径，写死进去。

**2. 不经过 shell。**

```ini
ExecStart=/bin/sh -c 'cd /opt/app && ./start.sh && echo done'   # 要用 shell 特性就这样写
ExecStart=/opt/app/start.sh                                     # 或者把逻辑写进脚本
```

`ExecStart=/opt/app/start.sh > /var/log/app.log` 这种写法里的 `>` **不会被解释**——它只会被当成一个奇怪的参数传给程序。重定向要用 `StandardOutput=file:/var/log/app.log`，或者在脚本内部做。

同理，单元里也**没有** `$PATH` 里的命令、没有你在 `.bashrc` 里 export 的变量。要环境变量就显式写 `Environment=` 或 `EnvironmentFile=`。

### 日常命令

```text
$ sudo systemctl daemon-reload      # 改了 unit 文件后必须执行
$ sudo systemctl start paideia
$ sudo systemctl status paideia
$ sudo systemctl enable paideia     # 开机自启
$ sudo systemctl restart paideia
$ sudo systemctl stop paideia
$ systemctl is-active paideia       # active / inactive / failed
$ systemctl is-enabled paideia      # enabled / disabled
$ systemctl list-units --failed     # 哪些服务起不来
```

`daemon-reload` 是最容易被忘记的一步：**改完 unit 不 reload，`start` 用的还是旧配置**，症状是"我明明改了怎么没生效"。

`enable` 和 `start` 也是两件事：`enable` 只管"下次开机自启"，`start` 只管"现在就起来"。两个都要就都执行（或者用 `systemctl enable --now paideia`）。

`status` 的输出值得学会读：

```text
● paideia.service - Paideia API
     Loaded: loaded (/etc/systemd/system/paideia.service; enabled; preset: enabled)
     Active: active (running) since Wed 2026-10-29 14:02:11 CST; 1h 3min ago
   Main PID: 9812 (java)
      Tasks: 42 (limit: 4629)
     Memory: 512.3M
        CPU: 47.102s
     CGroup: /system.slice/paideia.service
             ├─9812 /usr/bin/java -jar /opt/paideia/paideia.jar
             └─9840 mysqld --defaults-file=/opt/paideia/my.cnf
```

四行信息量最大：`Loaded` 看它从哪个文件加载、是否自启；`Active` 看状态和跑了多久；`Main PID` 是主进程；`CGroup` 里能看到它启动的**全部子进程**——这在排查"服务起了但端口被谁占着"时很关键。

### 出问题先看三处

```text
$ systemctl status paideia -l --no-pager    # -l 不截断长行
$ journalctl -u paideia -n 100 --no-pager   # 该服务最近 100 行
$ journalctl -u paideia -p err -b           # 本次开机以来的错误级别日志
```

最常见的三类失败：

| 现象 | 原因 |
| --- | --- |
| `status=203/EXEC` | `ExecStart` 路径不存在或没有执行权限 |
| `status=200/CHDIR` | `WorkingDirectory` 不存在 |
| `status=217/USER` | `User=` 指定的账号不存在 |
| `start request repeated too quickly` | 启动即崩，`Restart=` 重试太频繁被 systemd 拦住（先 `reset-failed`） |

```text
$ sudo systemctl reset-failed paideia    # 清掉失败计数再试
```

### 加固：几行就够的常见项

```ini
[Service]
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/paideia /var/log/paideia
```

- `NoNewPrivileges`：禁止服务通过 setuid 提权。
- `PrivateTmp`：给它一个私有的 `/tmp`，和其他进程隔离。
- `ProtectSystem=strict`：整个文件系统只读，只有 `ReadWritePaths` 里的可写。

这几项能挡住"服务被攻破后顺手改系统文件"的一大类后果，代价很小。注意 `ProtectSystem=strict` 之后要显式列出所有需要写的目录，漏了会报"只读文件系统"。

## 动手

```code-challenge
{"id":"linux-12-unit-bug","kind":"bug","title":"这个 unit 起不来，错在哪一行？","language":"ini",
 "code":"[Unit]\nDescription=Paideia API\nAfter=network-online.target\n\n[Service]\nWorkingDirectory=/opt/paideia\nExecStart=java -jar paideia.jar\nRestart=on-failure\n\n[Install]\nWantedBy=multi-user.target","bugLine":7,
 "explain":"systemd 不读 PATH，`ExecStart` 必须是绝对路径，否则报 `Executable path is not absolute`（status=203/EXEC）。应写成 `ExecStart=/usr/bin/java -jar paideia.jar` 或先 `which java` 查真实路径。"}
```

这一道写一份 unit 文件，用 `systemd-analyze` 做静态检查。

```code-challenge
{
 "id": "linux-12-shell-unit-file",
 "kind": "shell",
 "title": "写一份能过静态检查的 unit",
 "seed": [
  {
   "path": "app/paideia.jar",
   "content": "占位文件，不参与运行\n"
  }
 ],
 "starter": "# 写一个 Java 服务的 unit 到 app/paideia.service：\n# 用 /usr/bin/java 以 paideia 用户跑 /opt/paideia/paideia.jar，失败自动重启，开机自启\n",
 "judge": "set -e\n[ -f app/paideia.service ] || { echo \"app/paideia.service 不存在\"; exit 1; }\ncommand -v systemd-analyze > /dev/null || { echo \"镜像里没有 systemd-analyze\"; exit 1; }\nout=$(systemd-analyze verify app/paideia.service 2>&1 || true)\ncase \"$out\" in\n  *\"Executable path is not absolute\"*) echo \"ExecStart 必须是绝对路径：$out\"; exit 1;;\n  *\"Invalid section\"*) echo \"段落名不合法：$out\"; exit 1;;\n  *\"no ExecStart\"*) echo \"缺 ExecStart：$out\"; exit 1;;\nesac\ngrep -q '^ExecStart=/usr/bin/java' app/paideia.service || { echo \"ExecStart 应为 /usr/bin/java -jar ...\"; exit 1; }\ngrep -q '^User=paideia' app/paideia.service || { echo \"缺 User=paideia（服务不该以 root 跑）\"; exit 1; }\ngrep -q '^Restart=' app/paideia.service || { echo \"缺 Restart=（崩了要能自己起来）\"; exit 1; }\ngrep -q 'WantedBy=multi-user.target' app/paideia.service || { echo \"缺 WantedBy=multi-user.target，否则 enable 不了\"; exit 1; }",
 "explain": "四个要点：ExecStart 必须绝对路径（systemd 不读 PATH，就是第 01 关那个 203/EXEC 的成因）、以专用用户跑、失败重启、WantedBy 才能 enable。`systemd-analyze verify` 做的事和真实加载一致，它不报错基本就等于能起来。"
}
```

## 检查一下

```quiz
{"id":"linux-12-quiz","title":"systemd 三问","questions":[
 {"question":"改完 unit 文件后，为什么 `restart` 没生效？","options":["要先 `systemctl daemon-reload`","必须先重启机器","unit 文件不能改"],"answer":0,"explain":"systemd 缓存已解析的单元；改文件后必须 daemon-reload 让它重新读取，否则 restart 用的是旧配置。"},
 {"question":"`After=mysql.service` 保证了什么？","options":["只保证启动顺序，不保证 mysql 可用","保证 mysql 一定起来","保证 mysql 挂了本服务也停"],"answer":0,"explain":"After 只定顺序。要依赖 mysql 可用得加 Requires=（或 BindsTo=）；弱依赖用 Wants=。"},
 {"question":"服务报 `Too many open files`，改哪个配置？","options":["unit 里的 LimitNOFILE","/etc/security/limits.conf 就够","ExecStart 加参数"],"answer":0,"explain":"限流是每进程的，systemd 服务要写在 unit 的 LimitNOFILE=（或 [Service] 段的同名字段），改 shell 的 ulimit 不作用于它。"}
]}
```

## 常见误区

### 误区：把 `nohup` 换成 systemd 就自动重启了

**你以为**：写成服务就有了高可用。
**其实**：默认 `Restart=no`，进程崩了就是 `failed`，不会自己起来。要自动重启必须显式写 `Restart=on-failure`（或 `always`）并配 `RestartSec`。而 `Restart=always` 遇到"启动即崩"会快速重试，被 systemd 拦在 `start request repeated too quickly`——那说明问题在程序自己，不在重启策略。
**怎么验证**：`systemctl show paideia -p Restart` 看当前值；`kill` 掉主进程后 `status` 会在几秒内显示 `activating (auto-restart)`。

### 误区：unit 文件里能直接用 shell 变量

**你以为**：`Environment=JAVA_HOME=/opt/jdk21` 之后 `ExecStart=$JAVA_HOME/bin/java` 就成立。
**其实**：`$JAVA_HOME` 这种**变量展开在 `ExecStart` 里支持**（systemd 会展开 `Environment=` 定义过的变量），但不支持管道、重定向、`&&`、通配符——那些是 shell 的语法，systemd 不启动 shell。想用 shell 特性就显式 `/bin/sh -c '...'`。
**怎么验证**：写 `ExecStart=/opt/app/start.sh > /tmp/x.log`，`status` 会显示它把 `>` 当参数传给了脚本；改成 `StandardOutput=file:/tmp/x.log` 才生效。

### 误区：`systemctl enable` 之后服务就在跑

**你以为**：enable 等于启动。
**其实**：`enable` 只建了开机自启的软链接，当前状态还是 `inactive`。要立刻起来用 `systemctl enable --now 服务名`，或者再 `start` 一次。
**怎么验证**：`systemctl is-enabled x` 与 `systemctl is-active x` 是两个独立的问题，分别查。

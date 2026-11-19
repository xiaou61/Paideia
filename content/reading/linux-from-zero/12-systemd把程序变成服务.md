---
title: systemd：把程序变成服务
category: linux-from-zero
order: 12
minutes: 18
level: beginner
status: published
---

## 两个单元抢同一个端口，`restart` 改不到真正在跑的那个

`deploy/ops/install-ops.sh` 第 214–217 行有一段注释，是 2026-10-27 一次真实排查留下的，下面原文照抄（连它自己的半角引号一起）：

```text
# **包自带的 grafana-server.service 必须停用**（2026-10-27 实测踩过）：apt 装的 grafana 会
# enable 它自己的单元，而本脚本用的是自写的 grafana.service。两个单元抢 127.0.0.1:3000，
# 后启动的那个 bind 失败并进入 `Restart=on-failure` 重启循环（半小时内 66 次），
# 而先启动的那个只是"看着正常"——`systemctl restart grafana-server` 更是完全改不了真正在跑的那个。
```

这四行里同时成立三件事，每一件单独看都会让人误判：

- 两个单元都被 enable 了，都认为自己拥有 3000 端口；
- 输的那个不是「没起来」，是「起来就死、死了又起」——`Active:` 一行的状态在你敲命令的那一瞬间是什么，取决于你赶上了哪一拍；
- 赢的那个是谁，由启动顺序决定，跟哪份 unit 写得更好没关系。

所以「我的服务在跑吗」这个问题，`systemctl status` 一个命令回答不完，得连着端口和 PID 一起看。下面就拿这一份 `deploy/ops/grafana.service` 走完全程：写对、起得来、分得清在跑哪个进程、坏了先看哪。

> 写作环境是 Windows，没有 systemd。本文所有 `systemctl` / `journalctl` 的回显都标了「标准形态，未实跑」——命令、字段名、状态码按 systemd 文档与仓库里的真实文件写，具体数字（PID、内存）你在自己机器上看到的不一样。能在这台机器上真跑的部分（读仓库文件、静态对账）贴的是真实输出，并注明用什么跑的。

## 先看这份 unit 到底长什么样

完整内容（24 行，`deploy/ops/grafana.service`）：

```ini
[Unit]
Description=Grafana（Paideia 运维面板）
After=network-online.target prometheus.service

[Service]
Type=simple
User=grafana
Group=grafana
# 主配置走 /etc/grafana/grafana.ini；口令等敏感项走 600 的环境文件
EnvironmentFile=/etc/grafana/grafana.env
ExecStart=/usr/share/grafana/bin/grafana server \
    --homepath=/usr/share/grafana \
    --config=/etc/grafana/grafana.ini
Restart=on-failure
RestartSec=5
MemoryMax=512M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/grafana /var/log/grafana

[Install]
WantedBy=multi-user.target
```

三段各管一件事，管的对象不一样：

- **`[Unit]`** 写「我和别人是什么关系」——排序（`After=`/`Before=`）与需求（`Wants=`/`Requires=`）。这份文件第 3 行只写了 `After=`、没写 `Wants=`，这件事后面单独一节说——它是本文最长的一个坑。
- **`[Service]`** 写「怎么跑这个进程」：用哪个账号（`User=`）、执行什么（`ExecStart=`）、崩了要不要重来（`Restart=`）、给自己留几条退路（`MemoryMax=`、`ProtectSystem=` 这些）。
- **`[Install]`** 只被 `systemctl enable` 读，管「开机时谁把我拉起来」。`WantedBy=multi-user.target` 的意思是：enable 时在 `/etc/systemd/system/multi-user.target.wants/` 下建一个指向本文件的符号链接，开机走到 `multi-user.target` 时顺着链接把它拉进来（`systemd.unit(5)` 的 Example 1 就是这个例子）。没有 `[Install]` 段的 unit，`systemctl enable` 会直接拒绝——因为它不知道该往哪个 target 的 wants 目录里放链接。

顺带一个反常识的点：`enable` 和 `start` 是两件事。`enable` 只改开机行为，对当前进程毫无影响；`start` 只改当前进程，重启后不会自己回来。要一步到位是 `systemctl enable --now grafana.service`。仓库的安装脚本用的就是这个写法（`deploy/ops/install-ops.sh:225`）。

## 它到底读了哪个文件

别背 `/etc`、`/usr/lib`、`/run` 三个目录谁优先——直接看回显第一行。`Loaded:` 后面的括号就是它实际读的路径：

```text
# 标准形态，未实跑
$ systemctl status grafana.service --no-pager
● grafana.service - Grafana（Paideia 运维面板）
     Loaded: loaded (/etc/systemd/system/grafana.service; enabled; preset: enabled)
     Active: active (running) since Wed 2026-11-19 10:21:07 CST; 2h 14min ago
   Main PID: 1187 (grafana)
      Tasks: 21 (limit: 4629)
     Memory: 214.6M (max: 512.0M available: 297.3M)
     CGroup: /system.slice/grafana.service
             ├─1187 /usr/share/grafana/bin/grafana server --homepath=/usr/share/grafana ...
             └─1219 /usr/share/grafana/bin/grafana server --homepath=/usr/share/grafana ...
```

三行信息决定后面所有判断：

- **`Loaded:`** —— 读了哪个文件、有没有 enable、preset 是什么。如果你改的是一份文件、它读的是另一份（比如包自带的 `/usr/lib/systemd/system/grafana-server.service`），那后面全白干。
- **`Active:`** —— `active (running)` 只说明「主进程还活着」，不说明它绑到了端口、能连上数据库。`activating`/`failed` 反复出现就是重启循环。
- **`Main PID:` 与 `CGroup:`** —— 这一层是第 08 关 `ps` 的延伸：unit 名下挂着的是一整组进程（cgroup），`Main PID` 只是其中被认定为主进程的那个。所以停服务时 systemd 默认给整组发信号，而不是只杀一个 PID。

`Active:` 说 running 但端口不在它手里——这就是开头那个事故的样子。核对只需要一条命令（第 15 关的 `ss`）：

```text
# 标准形态，未实跑
$ ss -ltnp | grep ':3000'
LISTEN 0 4096 127.0.0.1:3000 0.0.0.0:* users:(("grafana",pid=1187,fd=7))
```

拿 `pid=1187` 去和 `Main PID` 对。对不上，就是「你在管的那个不是真正在跑的那个」。

同一条信息也能让机器读，仓库里就有现成用法——`deploy/ops/ops-textfile.sh` 把 unit 状态抓成 Prometheus 指标，靠的是 `systemctl show`，而不是去解析 `status` 的排版：

```bash
values=$(systemctl show "$unit" \
    --property=ActiveState,MemoryCurrent,MemoryMax 2>/dev/null) || values=""
```

`status` 给人看，排版会随版本变；`show` 的 `键=值` 给脚本看，稳定得多。

## ExecStart 的三条硬约束

**一、命令的首参数必须是绝对路径。** systemd 不查 PATH 去找可执行文件，`ExecStart=grafana server` 这种写法在启动时会直接失败（`status=203/EXEC`）。但要注意反向的那一半：**进程内部的 PATH 不是你的登录 shell**，systemd 给服务的是个固定值 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`（`systemd.exec(5)` 的 Environment Variables 一节）。所以「我在 shell 里敲 `java -jar` 好好的」和「unit 里同一行找不到 java」可以同时为真——区别在于你的 PATH 里有 `~/.sdkman/candidates/java/current/bin`，服务里没有。如果 `ExecStart=` 指向的是一个脚本，脚本内部再调 `java`，踩的就是这一条；让脚本自己设 PATH，或者在 unit 里写 `Environment=PATH=...`。

**二、不经过 shell。** `>`、`|`、`&&`、`$(...)`、`*` 都不由 shell 解释，systemd 只把这一行按空白和引号切成参数，然后 `execve()`。需要管道或重定向，就显式写一个 shell：`ExecStart=/bin/sh -c 'exec /opt/app/run.sh >> /var/log/app.log 2>&1'`。变量要展开成字面 `$`，写 `$$`。

**三、改完文件必须 `daemon-reload`。** unit 文件在管理器启动时被读进内存，之后看的是缓存。改了配置只 `restart`，起来的还是旧内容，症状是「我明明改了但报错一模一样」。

同一段里还有一处容易自相矛盾的地方，顺手说清：服务**不继承你登录 shell 的环境变量**——这不等于「unit 里读不到环境变量」。注入有三条路，从窄到宽：

1. `Environment=KEY=value`，只给这一个 unit；
2. `EnvironmentFile=/etc/grafana/grafana.env`，敏感项单独放一份 600 的文件（这份 unit 第 10 行就是）。**没有 `-` 前缀时，文件不存在服务直接起不来**；写成 `EnvironmentFile=-/path` 才是「缺了也继续」。仓库里两边都有真实用法：`/etc/grafana/grafana.env` 由安装脚本在缺的时候先建好再 `chmod 600`（`deploy/ops/install-ops.sh:150`、`:162`），所以它可以不加 `-`；
3. drop-in：`/etc/systemd/system/<unit>.d/*.conf`，只写要覆盖的那几行，不动原来的文件。仓库注入诊断令牌走的就是这条（`deploy/ops/install-ops.sh:124` 用 heredoc 写 `/etc/systemd/system/paideia.service.d/ops.conf`，路径变量在 `:120`）——好处是包升级时那份 unit 被覆盖掉，你的补充还在。

## 依赖行：`After=` 不拉起对方

这是本文最长的一节，因为仓库里 11 份 unit 有 9 份踩了同一个形态。下面是我在 Windows 上用 PowerShell 对 `deploy/ops/*.service` 做的一次静态对账（真跑，脚本放 `%TEMP%`，11 份 unit 的输出全抄在下面；最后一列判断的是：`After=` 里的 target 有没有被同一个 unit 的 `Wants=` 一起拉起）：

```text
PS> pwsh -NoProfile -File $env:TEMP\unit-audit.ps1
unit                      User              user_ok ReadWritePaths                    rw_ok EnvFile                  env_ok AfterTarget
----                      ----              ------- --------------                    ----- -------                  ------ -----------
alloy.service             alloy             ok      /var/lib/alloy                    ok                             -      network-online.target=NO-PULL
blackbox-exporter.service blackbox_exporter ok                                        -                              -      network-online.target=NO-PULL
grafana.service           grafana           ok      /var/lib/grafana /var/log/grafana ok    /etc/grafana/grafana.env ok     network-online.target=NO-PULL
loki.service              loki              ok      /var/lib/loki                     ok                             -      network-online.target=NO-PULL
mysqld-exporter.service   mysqld_exporter   ok                                        -                              -      network-online.target=NO-PULL
node-exporter.service     node_exporter     ok      /var/lib/node_exporter            ok                             -      network-online.target=NO-PULL
ops-textfile.service                        -                                         -                              -      -
paideia-backup.service                      -                                         -                              -      -
prometheus.service        prometheus        ok      /var/lib/prometheus               ok                             -      network-online.target=NO-PULL
redis-exporter.service    redis_exporter    ok                                        -                              -      network-online.target=NO-PULL
tempo.service             tempo             ok      /var/lib/tempo                    ok                             -      network-online.target=NO-PULL
```

9 份 unit 全写着 `After=network-online.target`，没有一份写 `Wants=network-online.target`。剩下两行是 `-`：`ops-textfile` 与 `paideia-backup` 都是 `Type=oneshot` 的一次性单元，没有 `User=`，`paideia-backup` 等的还是 `mysql.service` 这个真单元而不是 target——这一列只判 target。

`After=` 的语义是「如果这两个单元都要启动，让我排在它后面」——`systemd.unit(5)` 写得很直白：`Before=`/`After=` 生效的前提是 *and both units are being started*。它不负责把对方拉进这次启动事务。拉人是 `Wants=`/`Requires=` 的活，而需求类依赖**不决定顺序**（同一份文档：requirement dependencies do not influence the order）。两件事必须成对写：

```ini
[Unit]
Wants=network-online.target
After=network-online.target
```

所以那 9 行 `After=` 什么时候算数？只有当别的单元（或发行版的 preset）已经把 `network-online.target` 拉进这次启动时才算数。要确认就查谁 want 它：

```text
# 标准形态，未实跑
$ systemctl list-dependencies --reverse network-online.target
network-online.target
● └─apt-daily.service
```

（这里拿 Debian/Ubuntu 上 apt 自己的定时任务举例；具体是谁 want 它在各发行版、各安装方式下都不一样，以你机器上的输出为准。看着它为空，就说明那 9 行 `After=` 里其实一行都没生效。）

顺带解释这份 unit 第 3 行另一半为什么没问题：`After=network-online.target prometheus.service` 里的 `prometheus.service` 自己 `WantedBy=multi-user.target` 且已 enable，开机时它本来就在这次启动里，所以排序排得上。同一个 `After=`，一个目标在事务里、一个不在，行为完全不同。

再往下还有三个真实差异，照抄会出事：

- **`Requires=` 指向不存在的单元，这次启动事务根本建不起来**（报 `Unit xxx.service not found`）；`Wants=` 指向不存在的单元只在日志里留一条警告，本单元照起。所以下面的发行版差异不是洁癖问题。
- **数据库单元的名字是发行版给的**：Debian/Ubuntu 的 `mysql-server` 包提供 `mysql.service`，RHEL 系提供 `mysqld.service`。把 `After=mysql.service` 抄到 RHEL 上，等的是一个永远不存在的单元；Debian 上默认装的若是 MariaDB，名字又是 `mariadb.service`。写之前先 `systemctl list-unit-files | grep -i -e mysql -e mariadb` 看真名。
- **`network-online.target` 由谁「点亮」，取决于装了哪个 wait-online 实现**：`NetworkManager-wait-online`、`systemd-networkd-wait-online`、`ifupdown-wait-online` 都行，但装哪个、默认 enable 与否是发行版和安装方式决定的。它表示的是「有人报告网络已就绪」，不是「你的上游服务能连上了」——这两件事别混。

装包命令同理，Debian 系 `apt`、RHEL 系 `dnf`，本文不展开（第 11 关）。unit 文件覆盖不要把 `/usr/lib/systemd/system/` 里包自带的改掉——包升级会覆盖回去；要么在 `/etc/systemd/system/` 放同名文件，要么用 drop-in。

## 出问题先看哪

坏掉的服务基本只有三种脸：起不来、起来又死、起来了但不干活。三种都是先看同样的三处，按信息量从大到小：

```text
# 标准形态，未实跑
$ systemctl status grafana.service -l --no-pager     # 状态 + 最后 10 行日志 + 退出码
$ journalctl -u grafana.service -n 100 --no-pager    # 完整最后 100 行（第 13 关）
$ journalctl -u grafana.service -p err -b            # 本次开机以来 error 及以上
```

`status` 里那行 `status=203/EXEC` 的斜杠前面是 systemd 自己的退出码，后面是它的名字。常见几个：

| `status=` 看到什么 | 实际含义 | 下一步 |
| --- | --- | --- |
| `203/EXEC` | 找不到 `ExecStart=` 的可执行文件，或它没有执行权限 | `ls -l` 那个绝对路径；注意 `--homepath` 之类的参数不能写在路径前面 |
| `200/CHDIR` | `WorkingDirectory=` 指到了不存在的目录 | 改 `WorkingDirectory=`，或先用 `ExecStartPre=` 建目录 |
| `217/USER` | `User=` 指定的账号不存在 | `id grafana`；安装脚本里 `useradd --system` 那一行漏了 |
| `226/NAMESPACE` | 沙箱相关的挂载准备失败（`ProtectSystem=`、`ReadWritePaths=` 这一类） | 先看这些路径在不在、属主对不对 |
| `start request repeated too quickly` | 短时间内的启动次数撞上了限速，单元被钉在 `failed` | 先修根因，再 `systemctl reset-failed` 解锁。不解锁怎么改都不重启 |

「起来了但不干活」不在这个表里：`Active:` 是 `active (running)`，但端口没绑上、上游连不上、日志里一行业务输出都没有。这类只能靠端口和日志对照，也就是前面 `ss -ltnp` 那一步。

还有一条最容易被漏掉、也最反直觉的：**`Type=simple` 下 `systemctl start` 可能报了成功，服务其实是死的**。`systemd.service(5)` 在 `Type=` 的 `simple` 一节里明说了这一点——`simple` 会在 `fork()` 之后就认为启动完成，此时子进程还没 `execve()`，所以「`User=` 的账号不存在」「可执行文件不存在」这类准备阶段的失败，`start` 命令照样可能返回 0。文档给的建议是长驻服务用 `Type=exec`：它要等 `execve()` 成功才算启动完成，这类错误会直接报失败。判断依据永远是 `status` 和 `journalctl`，不是 `start` 的返回码。

`Restart=` 也要看清楚：`on-failure` 只在**非零退出、被信号杀掉、超时、OOM** 这几种情况下重来（`systemd.service(5)` 的 Table 1 是完整对照表）；干净退出不会重启，`systemctl stop` 停掉的也不会重启。而 `RestartSec=` 的默认值是 100ms——这解释了为什么「启动即崩」的服务会在一瞬间堆出一大片日志，然后撞上限速。这份 unit 显式写了 `RestartSec=5`，5 秒一拍，翻日志时才看得清。

## 加固：先给它划边界

这份 unit 从 17 行到 21 行是五条白送的约束，不改一行代码就能上：

- `NoNewPrivileges=true`：进程不能通过 setuid 提权。
- `PrivateTmp=true`：`/tmp`、`/var/tmp` 换成私有挂载，看不见别人的临时文件。
- `ProtectHome=true`：`/home`、`/root`、`/run/user` 对它不可见。
- `ProtectSystem=strict`：整个文件系统对它只读。
- `ReadWritePaths=/var/lib/grafana /var/log/grafana`：上面那条的唯一例外清单。

`ProtectSystem=strict` 和 `ReadWritePaths=` 必须成对理解。`systemd.exec(5)` 的原话是：开了 strict 之后「为了允许服务写某些目录，必须用 `ReadWritePaths=` 把它们加入白名单」。也就是说，加了 strict 却没补白名单，最先坏的不是启动，是**写日志和数据目录**——文件系统对它是只读的，`Permission denied` 或者 `Read-only file system` 会出现在业务日志里。这份 unit 的白名单正好对应 Grafana 的两处写入点：数据目录和日志目录。

还有一件配套的事：`ReadWritePaths=` 只是「允许写」，systemd 不会替你建这些目录、也不会改属主。所以安装脚本里一定有这两行（`deploy/ops/install-ops.sh:72`、`:73`）：

```bash
mkdir -p /var/lib/grafana /var/log/grafana
chown -R grafana:grafana /var/lib/grafana /var/log/grafana
```

`MemoryMax=512M` 是另一条：它写进 cgroup 的内存上限，超了内核会在这一组进程里 OOM kill，而不是把整台机器拖死。代价是 unit 会进 `oom-kill` 状态，`Restart=on-failure` 接得住它（`systemd.service(5)` 的表里 OOM 一行对 `on-failure` 是打勾的）。

## 动手

下面这份 unit 改错了三个地方，你看能不能一次说清每一处会报什么。

```code-challenge
{
  "id": "linux-12-grafana-unit-bug",
  "title": "这份 unit 起不来，错在哪",
  "kind": "bug",
  "language": "ini",
  "code": "[Unit]\nDescription=Grafana（Paideia 运维面板）\nAfter=network-online.target\n\n[Service]\nType=simple\nUser=grafana\nExecStart=grafana server --homepath=/usr/share/grafana --config=/etc/grafana/grafana.ini\nRestart=on-failure\nProtectSystem=strict\n\n[Install]\nWantedBy=multi-user.target\n",
  "bugLine": 8,
  "explain": "`ExecStart=` 的首参数必须是绝对路径，写成 `grafana` 会在启动时报 `status=203/EXEC`——systemd 不查 PATH。顺带两处：第 3 行只写了 `After=` 没写 `Wants=`，开机时 `network-online.target` 若没人拉进事务，这一行等于没写；第 10 行 `ProtectSystem=strict` 之后没有任何 `ReadWritePaths=`，Grafana 写 `/var/lib/grafana` 与 `/var/log/grafana` 会被只读挡掉。"
}
```

想继续练就把这份文件写到 `/etc/systemd/system/`，`mkdir -p` 好两个目录、`chown` 好属主，`systemctl daemon-reload` 之后再 `enable --now`。这一步我没法在 Windows 上替你验。

## 检查一下

```quiz
{"id":"linux-12-quiz","title":"systemd 三问","questions":[
 {"question":"`systemctl start grafana.service` 返回 0，`systemctl status` 却是 failed。最可能是哪种情况？","options":["unit 文件写错了，start 不可能返回 0","`Type=simple` 在 fork 之后就认为启动完成，准备阶段（账号、可执行文件）的失败还没暴露","必须先把 `[Install]` 段删掉才能 start"],"answer":1,"explain":"`systemd.service(5)` 的 `Type=simple` 一节写明：simple 在 `fork()` 后即认为启动完成，所以 `User=` 的账号不存在、可执行文件缺失这类失败，`start` 仍可能报成功。要这类错误直接失败，用 `Type=exec`；判断依据永远是 `status` 和 `journalctl`。"},
 {"question":"unit 里只有 `After=network-online.target`，没有 `Wants=`。这一行什么时候才真的起作用？","options":["一直起作用，`After=` 会自动把 target 拉起来","完全不起作用，`After=` 只能配合 `Before=` 使用","只有当别的单元已经把 `network-online.target` 拉进这次启动事务时才起作用"],"answer":2,"explain":"`Before=`/`After=` 只管顺序，前提是两个单元都在启动中；拉人是 `Wants=`/`Requires=` 的事，而需求类依赖不决定顺序。`systemctl list-dependencies --reverse network-online.target` 能看出谁 want 它。"},
 {"question":"改了 unit 文件里的 `ExecStart=`，`systemctl restart` 之后报错一字未变。先怀疑什么？","options":["没 `systemctl daemon-reload`，管理器用的还是内存里的旧配置","内存不够，进程没起来","`restart` 不支持改过的 unit，必须重启机器"],"answer":0,"explain":"unit 文件在管理器启动时被读入并缓存，改完要 `daemon-reload` 才会重新读盘；只 `restart` 起来的是缓存里那份旧配置，症状就是报错一模一样。"}
]}
```

## 还差的

- 我在这台机器上验不了任何运行期行为：`Type=simple` 下 `start` 报成功而服务已死、`ReadWritePaths=` 指向不存在的目录时报哪一条错，这两条的结论都来自文档，不是实测回显。第 226/NAMESPACE 那一行同样只是「这一类错误」的归纳。
- 仓库那 9 份 unit 现在能跑通，不代表 `After=network-online.target` 生效了——开机时到底有没有谁把那个 target 拉进事务，我查不了。要确定得在真机上 `systemctl list-dependencies --reverse network-online.target`，并 `systemd-analyze plot` 看关键路径。
- 发行版只覆盖了 Debian/Ubuntu 与 RHEL 系两条差异（`mysql.service` vs `mysqld.service`、`apt` vs `dnf`）。Alpine 用 OpenRC，整套 systemd 概念不适用；容器里一般没有 PID 1 的 systemd，`systemctl` 直接不可用。
- 没验的是「包升级会不会把 enable 状态改回去」：`install-ops.sh` 每次跑都会 `disable --now grafana-server.service`，但 `apt upgrade grafana` 不一定触发这个脚本，两个单元再次并存的可能性我没有排除。
- 本文那道题只在纸上改。那份 unit 我没在真机装上跑过，所以「改成什么样能起来」只有文档依据；要真验，得在 Debian 12 上装 systemd、建 `/var/lib/grafana`、`daemon-reload` 后 `enable --now`。

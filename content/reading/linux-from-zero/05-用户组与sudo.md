---
title: 用户、组与 sudo
category: linux-from-zero
order: 5
minutes: 18
level: beginner
status: published
---

## 同一个 `600`，root 能读，服务读不到

```text
$ sudo -u mysqld_exporter cat /etc/paideia-backup/exporter.cnf
cat: /etc/paideia-backup/exporter.cnf: Permission denied

$ sudo cat /etc/paideia-backup/exporter.cnf
[client]
user=paideia
password=（省略）
```

同一个文件，同一个 `600`。root 读得到，`mysqld_exporter` 读不到。

我原先的理解是「`600` 就是只有 root 能读」——按这个理解，上面两行应该都成功。错的。

`600` 是三个人的三个位：属主 `rw-`、组 `---`、其他人 `---`。**能不能读，取决于属主那一栏写的是谁**，跟「这文件重不重要」没关系。而 root 能读，跟这三位也没关系：euid 为 0 的进程带 `CAP_DAC_OVERRIDE`，DAC 检查整个被绕过（执行位是例外——root 执行一个文件，仍要求三个 x 位里至少有一个）。

这一段是**标准形态**：本机是 Windows，我没有 Linux 可跑，命令形状和 `cat:` 那行报错是按 coreutils 的输出写的，不是实测。下同，凡是 Linux 回显都只说形态。

我没见过这台机器上 `mysqld_exporter` 自己的日志原文，所以不编它的报错行。能确定的是内核那一层：同一个 inode，root 的 `open(2)` 成功，`mysqld_exporter` 的 `open(2)` 拿到 `EACCES`。

「属主该写谁」这件事，仓库里有一份现成的对照。`deploy/ops/install-ops.sh` 里四个 `chmod 600`，只有一个配了 `chown`：

```bash
# :140  备份脚本里的 mysqldump 用
chmod 600 /etc/paideia-backup/client.cnf
# :147-148  给 mysqld_exporter 进程自己用
chmod 600 /etc/paideia-backup/exporter.cnf
chown mysqld_exporter:mysqld_exporter /etc/paideia-backup/exporter.cnf
# :162  给 systemd 读
chmod 600 /etc/grafana/grafana.env
```

三个文件都装着口令，敏感程度没有差别。差别在**谁打开它**。

这两个数我用 PowerShell 数过（真跑，不是照着注释复述）：

```text
$ (Get-ChildItem deploy\ops\*.service).Count
11
$ (Get-ChildItem deploy\ops\*.service | Select-String -Pattern '^User=').Count
9
```

11 个 unit，9 个写了 `User=`；4 处 `chmod 600`，1 处 `chown`。两个数一摆，问题就从「口令文件怎么保护」变成「另外三个为什么不换属主」。

## 先问「谁打开它」，再问「要不要 chown」

同一台机器上的四个 600 文件，答案不一样，因为打开它们的进程不一样：

| 文件 | 谁打开 | 打开时的身份 | 属主该是谁 |
| --- | --- | --- | --- |
| `client.cnf` | `paideia-backup.sh` 里的 `mysqldump --defaults-extra-file` | `paideia-backup.service` 没写 `User=`，所以是 root | root，`chmod 600` 就够 |
| `exporter.cnf` | `mysqld_exporter` 进程自己（`--config.my-cnf`） | unit 的 `User=mysqld_exporter` | 必须是 `mysqld_exporter`，否则 `EACCES` |
| `grafana.env` | systemd（PID 1）读 `EnvironmentFile=` | root，读完之后才切到 `User=grafana` | root，`chmod 600` 就够 |
| `hook.token` | 安装脚本里 root 的 `tr` | root | root，`chmod 600` 就够 |

第二行是全文那个坑，第三行是最容易误判的一行：`grafana.service` 里写着 `User=grafana`，但 `EnvironmentFile=` 不是 grafana 读的，是 systemd 在主进程里以 root 读进来、再把变量塞给服务的。所以 `grafana.env` 属主留在 root 没问题——**这一点在 `deploy/ops/grafana.service:10` 和 `:7` 这两行之间就能读出来，不需要试**。

判断顺序因此是反的：不是「文件敏感 → `chmod 600`」，而是「谁打开 → 它当时的 uid 是谁 → 属主是不是它」。

## uid 是数字，名字只是翻译

内核不认识 `mysqld_exporter` 这个字符串，只认 uid/gid。名字来自 `passwd` 数据库：

```text
$ getent passwd mysqld_exporter
mysqld_exporter:x:997:997::/home/mysqld_exporter:/usr/sbin/nologin
```

（`997` 是占位，真实值由 `/etc/login.defs` 的 `SYS_UID_MIN`/`SYS_UID_MAX` 决定，`install-ops.sh:53` 用 `useradd --system` 从那个区间取号。别背区间——它写在文件里，而且 Debian 与 RHEL 系的默认文件不一样。）

七段用冒号分开，跟这篇有关的是四段。按「看到什么现象去读哪一段」排：

| 现象 | 去读哪一段 |
| --- | --- |
| unit 里写了 `User=mysqld_exporter` 却起不来，`status=217/USER` | 第 1 段，名字不存在（第 12 关有这张表） |
| `ls -l` 的属主栏显示成一串数字 | 第 3 段，这个 uid 没有对应名字 |
| `su - mysqld_exporter` 一句话不说就直接退出 | 第 7 段，shell 是 `/usr/sbin/nologin` |
| 第 6 段写着 `/home/mysqld_exporter`，目录却不存在 | `--no-create-home` 只跳过建目录，不改这个字段 |

第 2 段的 `x` 也不是密码，只是占位，哈希在 `/etc/shadow`——`/etc/passwd` 是全局可读的，正因为里面没有哈希。

顺带一句：`getent` 走 NSS，`cat /etc/passwd` 只读本地文件。账号在 LDAP/sssd 里时，`cat` 找不到、`id` 能解析，这不是文件坏了。

三个查身份的命令，注意哪个说谁：

```text
$ whoami
lzf
$ id
uid=1000(lzf) gid=1000(lzf) groups=1000(lzf),27(sudo),100(users)
$ id mysqld_exporter
uid=997(mysqld_exporter) gid=997(mysqld_exporter) groups=997(mysqld_exporter)
```

`id` 不带参数说的是**我**，带参数说的是**它**。这两个人的组列表可以完全不同，而「我能不能读这个文件」跟「它能不能读」是两个问题——`id mysqld_exporter` 不需要提权，因为 `passwd`/`group` 本来就全局可读；能查到一个人的 uid，不等于能读他的文件。

`groups=` 那一串是主组加所有附加组。授权时内核按这个顺序判：euid 等于文件属主就只看属主位；否则看 egid 和附加组是否命中文件所属组；都不命中才看其他人位。

## 加组救不了 `600`

按上面这个判定顺序，`600` 的组位是 0，所以**加进多少个组都拿不到权限**——没有可授予的位。这就是 `install-ops.sh:148` 选择 `chown` 而不是 `chgrp` 的原因：对着一个 `600` 的文件，`chgrp` 是空操作。

想让「一组进程读同一个文件」，位得先改：`640`（组可读）或 `660`（组可读写），并且那个进程的组列表里确实有它。这是第 04 关讲过的事，这里只补一句它在这种场景下的后果：**`chmod` 和 `chown` 不是两种风格的写法，它们在回答两个不同的问题**。

组在别处是真的管用的。同一份脚本第 56 行：

```bash
# Alloy 读 journald 必须在这两个组里；缺了不会报错，只是**一条日志都采不到**。
usermod -aG adm,systemd-journal alloy
```

这里组能用，因为 `/var/log/journal` 的位给了组读权限，而 alloy 进程直接去读那些文件。失效形态也写在那行注释里：不报错、不退出，只是什么都采不到。这是组授权和属主授权最大的体验差别——属主不对会当场 `EACCES`，组不对可以安静地空转。

两个操作上的坑：

- `-aG` 里的 `-a` 不能省。只写 `-G` 是「把附加组设置成这些」，会覆盖原列表；如果被改的账号原来在 `sudo` 组里，这一下就没了提权能力。
- 新的组关系对**已经在跑的会话**和**已经在跑的服务**都不生效。当前 shell 要 `newgrp` 或重新登录，服务要 `systemctl restart`。改完 `usermod` 立刻 `id`，看到的还是旧的。

服务最终拿到的身份 = unit 里的 `User=`/`Group=` 加上 `/etc/group` 里给这个账号列的附加组。想验证又不改 unit，就用 `sudo -u mysqld_exporter id`（下一节说它为什么比 `su -` 好用）。

## `umask 077` 在 `printf` 前，`chmod 600` 在 `if` 后

那两行不是重复，它们管的是两个分支：

```bash
if [ ! -f /etc/paideia-backup/exporter.cnf ]; then
    umask 077
    printf '[client]\nuser=paideia\npassword=%s\nhost=127.0.0.1\n' ... > /etc/paideia-backup/exporter.cnf
fi
chmod 600 /etc/paideia-backup/exporter.cnf
chown mysqld_exporter:mysqld_exporter /etc/paideia-backup/exporter.cnf
```

文件不存在时，`umask 077` 让它在**创建的那一瞬间**就是 `600`。少了这一行，默认 `umask 022` 会给它 `644`，从 `open(2)` 到后面那句 `chmod 600` 之间有一个窗口，另一个用户在这段时间里打得开。

文件已存在时（脚本是幂等的，重跑就是这条分支），`if` 里那几行根本不执行，只剩 `chmod 600` 兜底。所以 `umask` 管新建、`chmod` 管重跑，两条都留着才覆盖全。

`chmod` 和 `chown` 谁写前面不影响结果——root 改属主、改位都不受限制。真正要守的顺序是「两条都在服务启动之前」。

## 验证要换成那个身份，不然你验的是 root

这正是 `install-ops.sh` 那种脚本最容易骗人的地方：`sudo chmod 600` 之后，`sudo cat` 一定成功，而你刚验的是 root 能不能读。

```text
$ su - mysqld_exporter
This account is currently not available.

$ sudo -u mysqld_exporter cat /etc/paideia-backup/exporter.cnf
[client]
user=paideia
password=（省略）
```

`su -` 要启动一个登录 shell，撞上 `nologin` 就被挡住——`install-ops.sh:53` 建账号时正是用 `/usr/sbin/nologin` 禁交互登录。`sudo -u` 只 `exec` 那一条命令，不起登录 shell，所以 `nologin` 拦不住它。**「shell 是 nologin」不等于「这个账号不能借来验证」**。

`sudo -u` 依赖 sudoers 里允许 Runas（把自己放在 `sudo` 组里的默认配置是 `(ALL : ALL) ALL`，就不用管）。这台机器上 `sudo -l` 能看自己有什么：

```text
$ sudo -l
User lzf may run the following commands on web-01:
    (ALL : ALL) ALL
```

能不能把「只允许重启这一个服务」写窄？能，这与本文的场景对得上：

```text
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart paideia
```

它只匹配这一条命令加这一个 unit；`systemctl restart sshd` 不匹配。但有两点要说清，否则「写窄了」会变成错觉：

- 通配符的 `*` 能吃掉多个参数。写成 `/usr/bin/systemctl restart *` 就等于允许重启**任意** unit；写成整个 `/usr/bin/systemctl` 更糟——`systemctl edit`/`link` 能落下一个你写的 unit 文件，再启动它就是 root 进程。`sudo vi` 同理（`:!sh` 就是 root shell）。规则放宽到能启动任意程序时，等价于把 root 交出去，`NOPASSWD` 只是让它更方便。
- 规则必须用 `visudo` 改，因为它在保存时校验语法。写坏一行不是「那条规则失效」，是 `sudo` 整体拒绝执行——包括 root 自己用 `sudo`。

还有两个与「借身份」有关的默认值，别当保证：

```text
$ sudo env | grep -E '^(HOME|PATH)='
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HOME=/home/lzf
```

- `sudo 命令` 要的是**自己**的密码（`sudoers` 里可以改成 `rootpw`/`targetpw`，那就变），密码在默认 15 分钟（`timestamp_timeout`，可改）内不重复问。
- Debian/Ubuntu 的默认 sudoers 带 `Defaults env_reset` 和 `secure_path=`，所以 `PATH` 会被换成上面那串，不是「继承你的环境」。这就是「我 `~/.local/bin` 里的命令在 `sudo` 下找不到」的原因——把「sudo 保留环境变量」当结论，位置就找错了。
- `sudo -i` 换的是登录 shell，`HOME` 变 `/root`：进去干半小时活，`~/app` 指的是 `/root/app`。只想借身份改一个文件，用 `sudo 命令`；要连续几条，用 `sudo -s` 比 `-i` 少一个 `HOME` 的意外。

## 跑一遍

以下全部是标准形态，我没有 Linux 可跑，逐条按顺序在你自己机器上执行即可（前提：`mysqld_exporter` 账号已存在、`exporter.cnf` 已存在）。

1. `getent passwd mysqld_exporter` —— 有输出就说明名字能解析；第 7 段是 `nologin` 属正常。
2. `getent group mysqld_exporter` —— unit 里写了 `Group=mysqld_exporter`，这个组必须存在，否则服务起不来。
3. `sudo -u mysqld_exporter cat /etc/paideia-backup/exporter.cnf` —— 这一步就是结论。成功则后面不用做；`Permission denied` 才继续。
4. `sudo ls -l /etc/paideia-backup/exporter.cnf` 和 `sudo -u mysqld_exporter id` —— 前者的属主栏和后者的 uid 对不上，就是属主问题；对得上却仍拒，去看第 04 关的目录 `x` 位。
5. `sudo chown mysqld_exporter:mysqld_exporter /etc/paideia-backup/exporter.cnf`，然后重跑第 3 步。
6. `sudo systemctl restart mysqld-exporter`，`systemctl status mysqld-exporter --no-pager` —— 第 3 步通过之后再启动，起失败时你已经排掉了权限这一层。

## 动手

```code-challenge
{"id":"linux-05-user-order","kind":"order","title":"把一次「600 文件属主不对」的修复顺序排对","lines":[
  "sudo -u mysqld_exporter cat /etc/paideia-backup/exporter.cnf",
  "sudo systemctl start mysqld-exporter",
  "sudo systemctl stop mysqld-exporter",
  "sudo chmod 600 /etc/paideia-backup/exporter.cnf",
  "sudo chown mysqld_exporter:mysqld_exporter /etc/paideia-backup/exporter.cnf"
],"answer":[2,4,3,0,1],
 "explain":"先停服务，再换属主、确认位仍是 600，然后用 sudo -u 以目标身份读一次——这一步是唯一有意义的验证，用 root 读不算；最后才启动。换属主放在权限位之前或之后都行（root 不受位限制），但两件都要在重启之前做完。"}
```

## 检查一下

```quiz
{"id":"linux-05-quiz","title":"身份与提权","questions":[
 {"question":"一个文件是 600、属主是 root，由 User=mysqld_exporter 的服务去读，结果是什么？","options":["能读，因为服务由 systemd 启动","读不到，因为 600 的组位和其他位都是 0，属主也不是它","第一次能读，之后被缓存拒绝"],"answer":1,"explain":"600 只给属主 rw 两种权限，组位与其他位都是 0。属主是 root，所以只有 euid 为 0 的进程能过——mysqld_exporter 既不在属主位置，也没有可用的组位。"},
 {"question":"要让 mysqld_exporter 读到那个 600 文件，改动最小且不削弱保护的做法是？","options":["把 mysqld_exporter 加进 root 组","把文件改成 644","把属主 chown 成 mysqld_exporter，位保持 600"],"answer":2,"explain":"组位是 0 时加组不产生任何权限；644 会把口令给全机器读。换属主之后仍然只有「一个账号」能读，只是那个账号换了。"},
 {"question":"为什么 sudo cat 能读、sudo -u mysqld_exporter cat 读不到？","options":["root 的 euid 0 带 CAP_DAC_OVERRIDE，绕过了 DAC 检查","root 这个用户名被写进了文件的属主栏","sudo 会在执行前临时把文件属主改成自己"],"answer":0,"explain":"属主栏只有一个值，写的是 root 的 uid 或者别的数字，跟「用户名在不在」无关。root 能过是因为用了 euid 0 的能力位，不是因为它在这个文件的三个位里排上了位置；sudo -u 换成了目标 uid，能力位就没了。"}
]}
```

## 还差的

1. 这篇里所有 Linux 回显都是标准形态，一条都没有实跑（本机 Windows，没有 `/etc/passwd`，没有 `sudo`）。`systemctl` 提示行的措辞随 systemd 版本变，`sudo -l` 的块状输出随 sudo 版本变，我按当前版本的形态写的，没逐版本核。
2. ACL 完全没写。真要「两个不同的服务账号读同一个 600」，`setfacl` 是一条路，但它和 `600`、和打包/备份时 ACL 会不会丢，以及 `getfacl` 输出的正确读法，我都没验证。
3. SELinux / AppArmor 没写。enforcing 状态下，属主和三位都对也可能被策略拒（去读 `/etc` 下的东西就是典型），仓库这套装机脚本也没有处理策略这一步。
4. 容器和 NFS 的 uid 映射没写。容器里的 root 落到宿主机是另一个 uid，`chown` 出来的属主对不上；NFS 上 `chown` 会被 `root_squash` 直接拒。这两处「uid 不是 uid」的情况，本文的判定顺序要重来一遍。
5. `useradd --system` 是否连带建同名组，取决于 `/etc/login.defs` 的 `USERGROUPS_ENAB` 和发行版。我只写了用 `getent group` 去验，没在两种发行版上各确认一次；而 unit 里既然写了 `Group=`，组不存在就是起不来，这个缺口值得自己补测。

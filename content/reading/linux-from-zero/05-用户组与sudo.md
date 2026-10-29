---
title: 用户、组与 sudo
category: linux-from-zero
order: 5
minutes: 20
level: beginner
status: published
---

## 这一关在解决什么

为什么服务器上不直接用 root？`sudo` 和 `su` 差在哪？`id` 输出里那些组是怎么来的？这一关把"谁是这台机器上的谁"讲清楚，顺带解释一个很常见的怪现象：**同样的命令，别人能跑你跑不了**。

## 为什么要有普通用户

因为 **root 不受权限约束**。`rm -rf /` 在 root 身份下会真的开始删，而系统不会问一句。用普通用户 + `sudo` 的组合，是为了在两件事上留出一道闸：

- **误操作**：绝大多数破坏性命令在普通用户下会直接失败（"Permission denied"），你多了一次反悔机会。
- **可追溯**：`sudo` 执行的每条命令都会进日志，带上是谁、什么时候、跑了什么。共用 root 口令时，日志里只有一个 `root`，出事查不到人。

## 讲解

### 用户是谁：uid 与 gid

内核不认识用户名，只认识数字：**uid**（用户编号）和 **gid**（组编号）。`/etc/passwd` 是"名字 → 数字"的对照表。

```text
$ getent passwd lzf
lzf:x:1000:1000:LZF,,,:/home/lzf:/bin/bash
```

七个字段用冒号分隔：

| 字段 | 例子 | 含义 |
| --- | --- | --- |
| 用户名 | `lzf` | 登录名 |
| 口令占位 | `x` | 真正的哈希在 `/etc/shadow`，这里只是占位 |
| **uid** | `1000` | 用户编号，**root 固定是 0** |
| **gid** | `1000` | 主组编号（还有附加组，见 `id`） |
| 描述 | `LZF,,,` | 全名、房间号之类，可空 |
| 家目录 | `/home/lzf` | 登录后的默认目录 |
| 登录 shell | `/bin/bash` | 登录时启动的程序；`/usr/sbin/nologin` 表示**禁止登录** |

uid 的分布有个惯例，值得知道：

| 范围 | 谁 | 常见例子 |
| --- | --- | --- |
| `0` | root | 只有它 |
| `1–999` | 系统账号 | `www-data`、`mysql`、`sshd`，用来跑服务，通常不能登录 |
| `1000+` | 普通用户 | 你 |

所以看到 `cat /etc/passwd` 里一长串 `nologin` 的账号不要惊讶——那是各个服务自己的身份。

三个查身份的命令：

```text
$ whoami            # 我是谁（名字）
lzf
$ id                # 我是谁（uid/gid/所有组）
uid=1000(lzf) gid=1000(lzf) groups=1000(lzf),27(sudo),100(users)
$ groups            # 只列组
lzf : lzf sudo users
```

`id` 里的 `groups=` 是**主组 + 所有附加组**。判断"我有没有权限"时看这一行，而不是 `/etc/passwd` 里的 gid——附加组也生效。

### 组：一次给一批人授权

组的用处是**按组授权**，而不是按人。文件的属组是 `devs`、权限是 `rw-rw----`，那么 `devs` 组里所有人都有写权限；新来一个人只要 `usermod -aG devs`，不用改文件。

```text
$ sudo groupadd devs
$ sudo usermod -aG devs lzf        # -a 是追加！不加 -a 会覆盖掉其他附加组
$ id lzf
```

**`-aG` 里的 `-a` 必须写。** 只写 `-G` 会把用户的附加组**整组替换**掉——如果你在 `sudo` 组里，这一下就把自己从 `sudo` 组踢出去，从此提不了权（救法：单用户模式或另一台机器上改）。

新的组关系在**下次登录**生效。当前会话里 `id` 还看不到，要 `newgrp devs` 或重新登录。

### `su` 还是 `sudo`

两个都能让你变成 root 或别人，但机制完全不同：

| | `su` | `sudo` |
| --- | --- | --- |
| 全称 | substitute user | superuser do |
| 要谁的密码 | **目标用户**的（切 root 就要 root 口令） | **自己的**密码 |
| 生效范围 | 开一个完整的 shell | 只执行一条命令 |
| 日志 | 只记"某人 su 成了 root" | 记下**每一条**执行的命令 |
| 环境变量 | `su -` 载入目标用户的环境 | 默认保留大部分当前环境 |

所以人工运维正确姿势是 `sudo 命令`，而不是 `su -` 之后在里面泡一天。`su -`（带减号）才载入目标用户的环境变量；不带减号的 `su` 会让你带着自己的 `PATH` 和 `HOME` 干 root 的活，是各种"为什么文件跑到我家里了"的来源。

`sudo -i` 等价于 `su -` 但用你自己的密码：拿到一个 root 的登录 shell。**注意它的副作用**：

```text
$ echo $HOME
/home/lzf
$ sudo -i
# echo $HOME
/root
```

进去以后 `~` 变成了 `/root`，之前 `cd ~/app` 会跑到 `/root/app`。这就是"用 sudo -i 干了半天活，文件全在 /root 下"的成因。

### `sudoers`：谁能用 sudo 干什么

不想每次敲密码，或者想只允许某人执行某几条命令，改 `/etc/sudoers`。**永远用 `visudo` 改**，因为它在保存时检查语法——写错一行 `sudoers` 会让**所有人**都无法提权，包括 root 账号的 sudo。

```text
$ sudo visudo
```

里面常见的一行：

```text
%sudo   ALL=(ALL:ALL) ALL
```

四段读作：**`%sudo` 组** 在 **`ALL` 主机** 上 可以以 **`ALL` 用户:组** 身份 执行 **`ALL` 命令**。

几个真实需求对应的写法：

```text
# 只允许 deploy 重启一个服务，不用密码
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart paideia

# 允许运维组看日志、看进程
%ops ALL=(root) /usr/bin/journalctl, /usr/bin/ps
```

`NOPASSWD` 只该给**范围极窄**的命令。给 `NOPASSWD: ALL` 等于这个账号可以无密码提权，和把 root 口令给它没区别。

想确认自己有哪些 sudo 权限：

```text
$ sudo -l
User lzf may run the following commands on web-01:
    (ALL : ALL) ALL
```

### 服务用哪个身份跑

**每个进程都有一个 uid**，就是启动它的那个用户。所以：

- `systemctl start paideia` 起的服务，默认以 **root** 跑（单元里没写 `User=`）。
- 用 root 跑服务意味着一旦服务被攻破，攻击者直接是 root。正确做法是建一个专用系统账号，在 unit 里写 `User=paideia`（第 12 关有完整例子）。
- 服务账号的典型建法：

```text
$ sudo useradd --system --no-create-home --shell /usr/sbin/nologin paideia
```

`--system` 让 uid 落在 1–999，`nologin` 让它不能被交互登录，`--no-create-home` 表示不需要家目录。

### 谁登录过

排查"这台机器上现在谁在"：

```text
$ who            # 当前登录的会话
$ w              # 同上，多显示他们在干什么
$ last | head    # 最近的登录历史（含来源 IP）
$ lastb | head   # 登录失败记录（需要 root）
```

这些都读 `/var/log/wtmp`、`/var/log/btmp`。登录失败次数突然变多，通常意味着有人在爆破 SSH（第 16 关讲防护）。

## 动手

```code-challenge
{"id":"linux-05-user-order","kind":"order","title":"建一个能部署但不能乱来的账号","lines":[
 "sudo useradd -m -s /bin/bash deploy",
 "sudo passwd deploy",
 "sudo usermod -aG sudo deploy",
 "su - deploy",
 "id"
],"answer":[0,1,2,3,4],
 "explain":"先建用户（-m 建家目录），再设密码，再给附加组，最后用 `su -` 以该用户身份登录验证；`id` 输出里能看到 sudo 组才算成功。注意 `-aG` 的 -a 不能省。"}
```

## 检查一下

```quiz
{"id":"linux-05-quiz","title":"用户与提权","questions":[
 {"question":"`sudo` 要输入的密码是谁的？","options":["自己的","root 的","目标用户的"],"answer":0,"explain":"sudo 校验的是执行者本人的密码，这也是它比 su 更适合多人运维的原因：不用把 root 口令发给每个人。"},
 {"question":"`usermod -G devs lzf`（没写 -a）的后果是什么？","options":["lzf 的附加组被替换成只有 devs","devs 被删掉","没有任何效果"],"answer":0,"explain":"不带 -a 的 -G 是「设置附加组」，会覆盖原有列表。若 lzf 原来在 sudo 组里，这一下就失去了提权能力。"},
 {"question":"为什么服务不该用 root 跑？","options":["服务被攻破时攻击者直接拿到 root","root 跑的服务更慢","systemd 不支持 root"],"answer":0,"explain":"进程的权限等于启动它的用户。用专用低权限账号跑服务，即使被攻破也只损失那部分权限。"}
]}
```

## 常见误区

### 误区：`sudo` 就是"以 root 跑"

**你以为**：`sudo` 等价于切到 root。
**其实**：`sudo` 是按 `sudoers` 里的授权规则执行，可以限定成"只允许重启某个服务"。而且默认保留大部分自己的环境变量，`$HOME` 还是自己的（`sudo -i` 才会变成 `/root`）。很多"sudo 之后行为不一样"的怪事都源于环境差异。
**怎么验证**：`sudo env | grep -E 'HOME|PATH'` 与 `env` 对比；`sudo -i` 后再跑一次，`HOME` 会变成 `/root`。

### 误区：`/etc/passwd` 里有密码

**你以为**：这个文件名叫 passwd，应该存着口令。
**其实**：为了安全，密码哈希早搬到 `/etc/shadow` 了，`passwd` 第二位只是 `x`。`shadow` 只有 root 可读、`passwd` 全世界可读——所以"谁能读 passwd"不需要担心泄露密码。
**怎么验证**：`ls -l /etc/passwd /etc/shadow` 对比权限（644 vs 640 或 000）；`sudo cat /etc/shadow | head -2` 看真正的哈希字段。

### 误区：删用户会把它的文件一起删掉

**你以为**：`userdel deploy` 就干净了。
**其实**：`userdel` 默认**保留家目录和所有属于该 uid 的文件**。它们的属主会变成一串 uid 数字（因为没有对应名字），以后清理起来很麻烦。要一起删用 `userdel -r deploy`，而且执行前先 `find / -uid <uid>` 确认没有别的文件归属这个 uid——**uid 会被复用**，新用户可能继承旧文件的属主。
**怎么验证**：`userdel` 后跑 `ls -ln /home`，会看到数字 uid 的目录；`find / -xdev -uid 1001 2>/dev/null` 列出所有遗留文件。

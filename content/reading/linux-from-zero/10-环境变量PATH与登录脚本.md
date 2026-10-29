---
title: 环境变量、PATH 与登录脚本
category: linux-from-zero
order: 10
minutes: 22
level: beginner
status: published
---

## 这一关在解决什么

同一个命令，**你在终端里能跑，写进 systemd 就报 `command not found`**；`java -version` 正常，但 `sudo java` 找不到 java；`.bashrc` 里加的别名在脚本里不生效。

这些都是"环境变量什么时候被加载、被谁继承"的问题。这一关把 PATH 的查找机制和四类 shell 的启动文件讲清楚，以后这类问题能自己定位。

## 为什么有环境变量

因为程序不该把"这台机器的具体情况"写死在代码里：JDK 装在哪、数据库口令是什么、当前是开发还是生产。这些由**启动它的环境**提供，代码只读名字。

环境变量还有个特性：**它会被子进程继承**。你 `export A=1` 之后跑的任何程序都拿得到 `A`；但它只往下传，不往上传——子进程改了不影响父进程，这也是"我在脚本里 export 了，怎么外面没有"的原因。

## 讲解

### 查看与设置

```text
$ env | grep -E '^(PATH|HOME|LANG|JAVA_HOME)'
$ printenv PATH
$ echo $PATH
```

设置分两种，**区别很大**：

```text
$ NAME=value            # 只是当前 shell 的一个变量，不传给子进程
$ export NAME=value     # 导出成环境变量，子进程能继承
$ NAME=value ./app.sh   # 只对这一条命令生效，命令结束就没了
$ unset NAME            # 删掉
```

`export NAME=value` 和 `NAME=value` 之后的 `export NAME` 等价。要永久生效，就得写进启动脚本（下面讲）。

**最常见的用法是"只给这一条命令"**：

```text
$ SPRING_PROFILES_ACTIVE=prod java -jar app.jar
$ LANG=C sort a.txt
```

这种写法在临时验证时最安全，不会污染当前 shell。

### PATH：命令是怎么被找到的

你在终端敲 `ls`，shell 并不知道 `ls` 在哪。它做的是：**按 PATH 里列出的目录，从左到右找第一个名为 `ls` 的可执行文件**。

```text
$ echo $PATH | tr ':' '\n'
/usr/local/sbin
/usr/local/bin
/usr/sbin
/usr/bin
/sbin
/bin
```

三条推论：

- **顺序决定用哪个**。`/usr/local/bin` 在 `/usr/bin` 前面，所以你手工装在同名目录里的新版本会优先（这是"我把新版装到 /usr/local 覆盖了系统版"的机制）。
- **不在 PATH 里的命令只能用路径调用**。`./run.sh` 里的 `./` 就是"按路径直接执行"。这也是为什么当前目录**不在** PATH 里——如果在，你 `cd` 到一个恶意目录、敲 `ls` 就可能执行到别人放的同名文件。
- **同名命令冲突时**用 `type` 看清真相：

```text
$ type -a python3
python3 is /usr/local/bin/python3
python3 is /usr/bin/python3
$ which -a python3        # 只列可执行文件
$ command -v python3      # 给脚本用的写法，找不到返回非 0
```

`type` 还能识别别名和函数（`which` 不行），所以排查"我敲的到底是啥"用它最准。

往 PATH 里加目录（**注意顺序**）：

```text
$ export PATH=/opt/jdk21/bin:$PATH      # 放前面：优先
$ export PATH=$PATH:/opt/tools/bin      # 放后面：最后才找
```

写成 `PATH=/opt/jdk21/bin`（不接 `$PATH`）会把原来的值整个覆盖，之后 `ls`、`cat` 全找不到——这是新手最痛苦的一次翻车。

### 四类 shell 与它们的启动文件

bash 启动时读哪个文件，取决于它是**登录 shell 还是非登录、交互还是非交互**：

| 场景 | 例子 | 读什么 |
| --- | --- | --- |
| 交互式登录 shell | `ssh` 登录、`su -`、`bash -l` | `/etc/profile` → `~/.bash_profile`（或 `~/.bash_login`、`~/.profile`，取遇到的第一个） |
| 交互式非登录 shell | 在桌面打开终端、`bash` | `~/.bashrc` |
| 非交互 shell | `ssh host "命令"`、cron、脚本 | **什么都不读**（除非设了 `BASH_ENV`） |
| systemd 服务 | `systemctl start xxx` | **什么都不读**，环境完全由 unit 文件决定 |

这就是"命令行能用、服务不能用"的根因：**你在 `~/.bashrc` 里 export 的变量，systemd 根本看不见**。

Ubuntu/Debian 的 `~/.profile` 通常会主动 source `.bashrc`，所以登录 shell 也能拿到 `.bashrc` 里的设置，看起来"到处都生效"。但这是发行版的约定，不是 bash 的规则，别指望它在别处也成立。

顺序与位置：

| 文件 | 范围 | 用途 |
| --- | --- | --- |
| `/etc/profile` | 所有用户，登录时 | 系统级 PATH、语言环境 |
| `/etc/profile.d/*.sh` | 同上 | **放系统级环境变量的推荐位置** |
| `~/.bash_profile` / `~/.profile` | 单个用户，登录时 | 个人的登录初始化 |
| `~/.bashrc` | 单个用户，交互式 | 别名、提示符、个人 PATH |
| `/etc/bash.bashrc` | 所有用户，交互式 | 系统级别名 |

**实践建议**：

- 只跟"人怎么用终端"有关的（别名、`PS1`）→ `~/.bashrc`。
- 要影响服务的环境变量 → **别写 shell 脚本**，写 systemd 的 `Environment=` 或 `EnvironmentFile=`（第 12 关）。
- 想让 `ssh host "命令"` 也生效 → 写进 `/etc/environment`（由 PAM 读取，纯 `KEY=VALUE`，不支持变量展开）或在命令里显式带上。

### `sudo` 与 PATH：另一个坑

`sudo` 默认会把环境**清空重建**（`env_reset`），PATH 也换成 `secure_path`：

```text
$ echo $PATH
/opt/jdk21/bin:/usr/local/bin:/usr/bin:/bin
$ sudo sh -c 'echo $PATH'
/usr/sbin:/usr/bin:/sbin:/bin
```

所以"我自己能跑、sudo 之后 command not found"是同一个机制。三种解法：

```text
$ sudo env PATH=$PATH 命令            # 临时
$ sudo -E 命令                        # 保留当前环境（受 sudoers 的 env_keep 限制）
$ sudo /full/path/to/命令             # 最稳：直接给绝对路径
```

`sudo -E` 未必全保留，具体看 `sudo -l` 输出里的 `env_keep`。

### 其他几个会用到的变量

| 变量 | 作用 | 注意 |
| --- | --- | --- |
| `HOME` | 家目录 | `sudo -i` 后会变成 `/root`（第 05 关） |
| `LANG` / `LC_ALL` | 语言与字符集 | 容器里常是空，导致中文乱码；`export LANG=C.UTF-8` |
| `TZ` | 时区 | 不设就按系统时区；跨时区服务要显式设 |
| `JAVA_HOME` | JDK 根目录 | 只有依赖它的脚本才读，不是内核识别的 |
| `TMPDIR` | 临时目录 | 大文件解包要指到大盘，否则写满 `/tmp` |

`ulimit -a` 看进程资源上限（打开文件数、栈大小）。"Too many open files"就是 `ulimit -n` 太小——它是**每个进程**的限制，改法写在 unit 文件的 `LimitNOFILE=` 里对服务生效。

## 动手

```code-challenge
{"id":"linux-10-path","kind":"fill","title":"让 JDK 21 优先被找到","language":"bash",
 "template":"# 本会话生效，且不破坏原有 PATH\nexport JAVA_HOME=/opt/jdk21\nexport PATH=$JAVA_HOME/bin:{{0}}\n{{1}} -version",
 "options":[["$PATH","$JAVA_HOME","/bin"],["java","JAVA_HOME","$JAVA_HOME/bin/java"]],"answers":[0,0],
 "explain":"`$JAVA_HOME/bin:$PATH` 把新目录放在最前面，原有目录一个不丢；`export` 之后子进程（这里是 java）才拿得到。写成 `PATH=/opt/jdk21/bin` 会覆盖掉全部系统路径。"}
```

## 检查一下

```quiz
{"id":"linux-10-quiz","title":"环境三问","questions":[
 {"question":"`ssh host \"node -v\"` 报 command not found，但登录进去敲 `node -v` 正常，为什么？","options":["非交互非登录 shell 不读 .bashrc/.profile","node 只在登录时启动","ssh 不传 PATH"],"answer":0,"explain":"ssh 带命令时启动的是非交互 shell，bash 不读任何启动文件，PATH 只有默认值。这正是「服务里找不到命令」的同一机制。"},
 {"question":"`NAME=value` 与 `export NAME=value` 的差别？","options":["后者才让子进程看到","前者只在 root 下有效","后者会写进磁盘"],"answer":0,"explain":"不带 export 的变量只是当前 shell 的本地变量；export 才放进环境块，被 exec 出来的子进程继承。"},
 {"question":"`PATH=/opt/jdk21/bin` 这样写会怎样？","options":["原有 PATH 被整体覆盖，ls 等命令全部找不到","只是追加到末尾","需要重启才生效"],"answer":0,"explain":"PATH 是个普通字符串，赋值就是替换。必须写成 `PATH=/新目录:$PATH` 才保留原有目录。"}
]}
```

## 常见误区

### 误区：写进 `.bashrc` 的环境变量对所有场合都生效

**你以为**：`~/.bashrc` 是"全局配置"。
**其实**：它只对**交互式非登录 shell**生效。cron、systemd、`ssh host "cmd"`、IDE 里起的进程都不读它。这就是"IDE 里能跑、服务器上不行"的分界线。
**怎么验证**：`ssh localhost 'bash -c "echo \$MYVAR"'`（非交互）与登录后 `echo $MYVAR` 对比。

### 误区：`export` 一次，整个系统都变了

**你以为**：环境变量是系统级设置。
**其实**：它属于**进程**，子进程继承副本。你在终端 A 里 export，终端 B 里没有；你在子 shell 里改，父 shell 也没变。要"到处都有"，唯一可靠的位置是进程的启动配置：`/etc/profile.d/`（给登录 shell）、systemd unit（给服务）、`/etc/environment`（给 PAM 会话）。
**怎么验证**：开两个终端，一个 `export FOO=1`，另一个 `echo $FOO`——空的。

### 误区：`which` 是查命令位置的权威

**你以为**：`which python3` 给的就是你会执行的。
**其实**：`which` 只搜 PATH，不认识**别名和函数**。如果你 `alias python3=python3.12`，`which` 依然显示旧的路径，而真正执行的是别名。排查"我敲的到底是谁"要用 `type -a`。
**怎么验证**：`alias ll='ls -l'; type -a ll; which ll`——前者说它是别名，后者找不到或指向 `/usr/bin/ll`。

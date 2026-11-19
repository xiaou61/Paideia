---
title: 权限：rwx、chmod 与 umask
category: linux-from-zero
order: 4
minutes: 20
level: beginner
status: published
---

## `cd /opt/app` 先被拒绝，脚本权限根本还没轮到

```text
$ cd /opt/app
bash: cd: /opt/app: Permission denied
$ ls -ld /opt/app
drwx------ 3 root root 4096 Nov 19 10:02 /opt/app
$ sudo ls -l /opt/app/run.sh
-rwxr-xr-x 1 root root 412 Nov 19 09:58 /opt/app/run.sh
```

标准形态；这台 Windows 没有 Linux，不是本机输出。看到 `run.sh` 是 `755`，第一反应会是「脚本有 x，为什么跑不了」。实际是 `/opt/app` 这一级 `700`、属主 root：你连目录都进不去，里面文件的权限再对也没用。路径检查是逐级的，`/opt`、`/opt/app`、`run.sh` 任何一级缺 x，整条路就断。

## 九个位，三组人

`ls -l` 第一列去掉类型位，剩下九个字符分三组：属主 u、属组 g、其他人 o。每组 `r=4`、`w=2`、`x=1`。

| 位 | 值 | 文件上 | 目录上 |
| --- | --- | --- | --- |
| `r` | 4 | 读内容 | 列出名字（`ls`） |
| `w` | 2 | 改内容 | 创建、删除、改名目录项 |
| `x` | 1 | 执行 | 进入、遍历，访问已知子路径 |

`rwx` = 7，`rw-` = 6，`r-x` = 5，`r--` = 4，`---` = 0。所以 `755` 是 `rwxr-xr-x`，`644` 是 `rw-r--r--`，`600` 是 `rw-------`。

目录的 `r` 和 `x` 要分开看：

- 只有 `r`：能列出名字，但 `ls -l` 还要 `x` 才能 stat 每个条目，`cd` 也要 `x`。
- 只有 `x`：能进，能访问已知路径，但 `ls` 看不到目录里有哪些名字。
- `w` 要配合 `x`：只有在目录里有 `w+x`，才能创建、删除、改名目录项。

删文件看的是目录的 `w+x`，不是文件本身的 `w`。`/opt/app/conf` 是 `755 root:root` 时，你删不掉里面一个 `444` 的文件：

```text
$ ls -l /opt/app/conf/report.csv
-r--r--r-- 1 root root 100 Nov 19 09:40 /opt/app/conf/report.csv
$ rm /opt/app/conf/report.csv
rm: cannot remove '/opt/app/conf/report.csv': Permission denied
```

但如果目录对你有 `w+x`，即使文件是 `444`，你也能删掉它。文件自己的 `w` 只管内容，不管名字。反过来说，`chmod 444` 防的是改内容，不防删除。

## 修 `/opt/app`：先补目录的 x

假设已经有一个 `app` 组，部署账号 `lzf` 在组里；没有就先 `groupadd app`、`usermod -aG app lzf`，加组后要重新登录或 `newgrp app` 才生效。

```text
$ sudo chgrp -R app /opt/app
$ sudo chmod 750 /opt/app
$ sudo chmod 755 /opt/app/run.sh
$ sudo chmod 640 /opt/app/secrets.env
$ sudo chmod 2770 /opt/app/data
```

- `750`：root 可读写执行，`app` 组可读和进入，其他人没有任何权限。
- `755` 的脚本：属主可读写执行，组和其他人可读、可执行。
- `640` 的密钥文件：属主可读写，组可读，其他人不可见。只有属主需要读时用 `600`。
- `2770` 的共享数据目录：组内可读写执行，新文件继承 `app` 组，其他人进不来。`2` 是 setgid，后面讲。

`chgrp -R` 和 `chmod -R` 都会动整棵树。执行前先 `ls -ld /opt/app`，确认路径没有多一个空格、没有指到家目录或 `/`。

## `chmod`：数字和符号两种写法

数字写法直接给三组的值：

```text
$ chmod 755 /opt/app/run.sh       # rwxr-xr-x
$ chmod 640 /opt/app/secrets.env  # rw-r-----
$ chmod 600 /opt/app/secrets.env  # rw-------
$ chmod 700 /opt/app/private      # rwx------
```

符号写法在「只改一组」时更清楚：

```text
$ chmod u+x /opt/app/run.sh
$ chmod go-rwx /opt/app/secrets.env
$ chmod u=rw,g=r,o= /opt/app/secrets.env   # 640
$ chmod a+x /opt/app/run.sh
```

`chmod +x` 不写 `who` 时，GNU chmod 会按 `a` 处理，但 umask 里为 1 的位不加。常见 `umask 022` 没有 x 位，所以看起来等于 `a+x`；`umask 077` 时，一个 644 文件用 `chmod +x` 只加属主 x，结果是 `744`。要明确就写 `a+x` 或数字。

`chmod 777` 不是省事的开关。它让任何能登录这台机器的人都能改这个文件；如果它是 root 执行的服务或脚本，本地用户可以把代码塞进去等 root 跑。先找路径上哪一级缺权限，只补那一级。

## `chmod -R`：目录和文件不能用同一个模式

`chmod -R 777 /opt/app` 会把 `.conf`、`.env`、图片全变成可执行、所有人可写。配置文件不需要 x，密钥文件更不该让所有人写。

反过来更常见：`chmod -R 600 /opt/app` 会把目录的 x 也拿掉，普通属主连 `cd` 都进不去。root 可能凭 CAP_DAC_OVERRIDE 还能进，别拿 root 试完就以为没事。

把属主的 rw 补回来、给目录补 x，用大写 `X`：

```text
$ chmod -R u+rwX /opt/app
```

`X` 只给目录、以及原本就有某个 x 位的文件加 x。脚本原本没有 x 的话，`X` 不会把它救回来；这也不是还原到原权限，只是把属主救回来。目录还是进不去时，单独补：

```text
$ find /opt/app -type d -exec chmod u+x {} +
```

`chmod -R` 和软链接的坑在这里：

```text
$ ls -l /opt/app/current
lrwxrwxrwx 1 root root 28 Nov 19 09:50 /opt/app/current -> /srv/app/releases/2026-11-19
```

GNU `chmod -R` 默认按 `-H` 遍历：命令行参数里的目录软链接会跟进去，递归过程中遇到的软链接不跟。

```text
$ chmod -R 755 /opt/app
# current 是递归中遇到的链接，跳过；/srv/app/releases/2026-11-19 不变
$ chmod -R 755 /opt/app/current
# 命令行参数是链接，GNU chmod 先解开它，改到 /srv/app/releases/2026-11-19 整棵树
```

所以 `chmod -R 755 /opt/app` 可能漏掉 `current` 指向的 release 目录，服务还是起不来；而 `chmod -R 755 /opt/app/current` 可能改到 `/opt/app` 外面去。先 `readlink -f /opt/app/current`，确认目标再决定改哪边。

另外，`chmod -R` 会跨挂载点。`/opt/app/data` 如果是单独挂的盘，也会被一起改；要限定在一个文件系统里，用 `find /opt/app -xdev ...` 自己控制范围。

## `chown` 与 `chgrp`

```text
$ sudo chown lzf:devs /opt/app/app.conf
$ sudo chown -R www-data:www-data /srv/www
$ sudo chgrp -R app /opt/app
```

- 只有 root 能把文件给别人。普通用户改不了属主；改属组时，也只能改成自己所属的组。
- `sudo cp` 新建的目标文件属主是 root。目标文件已存在时，`cp` 写的是原来的 inode，属主保持原样。
- `cp -a` 在有权限时保留属主；普通用户跨文件系统时没有 `chown` 权限，属主可能保不住。

## `umask`：新建文件的默认上限

创建文件的程序一般传 `0666`（普通文件）或 `0777`（目录），内核再按 `umask` 去掉位：

```text
mode = 起点 & ~umask
```

| 对象 | 起点 | umask 022 | 结果 |
| --- | --- | --- | --- |
| 普通文件 | 666 | 666 & ~022 | 644 |
| 目录 | 777 | 777 & ~022 | 755 |

`umask 022` 表示组和其他去掉写位。`umask` 输出 `0022`，`umask -S` 输出 `u=rwx,g=rx,o=rx`。

| umask | 新文件 | 新目录 | 适用场景 |
| --- | --- | --- | --- |
| `022` | 644 | 755 | 很多发行版的默认，别人可读 |
| `027` | 640 | 750 | 组内可读/进入，不能写；其他人没有任何权限 |
| `002` | 664 | 775 | 组内可写，适合共享目录 |
| `077` | 600 | 700 | 密钥、数据库文件、私有目录 |

三件事要记住：

- `umask` 只能拿掉位，不能加位。程序调用 `open` 时明确要 `0600`，结果还是 `0600`，不会因为 umask 是 022 就变成 644。
- `umask` 不影响已经存在的文件；它只在创建时起作用。
- `umask` 不是所有发行版都设成 022。登录后先跑 `umask`，别背默认值。

临时改只在当前 shell 有效：

```text
$ umask 077
$ umask
0077
```

要永久改，得看谁在读你的启动文件：交互式 bash 读 `~/.bashrc`，登录 shell 读 `~/.profile` 或 `~/.bash_profile`，系统级 `/etc/profile` 只影响读它的登录 shell。systemd 启动的服务不读这些，服务单元里用 `UMask=`。这些位置在不同发行版上不完全一样，改完用 `umask` 和新进程确认。

## 三个特殊位：`/tmp`、`passwd` 和共享目录

`/tmp` 的权限：

```text
$ ls -ld /tmp
drwxrwxrwt 12 root root 4096 Nov 19 09:00 /tmp
```

末尾的 `t` 是 sticky bit（八进制 `1000`，也就是 `chmod 1777`）。没有 `t` 的 `777` 目录，谁都能删里面任何文件；有 `t` 时，只有文件属主、目录属主和 root 能删。前提是你还得有目录的 `w+x`，sticky 是在此之上再加的限制。

`/usr/bin/passwd` 的那一位：

```text
$ ls -l /usr/bin/passwd
-rwsr-xr-x 1 root root 68208 Nov 19 09:00 /usr/bin/passwd
```

属主执行位上的 `s` 是 setuid（八进制 `4000`）。普通用户执行 `passwd` 时，进程以文件属主 root 的身份运行，才能写 `/etc/shadow`。`s` 是小写表示执行位也在；如果显示大写 `S`，说明 setuid 位设了但没有 x 位。

setgid（八进制 `2000`）在文件上让进程以文件属组运行；在目录上让新建的文件和子目录继承该组。共享目录常见 `2775` 或 `2770`：

```text
$ chmod 2770 /opt/app/data
$ ls -ld /opt/app/data
drwxrws--- 3 root app 4096 Nov 19 09:30 /opt/app/data
```

Linux 内核不按脚本的 setuid 位提权；自己写的程序也不要随手加 setuid，出问题的方式比九个权限位多。

## 一组够用的权限

| 对象 | 权限 | 说明 |
| --- | --- | --- |
| 普通配置文件 | `644` | 大家可读，只有属主可改 |
| 含密钥/口令的配置 | `600` | 只有属主 |
| 可执行脚本、二进制 | `755` 或 `700` | 需要别人执行用 755，只自己用 700 |
| 私有目录（`~/.ssh`） | `700` | 只有属主能进 |
| 共享写目录 | `1777` | 人人可写，sticky 防互删 |
| 共享组目录 | `2770` 或 `2775` | 同组协作，新文件继承组 |

## 跑一遍

下面要在 Linux 上跑；这台 Windows 没有 Linux，我没执行过。

```text
$ cd /tmp
$ rm -rf perm-demo
$ umask
0022
$ mkdir -p perm-demo
$ touch perm-demo/a.conf
$ ls -ld perm-demo perm-demo/a.conf
drwxr-xr-x 2 lzf devs 4096 Nov 19 10:20 perm-demo
-rw-r--r-- 1 lzf devs    0 Nov 19 10:20 perm-demo/a.conf
$ umask 077
$ mkdir perm-demo/private
$ touch perm-demo/private/secret
$ ls -ld perm-demo/private perm-demo/private/secret
drwx------ 2 lzf devs 4096 Nov 19 10:20 perm-demo/private
-rw------- 1 lzf devs    0 Nov 19 10:20 perm-demo/private/secret
```

`umask 077` 只影响当前 shell；新开一个终端，`umask` 会回到启动文件里的值。

## 动手

```code-challenge
{"id":"linux-04-chmod","kind":"fill","title":"给脚本设一组够用不多余的权限","language":"bash",
 "template":"# 属主可读写执行，同组和其他人只读、可执行\nchmod {{0}} /opt/app/run.sh\n# 含密钥的配置：只有属主能读写\nchmod {{1}} /opt/app/secrets.env",
 "options":[["755","777","700"],["600","644","666"]],"answers":[0,0],
 "explain":"755 是脚本的标准权限；600 让密钥文件除属主外谁都读不到。777 会把写权限也放给所有人，是不该出现的默认值。"}
```

上面是选择题。下面这道要真的把权限改对——判定看的是 `stat` 出来的权限位，不是你的输出。

```code-challenge
{
 "id": "linux-04-shell-chmod",
 "kind": "shell",
 "title": "把密钥文件收成只有属主可读写",
 "seed": [
  {
   "path": "app/secrets.env",
   "content": "DB_PASSWORD=demo\n"
  },
  {
   "path": "app/app.conf",
   "content": "port=8080\n"
  }
 ],
 "starter": "# 只改 app/secrets.env：属主可读写，同组与他人没有任何权限\n",
 "judge": "set -e\nmode=$(stat -c %a app/secrets.env)\ncase $mode in\n  600) ;;\n  *) echo app/secrets.env 现在是 $mode，期望 600（只有属主可读写）; exit 1;;\nesac\nconf=$(stat -c %a app/app.conf)\ncase $conf in\n  644) ;;\n  *) echo app/app.conf 现在是 $conf，期望 644——别用 chmod -R 一起改; exit 1;;\nesac",
 "explain": "600 是 rw-------。640 会让同组读到口令，644 是所有人都能读。app.conf 不该被一起改，所以判定也检查它仍是 644——chmod -R 是这道题最常见的错法。"
}
```

## 检查一下

```quiz
{"id":"linux-04-quiz","title":"权限三问","questions":[
 {"question":"`/data/public` 是 755，里面的 `a.txt` 是 644，但 `cat` 报 Permission denied。最可能的原因？","options":["上级目录 /data 缺 x 位","文件属主不是自己","文件系统只读"],"answer":0,"explain":"访问路径要逐级检查，任何一级没有 x 就进不去。上级目录 700 时，里面的 755 目录对外人完全不可达。"},
 {"question":"目录 `/tmp` 是 `1777`，为什么你不能删别人的文件？","options":["末尾的 t（sticky bit）只允许文件属主、目录属主和 root 删","/tmp 是只读的","因为别人的文件是 644"],"answer":0,"explain":"sticky bit 让 777 目录里的删除只允许文件属主、目录属主和 root 执行；前提是目录本身有 w+x。/tmp 因此人人都能建，却删不了别人的。"},
 {"question":"新建文件的默认权限 644 是怎么来的？","options":["起点 666 去掉 umask 022 的写位","起点 777 去掉 umask 022 的写位","内核写死的"],"answer":0,"explain":"普通文件起点是 666（默认不给执行位），目录起点是 777；两者都要按 umask 去掉相应位。所以 umask 022 得到文件 644、目录 755。"}
]}
```

## 还差的

- 这台机器没有 Linux 环境，`ls -ld /opt/app`、`/tmp` 的 `1777`、`/usr/bin/passwd` 的 setuid 位都是标准形态，不是本机实测；具体发行版的路径和权限可能不同。
- ACL、SELinux/AppArmor、mount 的 `noexec`、NFS 的 root squash 没展开。这些会让九个位看起来对，操作仍被拒。
- systemd 的 `UMask=`、cron 的 umask 没在真实服务上验证；不同服务管理器和启动方式可能不同。
- Linux 内核忽略脚本 setuid 只写了一句话，没做实验；`chmod -R` 跨挂载点的范围也没实测。

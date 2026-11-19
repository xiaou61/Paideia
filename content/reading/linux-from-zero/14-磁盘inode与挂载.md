---
title: 磁盘、inode 与挂载
category: linux-from-zero
order: 14
minutes: 18
level: beginner
status: published
---

## 0.85 变成 0.15 的那一行

仓库里磁盘告警的阈值从这里来（`deploy/ops/ops-thresholds.conf:10`）：

```ini
DISK_USED_ERROR=0.85
```

`deploy/ops/install-ops.sh:43-44` 渲染告警文件时把它翻了个面：

```sh
DISK_FREE_RATIO=$(awk -v v="$DISK_USED_ERROR" 'BEGIN{printf "%.2f", 1-v}')
DISK_FREE_PERCENT=$(awk -v v="$DISK_USED_ERROR" 'BEGIN{printf "%.0f", (1-v)*100}')
```

这两行我在本机 Git Bash 里原样跑过（`awk` 是 MSYS 自带的 GNU awk，不是 Linux）：

```text
$ grep -E "^DISK_USED_ERROR=" deploy/ops/ops-thresholds.conf
DISK_USED_ERROR=0.85
$ awk -v v=0.85 'BEGIN{printf "DISK_FREE_RATIO=%.2f\nDISK_FREE_PERCENT=%.0f\n", 1-v, (1-v)*100}'
DISK_FREE_RATIO=0.15
DISK_FREE_PERCENT=15
```

于是写进 `deploy/ops/provisioning/alerting/paideia-rules.yaml:66-67` 的规则长这样（占位符由脚本替换，仓库里存的是占位符本身）：

```yaml
expr: >-
  (node_filesystem_avail_bytes{mountpoint="/"}
   / node_filesystem_size_bytes{mountpoint="/"}) < bool 0.15
```

「已用 85%」和「可用不到 15%」在数学上是同一句话，所以这次换算没毛病。但别以为它是唯一一处：`backend/paideia-ops/.../OpsProperties.java:117-120` 里还有一份代码默认值（warn 0.70 / error 0.85），环境变量没注入时用的就是那份，两处得一起改。

我原来以为这条规则等价于「`df` 的 `Use%` 走到 85% 就发邮件」，实际不是——`df` 的 `Use%` 和「1 − 可用/总量」是两个口径，差在保留块上。

`mkfs.ext4` 默认给 root 留 5%（`-m` 可改；XFS 根本没有这个概念）。这 5% 算在 `Size` 里，不算在 `Avail` 里，所以规则的比值把保留块当成「已用」，而 `df` 把保留块既不算已用、也不放进分母。按 40G 根分区、保留 5% 算一遍：规则在可用 6G 时响，此时已用 32G，`df` 的 `Use%` 是 32/(32+6)=84%，不是 85%。差不到一个百分点，方向是「告警先响」。

顺便，`df` 的百分比是向上取整的。这台机器 `193731096576 / 214749409280` = 90.2%，`df` 显示 91%（输出在后面「跑一遍」里）。所以别拿 `df` 的整数去卡阈值，差一格是常事。

还有个方向问题：把 `<` 写成 `>`，后果不是「晚一点报」，是正常机器上永远在报——可用比例基本都大于 0.15，条件恒真。

## `df` 说满了，`du` 说不满

同一台机器，根分区 40G。下面是示意输出（`标准形态，未实跑`，数字取这台 40G 盘为例）：

```text
# 标准形态，未实跑
$ df -h /
Filesystem      Size  Used Avail Use% Mounted on
/dev/vda1        40G   38G  1.2G  98% /
$ du -shx / 2>/dev/null
17G	/
```

`df` 读的是文件系统自己的记账（superblock 里还剩多少块），`du` 是遍历目录、把每个文件的 `st_blocks` 加起来。两个数字不是一回事，对不上通常是下面五种之一：

| 症状 | 先看哪 | 它在回答什么 |
| --- | --- | --- |
| `df` 高、`du` 低很多 | `lsof +L1` | 有没有「已删除但仍被进程打开」的文件 |
| `df` 高、`du` 低很多 | 用 root 重跑 `sudo du -shx /` | 刚才那次 `du` 是不是静默跳过了 700 目录 |
| `df` 高、`du` 低很多 | `findmnt` 看这个挂载点 | 有没有文件被挂载盖住，`du` 走不到 |
| `df` 高、`du` 也高 | `du -hx --max-depth=1 / \| sort -h` | 是不是真有大目录，从哪一层开始大 |
| `df -i` 100%、`df -h` 还有空间 | `df -i` | 是不是 inode 先没了 |

第二行是我以前漏掉的，也是新手最容易踩的：**`du` 遇到无权限的目录会跳过，只在 stderr 留一行 Permission denied**。`/var/lib/mysql`、某些 `/var/lib/docker` 子目录都是 700，普通用户 `du` 出来的数会小一大截，然后你就去查「删了没释放」了，方向就错了。所以第一步是用 root 再跑一遍，对上了就到此为止。

### 删了但没释放

进程打开一个文件之后，`rm` 只是把目录项摘掉、把链接数减到 0。只要还有进程握着文件描述符，inode 和数据块都不会还回去，`df` 继续把它们算作已用，`du` 已经看不到它了。

```text
# 标准形态，未实跑（PID 与文件名是示意）
$ lsof +L1
COMMAND  PID USER    FD   TYPE DEVICE    SIZE/OFF NLINK   NODE NAME
java    4242 paideia 3w   REG  254,1  12884901888     0  41234 /opt/paideia/app.log (deleted)
```

`+L1` 是「链接数小于 1」，也就是已删除但仍被打开；`FD` 列的 `3w`，那个 `w` 表示这个描述符可写——这一点很关键，后面截断要用。

三层处理，从轻到重：

1. **让进程自己重开文件**。很多服务认 `SIGUSR1` 或 `SIGHUP`（第 09 篇讲过信号），systemd 管的服务先试 `systemctl reload <unit>`，别一上来就 kill。
2. **原地截断**，走 `/proc`：`: > /proc/4242/fd/3` 或 `truncate -s 0 /proc/4242/fd/3`。能成功的前提是那个 fd 可写；只读打开的 fd 会直接拒绝。
3. **重启进程**。空间立刻回来，代价是你知道的。

这也是「日志别直接 `rm`」的机制来源（第 13 篇）：要清空就 `truncate -s 0 日志` 或 `: > 日志`，空间当场回来、路径还在；先 `rm` 再 `truncate` 是行不通的——路径已经没了，只能走 `/proc/<pid>/fd/N`。本仓库的 `paideia.service` 日志走 journald，清理由 `journalctl --vacuum-size=` / `--vacuum-time=` 做，别去 `rm /var/log/journal`。

### Docker 的账

如果这台机器上跑着 Docker，`du` 在 `/var/lib/docker` 上的数字不能当「能腾出多少」用：overlay2 的层被多个镜像共享，`du` 一次运行内把同 inode 的硬链接只算一次，所以它既不重复计、也不等于可回收量；而 `rm` 掉的容器留下的层，`df` 里还没释放。这种时候看 Docker 自己的账：

```text
# 标准形态，未实跑
$ docker system df
TYPE            TOTAL     ACTIVE    SIZE      RECLAIMABLE
Images          23        4         8.35GB    6.18GB (74%)
Containers      6         2         120MB     88MB
Local Volumes   9         2         1.2GB     900MB
```

`RECLAIMABLE` 那一列才是「删了能回来多少」。8.35G / 6.18G 这组数字是 `paideia-rules.yaml` 里那条磁盘告警的说明原文写的——写它那天的数字，不是常态，看到自己机器上是别的值别奇怪。

顺带提醒一句：`docker system prune -a --volumes` 的 `--volumes` 会删掉「没有被容器引用」的命名卷，那里面可能有数据库数据。清之前先 `docker volume ls` 对一眼。

## 空间还空，文件却建不出来

```text
# 标准形态，未实跑
$ df -i /
Filesystem      Inodes  IUsed   IFree IUse% Mounted on
/dev/vda1      2621440 2621440       0  100% /
$ touch /tmp/x
touch: cannot touch '/tmp/x': No space left on device
```

`df -h` 还有几十 G，`touch` 却报 `No space left on device`，就是这一种。原因是 inode 和块是两套配额，而**inode 够不够用，取决于文件系统的类型**，这里必须分开说：

- **ext4**（Debian、Ubuntu 的默认）：inode 总数在 `mkfs` 时按 `bytes-per-inode` 推算或由 `-N` 指定，之后固定。`tune2fs` 改不了这个总数；扩分区时 `resize2fs` 会按同样比例补一些，想把已有文件系统调密只能重建。所以「几十万个小文件吃光 inode」在 ext4 上是真实故障。
- **XFS**（RHEL 7 之后的默认）：inode 按需从空闲空间里分配，`df -i` 显示的 `Inodes` 是个很远的上限。在 XFS 上基本见不到「空间还剩很多但 inode 满」——它会先把空间用完。

所以同一句「inode 满了」，在 Ubuntu 机器上要查，在 Rocky 机器上大概查不到。查之前先 `df -T /` 看 `Type` 那一列。

这台机器上还有两个会造小文件的地方，都在仓库里能看到：

- **Loki 的 chunk**：`deploy/ops/loki-config.yaml:21` 的 `chunks_directory: /var/lib/loki/chunks`，日志按块落盘，一个块一个文件。它的留存是 `retention_period: 720h`（同文件第 39 行，30 天），也就是说这堆小文件不是「过一会儿自己就没了」，得等 compactor 按保留期删。
- **沙箱的 tmpfs**：`backend/paideia-sandbox/.../SqlContainerPool.java:133` 给容器 `/tmp` 挂的是 `--tmpfs /tmp:rw,size=768m,exec`，shell 沙箱只有 64m（`backend/paideia-sandbox/images/shell/build.sh:25`）。tmpfs 有 inode 上限，也有 size 上限，灌满报的同样是 `No space left on device`。

找哪个目录文件最多，`-xdev` 别省：

```text
# 标准形态，未实跑
$ find / -xdev -type f 2>/dev/null | wc -l
$ for d in /var/*; do printf '%s %s\n' "$(find "$d" -xdev -type f 2>/dev/null | wc -l)" "$d"; done | sort -rn | head
```

`-xdev` 是不跨文件系统，否则 `find /` 会走进 `/proc`、`/sys`、`/dev/shm` 和别的挂载点：`/proc` 下的条目一直在变，`/proc/kcore` 还会报一个巨大到离谱的尺寸，白跑。

## 把一块盘挂到 `/var/lib/loki`

假设根分区开始装不下 30 天的日志了，下一步就是加一块盘。新盘不会自己出现在目录里，得挂上去。

```text
# 标准形态，未实跑
$ lsblk
NAME   MAJ:MIN RM  SIZE RO TYPE MOUNTPOINTS
vda    254:0    0   40G  0 disk
└─vda1 254:1    0   40G  0 part /
vdb    254:16   0  196G  0 disk
└─vdb1 254:17   0  196G  0 part
$ sudo mount /dev/vdb1 /var/lib/loki
$ findmnt /var/lib/loki
TARGET         SOURCE    FSTYPE OPTIONS
/var/lib/loki  /dev/vdb1 ext4   rw,relatime
```

`mount` 是临时的，重启就没了。要长期生效写 `/etc/fstab`：

```
# <设备>              <挂载点>        <类型>  <选项>            <dump> <pass>
UUID=1a2b3c4d-...      /              ext4    errors=remount-ro  0      1
UUID=9f8e7d6c-...      /var/lib/loki  ext4    defaults,nofail    0      2
```

三个点，每一个都有代价：

**一、用 UUID，别用 `/dev/vdb1`。** 云主机上设备名跟着挂载顺序走：这块盘今天是 `vdb`，明天多挂一块就可能变 `vdc`；NVMe 机器上又是 `nvme0n1p1` 这套名字。UUID 是文件系统自己的身份证，不会变。查它用 `blkid`（`lsblk -f` 也能顺手看到）。

**二、`nofail` 是给开机留的退路。** 数据盘写进 fstab 又没加 `nofail`，盘一掉或者一没挂上，启动就进紧急模式——云上「机器起不来、只能进控制台」的一大来源。加了 `nofail`，设备缺失时启动继续。想再快一点，配 `x-systemd.device-timeout=10s` 把等设备的默认超时压短。

**三、`pass` 是 fsck 的顺序。** 根分区写 1，其它数据盘写 2 或 0（0 表示开机不查）。

### 改完 fstab，别只信 `mount -a`

```text
# 标准形态，未实跑
$ sudo mount -a
$ findmnt --verify
$ findmnt /var/lib/loki
```

我原来也想写「`mount -a` 不报错就说明 fstab 是对的」。这是把条件写成了保证，两处都不成立：

- `mount -a` 只处理**还没挂上**的条目。已经挂着的会被跳过，所以它验不了那一条。
- 带 `nofail` 的条目，设备缺失时被跳过而不是报错。于是「没报错」有两种可能：真挂上了，或者被跳过了。

分辨只有一个办法：`findmnt /var/lib/loki` 看有没有输出。`findmnt --verify` 是专门校验 fstab 的命令，但它在较新的 util-linux 上才有，老系统直接 `command not found`，那时候就靠 `mount -a -v` 加肉眼对 UUID。

还有一步容易漏：改完 fstab 要 `systemctl daemon-reload`。systemd 会把 fstab 转成 `.mount` 单元，不重载的话新条目要等下次开机才生效。

### 挂载遮蔽：文件明明在，却看不见

`mount` 是「把新文件系统盖在这个目录上」，不是「把文件搬进去」。目录里原来的东西不会消失，只是被盖住了。

```text
# 标准形态，未实跑
$ sudo mount --bind / /mnt/root
$ ls /mnt/root/var/lib/loki
```

`mount --bind /` 是把根文件系统再挂一份到别处，从 `/mnt/root` 往下看，看到的就是「被盖住的那一层」。典型场景是先往 `/var/lib/loki` 写了几十 G 日志、之后才把盘挂上去，于是这几十 G 既不在新盘上、也不在 `du` 的统计里，却还实实在在占着根分区。

所以顺序是：**先挂盘，再往里写**。已经写错了，就 `mount --bind` 找到它、挪走、再 `umount` 掉 bind。

## 跑一遍

这台是 Windows，没有 Linux。能真跑的我都跑了，跑的是 Git Bash（MSYS2 的 POSIX 层，不是 Linux 内核），跑不了的一律标了「标准形态，未实跑」。

```text
$ uname -s; uname -r
MINGW64_NT-10.0-22000
3.4.10-87d57229.x86_64
$ df -h
Filesystem            Size  Used Avail Use% Mounted on
C:/Program Files/Git  201G  181G   20G  91% /
D:                    138G  119G   19G  87% /d
E:                    138G  107G   32G  78% /e
F:                    466G  221G  246G  48% /f
$ df -i
Filesystem           Inodes IUsed IFree IUse% Mounted on
C:/Program Files/Git      -     -     -     - /
D:                        -     -     -     - /d
```

`df -i` 整列是 `-`。这是 MSYS 的实情：它把 Windows 卷映射成 POSIX 路径，拿不到 inode 计数，于是填 `-`。也就是说**这台机器上根本演示不了 inode 耗尽**——想验，得有一台 ext4 的 Linux。

`du` 和 `stat` 能跑，但数字的含义要打个折：

```text
$ stat -c "%i %h %s %n" deploy/ops/ops-thresholds.conf
10696049118187975 1 584 deploy/ops/ops-thresholds.conf
$ du -sh content
6.1M	content
$ find content -type f | wc -l
484
```

`stat` 的 `%i` 给出 10696049118187975，看着像个 inode 号，其实是 MSYS 从 Windows 文件 ID 折出来的。它**不是** ext4 的 inode 编号，别拿它去推「这个文件系统还剩多少 inode」——`%h` 的硬链接数 1、`%s` 的 584 字节这两个能信。

## 动手

```code-challenge
{"id":"linux-14-df-du","kind":"fill","title":"先分清是块满了还是 inode 满了","language":"bash",
 "template":"# 1. 看容量口径\n{{0}} -h /\n# 2. 看 inode 口径\n{{1}} -i /\n# 3. 找大目录：-x 不跨文件系统\ndu -hx --max-depth=1 / | sort -h | tail -5",
 "options":[["df","du","ls"],["df","du","stat"]],
 "answers":[0,0],
 "explain":"df 读文件系统记账，能反映「已删除但没释放」的块；du 只遍历目录里看得见、读得到的文件。inode 只有 df -i 这一条路，du 完全不涉及。"}
```

## 检查一下

```quiz
{"id":"linux-14-quiz","title":"磁盘三问","questions":[
  {"question":"仓库里那条规则写的是 `...avail_bytes / size_bytes < bool 0.15`。如果把 `<` 改成 `>`，会发生什么？","options":["永远不触发","只在 inode 用满时触发","采集正常时也一直在触发"],"answer":2,"explain":"可用比例正常时就大于 0.15，条件恒真，于是这次采集恒为 1，告警一直 firing。方向写反的典型样子。"},
  {"question":"`df -h` 说 91%，`sudo du -shx /` 加起来只有 60%，先查哪个？","options":["`lsof +L1` 找被删但仍被打开的文件","`df -i` 看 inode","`mount --bind` 看挂载遮蔽"],"answer":0,"explain":"是这个症状时，最省事的一步就是 lsof +L1：有 (deleted) 且 NLINK 0 的行就是答案。注意 du 一定要用 sudo，否则 700 目录被跳过，你会先误判成「没释放」。"},
  {"question":"数据盘在 fstab 里带 `nofail`，盘掉了开机照样起来，`mount -a` 也没报错——这能说明盘挂上了吗？","options":["能，返回码 0 就是挂上了","不能，设备缺失会被静默跳过，得 `findmnt /var/lib/loki` 确认","能，mount -a 会把 fstab 每一条都挂一遍"],"answer":1,"explain":"mount -a 只处理未挂载条目，nofail 又把设备缺失当可忽略。唯一可信的确认是 findmnt 有输出，或者 lsblk 上那个挂载点非空。"}
]}
```

## 还差的

- **Linux 上的 inode 耗尽没验过。** `df -i` 到 100% 时 `touch` 的报错原文、清完文件后 inode 什么时候真的回收，我只有文档依据（`标准形态，未实跑`），没有一台 ext4 机器上的输出。
- **`mount -a` 对 `nofail` 条目的返回码，我没查到确定的结论。** util-linux 各版本对它的处理不一定一样，所以我只敢写「别用它判断挂上了」，没写它的退出码具体是什么。
- **XFS 那句「基本见不到 inode 满」是按分配机制推的**，我没有在 XFS 上压过几十万个小文件。想确认就自己拿一台 Rocky 机器 `df -T` 看一眼再灌。
- **`findmnt --verify` 从哪个 util-linux 版本才有，我没核。** 老系统上它不存在，这一点我确认过（本机 Git Bash 根本没装 `findmnt`），但分界线在哪个版本不知道。
- **容器里 tmpfs 的 inode 上限具体怎么算**，我没查到能引用的出处，正文只写了「有上限」，没给公式。

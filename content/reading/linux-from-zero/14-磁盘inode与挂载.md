---
title: 磁盘、inode 与挂载
category: linux-from-zero
order: 14
minutes: 24
level: beginner
status: published
---

## 这一关在解决什么

磁盘满，是线上最常见、也最容易查错的一类故障。查错的原因是**"满"有三种**：

1. 文件真的占满了空间；
2. 文件被删了但进程还开着，空间没释放；
3. 空间还有，但 **inode 用完了**，连一个空文件都建不出来。

第 3 种会让 `df -h` 显示还有几十 G 空余却报 `No space left on device`，第一次遇到几乎必慌。这一关把三种都讲清楚，外加"挂载"这件事。

## 为什么会有 inode

因为 Linux 把"文件的名字"和"文件的内容"分开了：

- **inode** 存元信息：属主、权限、大小、时间、数据块在哪；
- **目录项**只是"名字 → inode 号"的映射。

一个文件必须先有一个 inode。**inode 的总数在格式化时就定死了**（`mkfs` 的 `-N` 或按 `bytes-per-inode` 推算）。所以几十万个小文件会把 inode 吃光，而占用空间可能只有几百 MB。

## 讲解

### `df` 与 `du`：两个不同的口径

```text
$ df -h
Filesystem      Size  Used Avail Use% Mounted on
/dev/vda1        40G  38G  1.2G  98% /
tmpfs           3.9G     0  3.9G   0% /dev/shm
/dev/vdb1       196G   12G  184G   6% /data
```

`df` 看的是**文件系统这一层**（superblock 里记的块分配情况），列的是挂载点。

```text
$ du -sh /var/* | sort -h | tail -5
1.2G    /var/log
3.4G    /var/lib/docker
7.8G    /var/lib/mysql
```

`du` 看的是**目录里可见文件的大小之和**。

两者对不上是常态，原因就三种（加上挂载遮蔽，共四种）：

| 现象 | 原因 | 怎么查 |
| --- | --- | --- |
| `df` 高、`du` 低很多 | 有文件被删但进程还开着 | `lsof +L1` |
| `df` 高、`du` 低很多 | 挂载点下面还有被覆盖的旧文件 | `mount --bind` 到临时目录再看 |
| `df -i` 100%、`df -h` 还空 | inode 耗尽 | `df -i`、`find / -xdev -type f \| wc -l` |
| `du` 相加超过 `df` | 硬链接被重复计算 / 同一文件系统被多处挂载 | `df` 看是否同一个 `Filesystem` |

### 被删除但仍被打开的文件

进程打开一个文件后，即使你 `rm` 了它，**只要进程不关闭文件描述符，inode 和它的数据块都不会释放**。

```text
$ lsof +L1
COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NLINK  NODE NAME
java     9812 paideia 3w   REG  254,1  12.4G     0 12345 /var/log/paideia/app.log (deleted)
```

`+L1` 是"链接数小于 1"，也就是"已删除但仍被打开"。最后一列的 `(deleted)` 是关键。

处理方式三种，按影响从轻到重：

1. **让进程重开文件**：`kill -USR1 <pid>`（很多服务支持）或 `systemctl reload 服务`。Nginx 收到 `USR1` 会重开日志文件，空间立刻回来。
2. **原地截断**：`truncate -s 0 /proc/9812/fd/3`。走 `/proc/<pid>/fd` 直接操作那个文件，不用重启。
3. **重启进程**：最后手段。

**这也是"日志文件不能直接 `rm`"的原因**（第 13 关）。删之前先 `truncate -s 0`。

### inode 用完了

```text
$ df -i
Filesystem      Inodes   IUsed   IFree IUse% Mounted on
/dev/vda1      2621440 2621440       0  100% /
$ touch /tmp/x
touch: cannot touch '/tmp/x': No space left on device
```

`No space left on device` 而 `df -h` 还有空间，就是它。常见制造者：

- 邮件队列（`/var/spool/postfix`）里几十万个小文件；
- PHP/session 文件（`/var/lib/php/sessions`）；
- 应用产生的海量小文件（每次上传一张缩略图、每个请求写一个临时文件）；
- `node_modules`、构建缓存。

找出哪个目录文件最多：

```text
$ find / -xdev -type f 2>/dev/null | awk -F/ '{print "/"$2"/"$3}' | sort | uniq -c | sort -rn | head
$ for d in /var/*; do echo "$(find "$d" -xdev -type f 2>/dev/null | wc -l) $d"; done | sort -rn | head
```

`-xdev` 很重要：**不要跨文件系统**，否则 `find /` 会进 `/proc`、`/sys` 和别的挂载点，白跑还可能卡住。

清完文件后 inode 才释放。想从根本上调整 inode 数量，只能在格式化时定（线上做不到，代价是重建文件系统）——所以真正的解法是**别让程序制造海量小文件**。

### 挂载：一块盘怎么出现在一个目录下

```text
$ lsblk
NAME   MAJ:MIN RM  SIZE RO TYPE MOUNTPOINTS
vda    254:0    0   40G  0 disk
└─vda1 254:1    0   40G  0 part /
vdb    254:16   0  196G  0 disk
└─vdb1 254:17   0  196G  0 part /data
$ findmnt /data
TARGET SOURCE    FSTYPE OPTIONS
/data  /dev/vdb1 ext4   rw,relatime
```

挂载命令（临时，重启即失）：

```text
$ sudo mount /dev/vdb1 /data
$ sudo umount /data
```

永久挂载写在 `/etc/fstab`：

```text
# <设备>                                  <挂载点> <类型> <选项>              <dump> <pass>
UUID=1a2b3c4d-...                          /        ext4   errors=remount-ro   0      1
UUID=9f8e7d6c-...                          /data    ext4   defaults,nofail     0      2
```

三个必须知道的点：

- **用 UUID，不要用 `/dev/vdb1`**。设备名在插拔或换云盘后可能变（`vdb` 变成 `vdc`），UUID 不变。查 UUID：`blkid`。
- **`nofail`**：设备不存在时系统仍能启动。数据盘写进 fstab 又没加 `nofail`，盘一掉机器就起不来——这是云上服务器开不了机的高频原因。
- **`pass` 列**：根分区是 1，其他数据盘写 2 或 0（0 表示开机不检查）。

改完 fstab **必须验证**，否则下次重启可能进救援模式：

```text
$ sudo mount -a        # 按 fstab 全部挂一次，有错会立刻报
$ sudo findmnt --verify
```

`mount -a` 不报错就说明语法和 UUID 都对。

### 挂载遮蔽：为什么"文件明明在，却看不见"

如果你往 `/data` 目录里写了东西，然后**才**把一块盘挂到 `/data`，那些文件就被"盖住"了——它们还在原分区的那个目录里，但 `/data` 现在指向新盘。

```text
$ sudo mount --bind / /mnt/root     # 把根分区挂到别处，才能看到被盖住的内容
$ ls /mnt/root/data
```

`mount --bind` 是查看隐藏内容的常用手段。清理这种残留文件要么在遮蔽前删掉，要么挂载到临时点后处理。

### 空间去哪了：一份急救顺序

磁盘告警时的固定动作，从快到慢：

```text
$ df -h                    # 哪个分区满了
$ df -i                    # 是不是 inode
$ du -h --max-depth=1 /    # 顶层哪个目录大（-xdev 避免跨盘）
$ du -h --max-depth=1 /var | sort -h | tail
$ lsof +L1                 # 被删未释放
$ journalctl --disk-usage  # journal 多大
$ docker system df         # 有 docker 时看镜像/容器/卷占用
```

`docker system df` 值得单独记：镜像层、停止的容器、匿名卷三者能悄悄占几十 G，而且 `du` 在 `/var/lib/docker` 上给出的数字常常看不懂。

## 动手

```code-challenge
{"id":"linux-14-df-du","kind":"fill","title":"判断是空间满还是 inode 满","language":"bash",
 "template":"# 1. 看容量\n{{0}} -h /\n# 2. 看 inode\n{{1}} -i /\n# 3. 找最大目录\ndu -h --max-depth=1 /var | sort -h | tail -5",
 "options":[["df","du","ls"],["df","du","stat"]],"answers":[0,0],
 "explain":"df 读文件系统记账（能反映被删未释放的块），du 只统计目录里可见文件的大小；`df -i` 才是 inode 的检查方式。"}
```

## 检查一下

```quiz
{"id":"linux-14-quiz","title":"磁盘三问","questions":[
 {"question":"`df -h` 还有空间但 `touch` 报 No space left on device，最可能是什么？","options":["inode 用完了","磁盘只读","文件系统损坏"],"answer":0,"explain":"inode 在格式化时定量，海量小文件能把它吃光而空间仍空。看 `df -i` 的 IUse%。"},
 {"question":"`df` 显示 98%、`du` 加起来只有 40%，差别在哪？","options":["有文件被删但进程还打开着","du 统计不到根目录","df 把缓存算进去了"],"answer":0,"explain":"被删除但仍被打开的文件，df 仍算占用（块没释放），du 看不到（目录项已删）。用 `lsof +L1` 找。"},
 {"question":"fstab 里数据盘没写 `nofail` 的后果？","options":["盘不存在时系统可能起不来","数据盘只读挂载","开机变慢"],"answer":0,"explain":"fstab 里的设备挂不上会让启动进入紧急模式。云盘尤其容易掉，数据盘一律加 nofail。"}
]}
```

## 常见误区

### 误区：`du -sh /` 的加起来就该等于 `df` 的 Used

**你以为**：两个工具统计同一件事。
**其实**：`df` 是文件系统记账（含被删未释放的块、元数据、保留块），`du` 是遍历目录看可见文件。ext4 默认还给 root 保留 5% 空间，`df` 的 `Use%` 与 `Avail` 会因为这部分出现"看起来对不上"的差异。对不上不代表有故障，要看差多少、再按上面四种原因排除。
**怎么验证**：`df -h /` 与 `du -shx /` 对比；`tune2fs -l /dev/vda1 | grep -i reserved` 看保留块。

### 误区：删了大文件空间立刻回来

**你以为**：`rm` 就是释放。
**其实**：只有**链接数为 0 且没有进程打开**时才真正释放。日志、数据库数据文件、正在上传的临时文件都属于"有进程开着"，删了反而不释放——这比没删更难查，因为目录里已经看不到它了。
**怎么验证**：`rm` 之后 `df -h` 没变、`lsof +L1` 里出现 `(deleted)`，就是这个情况。

### 误区：`/tmp` 在内存里，写多大都无所谓

**你以为**：tmpfs 是内存，速度快、不占磁盘。
**其实**：tmpfs **占的就是内存**。写满 tmpfs 等于吃掉可用内存，可能触发 OOM Killer 杀掉别的进程。容器里的 `/tmp` 常被设成 64MB 限额，也正是因为这个。
**怎么验证**：`df -h /tmp` 与 `mount | grep /tmp` 看它是不是 tmpfs；`df -h /dev/shm` 同理（很多程序把共享内存放这儿）。

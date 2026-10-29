---
title: SSH：登录、免密与隧道
category: linux-from-zero
order: 16
minutes: 26
level: beginner
status: published
---

## 这一关在解决什么

SSH 是你和远端服务器之间唯一的一条线。这一关讲四件事：**密钥登录怎么配置（以及为什么权限必须小）**、**`~/.ssh/config` 怎么省掉一堆参数**、**端口转发怎么把远端端口搬到本地**、**服务器怎么挡住爆破**。

日常最高频的两句是 `ssh 别名` 和 `ssh -L 本地端口:目标:远端端口`，学会这两句就够用了。

## 为什么用密钥而不是密码

因为**密码可以被猜，密钥不能**。更实际的三条理由：

- 服务器可以**完全关掉密码登录**，爆破就没有入口；
- 密钥认证过程中私钥**从不离开你的机器**，服务端只拿到签名；
- 自动化（git、部署脚本、rsync）可以用密钥，密码在脚本里是要写死明文的。

代价是私钥文件就是你的身份，丢了等于密码泄露，所以它必须能且只能被你自己读（下面讲 `600` 的道理）。

## 讲解

### 第一次连：`ssh` 在做什么

```text
$ ssh lzf@203.0.113.10
The authenticity of host '203.0.113.10' can't be established.
ED25519 key fingerprint is SHA256:xxxxx.
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```

这一句是**主机密钥验证**：服务器在证明"我真的是那台机器"。回答 `yes` 后它的公钥会被记进 `~/.ssh/known_hosts`，以后再连就自动校验。

如果重装系统或更换服务器后出现：

```text
WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!
```

**不要直接删 `known_hosts`**——先确认这台机器确实是你的（换 IP、重装是正常原因；没换过就是有人在中间劫持）。确认后：

```text
$ ssh-keygen -R 203.0.113.10      # 删掉旧指纹
```

### 生成密钥并上传

```text
$ ssh-keygen -t ed25519 -C "lzf@laptop"
$ ssh-copy-id lzf@203.0.113.10
```

`-t ed25519` 是现在的默认推荐（比 RSA 短、快、安全）。`-C` 是注释，写在公钥尾部，用来辨认"这把钥匙是谁的"。

`ssh-copy-id` 做的是：把 `~/.ssh/id_ed25519.pub` 的内容**追加**到服务器的 `~/.ssh/authorized_keys`。它还会顺手设对权限，这也是推荐用它而不是手动 `cat >>` 的原因。

没有 `ssh-copy-id`（Windows 常见）时手动做：

```text
$ cat ~/.ssh/id_ed25519.pub | ssh lzf@host "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

### 权限：SSH 会拒绝"太开放"的私钥

```text
$ chmod 700 ~/.ssh
$ chmod 600 ~/.ssh/id_ed25519
$ chmod 644 ~/.ssh/id_ed25519.pub
$ chmod 600 ~/.ssh/authorized_keys
$ chmod 600 ~/.ssh/config
```

权限不对时报错是这样的：

```text
Permissions 0644 for '/home/lzf/.ssh/id_ed25519' are too open.
```

这不是啰嗦，是安全要求：私钥可被同组或其他用户读取，等于钥匙挂在门上。

三行记住：

| 文件 | 权限 | 原因 |
| --- | --- | --- |
| `~/.ssh` | `700` | 只有你能进 |
| 私钥（无 `.pub`） | `600` | 只有你能读 |
| 公钥、`known_hosts` | `644` | 公开无害 |
| `authorized_keys` | `600` | 别人改它就能加自己的钥匙 |

### `~/.ssh/config`：把参数写死

```text
Host prod
    HostName 203.0.113.10
    User deploy
    Port 2222
    IdentityFile ~/.ssh/id_ed25519_prod
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

之后 `ssh prod` 就等于那一整串。三个额外好处：

- **同一台机器多个身份**：`Host prod` 和 `Host prod-root` 用不同的 `User` 与密钥，不会互相覆盖。
- **`ProxyJump` 跳板**：`ProxyJump bastion` 让你先连跳板机再进内网机器，不用手动 `-J`。
- **`ServerAliveInterval 30`**：每 30 秒发一个心跳，防止 NAT/防火墙把空闲连接掐掉（"ssh 挂着不动，几分钟后就断了"就是它）。

`scp`、`rsync`、`git` 都会读这份配置，所以别名在它们里也能用。

### 端口转发：把远端端口搬到本地

三个方向，日常用的是第一个：

**本地转发（最常用）**：把远端的端口映射到本地。

```text
$ ssh -N -L 3307:127.0.0.1:3306 prod
```

读法：`-L 本地端口:目标主机:目标端口`。连接你本机的 `3307`，流量经 SSH 到 `prod`，再由 `prod` 连它的 `127.0.0.1:3306`。

- `-N`：不执行远程命令，只做转发（不加的话会给你一个 shell，同时也能转发）。
- `-f`：转到后台。
- 之后本机 `mysql -h 127.0.0.1 -P 3307` 就连上了远端的 MySQL，**而 MySQL 依然只听回环，对外完全不可达**。

这就是"数据库不暴露公网，但本地能连上去调试"的标准做法，比给数据库开公网端口安全得多。

**远程转发**：把本地端口暴露到远端。

```text
$ ssh -R 8080:127.0.0.1:8080 prod
```

在 `prod` 上访问 `127.0.0.1:8080` 会转到你本机的 8080。常用于"让人看我在本地跑的东西"。

**动态转发（SOCKS 代理）**：

```text
$ ssh -D 1080 prod
```

本机开一个 SOCKS5 代理，走 SSH 出去。浏览内网页面时有用。

转发没通，按这个顺序查：`-v` 看连接与通道协商；确认目标地址是**从远端机器看**的地址（`127.0.0.1:3306` 是远端的回环）；确认本机端口没被占用。

### 传文件：`scp` 与 `rsync`

```text
$ scp file.tar.gz prod:/tmp/
$ scp -r dist/ prod:/opt/app/
$ rsync -avz --delete dist/ prod:/opt/app/dist/
```

差别值得知道：

- `scp` 每次全量复制，简单。
- `rsync` **只传差异**，大目录反复同步时快几十倍；`--delete` 让远端与本地完全一致（慎用，它会删远端多余的文件）；`-z` 压缩传输；`-a` 保留权限与时间。

`rsync` 的路径写法有个经典坑：`dist/` 与 `dist` 不一样——带斜杠是"复制目录里的内容"，不带是"复制这个目录本身"。

### 服务器侧：挡住爆破

默认配置有几个必须改的（`/etc/ssh/sshd_config`）：

```text
PermitRootLogin no              # 不允许 root 直接登录
PasswordAuthentication no       # 关掉密码登录（先确认密钥能登！）
PubkeyAuthentication yes
MaxAuthTries 3
ClientAliveInterval 300
```

**关密码登录前一定要新开一个终端验证密钥能登进去**，否则改完 `reload` 就把自己锁在外面（云控制台的 VNC 是唯一救法）。

改完：

```text
$ sudo sshd -t                  # 先检查配置语法
$ sudo systemctl reload sshd
```

`sshd -t` 这一步别省：语法错时 `reload` 会让 sshd 起不来，同样把自己锁在外面。

再上一层是 `fail2ban`：监视 `auth.log`，同一个 IP 连续失败几次就临时封掉。它能显著减少日志噪音，但**不能替代关掉密码登录**——爆破本身还是会发生，只是进不来。

想确认自己被爆破得有多厉害：

```text
$ sudo journalctl -u ssh --since today | grep -c "Failed password"
$ sudo lastb | head
```

### 一个常见需求：多台机器免密互信

```text
$ ssh-keygen -t ed25519 -f ~/.ssh/id_deploy -N ""
$ ssh-copy-id -i ~/.ssh/id_deploy.pub deploy@a
$ ssh-copy-id -i ~/.ssh/id_deploy.pub deploy@b
```

`-N ""` 是空口令（自动化必须空，否则每次都要输）。这把钥就是一台机器的身份，**别把它提交进仓库**，也别在多个无关环境复用。

## 动手

```code-challenge
{"id":"linux-16-ssh-perm","kind":"fill","title":"把权限收紧到 ssh 愿意接受","language":"bash",
 "template":"chmod {{0}} ~/.ssh\nchmod {{1}} ~/.ssh/id_ed25519\nchmod 644 ~/.ssh/id_ed25519.pub",
 "options":[["700","755","777"],["600","644","640"]],"answers":[0,0],
 "explain":"目录 700（只有属主能进），私钥 600（只有属主能读写）。权限过宽时 ssh 会直接拒绝使用这把钥匙并报 `are too open`。"}
```

## 检查一下

```quiz
{"id":"linux-16-quiz","title":"SSH 三问","questions":[
 {"question":"`ssh -L 3307:127.0.0.1:3306 prod` 里的 `127.0.0.1` 指谁的？","options":["远端 prod 的回环地址","本机的回环地址","数据库服务器的公网地址"],"answer":0,"explain":"-L 的格式是 本地端口:目标主机:目标端口，目标地址是从「远端」那一侧解析的，所以 127.0.0.1:3306 指的是 prod 自己的 MySQL。"},
 {"question":"`authorized_keys` 权限设成 644 会怎样？","options":["sshd 会拒绝使用它，登录失败","没有影响","性能下降"],"answer":0,"explain":"StrictModes 默认开启，组或其他用户可写的 authorized_keys 会被忽略，表现为「密钥明明加进去了还是让输密码」。"},
 {"question":"关掉密码登录前必须先做什么？","options":["另开一个终端验证密钥能登录","备份 sshd_config","重启服务器"],"answer":0,"explain":"配置改错或密钥没生效时，reload 之后就没有任何登录方式了，只能靠云控制台的 VNC 救。"}
]}
```

## 常见误区

### 误区：把 SSH 端口改成 2222 就安全了

**你以为**：非 22 端口能挡住攻击。
**其实**：只减少日志噪音，扫全端口的工具几分钟就能找到。真正的防线是**关掉密码登录** + 不用 root 登录 + 密钥 600。改端口最多算"降噪"，别把它当安全措施。
**怎么验证**：`journalctl -u ssh | grep -c "Failed password"` 改端口前后对比，噪音会降；但只要有密码登录，爆破照样在试。

### 误区：公钥和私钥都能拿来登录

**你以为**：两个文件差不多，随便拷一个过去就行。
**其实**：**私钥留在本机，永不外传；公钥（`.pub`）传到服务器**。反过来（把私钥拷到服务器）等于把身份扔在一堆人共用的机器上。`ssh-copy-id` 帮你做的是正确的方向。
**怎么验证**：看文件开头——公钥是一行以 `ssh-ed25519`/`ssh-rsa` 开头的文本；私钥是 `-----BEGIN OPENSSH PRIVATE KEY-----` 包起来的多行。前者可以随便贴，后者泄露了要立刻换。

### 误区：`scp -r` 和 `rsync -a` 是一回事

**你以为**：都传目录，随便选一个。
**其实**：`scp` 每次全量重传；`rsync` 只传差异，还能 `--delete` 做镜像。大目录、反复部署时差异是数量级的。而 `rsync` 的路径斜杠语义（`dist/` vs `dist`）与 `scp` 不同，第一次用时要把目标路径在 `--dry-run` 里看清楚。
**怎么验证**：`rsync -avz --dry-run dist/ prod:/opt/app/dist/` 看它会动哪些文件，再去掉 `--dry-run` 执行。

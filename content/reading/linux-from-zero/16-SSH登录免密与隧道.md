---
title: SSH：登录、免密与隧道
category: linux-from-zero
order: 16
minutes: 20
level: beginner
status: published
---

## 一条连接 5.7 秒，其中 TCP 只占 0.03 秒

`scripts/release.ps1` 里留着同一个脚本两次实测的两条注释：

- `scripts/release.ps1:383-384`：「实测每次连接 5.7 秒起步——TCP 到 22 端口只要 0.03 秒、服务器 usedns 已是 no」
- `scripts/release.ps1:14-15`：「远端只开两次 SSH 连接……Windows 上没法做连接复用（ControlMaster 传不了 socket fd），每次连接 5.7 秒起步」

两句拼出一个很具体的问题：TCP 到 22 端口只要 0.03 秒，一条 SSH 连接却要 5.7 秒。而它又换不了连接复用——脚本里每跑一条远端命令，这 5.7 秒就得重付一遍。

所以那 5.7 秒几乎全花在「TCP 连上之后」。连上之后还有三段，每段都在等对端回话：

| 段 | 谁先开口 | `ssh -v` 里属于这一段的标志行 | 卡在这里时先看哪 |
| --- | --- | --- | --- |
| TCP | 客户端发 SYN | `Connecting to <host> [<ip>] port 22.` → `Connection established.` | 路由、端口、安全组。这一段失败时，`Local version` 那行根本不会出现 |
| 版本交换 | 客户端先发自己的 banner，再读对端第一行 | `Local version string SSH-2.0-<客户端>` → `Remote protocol version 2.0, remote software version <服务端>` | 对面那个端口上跑的到底是不是 SSH |
| 密钥交换 | 双方各发一份 KEXINIT，发完不等对方，各读各的 | `SSH2_MSG_KEXINIT sent` / `received` → `kex: algorithm:` → `Server host key:` | 算法对不上，或者主机密钥变了（known_hosts 那一步） |
| 认证 | 客户端按服务端给出的方法逐个试 | `Authentications that can continue:` → `Offering public key:` | 公钥在不在服务端 `authorized_keys` 里、私钥有没有被送到客户端手上 |

这张表就是这一篇要讲的全部：**把 `ssh -v` 按段读**。它不是日志噪音，是一张「我排到第几步、在等谁」的进度表。`ssh -v` 只回答顺序，不回答耗时——5.7 秒怎么分配到四段上，得自己给每行打时间戳，我没做，所以这里只讲读法，不讲分配。

下面是一份真跑出来的完整输出，四段全在，用来对照上表（完整 37 行，删掉的是算法协商的重复行，剩下这 15 行）：这台机器是 Windows，`-F` 指向一个临时空配置，免得读到我本机 `~/.ssh/config`，密钥也是临时生成的。

```text
$ ssh -V
OpenSSH_for_Windows_8.1p1, LibreSSL 3.0.2

$ ssh -v -T -F .\empty_config -o IdentitiesOnly=yes -o BatchMode=yes \
    -o UserKnownHostsFile=.\gh_known_hosts -i .\lab_ed25519 git@github.com
debug1: Connecting to github.com [140.82.114.3] port 22.
debug1: Connection established.
debug1: Local version string SSH-2.0-OpenSSH_for_Windows_8.1
debug1: Remote protocol version 2.0, remote software version bdb6c72c
debug1: SSH2_MSG_KEXINIT sent
debug1: SSH2_MSG_KEXINIT received
debug1: kex: algorithm: curve25519-sha256
debug1: kex: host key algorithm: ssh-ed25519
debug1: Server host key: ssh-ed25519 SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU
debug1: Host 'github.com' is known and matches the ED25519 host key.
debug1: Authentications that can continue: publickey
debug1: Next authentication method: publickey
debug1: Offering public key: .\lab_ed25519 ED25519 SHA256:/2H1u6JkKt3m3HJG6yKAi1s4GWq8tDvtPwqEQ4VtWlg explicit
debug1: Authentications that can continue: publickey
debug1: No more authentication methods to try.
git@github.com: Permission denied (publickey).
```

最后一行退出码 255，原因很简单：那把临时密钥本来就没加到任何 GitHub 账户上。它反而是一次干净的演示——**服务端从头到尾只收到公钥，私钥一个字节都没离开本机**；指纹是客户端为了给你看才现算的。

「客户端先说」这个方向值得单独验一次，因为顺手就会讲反。我用 python 在本地起了三个假服务端，各接一次 `ssh -v 127.0.0.1:<端口>`：

```text
① 2223：accept 之后一个字不说
   debug1: Connection established.
   debug1: Local version string SSH-2.0-OpenSSH_for_Windows_8.1
   （12 秒无新输出，我手动结束）

② 2224：先发 SSH-2.0-lab-1.0\r\n，然后沉默
   debug1: Remote protocol version 2.0, remote software version lab-1.0
   debug1: no match: lab-1.0
   debug1: Authenticating to 127.0.0.1:2224 as 'lab'
   debug1: SSH2_MSG_KEXINIT sent
   （8 秒无新输出，我手动结束）

③ 2225：先回 HTTP/1.1 400 Bad Request\r\n\r\n
   debug1: kex_exchange_identification: banner line 0: HTTP/1.1 400 Bad Request
   debug1: kex_exchange_identification: banner line 1:
   Connection timed out during banner exchange
```

① 里客户端已经把 `SSH-2.0-...` 发出去了，然后一直等——**先自报版本、再读对端那行**，不是反过来。② 里客户端收下对端版本、发了自己的 KEXINIT，然后等对方那份：KEXINIT 没有谁必须先发。③ 是这三个里最有用的一个：对端第一行不是 `SSH-` 开头时，这个客户端不报「非法字符」，而是把那几行当成登录欢迎语收下来（`banner line 0/1`），然后继续等真正的版本行，等到超时。**所以当你连的 22 端口后面其实是个 HTTP 服务、反向代理或云防火墙时，`ssh` 的症状是「超时」而不是「格式错」**——别被那句 timeout 骗去查网络。

## 停下来的时候，最后一行是哪句

`ssh` 挂住或失败时，`-v` 的最后一行就是责任方。这张表按「最后一行」查，比按报错文字猜准：

| `-v` 最后停在这 | 停在哪一段 | 先看什么 |
| --- | --- | --- |
| `Connecting to ...` 之后没有 `Connection established.` | TCP | 端口通不通。这台 Windows 上 `ssh` 打一个没人监听的本地端口，整条命令 **2.08 秒**后才退出并报 `Connection refused`；第 15 课量到 curl 在同一台机器上报的那两秒并不在握手上（`%{time_connect}` 是 0），所以这里的两秒也不能当网络耗时的证据 |
| `Connection established.` 之后没有 `Remote protocol version` | 版本交换 | 对面端口上不是 SSH。这个构建会把非 `SSH-` 开头的行当欢迎语收下，等到超时为止 |
| `SSH2_MSG_KEXINIT sent` 之后没有 `received` | 密钥交换 | 对端发了版本行就不说话了——中间设备在按行过滤 |
| `Server host key:` 之后报主机密钥不匹配 | 密钥交换末段 | 服务器换过密钥，或者你被接到了别处。确认新指纹真属于这台机器再删 known_hosts 里那行 |
| `Authentications that can continue:` 之后没有 `Offering public key:` | 认证 | 客户端手上根本没有可用的私钥：`-i` 写错、agent 里没这把钥匙、或者 `IdentitiesOnly` 配上了但名字对不上 |
| 有 `Offering public key:`，之后又是 `Authentications that can continue:` | 认证 | 服务端收到了公钥但不认。查 `authorized_keys` 的内容、路径与权限（下一节） |
| `No more authentication methods to try.` | 认证结束 | 服务端只允许 publickey，而所有钥匙都被拒了。这句话本身就说明「密码这条路没开」 |

关于超时，补一个这台机器上量出来的边界：加 `-o ConnectTimeout=3` 去打那个「接了 TCP 但不说 SSH」的假服务端，**3.12 秒**就自己退出并报 `Connection timed out during banner exchange`。也就是说这个构建上 `ConnectTimeout` 把 banner 交换也一起限住了（同一轮里另一个封闭端口 3.05 秒报的是 `kex_exchange_identification: write: Connection refused`）。换版本换平台我没验，只报这一次。

## 密钥：指纹是公钥的摘要，不是文件的摘要

`ssh-keygen` 不带 `-t` 生成什么，取决于版本。这台机器（`OpenSSH_for_Windows_8.1p1`）上不给 `-t` 得到的是 RSA 3072：

```text
$ ssh-keygen -l -f defaultkey.pub
3072 SHA256:ENNxg0lCWYF3NiJb/85HQBn/04boQfmCvDzfP94pnr4 lenovo@LAPTOP-5M8H38DP (RSA)
```

而 OpenSSH 9.5 的 release note 写的是「ssh-keygen(1): generate Ed25519 keys by default」，2023-10-04 那版起生效（`https://www.openssh.org/txt/release-9.5`）。**同一句「不带参数就是默认算法」，在 8.x 和 9.5+ 上是两个不同答案**，这也是为什么这类文章必须标版本。要可移植，就把 `-t` 写上：

```text
$ ssh-keygen -t ed25519 -f .\lab_ed25519 -N '' -C 'lab@temp'
Generating public/private ed25519 key pair.
Your identification has been saved in .\lab_ed25519.
Your public key has been saved in .\lab_ed25519.pub.
The key fingerprint is:
SHA256:/2H1u6JkKt3m3HJG6yKAi1s4GWq8tDvtPwqEQ4VtWlg lab@temp
The key's randomart image is:
+--[ED25519 256]--+
...
+----[SHA256]-----+
```

私钥 399 字节、公钥 91 字节。有三件事可以当场验证，都不需要连服务器：

```text
# 1. 从私钥重算公钥，和 .pub 逐字比对
$ ssh-keygen -y -f .\lab_ed25519
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEn2s/BZIm1DojbWM9ooj3pOaTtd4eSktNNceVqqPKtm lab@temp

# 2. 对私钥和对 .pub 打指纹，得到同一个值
$ ssh-keygen -l -f .\lab_ed25519
256 SHA256:/2H1u6JkKt3m3HJG6yKAi1s4GWq8tDvtPwqEQ4VtWlg lab@temp (ED25519)
```

第 1 条说明 `.pub` 丢了能从私钥重建；第 2 条说明指纹是**公钥的摘要**，跟文件字节、跟注释都没关系——所以「把指纹报给我」只需要一串 `SHA256:`，不需要传文件。主机密钥也走这套：第一次连接时 `ssh` 问你要不要信任它，你可以拿 `ssh-keyscan` 抓到的指纹和官方公布的对一遍。GitHub 的 ed25519 主机密钥我扫出来是 `SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU`，和它官方文档（`docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints`）上公布的一致。

上传公钥这一步，这台机器上没有 `ssh-copy-id`（`command -v ssh-copy-id` 是空的），所以标准做法就是两步：把 `.pub` 传上去，然后追加进 `authorized_keys`。

```bash
# 标准形态，未实跑
scp ~/.ssh/id_ed25519.pub lab@host:/tmp/
ssh lab@host 'mkdir -p ~/.ssh && cat /tmp/id_ed25519.pub >> ~/.ssh/authorized_keys'
```

追加而不是覆盖，这一点很重要：`>` 会把别人的钥匙一起清掉，而且是静默清掉。

## config 里谁说了算：`ssh -G` 打出来的才算

`ssh -G` 不建立连接，只把「这条命令最终会用哪些参数」打印出来。我手上那份临时配置打出来 67 行，关键几行是：

```text
$ ssh -G -F .\lab_config lab
user labuser
hostname 127.0.0.1
port 2222
serveralivecountmax 3
serveraliveinterval 30
identityfile C:\Users\...\lab_ed25519
identityfile C:\Users\...\defaultkey
```

`identityfile` 出现两行，是因为它**追加**：先是 `Host lab` 块里那把，再是 `Host *` 块里那把，客户端会按顺序都试。而 `ServerAliveInterval`/`ServerAliveCountMax` 我只是写在文件里，`ssh -G` 就原样回显——这一条正好用来验证「改了怎么不生效」：以为生效了却没生效时，先 `ssh -G`，它打出来的就是生效值。

匹配规则我在这里猜反过一次。原始配置是 `Host *` 块写在前、`Host lab` 块写在后：

```text
$ ssh -G -F .\lab_config lab
user defaultuser
hostname 127.0.0.1
port 22
```

`Host lab` 明明更具体，却没赢：`user` 和 `port` 是前面那个 `Host *` 块给的，`hostname` 才是后面那块给的（因为前面的块没提到它）。`ssh_config(5)` 的原话是「Unless noted otherwise, for each keyword, the first obtained value will be used」——**文件里第一次拿到的值赢，不是越具体越优先**。想覆盖就用命令行，`-o` 最后说了算：同一套配置加 `-o Port=2200`，输出变成 `port 2200`。`sshd_config` 用的是同一句规则。

另外，别名不存在时不会报错：`ssh -G -F .\lab_config nonexistent-alias` 打出 `user lenovo`（本机登录名）、`hostname nonexistent-alias`（字面量）、`port 22`。别名没命中就当普通主机名用——所以「配了别名却连到别处」这种事故，`ssh -G` 一眼能看出来。

## 隧道：`-L` 在你这一端 listen，`-R` 在对面 listen

转发的参数也能从 `ssh -G` 里读出来，这是查「我到底把哪个端口转到哪里了」最省事的办法：

```text
$ ssh -G -F .\lab_config lab -L 3307:127.0.0.1:3306
localforward 3307 [127.0.0.1]:3306

$ ssh -G -F .\lab_config lab -L 3307:localhost:3306
localforward 3307 [localhost]:3306

$ ssh -G -F .\lab_config lab -L 0.0.0.0:3307:db.internal:3306
localforward [0.0.0.0]:3307 [db.internal]:3306

$ ssh -G -F .\lab_config lab -R 8080:127.0.0.1:8080
remoteforward 8080 [127.0.0.1]:8080

$ ssh -G -F .\lab_config lab -D 1080
dynamicforward 1080
```

四点读法：

- `-L [绑定地址:]本地端口:目标主机:目标端口`：**本地** listen 一个端口，连接进来后由**服务端**去连目标。目标主机是服务端解析的——所以写 `localhost` 指的是服务器自己，`ssh -G` 把这个名字原样留着（`[localhost]:3306`），不在本地解析。
- 不写绑定地址时，本地那一端默认只给本机用（`GatewayPorts` 控制这条默认），别人连不到。要开放得显式写 `0.0.0.0:3307:...`，代价是你那台机器上的任何人都能连进来。
- `-R 8080:127.0.0.1:8080`：方向反过来，**服务端** listen 8080，连进来的连接回到**你这端**的 `127.0.0.1:8080`。最后那个 `127.0.0.1` 是你这边的目标地址，不是服务器上的地址——这是 `-R` 最容易看反的一处，`ssh -G` 打出来的 `remoteforward 8080 [127.0.0.1]:8080` 里它就在主机位上。服务端到底绑在哪个地址，`sshd_config(5)` 写得很直接：「By default, sshd binds remote port forwardings to the loopback address. This prevents other remote hosts from connecting to forwarded ports」，`GatewayPorts` 默认 `no`。所以第三台机器连不上不是转发坏了；要让外面能用，就在服务端改 `GatewayPorts`，再用 `sshd -T | grep gatewayports` 确认生效值。
- `-D 1080` 是 SOCKS 代理，不在固定端口上 listen 一个目标。

转发全部标「标准形态，未实跑」：这台机器没有 sshd、没有 Linux 主机，我只验证了参数会被怎么记下来，没验证数据真的过去。真跑时的观察点在第 15 课那张表：`ss -lntp` 看本地那端有没有监听，再从那台机器上确认连接来自哪里。

## 两条权限规则，分属两端

「SSH 密钥文件权限」这句话底下其实是两条不同的规则，混在一起讲就会得出错的结论：

| 谁在检查 | 检查什么 | 判据 | 违反时你看到什么 |
| --- | --- | --- | --- |
| POSIX 上的 `ssh` 客户端 | 私钥文件本身 | 组和其他人不能有任何位：`mode & 077 == 0`，`600`、`400` 行，`640`、`644` 不行 | 客户端本地就拒用这把私钥并抱怨权限太开放，连接还没开始。**这条我没在这台机器上跑出来**，见下一行 |
| `OpenSSH_for_Windows_8.1p1` 客户端 | 同上，但这个构建不拦 | 我给私钥和 config 都加了 `Everyone:(F)`（全权），`ssh -v -i` 照常打出 `Offering public key:`，没有 `too open` 也没有 `Bad owner or permissions` | 什么都没发生。所以我只记现象，没去追它为什么 |
| 服务端 `sshd`（`StrictModes` 默认 `yes`） | `~/.ssh` 与 `authorized_keys` 的属主和**写**权限 | 属主必须是该用户，且组/他人不可写。`644` 的 `authorized_keys` 能用，`755` 的 `~/.ssh` 也能用；`664`、`666` 才会被忽略 | 客户端这一侧只看到还在要密码、或者 publickey 被拒——报错在服务端日志里 |

第三条的判据来自 `sshd_config(5)`：`StrictModes` 的说明是「防止新手把目录或文件放成谁都能写」，限制的是**写**，不是**读**。所以「`authorized_keys` 必须是 600」是错的：644 让人能读、没人能写，正是它接受的状态。如果你按「600 才安全」的思路去改而问题没解决，就会在错误的方向上耗时间——真正要查的是公钥那行有没有被换行拆断、`AuthorizedKeysFile` 指到哪、`~/.ssh` 的属主对不对。

Windows 上这套 `chmod` 根本不存在（`chmod` 在 PowerShell 里不是原生命令，`icacls` 才是），所以照抄 Linux 的权限表在新版 Windows 上不一定对得上——这也正是上面第二行那个「不拦」值得单独记一笔的原因。

## 跑一遍

真跑过的（全部在 `$env:TEMP` 下，没有动 `~/.ssh`）：`ssh -V`、`ssh-keygen` 生成（不带 `-t` 和 `-t ed25519` 各一次）、`ssh-keygen -y`、`ssh-keygen -l`、`ssh -G` 共六次（覆盖 `-L`/`-R`/`-D` 与匹配规则）、`ssh -v` 共四次（三个本地假服务端 + github.com）、`ssh-keygen -F` 与 `-R` 操作 `known_hosts`。

没跑的：`sshd` 本身（这台机器没装服务）、任何一次真实的端口转发、`ssh-copy-id`（没有这个命令）、`rsync`（同样没有，所以「比 `scp` 快很多」这类对比数字这一篇里一个都不给）。上面所有 `# 标准形态，未实跑` 的段、以及 `sshd -T` 的输出，都属于这一类。

## 动手

```code-challenge
{"id":"linux-16-ssh-perm","kind":"fill","title":"两端各一条权限规则，别记混","language":"bash",
 "template":"# 1. POSIX 上 OpenSSH 客户端接受的私钥权限：组与他人必须没有任何位\nchmod {{0}} ~/.ssh/id_ed25519\n# 2. 服务端 StrictModes 只要求组与他人不可写——下面哪个会让 sshd 忽略公钥\nchmod {{1}} ~/.ssh/authorized_keys\n# 3. 实际生效的服务端值（要 root）\nsshd -T | grep -E '^(strictmodes|pubkeyauthentication)'",
 "options":[["600","640","644"],["644","600","664"]],
 "answers":[0,2],
 "explain":"第一条是客户端自己检查私钥：600 是组与他人零权限，640 让同组能读、644 让所有人能读，都会被拒用。第二条是服务端检查 authorized_keys：StrictModes 只管写权限，所以 644 完全正常（能读、没人能写），只有 664 这种组可写的会被忽略。第三条不要背默认值——各发行版的打包配置会改它，sshd -T 打出来的才是它这次启动真正在用的值。"}
```

## 检查一下

```quiz
{"id":"linux-16-quiz","title":"按段读 ssh -v","questions":[
  {"question":"`ssh -v` 打出 `debug1: Local version string SSH-2.0-OpenSSH_for_Windows_8.1` 之后就再没有新输出，对端 TCP 明明是通的。卡在哪一段？","options":["认证：对端不接受我的公钥","版本交换：我报完了自己的版本，在等对端那一行，它一个字都没发","密钥交换：KEXINIT 发出去了没回音"],"answer":1,"explain":"这个客户端连上后先自报 banner，再读对端第一行。`SSH2_MSG_KEXINIT sent` 那行都没出现，说明还没进密钥交换，更没到认证。"},
  {"question":"公钥内容确认无误，服务端 `~/.ssh/authorized_keys` 权限是 `644`，登录仍被要求输密码。哪个判断对？","options":["644 不是原因：StrictModes 只要求属主对且组/他人不可写，该往公钥行、AuthorizedKeysFile 和属主上找","644 一定是原因：authorized_keys 必须只有属主可读","644 一定是原因：~/.ssh 必须是 700，755 会让 sshd 忽略里面的公钥"],"answer":0,"explain":"StrictModes 限制的是写权限，不是读权限，所以 644 的 authorized_keys 与 755 的 ~/.ssh 都被接受；664、666 这类组/他人可写的才会被忽略。"},
  {"question":"`ssh -R 8080:127.0.0.1:8080 server` 连上后，登录 server 本机 `curl 127.0.0.1:8080` 通，但第三台机器连 `server:8080` 连不上。最可能是？","options":["`-R` 只是把端口告诉服务端、本身不会在服务端 listen，得配 `-L` 才通","服务端 sshd 默认禁止转发，得先开 AllowTcpForwarding","服务端的远程转发默认只绑回环（GatewayPorts 默认 no），所以别的机器连不上——这是绑定地址的问题，不是转发坏了"],"answer":2,"explain":"sshd_config(5) 对 GatewayPorts 的说明是：默认把远程转发的端口绑在回环地址上，正是为了不让别的远端主机连进来。而 ssh -G 里那行 remoteforward 8080 [127.0.0.1]:8080 的 127.0.0.1 是回到你这端的目标地址，不是服务端绑定的地址，这两件事最容易混。AllowTcpForwarding 的默认值是 yes，所以不是「默认禁止转发」。"}
]}
```

## 还差的

- **真实转发一次都没通。** `-L`/`-R`/`-D` 我只验证了参数怎么落进生效配置，没验证数据真的流过去。想验就在一台有 sshd 的机器上起应用，`ssh -L` 过去再 `ss -lntp` 与目标侧两头对照。
- **服务端那半边的输出全是标准形态。** `sshd -T` 和 `journalctl -u ssh` 里的失败行我一条都没实际读到（这台机器没有 sshd）。文里引的默认值——`StrictModes` 是 yes、`GatewayPorts` 是 no、`PubkeyAuthentication` 是 yes——都摘自 `sshd_config(5)` 原文，不是从任何一台机器上 `sshd -T` 打出来的；各发行版打包会改，以实际打印为准。
- **5.7 秒的分布没量。** 我只知道它不在 TCP 上——仓库注释记的是每次连接 5.7 秒、TCP 0.03 秒。四段各占多少我不知道，`ssh -v` 只给顺序不给时间戳。想验就 `ssh -vvv -E 日志文件` 再拿每行时间戳算间隔。
- **这台 Windows 上两个耗时异常没查清。** 连没人听的本地端口要 2.08 秒才报拒绝；`ConnectTimeout=3` 会连 banner 阶段一起限住（3.12 秒退出）。我只记了 8.1p1 这一次的现象，没测其它版本，也没读实现，所以不外推。
- **免密登录的「服务端这半边」没有真机验证。** 上传公钥后第一次登录、`StrictModes` 在什么时候生效、`~/.ssh` 属主错了会报什么，全是按 `sshd_config(5)` 的语义推的，不是我看日志看到的。

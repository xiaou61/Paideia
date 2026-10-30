---
title: 第一个容器：docker run 拆开看
category: docker
order: 2
minutes: 22
level: beginner
status: published
---

## 这一关在解决什么

`docker run` 有几十个参数，但日常用的就八个。这一关把它们分成四组讲清楚：**跑在哪个模式（前台/后台）、叫什么名字、端口怎么通、用完删不删**。

```sketch
{
  "title": "docker run 其实做了四件事",
  "rows": [
    [
      { "id": "pull", "title": "拉镜像", "sub": "本地没有就先拉", "tone": "gray" },
      { "id": "layer", "title": "建可写层", "sub": "在只读层上叠一层", "tone": "blue" },
      { "id": "net", "title": "配网络与端口", "sub": "端口映射在这一步", "tone": "blue" },
      { "id": "start", "title": "起进程", "sub": "前台还是后台看 -d", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "pull", "to": "layer" },
    { "from": "layer", "to": "net" },
    { "from": "net", "to": "start" }
  ]
}
```

顺带解决四条高频报错：`port is already allocated`、`name is already in use`、`permission denied ... docker.sock`、`exec format error`。

## 为什么 `run` 其实做了四件事

```text
$ docker run hello-world
Unable to find image 'hello-world:latest' locally
latest: Pulling from library/hello-world
...
Hello from Docker!
```

这一条命令背后是：**拉镜像 → 创建容器（加可写层）→ 启动它 → 把输出接到你的终端**。对应四个可单独执行的命令 `docker pull`、`docker create`、`docker start`、`docker attach`。理解这个分解，后面所有操作都能对上号。

## 讲解

### 四组参数

**第一组：前台还是后台**

```text
$ docker run -it ubuntu bash        # 前台 + 交互，进去就是一个 shell
$ docker run -d nginx               # 后台，返回容器 ID
```

| 参数 | 含义 |
| --- | --- |
| `-i` | 保持 stdin 打开 |
| `-t` | 分配一个伪终端（TTY） |
| `-it` | 两个一起：**交互式**，必须有它才能进 shell |
| `-d` | detached，后台运行 |

`-it` 少了任何一个都不好用：只 `-i` 没有提示符，只 `-t` 输入不进去。

**第二组：名字与自动清理**

```text
$ docker run -d --name paideia-api nginx
$ docker run --rm alpine echo hi
```

| 参数 | 作用 |
| --- | --- |
| `--name` | 给容器起名。不起名 Docker 会随机生成（`festive_pasteur` 那种） |
| `--rm` | 容器退出后自动删除。**一次性任务必须加**，否则 `docker ps -a` 会堆几百个 Exited |

有名字之后，`docker logs 名字`、`docker exec 名字` 就不用每次查 ID。名字全机器唯一，重复用会报 `Conflict. The container name "/x" is already in use`（先 `docker rm` 旧的）。

**第三组：端口**

```text
$ docker run -d -p 8080:80 nginx
$ docker run -d -p 127.0.0.1:8080:80 nginx
$ docker run -d -p 8080:80 -p 8443:443 nginx
```

格式是 `宿主端口:容器端口`（还可以在最前面加宿主 IP）。

三个必须知道的行为：

- **`-p 8080:80` 默认绑在所有网卡上（`0.0.0.0`）**，也就是公网可达。想只给本机用，写 `-p 127.0.0.1:8080:80`。这是容器安全里最常见的一个疏忽（Linux 线第 15 关讲的是同一个道理）。
- `-p 8080:80` 里的 `8080` 不是容器里的端口。容器里 Nginx 还是听 80，只是宿主的 8080 转发到它。
- 宿主端口被占用时报 `Bind for 0.0.0.0:8080 failed: port is already allocated`。查是谁占的：`ss -lntp 'sport = :8080'`（Linux 线第 15 关）。

用 `-P`（大写）可以"随机映射所有 `EXPOSE` 的端口"，实际用的端口查：

```text
$ docker port <容器>
```

**第四组：环境变量与启动命令**

```text
$ docker run -d -e MYSQL_ROOT_PASSWORD=secret -e TZ=Asia/Shanghai mysql:8
$ docker run --rm mysql:8 mysqld --version      # 覆盖镜像的默认命令
```

`-e` 传环境变量（镜像文档里会列出来要哪些）。**命令行里的 `-e` 会进 `docker inspect` 和 shell 历史**，口令别这么传（第 12 关）。

镜像名后面的一切都**覆盖**镜像里定义的 `CMD`：

```text
$ docker run --rm ubuntu ls -l /     # 不启动 bash，直接跑 ls
```

所以 `docker run ubuntu` 会立刻退出——`bash` 没有终端、stdin 立刻 EOF，就退出了。这不是镜像坏了。

### 日常七个命令

```text
$ docker ps                    # 正在跑的
$ docker ps -a                 # 包含已退出的
$ docker logs -f 名            # 跟日志（-f 跟随，--since 10m 限定时间）
$ docker stop 名               # SIGTERM + 等 10 秒 + SIGKILL
$ docker kill 名               # 直接 SIGKILL
$ docker rm 名                 # 删已退出的；-f 连正在跑的强删
$ docker exec -it 名 sh        # 进正在跑的容器（第 10 关细讲）
```

`stop` 和 `rm` 的区别是最容易混的：**`stop` 只停，容器还在**（占磁盘、占 `docker ps -a` 的一行）；`rm` 才真正删。`stop` 之后要删还得 `docker rm`。

### 四条高频报错

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `port is already allocated` | 宿主端口被占（可能是别的容器） | `ss -lntp` 查；`docker ps` 看是不是旧容器还占着 |
| `Conflict. ... name is already in use` | 名字重复（含已退出的容器） | `docker rm 旧容器` 或换名字 |
| `permission denied while trying to connect to the Docker daemon socket` | 当前用户不在 `docker` 组 | 见下面的误区——**理解代价再决定** |
| `exec format error` | 镜像架构与宿主不符（如 arm64 镜像跑在 x86） | `docker image inspect --format '{{.Architecture}}'`；拉对平台或用 `--platform` |

最后一条在 Apple Silicon 上很常见：本地构建的镜像是 `arm64`，推到 x86 服务器上跑就 `exec format error`。构建时要显式指定平台（第 04 关）。

### 容器起不来，先看两处

```text
$ docker ps -a                                  # 状态码是什么
$ docker logs 容器                              # 主进程输出了什么
$ docker inspect 容器 --format '{{.State.ExitCode}} {{.State.Error}}'
```

退出码是判据（细节在第 10 关）：

| ExitCode | 常见含义 |
| --- | --- |
| `0` | 正常结束（一次性任务跑完了） |
| `1` | 应用自己报错退出 |
| `125` | Docker 自己拒绝启动（参数错、镜像不存在） |
| `126` | 命令存在但不可执行（没有执行权限） |
| `127` | 命令找不到（镜像里没这个程序，或 PATH 不对） |
| `137` | 被 SIGKILL 杀（多半是内存超限，`docker inspect` 的 `OOMKilled` 为 true） |
| `143` | 收到 SIGTERM 退出（`docker stop` 的正常结果） |

看到 `137` 就去查内存上限，不要怀疑代码。

## 动手

```code-challenge
{"id":"docker-02-loopback","kind":"fill","title":"只让本机能访问这个容器","language":"bash",
 "template":"# 宿主 8080 只绑回环，转发到容器 80\ndocker run -d --name web {{0}} nginx\ndocker ps --format '{{1}}'  # 确认只是 127.0.0.1:8080->80/tcp",
 "options":[["-p 127.0.0.1:8080:80","-p 8080:80","-P"],["{{.Ports}}","{{.Status}}","{{.Names}}"]],"answers":[0,0],
 "explain":"`-p 8080:80` 的宿主端默认绑 0.0.0.0（公网可达）；写全 `127.0.0.1:8080:80` 才只给本机。`docker ps --format '{{.Ports}}'` 能直接看到绑定地址。"}
```

## 检查一下

```quiz
{"id":"docker-02-quiz","title":"run 三问","questions":[
 {"question":"`-p 8080:80` 里的 8080 指谁的端口？","options":["宿主机的 8080，转发到容器的 80","容器的 8080","两边都是 8080"],"answer":0,"explain":"格式是 宿主端口:容器端口。容器里服务还是监听 80，不需要改配置。"},
 {"question":"容器状态是 Exited (137)，最可能是什么？","options":["被 SIGKILL 杀，常见于内存超限","应用正常退出","参数错误"],"answer":0,"explain":"137 = 128+9。先看 `docker inspect --format '{{.State.OOMKilled}}'`，true 就是内存上限太小。"},
 {"question":"为什么一次性任务要加 `--rm`？","options":["容器退出后自动删除，避免堆积占磁盘","让它运行更快","允许重复使用容器名"],"answer":0,"explain":"退出容器仍占磁盘与名字。--rm 让它退出即删；对 CI、批处理这类任务是默认选项。"}
]}
```

## 常见误区

### 误区：`-p 8080:80` 是"只给本机用"

**你以为**：端口映射是局部的。
**其实**：不写宿主 IP 时绑定的是 `0.0.0.0`，**公网可达**。所以只在本地开发用的服务要写 `-p 127.0.0.1:8080:80`，生产环境更应该只让反向代理访问容器网络，压根不做端口映射。
**怎么验证**：`docker ps --format '{{.Ports}}'` 显示 `0.0.0.0:8080->80/tcp` 就是全开；`127.0.0.1:8080->80/tcp` 才是本机。

### 误区：把用户加进 `docker` 组只是"免 sudo"

**你以为**：`usermod -aG docker $USER` 是个便利设置。
**其实**：**在 `docker` 组里等于拥有宿主 root**——能启动一个挂载 `/` 的容器，然后改宿主的任何文件。这不是"免 sudo"，是"永久免密码 root"。多用户机器上要谨慎授予，个人开发机可接受。
**怎么验证**：组里的普通用户跑 `docker run --rm -v /:/host alpine cat /host/etc/shadow`，能直接读到宿主的口令哈希。

### 误区：`docker stop` 会把容器删掉

**你以为**：停了就干净了。
**其实**：`stop` 只是结束进程，容器、它的可写层、它的名字都还在，占着磁盘和名字。长期不清理会出现"端口被占""名字重复"，以及磁盘悄悄被几十 GB 的 Exited 容器吃掉。
**怎么验证**：`docker ps -a | wc -l` 与 `docker ps | wc -l` 的差值就是堆积量；`docker system df` 看可回收空间。

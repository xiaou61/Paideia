---
title: 第一个容器：docker run 拆开看
category: docker
order: 2
minutes: 19
level: beginner
status: published
---

## `docker ps` 是空的，`web` 却还在 `docker ps -a` 里

```text
$ docker ps
CONTAINER ID   IMAGE   COMMAND   CREATED   STATUS   PORTS   NAMES

$ docker ps -a
CONTAINER ID   IMAGE        COMMAND                   CREATED       STATUS                   PORTS   NAMES
<container-id> nginx:1.30   "/docker-entrypoint.…"   2 hours ago   Exited (0) 2 hours ago           web
```

（标准输出形态；本机没装 Docker，未实跑。列宽随终端变化。）

上一篇留下的 `web` 还在，只是停了。`docker ps` 默认只看运行中的容器，`-a` 才看全部。`Exited` 不等于删除：容器、名字、可写层都还在。

这时候再跑一次同名的 `run`，会被名字挡住：

```text
$ docker run -d --name web -p 8080:80 nginx:1.30
docker: Error response from daemon: Conflict. The container name "/web" is already in use by container "<container-id>". You have to remove (or rename) that container to be able to reuse that name.
```

这不是 `-p` 写错了，是 `docker run` 在创建新容器，而旧容器还占着名字。`docker start web` 启动旧容器；`docker rm web` 删掉旧容器后，`run` 才能创建新的。

## `run` 不是 `start`，也不是 `restart`

`docker run` 做的是：本地没有镜像就先拉，创建容器，启动它；前台模式还会把终端接上去。对应可以拆成 `docker pull`、`docker create`、`docker start`、`docker attach`。`-d` 只是不 attach，仍然会创建并启动。

```text
$ docker rm web
web

$ docker run -d --name web -p 127.0.0.1:8080:80 nginx:1.30
<container-id>

$ docker ps
CONTAINER ID   IMAGE        COMMAND                   CREATED        STATUS        PORTS                      NAMES
<container-id> nginx:1.30   "/docker-entrypoint.…"   3 seconds ago  Up 2 seconds  127.0.0.1:8080->80/tcp     web

$ curl -I http://127.0.0.1:8080
HTTP/1.1 200 OK
...
```

第一次运行某个镜像时，`docker run` 会先拉取；老式输出以 `Unable to find image ... locally` 开头，containerd 镜像存储下的进度行可能不同。本地已有镜像就没有这段。拉取进度的具体行随镜像存储和终端变化，不要逐字抄。`-d` 成功时只打印容器 ID，不会把 nginx 的日志接到你的终端。

四个动作的区别：

| 命令 | 做什么 | 可写层 |
| --- | --- | --- |
| `docker run` | 创建新容器并启动 | 新的一层 |
| `docker start` | 启动已经存在的容器 | 沿用旧容器的那一层 |
| `docker restart` | 停止再启动同一个容器 | 沿用 |
| `docker exec` | 在运行中的容器里再起一个进程 | 不改主进程 |

所以 `docker start web` 不会读取你后来在命令行里写的 `-p`、`-e` 或新命令；它启动的是旧容器，配置还是创建时那份。要换配置，删掉旧容器重新 `run`。

## 端口、名字、清理

`-p 宿主端口:容器端口` 里的冒号两边不是同一个东西。下面三种发布方式依次演示；每次切换先删掉上一个 `web`：

```text
$ docker rm -f web
$ docker run -d --name web -p 8080:80 nginx:1.30

$ docker rm -f web
$ docker run -d --name web -p 127.0.0.1:8080:80 nginx:1.30

$ docker rm -f web
$ docker run -d --name web -P nginx:1.30
$ docker port web
```

- `-p 8080:80` 把宿主的 8080 转到容器的 80；容器里的 nginx 还是监听 80。
- 不写宿主 IP 时，宿主端绑在所有网卡上，`docker ps` 会显示 `0.0.0.0:8080->80/tcp`（有些配置还会多一行 IPv6）。只想本机访问，写 `127.0.0.1:8080:80`。
- `-P` 把镜像 `EXPOSE` 的端口随机映射到宿主；实际端口用 `docker port` 查。
- `EXPOSE` 本身只是声明，不会发布端口；真正发布靠 `-p` 或 `-P`。

实际只留一个，别把三个都开着。

名字也占资源。`--name` 在整台 Docker 主机上唯一，已退出的容器也算；不想留着就 `docker rm`。一次性任务加 `--rm`，容器退出就自动删；`--rm` 不能和 `--restart` 一起用。

`stop`、`kill`、`rm` 的区别：

下面四条是不同操作，按场景选；不要当成连续脚本：

```text
$ docker stop web       # 默认 SIGTERM；nginx 镜像是 SIGQUIT；Linux 等 10 秒，Windows 30 秒
$ docker kill web       # 直接 SIGKILL
$ docker rm web         # 删已停止的容器
$ docker rm -f web      # 运行中的也杀掉再删
```

`docker stop` 之后容器还在，只是 `Exited`；`docker rm` 才是删除。`docker ps -a` 里堆得多了，先看 `docker system df` 的 `RECLAIMABLE`，再决定是否 `docker container prune`。后者会删所有已停止容器，执行前确认没有还要 `start` 的。

## 日志和 exec 不是一回事

```text
$ docker logs --tail 100 web
$ docker logs -f --since 10m web
$ docker exec -it web sh
```

`docker logs` 读的是容器主进程写到 stdout/stderr 的内容。应用把日志写进容器里的文件，`docker logs` 就看不到；这时要 `docker exec` 进去看文件，或者把日志配到 stdout。

`docker exec` 只在运行中的容器里工作，它起的是一个新进程，不是“进入主进程”。主进程已经退出时，`exec` 会报容器没在运行。`-i` 保留 stdin，`-t` 分配 TTY；要正常交互，两个都要。

## `-e` 只是设环境变量

```text
$ docker run --rm -e TZ=Asia/Shanghai nginx:1.30 sh -c 'echo $TZ'
Asia/Shanghai
```

这条用一次性的 `sh` 打印变量，避免和前面运行中的 `web` 撞名字。`-e` 只是把变量放进进程环境，镜像里的程序读不读是另一回事。`nginx:1.30` 的监听端口写在配置文件里，`-e PORT=9090` 不会让它改听 9090。

`-e` 和 `--env-file` 的值都会进容器配置，`docker inspect` 能读到。别把口令长期放在命令行里：shell 历史、CI 日志、`docker inspect` 都可能留下。敏感值用 secret 挂载或外部密钥管理，并限制谁能访问 Docker daemon。

## 退出码先看 0、137、143

```text
$ docker inspect --format '{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}' web
running 0 false
```

`web` 还在跑时，`Status` 是 `running`，`ExitCode` 先当占位；停掉后才会变成 `exited` 加上真正的退出码。`State.ExitCode` 是容器主进程的退出码；`State.OOMKilled` 是 Docker 记录的 OOM 标记；`State.Error` 是 daemon 记录的启动错误，可能是空的。常见值：

| ExitCode | 含义 |
| --- | --- |
| `0` | 正常结束，一次性任务跑完了 |
| `1` | 应用自己报错退出 |
| `125` | Docker 自己拒绝启动，参数错或镜像不存在 |
| `126` | 命令存在但不可执行 |
| `127` | 命令找不到，镜像里没这个程序或 PATH 不对 |
| `137` | 被 SIGKILL 杀：可能是容器内存超限、`docker kill`、`docker stop` 超时，或宿主 OOM |
| `143` | 被 SIGTERM 的默认动作终止；如果应用处理了 SIGTERM 并正常退出，看到的可能是 `0` |

nginx 收到 SIGQUIT 正常退出时是 `0`；`143` 只说明进程被 SIGTERM 的默认动作终止，不是 nginx 的固定结果。

看到 `137` 不要只怀疑代码。先看 `OOMKilled`：`true` 是容器被 OOM killer 杀，先查容器内存上限；也可能是宿主内存压力。`false` 还要查 `docker kill` 和 `docker stop` 超时。

排查顺序：

```text
$ docker ps -a
$ docker logs --tail 100 web
$ docker inspect --format '{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}}' web
```

## 四条高频报错

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `port is already allocated` | 宿主端口被另一个运行中的容器或进程占了 | `docker ps` 看容器；Linux 上 `ss -lntp` 看进程 |
| `Conflict. ... name is already in use` | 名字重复，已退出的容器也算 | `docker rm` 旧容器，或换名字 |
| `permission denied ... docker.sock` | 当前用户不在 `docker` 组，或 socket 权限不对 | 理解代价再决定；加入 `docker` 组等于给宿主 root 权限 |
| `exec format error` | 镜像架构和宿主不符，或入口不是当前内核能执行的格式 | `docker image inspect --format '{{.Os}}/{{.Architecture}}'`；用 `--platform` 选对架构，必要时装模拟 |

最后一条在 Apple Silicon 上会遇到：本地构建的是 `arm64`，推到 `x86_64` 服务器上跑就报 `exec format error`。`--platform linux/amd64` 可以选镜像架构，但跨架构运行需要 Docker Desktop 的模拟或 Linux 上的 binfmt/qemu，不是免费的性能。

## 动手

```code-challenge
{"id":"docker-02-loopback","kind":"fill","title":"只让本机能访问这个容器","language":"bash",
 "template":"# 宿主 8080 只绑回环，转发到容器 80\ndocker run -d --name web-challenge {{0}} nginx:1.30\ndocker ps --format '{{1}}'  # 确认只是 127.0.0.1:8080->80/tcp",
 "options":[["-p 127.0.0.1:8080:80","-p 8080:80","-P"],["{{.Ports}}","{{.Status}}","{{.Names}}"]],"answers":[0,0],
 "explain":"`-p 8080:80` 的宿主端默认绑 0.0.0.0；写全 `127.0.0.1:8080:80` 才只给本机。`docker ps --format '{{.Ports}}'` 直接显示绑定地址。"}
```

## 检查一下

```quiz
{"id":"docker-02-quiz","title":"run 三问","questions":[
 {"question":"`-p 8080:80` 里的 8080 指谁的端口？","options":["宿主机的 8080，转发到容器的 80","容器的 8080","两边都是 8080"],"answer":0,"explain":"格式是宿主端口:容器端口。容器里的 nginx 仍然监听 80。"},
 {"question":"容器状态是 `Exited (137)`，先查什么？","options":["`OOMKilled` 是否为 true，再查 docker kill、stop 超时和宿主 OOM","直接改代码","重新拉镜像"],"answer":0,"explain":"137 = 128 + 9。`OOMKilled` 为 true 是容器被 OOM killer 杀，先查容器内存上限；false 还要查 docker kill、stop 超时和宿主内存，不能只归咎于应用。"},
 {"question":"`docker run` 和 `docker start` 的区别是什么？","options":["run 创建新容器；start 启动已有容器","run 只能前台，start 只能后台","两者完全一样"],"answer":0,"explain":"`run` = 创建 + 启动（前台还会 attach）；`start` 只启动已经存在的容器，保留它的可写层和配置。"}
]}
```

## 还差的

这篇只讲单容器的手工命令。卷、容器网络、Compose 分别在 05、06、07 关；端口在 IPv4/IPv6 双栈、宿主防火墙/nftables 下的实际行为没有在本机验证。`exec format error` 只给了判断路径，没有在 arm64 机器上复现。本机没装 Docker，所有回显都是标准形态。

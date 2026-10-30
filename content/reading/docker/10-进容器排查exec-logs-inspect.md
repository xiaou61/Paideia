---
title: 进容器排查：exec、logs、inspect
category: docker
order: 10
minutes: 24
level: beginner
status: published
---

## 这一关在解决什么

容器起不来、起来了连不上、跑着跑着死了——三种情况的查法完全不同，用错工具只会得到"看不到日志"。

这一关给一套固定顺序：**先看状态与退出码，再看日志，再看配置，最后才进去**。

```sketch
{
  "title": "排查顺序：状态 → 日志 → 配置 → 进去",
  "rows": [
    [
      { "id": "state", "title": "看状态与退出码", "sub": "docker ps -a", "tone": "gray" },
      { "id": "log", "title": "看日志", "sub": "docker logs", "tone": "blue" },
      { "id": "conf", "title": "看配置", "sub": "inspect 出的环境与挂载", "tone": "blue" },
      { "id": "exec", "title": "最后才进去", "sub": "容器还活着时 exec 才有用", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "state", "to": "log" },
    { "from": "log", "to": "conf" },
    { "from": "conf", "to": "exec" }
  ]
}
```

## 为什么顺序重要

因为"进容器"（`docker exec`）**只对正在运行的容器有效**。容器已经 Exited，`exec` 直接报 `container is not running`——很多人卡在这一步，然后去猜。

所以第一问永远是：**它现在是什么状态？**

## 讲解

### 第一步：状态与退出码

```text
$ docker ps -a
CONTAINER ID   IMAGE        STATUS                     NAMES
4c1d2e3f4a5b   myapp:v1     Exited (1) 2 minutes ago   api
9e8d7c6b5a41   postgres:16  Up 3 minutes (healthy)     db
```

`STATUS` 列直接给出结论：

| 显示 | 含义 |
| --- | --- |
| `Up 3 minutes` | 主进程在跑 |
| `Up 3 minutes (healthy)` | 健康检查通过（镜像里定义了 `HEALTHCHECK`） |
| `Up 3 minutes (unhealthy)` | 在跑但检查失败——**应用层有问题** |
| `Exited (0)` | 正常结束（一次性任务就是这样） |
| `Exited (1)` | 应用自己报错退出 |
| `Restarting (1) 5 seconds ago` | 反复崩溃重启 |
| `Created` | 创建了但没启动 |

退出码对照（第 02 关列过，这里按排查价值重排）：

| 码 | 优先怀疑 |
| --- | --- |
| `1` | 应用自己的错误 → **看日志** |
| `137` | 内存超限被 SIGKILL → 查 `OOMKilled` |
| `143` | 收到 SIGTERM（`docker stop` 的正常结果） |
| `127` | 镜像里没有这个命令 → 查 `ENTRYPOINT`/`CMD` 与 PATH |
| `126` | 命令存在但不能执行（缺 `+x` 或 shebang 错） |
| `125` | Docker 拒绝启动（参数/镜像问题） |

```text
$ docker inspect api --format 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}} err={{.State.Error}}'
exit=137 oom=true err=
```

`oom=true` 就到此为止了，不用再查代码——去调 `--memory` 或查内存泄漏（第 09 关）。

### 第二步：日志

```text
$ docker logs api
$ docker logs --tail 100 -f api
$ docker logs --since 10m --until 5m api
$ docker logs -t api                 # 带时间戳
```

**`docker logs` 只能看到主进程写到 stdout/stderr 的内容**。如果应用把日志写进了文件（`/var/log/app.log`），`docker logs` 是空的——这不是"没有日志"，是"日志没走标准输出"。

两种情况分别处理：

```text
# 日志在容器文件系统里
$ docker exec api tail -n 100 /var/log/app.log
$ docker cp api:/var/log/app.log ./app.log        # 拿到宿主上看

# 容器已经退出了，exec 不了 —— 用 cp 取（cp 对停止的容器也有效）
$ docker cp api:/var/log/app.log ./app.log
```

`docker cp` 是"容器已停但你想看里面文件"的唯一正路。

### 第三步：配置到底成了什么

```text
$ docker inspect api
```

输出很长，五个字段最有用：

```text
$ docker inspect api --format '{{json .Config.Env}}'
$ docker inspect api --format '{{json .Config.Cmd}} {{json .Config.Entrypoint}}'
$ docker inspect api --format '{{json .Mounts}}'
$ docker inspect api --format '{{json .NetworkSettings.Ports}}'
$ docker inspect api --format '{{.State.StartedAt}} {{.RestartCount}}'
```

| 字段 | 回答什么问题 |
| --- | --- |
| `State.StartedAt` | 到底什么时候启动的（对比"我以为部署了"） |
| `RestartCount` | 重启了几次（不是"重启了吗"，是"重启了多少次"） |
| `Config.Env` | 环境变量真的传进去了吗 |
| `Config.Cmd` / `Entrypoint` | 最终执行的命令是什么（镜像的 + 你覆盖的） |
| `Mounts` | 卷/绑定挂载有没有生效（空 = 数据在可写层） |
| `NetworkSettings.Ports` | 端口映射有没有生效 |

**"我明明设了环境变量"** 与 **"配置挂载了但没生效"** 这两类问题，答案都在这里。

### 第四步：进容器看

```text
$ docker exec -it api sh
$ docker exec -u 0 api sh              # 以 root 进去（镜像默认用户不是 root 时）
$ docker exec -w /app api ls -l
$ docker exec api env | sort
$ docker exec api ss -lntp             # 容器里谁在监听
```

几个要点：

- **`sh` 而不是 `bash`**：精简镜像（Alpine、distroless 基础镜像）里只有 `sh`。
- **镜像里没有 shell 时** `exec` 会失败。distroless 是刻意不给 shell 的，这时候用 `docker cp` 取文件，或用 `--network container:` 起一个工具容器（第 06 关），或者 `docker debug`（需要订阅）。
- **`exec` 是新建进程**，它有自己的环境（多数继承容器配置），改了它的环境不影响主进程。

### 第五步：容器根本起不来怎么办

如果 `Exited (127)` 且日志是空的，你需要"在不启动它的情况下"检查镜像：

```text
$ docker run --rm -it --entrypoint sh 镜像      # 强制用 sh 进入，绕过 ENTRYPOINT
$ docker create --name probe 镜像 && docker cp probe:/app ./app-from-image && docker rm probe
```

第一条是最常用的：`--entrypoint sh` 让你绕过默认启动命令，看看镜像里到底有什么、PATH 对不对、jar 在不在。

### 常用事件与统计

```text
$ docker events --filter container=api --since 10m     # 启动/停止/重启事件流
$ docker top api                                       # 容器里的进程（宿主 PID 也能看到）
$ docker stats --no-stream api
$ docker port api
```

`docker events` 在"服务莫名重启"时特别有用——它记录的是 Docker 层的事件，比应用日志更早、更客观。

### 网络排查的三个入口

```text
$ docker exec api getent hosts db        # 名字解析（第 06 关）
$ docker exec api wget -qO- http://db:5432 2>&1 | head   # 或者用应用自带工具
$ docker run --rm --network container:api nicolaka/netshoot ss -lntp
```

第三条是"镜像里没工具"的通用解：起一个装了全套网络工具的容器，共享目标容器的网络命名空间。不动目标镜像、不影响它运行。

## 动手

```code-challenge
{"id":"docker-10-triage-order","kind":"order","title":"容器起不来时的排查顺序","lines":[
 "docker ps -a",
 "docker logs 容器",
 "docker inspect 容器 --format 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}'",
 "docker inspect 容器 --format '{{json .Config.Env}} {{json .Mounts}}'",
 "docker run --rm -it --entrypoint sh 镜像"
],"answer":[0,1,2,3,4],
 "explain":"先确认状态与退出码（决定后面查什么），再看主进程输出，再看 OOM 与退出原因，然后核对配置是否真的生效，最后才绕过 ENTRYPOINT 进镜像检查环境。注意 Exited 的容器不能 exec。"}
```

## 检查一下

```quiz
{"id":"docker-10-quiz","title":"排查三问","questions":[
 {"question":"容器已经 Exited，`docker exec` 报 not running，怎么取里面的日志文件？","options":["用 `docker cp 容器:/路径 ./本地`","先 exec 再 cat","只能重启容器"],"answer":0,"explain":"exec 只对运行中的容器有效；docker cp 对停止的容器仍然可用，是唯一不重启就能取文件的办法。"},
 {"question":"`docker logs` 是空的，但应用确实在打日志，为什么？","options":["日志写进了文件，没走 stdout/stderr","日志驱动被禁用了","需要 -f"],"answer":0,"explain":"docker logs 只采集主进程的 stdout/stderr。写文件的日志要用 docker exec tail 或 docker cp 取。"},
 {"question":"`Exited (137)` 且 `OOMKilled=true`，下一步？","options":["调 --memory 或查内存泄漏","改 ENTRYPOINT","重建镜像"],"answer":0,"explain":"137 是 SIGKILL，OOMKilled 确认是内存超限被内核杀。先看 docker stats 的峰值与 JVM 堆设置。"}
]}
```

## 常见误区

### 误区：镜像里装个 bash/curl 方便排查

**你以为**：出问题能进去看，值得。
**其实**：调试工具让镜像变大、攻击面变宽（curl 能下载并执行任意东西），而且**生产上真出事时你未必能进得去**（容器已停、或集群不给你 exec）。更稳的做法是把排查能力放在外面：`docker logs`、`docker inspect`、`docker cp`、共享网络命名空间的工具容器。
**怎么验证**：`docker run --rm --network container:api nicolaka/netshoot ss -lntp` 一行就能看到目标容器的监听，完全不需要它自带工具。

### 误区：`docker logs -f` 看到最后一行就是"最新状态"

**你以为**：日志停了说明服务卡住了。
**其实**：应用可能只是这段时间没有输出（空闲时正常）。反过来，日志刷得飞快也可能是健康检查在自己打日志。判断"活着吗"要看 `docker ps` 的 `STATUS`、`docker stats` 的 CPU，以及**端口能不能连上**。
**怎么验证**：`docker exec api ss -lntp` 与从宿主 `curl -I 127.0.0.1:映射端口` 两条一起看。

### 误区：`docker restart` 能修好一切

**你以为**：出问题就重启，简单。
**其实**：重启会丢掉可写层里没挂载的改动（第 05 关）、清空内存里的状态（缓存、连接池、未完成的任务），而且**不改变根因**——如果是配置错，重启后照样错，只是把日志里的第一条错误冲掉了。先按本关的顺序留下证据（`docker inspect` 的输出、日志片段），再考虑重启。
**怎么验证**：`docker inspect --format '{{.RestartCount}} {{.State.StartedAt}}'`，重启次数高说明每次都在修表面。

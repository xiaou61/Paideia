---
title: Compose：一份 yml 起整套环境
category: docker
order: 7
minutes: 21
level: beginner
status: published
---

## 一条 28 个元素的 `docker run`

SQL 沙箱起容器，实际执行的命令长这样（值取自 `backend/paideia-app/src/main/resources/application.yml:234-239` 与代码里的常量，未实跑）：

```text
docker run -d --name paideia-sql-pool-0 \
  --network=none --read-only --tmpfs /tmp:rw,size=768m,exec \
  --user 999:999 --cap-drop=ALL --security-opt=no-new-privileges \
  --memory=512m --memory-swap=512m --cpus=1 --pids-limit=128 \
  --ulimit nofile=1024 --ulimit nproc=128 --stop-timeout=0 -e TZ=UTC \
  paideia-sandbox-mysql@sha256:e2eafb6ea161ddda420bde0f8d5ec3b0b03115811ea9507a23710034ef6d0a4f \
  sh -c '<boot.sh>'
```

它不是手打的，是 `backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/SqlContainerPool.java:129-149` 里用 `List.of` 拼出来的：28 个元素（`/tmp:rw,size=768m,exec` 算一个）。同一个文件的 `waitReady` 接着每 200 毫秒 `docker exec` 进去跑一次 `mysqladmin --socket=/tmp/m.sock -uroot ping`，30 秒还没通就 `docker rm -f` 掉重来。

「按固定配置起一个长期存在的容器」加上「等它能应答」——这两件事正是 Compose 存在的理由。所以我把这 28 个元素当成一道题：多少能写成 yml 的键，写成文件之后多拿到什么，写不过去的那部分又卡在哪。

先交代一句本机的实话，它决定你该怎么读后面的回显：

```text
PS> docker --version
docker: The term 'docker' is not recognized as a name of a cmdlet, function, script file,
or executable program.
```

**下面所有 `docker compose` 的输出都按官方文档写成标准形态，没有一条在本机跑过。** 能和真实代码对上的，只有仓库里的文件和命令本身。

顺便说清这条命令为什么值得单独存在。一次性起容器每次执行要付三笔固定成本：起容器 1.1 秒、拷 78MB 基准 datadir 1.05 秒、mysqld 初始化 InnoDB 约 2 秒（`SqlContainerPool.java:19-21`，仓库自己的记录）。那段注释还写着「线上实测一次 SQL 要 3.5–4.2 秒，而数据集只有 1.4 KB」，池化之后约 120 毫秒。慢的不是 SQL，是每次都重开一台机器——所以池子要的是常驻容器，接下来这份 yml 描述的也是常驻容器。

## 翻得过去的部分

```yaml
name: paideia-sandbox

services:
  sql:
    image: paideia-sandbox-mysql@sha256:e2eafb6ea161ddda420bde0f8d5ec3b0b03115811ea9507a23710034ef6d0a4f
    read_only: true
    network_mode: none
    tmpfs:
      - /tmp
    user: '999:999'
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    mem_limit: 512m
    memswap_limit: 512m
    cpus: 1
    pids_limit: 128
    ulimits:
      nofile: 1024
      nproc: 128
    stop_grace_period: 0s
    environment:
      TZ: UTC
    entrypoint: [sh]
    command: [-c, /usr/local/bin/boot.sh]
    healthcheck:
      test: ['CMD-SHELL', 'mysqladmin --socket=/tmp/m.sock -uroot ping']
      interval: 200ms
      retries: 150
```

逐行对回那条命令，14 行里 11 行是一对一的直译：

| 命令里的参数 | yml 里的键 | 换成键之后影响什么 |
| --- | --- | --- |
| `--network=none` | `network_mode: none` | 容器里没有网卡。慢 SQL 外连不出去，「连不上」和「服务没起」从此是两个不同信号（第 06 关） |
| `--read-only` | `read_only: true` | 根文件系统只读。mysqld 默认往 `/var/run/mysqld` 写 pid 和 socket，写不进去时它不报错也不退出——所以 `--socket`、`--pid-file` 必须指到 tmpfs |
| `--user 999:999` | `user: '999:999'` | 进程不是 root。写的是 uid:gid 而不是用户名；镜像里 999 就是 mysql 用户，datadir 的属主得跟着它 |
| `--cap-drop=ALL` | `cap_drop: - ALL` | 内核能力全丢，容器里没有 `chown root`、绑低端口这类手段 |
| `--security-opt=no-new-privileges` | `security_opt: - no-new-privileges:true` | setuid 二进制也提不了权，加固是叠加的，不靠某一条 |
| `--memory=512m` `--memory-swap=512m` | `mem_limit` 加 `memswap_limit` | 两个都写死时容器不能用 swap。仓库这两处记录对不上：`application.yml:236` 配的是 512m，`SqlContainerPool.java:137-139` 记着 512m 时 mysqld 会被 OOM 静默杀掉、1g 正常（2026-10-28）。我没复现，照抄配置翻进 yml 就还是 512m |
| `--cpus=1` | `cpus: 1` | 卡的是 CPU 时间片上限，不是速度；压狠了只会让启动更慢 |
| `--pids-limit=128` | `pids_limit: 128` | 别照抄 Java/Python 那档的 64：`application.yml:238` 写着 mysqld 是多线程的，启动期就要几十个线程 |
| `--ulimit nofile=1024` `--ulimit nproc=128` | `ulimits:` 下两个整数 | 单写整数就是软硬上限设成同一个值 |
| `--stop-timeout=0` | `stop_grace_period: 0s` | 停容器不等它体面退出。datadir 在 tmpfs 里，容器一没数据就没了，等也没用；换成命名卷的地方别这么写 |
| `-e TZ=UTC` | `environment: TZ: UTC` | 时区跟着容器走，`NOW()` 和日志时间才和面板上看到的一致 |

还有三行不是直译，动手之前得知道要连带改别的：

| 命令里的参数 | 为什么不能直接抄 |
| --- | --- |
| `--name paideia-sql-pool-0` | 这一行我**故意没写** `container_name`。写了它，这个服务就只能有 1 个副本；而池子要的恰好是 0 到 N 编号的多台 |
| `--tmpfs /tmp:rw,size=768m,exec` | `tmpfs:` 收的是挂载点清单，只写 `/tmp` 就丢掉了大小和 `exec`。长语法 `mounts[].tmpfs` 只收 `size` 与 `mode` 两个键，`exec` 没有对应项——这是本篇唯一搬不过去的那个参数，见「还差的」 |
| `sh -c '<boot.sh>'` | 那段脚本在 Java 里是一个字符串常量。搬进 yml 之前得先把它落到镜像里（`COPY boot.sh /usr/local/bin/`），那就不是「换个写法」，是改镜像构建 |

参数能对上，不等于行为能对上。写进文件之后真正多出来的是另外三件事，命令行给不了。

## 多出来的第一样：一个项目边界

项目名决定三样东西的前缀：容器名、网络名、卷名。默认取 compose 文件所在目录名，优先级是 `-p` 参数 > `COMPOSE_PROJECT_NAME` 环境变量 > 文件里的顶层 `name:` > 目录名（官方文档写的顺序）。上面那份文件写了 `name: paideia-sandbox`，所以在哪个目录执行都叫这个名字。

这就是「一份 yml 起整套环境」里「整套」的全部含义：不是功能上多出什么，是这一组东西有了同一个名字，`docker compose ps` 一次列全，`down` 一次收干净。

名字也不是审美问题。池子的容器叫 `paideia-sql-pool-0`，是 `"paideia-sql-pool-" + slot.index` 拼出来的（`SqlContainerPool.java:123`），因为后面每次执行都要 `docker exec -i <那个名字>` 进去灌 SQL。交给 compose 之后，同一个位置会变成 `paideia-sandbox-sql-1`（项目名-服务名-序号，从 1 起）——代码要按名字进容器，名字谁定就成了后面那道坎。

## 多出来的第二样：`up` 什么时候会重建

`up -d` 不是每次都重建。容器已经在、服务配置没变、镜像也没变，它什么都不做。会让它 stop 再 recreate 的，是「容器创建之后，配置或镜像变了」。

真实后果：改完 Dockerfile 里那段自检脚本，`docker compose up -d` 之后容器里跑的还是旧的——没有 build，镜像没变，容器就没有理由重建。`down` 再 `up` 也一样，它只是拿同一个旧镜像重起一次。

要它一定按新东西起，三个开关：

- `--build`：起之前先构建，改的是构建产物时用它
- `--force-recreate`：不管变没变都重建，调试用
- `--no-recreate`：反过来，拦着它重建

`up -d` 返回 0 只说明容器在跑，不说明能应答。要等健康状态通过才返回，用 `up -d --wait`（隐含 `-d`）。上面那份 yml 里的 `healthcheck` 就是给这个用的：`interval: 200ms` 加 `retries: 150` 抄的是 `waitReady` 的节奏——每 200 毫秒 ping 一次、30 秒放弃，只是这 30 秒由 Docker 帮你数，不用自己写循环。为什么 `depends_on` 的短写法靠不住、`service_healthy` 又该怎么用，第 15 关整篇在拆，这里不展开。

## 多出来的第三样：`down` 删到哪一层

这块最容易出事，三档差别只在删不删卷：

| 命令 | 容器与网络 | `volumes:` 里的命名卷 | 匿名卷 |
| --- | --- | --- | --- |
| `docker compose stop` | 停容器，不删 | 留 | 留 |
| `docker compose down` | 删掉文件里定义的服务容器、默认网络、`networks:` 里定义的网络 | 留 | 留 |
| `docker compose down -v` | 同上 | 删 | 删 |

绑定挂载（`./data:/var/lib/mysql` 这种）不在任何一行的删除范围里——`-v` 删不到宿主机目录，那得自己看着办。`external: true` 的网络和卷在任何一档里都不删，因为那不是这份文件创建的。第 11 关里用 `down -v` 重置数据库，用的就是这张表最后一行。

## 翻不过去的：单位是「第 3 号槽位」

池子要的三个动作，在 compose 里找不到对应：

- **单独重建一个槽位。** `ensureContainer` 是 `docker rm -f <名字>` 再 `docker run`，只动那一台（`SqlContainerPool.java:128-150`）。compose 的粒度是「服务」：`docker compose rm -sf sql` 会把 sql 的所有副本一起删，`up -d sql` 再一起起。没有「只换第 3 个」这个操作。
- **自己决定容器叫什么。** 前面说过，`docker exec` 是按名字进去的；compose 那边名字由项目名、服务名、序号拼，要拿回来只能 `docker compose ps --format json` 或者写死 `container_name`——后者把副本数锁成 1。
- **每次执行往容器里灌数据。** `docker exec -i` 的 stdin 上写的是两段帧：先数据集长度和内容，再 SQL 长度和内容（`SqlContainerPool.java:256-264`）。compose 里的 `stdin_open: true` 只是把管道打开，往里写什么、什么时候关，仍然是 compose 之外的事。

一次性那条路（`backend/paideia-sandbox/src/main/java/io/github/xiaou61/sandbox/internal/run/DockerCommand.java:295-331`）里还有两样在 compose 里根本没有键：`--rm`（服务不是这么用的，`docker compose run --rm` 是一次性任务）和 `--cidfile`。我逐键核了 compose-spec 的 schema（`schema/compose-spec.json`，91,542 字节那份）：`cidfile` 出现 0 次，而 `mem_limit`、`memswap_limit`、`cpus`、`pids_limit`、`stop_grace_period`、`cap_drop`、`security_opt`、`stdin_open`、`tmpfs` 都在。

所以这个仓库里没有 compose 文件，不是漏了。池子的单位是「第 3 号槽位」，compose 的单位是「文件里定义的那一组」；把槽位交给 compose，等于把容器名和生命周期交出去，而这两样恰好是按需 `exec` 和单独重建依赖的东西。

要给本地调试写一份，就是上面那份 yml：起一台和池子同配置的 sql，`down` 一次收干净。

## 症状先看哪一条

| 症状 | 先跑 | 为什么 |
| --- | --- | --- |
| `up -d` 之后容器里还是旧脚本 | `docker compose config` 看一眼，再 `docker compose up -d --build` | 配置和镜像都没变时 compose 不重建；改了 Dockerfile 不 build 就不会重建 |
| `up -d` 返回 0，但服务还不能应答 | `docker compose up -d --wait`，或者 `docker compose ps` 看状态 | `up -d` 只保证容器在跑；健康状态要 `--wait` 才等 |
| 同一份文件在不同目录执行，出来两套容器 | `docker compose ls` 看项目名，需要时用 `-p` 固定 | 项目名默认取文件所在目录名 |
| `down` 之后数据还在，或者数据没了 | 先 `docker volume ls`，再看 `/tmp` 之外有没有 `volumes:` | 默认不删卷；`-v` 才删命名卷和匿名卷 |
| 服务之间连不上 | `docker compose ps` 看是不是同一个项目，再确认 `network_mode` 不是 `none` | 不同项目各有一套网络；`none` 的容器本来就没网卡 |

## 跑一遍（标准形态，未实跑）

```bash
docker compose config
docker compose up -d --wait
docker compose ps
docker compose down
```

`config` 值得先说：它把文件解析成最终模型再打印出来，插值替换过的值、补上的默认值、被丢弃的废弃键，在这一步都能看见。键会按字母序重排，所以别拿输出跟手写文件逐行对位置。

```text
> docker compose config
name: paideia-sandbox
services:
  sql:
    cap_drop:
      - ALL
    cpus: 1
    environment:
      TZ: UTC
    healthcheck:
      test:
        - CMD-SHELL
        - mysqladmin --socket=/tmp/m.sock -uroot ping
    ...
    read_only: true
    stop_grace_period: 0s
```

```text
> docker compose up -d --wait
[+] Running 2/2
 ✔ Network paideia-sandbox_default  Created
 ✔ Container paideia-sandbox-sql-1  Healthy
```

两行就够说明问题：网络是 compose 顺手建的（名字带项目前缀），容器名里的序号从 1 开始。`down` 之后这两样都没了，卷还在。

## 你填两处

下面这份是池子那份 yml 的骨架，两处空着（镜像换成本地 tag，省掉那串 digest）：

```code-challenge
{"id":"docker-07-healthy","kind":"fill","title":"把池子的 user 与 healthcheck 补回来","language":"yaml",
 "template":"services:\n  sql:\n    image: paideia-sandbox-mysql:local\n    read_only: true\n    network_mode: none\n    tmpfs:\n      - /tmp\n    user: '{{0}}'\n    healthcheck:\n      test: [{{1}}, 'mysqladmin --socket=/tmp/m.sock -uroot ping']\n      interval: 200ms\n      retries: 150",
 "options":[["999:999","root","mysql"],["CMD-SHELL","CMD","SHELL"]],"answers":[0,0],
 "explain":"`--user 999:999` 翻成 `user: '999:999'`，写的是 uid:gid 而不是用户名；镜像里这个 uid 就是 mysql 用户。`test` 的第一项只有 `CMD-SHELL` 与 `CMD` 两种：要 shell 解释（引号、重定向、`&&`）用 `CMD-SHELL`，直接把 argv 交给 exec 用 `CMD`；`SHELL` 不是合法值。"}
```

## 自测三题

```quiz
{"id":"docker-07-quiz","title":"Compose 三问","questions":[
 {"question":"改完 Dockerfile 里那段自检脚本，`docker compose up -d` 之后容器里跑的还是旧的。先做什么？","options":["先 `docker compose down` 再 `up -d`","先 `docker compose up -d --build`","先 `docker pull` 一个新 tag"],"answer":1,"explain":"`down` 之后 `up` 只是拿同一个没变过的镜像重起一次，脚本还是旧的。改了 Dockerfile 要先 build，`--build` 让它先构建再起；配置与镜像都没变时 compose 本来就不会重建容器。"},
 {"question":"`docker compose down` 之后，`volumes:` 里定义的那个命名卷还在吗？","options":["在，默认只删容器和网络，删卷要加 `-v`","不在，`down` 会连卷一起删","看镜像有没有 tag"],"answer":0,"explain":"`down` 默认只删 compose 文件定义的服务容器与对应网络，命名卷和匿名卷都保留。要一起删得写 `down -v`，而 `-v` 也只删 `volumes:` 里的命名卷和挂在容器上的匿名卷，绑定挂载的宿主机目录不动。"},
 {"question":"下面哪一样在 compose 的 `services` 里没有对应的键？","options":["`--pids-limit`","`--stop-timeout`","`--cidfile`"],"answer":2,"explain":"`pids_limit` 和 `stop_grace_period` 都在 compose-spec 的 schema 里；`cidfile` 一个键都没有（逐键核过）。一次性容器靠 cidfile 记容器 id，换到 compose 就没有等价写法。"}
]}
```

## 还差的

- **本机没有 Docker。** 所有 `docker compose` 回显都是按官方文档写的标准形态，没有一条实跑过；镜像那串 digest 是从 `application.yml:234` 抄的，不是我 inspect 出来的。
- **`tmpfs:` 只搬到一半。** 服务级的 `tmpfs:` 收的是挂载点，`size=768m` 与 `exec` 在它里面的写法我没核实；spec 里长语法的 `mounts[].tmpfs` 只有 `size`（字节）和 `mode`（八进制）两个键，`exec` 没有对应项。
- **512m 到底会不会被 OOM 杀掉，没复现。** `application.yml:236` 配 512m，`SqlContainerPool.java:137-139` 又记着 512m 会被静默杀掉、1g 正常。两份原文都在仓库里，我只把它们摆出来，没有在容器里量过。
- **`container_name` 与多副本的冲突只核到文档语义。** 容器名要唯一，所以写死之后不能 `--scale`；具体报什么错我没见过，`--scale` 之后代码要怎么找到各个容器，我也没做实验。
- **`interval: 200ms` 没调过。** 这个节奏是从 `waitReady` 抄的，Docker 每次探测本身的开销、慢机器上 200 毫秒是否够用，都没有量。

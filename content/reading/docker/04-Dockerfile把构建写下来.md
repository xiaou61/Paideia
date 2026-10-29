---
title: Dockerfile：把构建过程写下来
category: docker
order: 4
minutes: 30
level: beginner
status: published
---

## 这一关在解决什么

镜像不是手工攒出来的，是**用 Dockerfile 描述出来的**。这一关讲四件事：

- 常用指令各自干什么、`CMD` 和 `ENTRYPOINT` 到底差在哪；
- 为什么指令顺序决定了"改一行代码要不要等十分钟"；
- 多阶段构建怎么把 500MB 的构建工具留在构建阶段、不进运行镜像；
- `.dockerignore` 为什么是必须的。

## 为什么要写成文件

因为**可复现**。手工改容器做出来的镜像没人说得清它是怎么来的；Dockerfile 是一份能被 review、被版本管理、被任何人一键重建的记录。

它同时也是**唯一正确的改镜像方式**——`docker exec` 进去改的文件下次重建就没了（第 01 关）。

## 讲解

### 常用指令

| 指令 | 作用 | 备注 |
| --- | --- | --- |
| `FROM` | 基础镜像 | 每个 Dockerfile 的第一条（`ARG` 除外） |
| `WORKDIR` | 设置工作目录 | **之后的相对路径都以它为基准**，目录不存在会自动建 |
| `COPY` | 复制文件进去 | 推荐用它传本地文件 |
| `ADD` | 复制 + 自动解压 tar + 支持 URL | 除了解压 tar，一律用 `COPY` |
| `RUN` | 构建时执行命令 | 每条产生一层（第 03 关） |
| `ENV` | 设环境变量 | 运行期还在，会被 `docker inspect` 看到 |
| `ARG` | 构建参数 | 只在构建期有效；`--build-arg` 传 |
| `EXPOSE` | 声明端口 | **只是文档**，不做映射；映射靠 `-p` |
| `USER` | 切到哪个用户运行 | 最后一条 `USER` 决定运行身份 |
| `CMD` | 默认命令 | **可被 `docker run 镜像 命令` 覆盖** |
| `ENTRYPOINT` | 入口程序 | 不被覆盖，`docker run` 后面的东西变成它的参数 |
| `HEALTHCHECK` | 健康检查 | 让 `docker ps` 显示 `(healthy)` |
| `VOLUME` | 声明匿名卷 | 容器删了卷还在（第 05 关） |

### `CMD` 与 `ENTRYPOINT`

这是最容易混的一对。规则只有一条：**`ENTRYPOINT` 是程序，`CMD` 是默认参数**。

```dockerfile
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--spring.profiles.active=prod"]
```

- `docker run 镜像` → `java -jar /app/app.jar --spring.profiles.active=prod`
- `docker run 镜像 --spring.profiles.active=dev` → 后面的参数**替换 CMD**，变成 dev

只写 `CMD` 时，`docker run 镜像 别的东西` 会把整个命令替换掉——所以"为什么我 run 的时候程序没启动、跑的去是 `bash`"通常是这个原因。

**两种写法，差别很大**：

```dockerfile
CMD ["java", "-jar", "app.jar"]      # exec 形式（JSON 数组）：直接 exec，PID 1 是 java
CMD java -jar app.jar                # shell 形式：被包成 /bin/sh -c "java -jar app.jar"
```

shell 形式下 **PID 1 是 `sh`**，它不会把 SIGTERM 转发给 java，于是 `docker stop` 要等 10 秒超时才强杀（第 01 关的 PID 1 问题）。**能用 exec 形式就用 exec 形式**——这也是本关动手题要抓的 bug。

### 顺序决定构建速度

`RUN`、`COPY`、`ADD` 每条产生一层，**只要某一层的输入没变，这一层和它之前的层全部命中缓存**。

所以正确的顺序是：**变化越少的越靠前**。

```dockerfile
# ✗ 反例：源码先拷进去，改一行代码就要重新下载全部依赖
COPY . .
RUN mvn -B dependency:go-offline
RUN mvn -B package -DskipTests
```

```dockerfile
# ✓ 正例：先只拷依赖清单，依赖装完再拷源码
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests
```

差别是"每次改代码重跑 3 分钟"和"只重跑最后 20 秒"。Node 项目里对应的是先 `COPY package.json pnpm-lock.yaml` 再 `RUN pnpm install`，最后才 `COPY . .`。

一个配套动作：**`.dockerignore`**。

```text
.git
node_modules
target
*.log
.env
```

没有它，`COPY . .` 会把 `node_modules`（几百 MB）、`target`、`.git` 整个塞进构建上下文：构建变慢、镜像变大，最糟的是**本地的 `.env` 和密钥被烤进镜像**（第 12 关）。

### 多阶段构建：构建工具不进运行镜像

Java 项目最典型的写法：

```dockerfile
# 第一阶段：构建（有 JDK、Maven，体积大，但不进最终镜像）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 第二阶段：运行（只有 JRE 和 jar）
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
RUN useradd --system --uid 10001 app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
```

`COPY --from=build` 只把产物从第一阶段搬过来，Maven、源码、`~/.m2` 全留在第一阶段然后被丢弃。结果是运行镜像从 800MB 降到 200MB 左右。

四个细节值得注意：

- **`AS build`** 给阶段起名，后面才能 `--from=build`。也可以用数字 `--from=0`，但名字更好读。
- **`USER 10001`** 让容器不以 root 运行（第 12 关）。
- **`-XX:MaxRAMPercentage=70`** 让 JVM 按容器内存上限算堆，而不是按宿主的物理内存——不设的话容器给了 1G、JVM 却以为有 32G，然后被 OOM Killer 干掉（`ExitCode 137`）。
- 构建期需要联网装依赖，**运行期不需要**。所以生产网络的出口策略可以只对构建机放开。

### `ARG` 与 `ENV`

```dockerfile
ARG VERSION=1.0.0            # 构建期可覆盖：--build-arg VERSION=1.0.1
ENV APP_ENV=prod             # 运行期也在
RUN echo "building $VERSION" # ARG 在 RUN 里可见
```

**`ARG` 不能用来传密钥**：它会被记进镜像的构建历史（`docker history` 能看到 `--build-arg` 的值）。同理 `ENV` 里的口令也会被 `docker inspect` 读出来。密钥应该在运行时通过 `-e`/`--env-file`/密钥管理注入（第 12 关）。

### 构建命令

```text
$ docker build -t myapp:v1 .
$ docker build -t myapp:v1 --build-arg VERSION=1.0.2 .
$ docker build --platform linux/amd64 -t myapp:v1 .
$ docker build --progress=plain --no-cache -t myapp:v1 .
```

`.` 是**构建上下文**（整个目录会被送给 daemon），不是 Dockerfile 的路径。`--platform` 在 Apple Silicon 上构建给 x86 服务器用的镜像时必须写，否则部署时报 `exec format error`（第 02 关）。

`--no-cache` 只在真的需要"从零验证"时用；日常构建靠缓存，看到 `CACHED` 说明命中。

## 动手

```code-challenge
{"id":"docker-04-shell-form","kind":"bug","title":"这个镜像每次重启都要等 10 秒，问题在哪一行？","language":"dockerfile",
 "code":"FROM eclipse-temurin:21-jre\nWORKDIR /app\nCOPY target/app.jar app.jar\nENTRYPOINT java -jar app.jar","bugLine":4,
 "explain":"shell 形式会让 `/bin/sh -c \"java -jar app.jar\"` 成为 PID 1，sh 不转发 SIGTERM，Docker 等 10 秒超时后强杀。改成 exec 形式 `ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]`，java 直接成为 PID 1，优雅停机会生效。"}
```

## 检查一下

```quiz
{"id":"docker-04-quiz","title":"Dockerfile 三问","questions":[
 {"question":"`docker run 镜像 bash` 能覆盖的是哪一条指令？","options":["CMD","ENTRYPOINT","RUN"],"answer":0,"explain":"run 后面的命令替换 CMD；ENTRYPOINT 固定为程序本身，后面的内容会变成它的参数。"},
 {"question":"为什么要把 `COPY pom.xml` 放在 `COPY src` 之前？","options":["依赖层能命中缓存，改代码时不用重装依赖","先拷小文件更安全","pom 必须在 src 里"],"answer":0,"explain":"构建缓存按层从前往后匹配；依赖清单不变，依赖层就复用，只重建源码之后的层。"},
 {"question":"`EXPOSE 8080` 做了什么？","options":["只声明文档用途，不映射端口","把容器 8080 映射到宿主 8080","开放防火墙"],"answer":0,"explain":"EXPOSE 只是元信息和给 -P 用的提示。真正映射必须 `-p 宿主:容器`。"}
]}
```

## 常见误区

### 误区：`RUN apt-get update` 和 `RUN apt-get install` 分两条写更清楚

**你以为**：分开写可读性好。
**其实**：`update` 那一层会被缓存，几个月后 `install` 层重新构建时用的是**过期的索引**，于是装到旧版本，甚至报 404（包已经被仓库下架）。正确写法是合成一条：

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
```

**怎么验证**：`docker build --no-cache` 与带缓存的构建对比；或者等一个月再看，两条分开写的版本会开始报 `404 Not Found`。

### 误区：镜像里要装 `vim`、`curl` 方便排查

**你以为**：进去能改能查，方便。
**其实**：每个工具都是体积和攻击面（`curl` 还能当下载器用）。正确做法是**排查时用 `docker cp` 或临时起一个工具容器共享网络**（第 10 关），不要往生产镜像里塞调试工具。真要进容器，用 `docker exec` 加上 `nsenter` 或直接用宿主的 `ss`/`tcpdump` 看。
**怎么验证**：`docker run --rm --network container:目标容器 nicolaka/netshoot ss -lntp` 能在同一个网络命名空间里看端口，完全不动目标镜像。

### 误区：`ADD` 比 `COPY` 更强，用 `ADD` 就行

**你以为**：功能多是好事。
**其实**：`ADD` 会自动解压本地 tar、还会从 URL 下载——这两条"自动"都是隐式行为：从 URL 下载的文件**权限是 600**、不会校验哈希、每次构建都重新下载（缓存失效）。除了"确实要解压 tar 包"这一种情况，`COPY` 更可预测。
**怎么验证**：`ADD https://example.com/x.sh /x.sh` 之后 `docker run --rm 镜像 ls -l /x.sh`，会看到 `-rw-------`，与 `COPY` 的权限不同。

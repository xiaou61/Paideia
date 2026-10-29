---
title: 构建缓存与 BuildKit：改一行代码为什么要等十分钟
category: docker
order: 14
minutes: 26
level: intermediate
status: published
---

## 这一关在解决什么

改一行代码，`docker build` 从头跑：重下依赖、重装系统包、重编译——**十分钟**。

**结论先说**：Docker 的缓存是**按层、按顺序、按内容哈希**匹配的，任何一条指令的输入变了，
它和它**后面所有层**都失效。所以慢的从来不是编译，是"你让依赖装在会被打穿的那一层里"。

## 讲解

### 缓存怎么判定

```
Sending build context to Docker daemon
Step 1/6 : FROM maven:3.9-eclipse-temurin-21
 ---> Using cache
Step 2/6 : COPY pom.xml .
 ---> Using cache
Step 3/6 : RUN mvn -q -DskipTests dependency:go-offline
 ---> Using cache
Step 4/6 : COPY src ./src
 ---> 7c3f2a1b9d
Step 5/6 : RUN mvn -q -DskipTests package
 ---> Running in 4a1f...
```

规则就三条：

1. **指令相同 + 输入相同 → 命中缓存**。`COPY` 的"输入"是文件内容哈希（不是修改时间）。
2. **任何一层未命中，后面全部失效**，哪怕后面那层的输入没变。
3. `RUN` 的"输入"是命令行文本——**不包含它读到的文件**。所以 `RUN curl ...` 里的 URL 变了、
   远程内容变了，Docker 都不知道。

第 4 步 `COPY src` 一变，第 5 步必然重跑。这没问题；问题出在**依赖装在第 5 步里**：

```dockerfile
# 反例：依赖和源码在同一层
COPY . .
RUN mvn -q -DskipTests package
```

改一行 Java，`COPY . .` 的哈希就变了 → 依赖全部重下。

### 正确顺序：稳定的在前，易变的在后

```dockerfile
# 1. 系统依赖（很少变）
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 2. 语言依赖清单（偶尔变）
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# 3. 源码（经常变）
COPY src ./src
RUN mvn -q -DskipTests package
```

`apt-get update && install` 必须写**一条**：分两条写时，`update` 那层会被缓存，
几个月后 `install` 重建用的是过期索引，装到旧版本甚至 404。

### BuildKit 带来的第二种缓存：cache mount

BuildKit（Docker 23+ 默认）支持把**包管理器缓存目录挂成不进入层的缓存**：

```dockerfile
# syntax=docker/dockerfile:1
FROM golang:1.22 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build -o /out/app ./cmd/app
```

`--mount=type=cache` 与 `COPY` 的层缓存是两个东西：

| | 层缓存 | cache mount |
| --- | --- | --- |
| 存在哪 | 镜像层里 | 构建器本地 |
| 进最终镜像吗 | 进 | **不进** |
| 输入变了还在吗 | 该层失效就没了 | **还在**，所以编译能续上 |
| 跨机器复用 | 需要 registry 缓存 | 需要 `--cache-from` |

Java 的等价物是把 `~/.m2` 做成 cache mount：

```dockerfile
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package
```

这样即使 `COPY src` 变了、`mvn` 那一层重建，依赖**仍在本地缓存里**，不用重下。

### CI 里的缓存：`--cache-from` / `--cache-to`

本地缓存到 CI 就没了。BuildKit 可以把缓存导出到 registry：

```text
$ docker buildx build \
    --cache-from type=registry,ref=registry.example.com/app:buildcache \
    --cache-to   type=registry,ref=registry.example.com/app:buildcache,mode=max \
    -t registry.example.com/app:1.0.0 --push .
```

`mode=max` 会连**中间层**一起导出（缓存更全、体积更大）；默认 `min` 只导出最终阶段。

### 怎么知道缓存有没有命中

```text
$ docker build --progress=plain -t demo . 2>&1 | grep -E "CACHED|DONE"
#5 [2/6] COPY pom.xml .                       CACHED
#6 [3/6] RUN mvn dependency:go-offline         CACHED
#7 [4/6] COPY src ./src                        DONE 0.1s
```

`CACHED` 就是命中。看不到这条说明你改了构建上下文里它依赖的东西——最常见的元凶是
**`.dockerignore` 没写**，`COPY . .` 把 `.git`、`node_modules`、`target` 全都算进了哈希。

## 动手

```text
$ docker build --progress=plain -t demo . | grep -c CACHED   # 记录命中层数
# 改一行源码
$ docker build --progress=plain -t demo . | grep -c CACHED   # 应该只少 1–2 层
```

再故意把这行加到 `COPY pom.xml` 前面试试，看命中数掉成 0：

```text
COPY . .
RUN mvn -q -DskipTests package
```

## 检查一下

```quiz
{"id":"docker-14-quiz","title":"构建缓存三问","questions":[
 {"question":"某一层缓存未命中，后面没变的层会怎样？","options":["全部失效，重新执行","继续用缓存","自动从缓存恢复，但只在同一台机器上"],"answer":0,"explain":"缓存是链式的：前一层变了，后面每一层的输入都变了（父层哈希变了），所以必须重跑。"},
 {"question":"cache mount 和层缓存最大的区别是什么？","options":["cache mount 的内容不进最终镜像，即使该层失效也还在","cache mount 只能用于 node","层缓存不进镜像"],"answer":0,"explain":"cache mount 挂在构建器本地，不写入镜像层；它的目的是让「重建那一层时包缓存还在」。"},
 {"question":"COPY . . 为什么容易打穿缓存？","options":["它把 .git、target、node_modules 等易变文件都算进了哈希","它语法本身就是反模式","它总是最后一个执行"],"answer":0,"explain":"层缓存看输入内容哈希；构建上下文里任何无关文件变动都会改变 COPY . 的哈希。用 .dockerignore 排除它们。"}
]}
```

## 常见误区

### 误区：`docker build --no-cache` 能解决"镜像内容过期"

**你以为**：缓存的层不可信，干脆全关。
**其实**：`--no-cache` 只是把全部重跑一遍，问题没解决——**根因是 `RUN` 的输入不包含"远程内容"**。
比如 `RUN apt-get install -y mypkg` 永远不会因为仓库更新而重建。要更新包，就要显式
`--no-cache` 或者改那一行（例如加 `ARG CACHE_BUST=2` 让指令文本变化）。

### 误区：`.dockerignore` 只是优化，不写也行

**你以为**：多传几 MB 构建上下文无所谓。
**其实**：它决定 `COPY . .` 的哈希、决定构建上下文大小（远端构建时要传上去）、
还可能把**密钥**（`.env`、`*.pem`）一起打进镜像。它不是优化，是安全与缓存的双重刚需。

## 小结

缓存是三条规则：按层顺序匹配、内容哈希判定、断了就全断。所以写法只有一条纪律：
**稳定在前、易变在后**；依赖清单单独占一层；用 `.dockerignore` 把无关文件挡在上下文外；
需要"包缓存跨层存活"时用 BuildKit 的 cache mount，CI 里再配 `--cache-to`。

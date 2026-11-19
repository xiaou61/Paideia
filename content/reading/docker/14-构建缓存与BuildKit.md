---
title: 构建缓存与 BuildKit：改一行代码为什么要等十分钟
category: docker
order: 14
minutes: 16
level: intermediate
status: published
---

# 构建缓存与 BuildKit：改一行代码为什么要等十分钟

## 两种日志，两种世界

这是一段老构建器的输出：

```text
Sending build context to Docker daemon  248.7MB
Step 1/6 : FROM maven:3.9-eclipse-temurin-21
 ---> Using cache
Step 2/6 : COPY pom.xml .
 ---> Using cache
Step 3/6 : RUN mvn -q -DskipTests dependency:go-offline
 ---> Using cache
Step 4/6 : COPY src ./src
 ---> 7c3f2a1b9d
Step 5/6 : RUN mvn -q -DskipTests package
 ---> Running in 4a1f2c...
```

同样一个 Dockerfile，Docker 23 之后默认的 BuildKit 把它打成这样：

```text
#1 [internal] load build definition from Dockerfile
#1 transferring dockerfile: 512B done
#4 [internal] load metadata for docker.io/library/maven:3.9-eclipse-temurin-21
#6 [2/6] COPY pom.xml .
#6 CACHED
#7 [3/6] RUN mvn -q -DskipTests dependency:go-offline
#7 CACHED
#8 [4/6] COPY src ./src
#8 DONE 0.1s
#9 [5/6] RUN mvn -q -DskipTests package
#9 DONE 132.7s
```

两处差别值得记住，因为你会用它们判断"我到底在不在 BuildKit 上"：

- 老格式是 `Step 3/6 :`，BuildKit 是 `#7 [3/6]`——每行带一个**全局节点号**（`#1`、`#4` 这些内部步骤也占号，所以号不连续）；
- `CACHED` 在**下一行**，不在同一行。老格式才是 `---> Using cache` 跟在后面。

耗时数字随机器变，形态是固定的。**下面所有输出我都只写形态、不写它花了多少秒**——我这台开发机没装 Docker，抄一个精确到小数点后一位的时间就是在骗你。

## 缓存怎么判定：三条规则

先说结论，再逐条拆：

1. **指令相同 + 输入相同 → 命中缓存。**
2. **任何一层未命中，它后面所有层全部失效**，哪怕后面那层自己的输入没变。
3. **`RUN` 的输入只有命令行文本**（以及它引用的文件），不包含这行命令在运行过程中从网络上取到的东西。

第 3 条是最容易被误传的一条，下面单开一节。

第 2 条解释了"为什么改一行 Java 要等十分钟"：`COPY src` 一变，它后面的 `RUN mvn package` 必然重跑。**问题不在于它会重跑，而在于你把依赖下载也放进了那一层。**

```dockerfile
# 反例：依赖和源码在同一层
COPY . .
RUN mvn -q -DskipTests package
```

改一行源码 → `COPY . .` 的哈希变了 → 这一层失效 → `mvn package` 从零开始，依赖全部重下。

### 正确顺序：稳定在前，易变在后

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

两个细节：

- `apt-get update` 和 `install` **必须写在一条 `RUN` 里**。分两条写时，`update` 那层会被一直复用，几个月后 `install` 重建时用的是**过期索引**，轻则装到旧版本，重则 `404 Not Found`。
- 第二步只 `COPY pom.xml`（Maven 项目要把 `pom.xml` 拷全，多模块项目还要拷各子模块的 `pom.xml`），而不是 `COPY . .`——这样改源码不会动依赖层。

`dependency:go-offline` 不是万能的：它按 `pom.xml` 里声明的依赖下载，遇到 profile、可选依赖、插件动态解析的依赖时仍可能漏。真正的检验标准只有一个——第 3 步是不是真的没有再去下载。

## `RUN` 的输入里，到底有没有"远程内容"

我见过的说法是：*`RUN` 的输入是命令行文本，所以 URL 变了 Docker 也不知道，远程内容变了也不知道*。

**前半句错，后半句对。** 拆开看：

| 变化 | 会不会失效 | 为什么 |
| --- | --- | --- |
| 命令行文本变了（改了 URL、改了参数） | **会** | 文本就是输入的一部分 |
| 同一个 URL，远端内容变了 | **不会** | 哈希只看文本，不看它下载到了什么 |
| `COPY` 的源文件内容变了 | **会** | `COPY` 的输入是文件内容哈希 |
| `COPY` 的源文件 mtime 变了、内容没变 | **不会** | 哈希看内容，不看时间 |

所以 `RUN curl -o x.tar.gz https://example.com/v1.2.3.tar.gz` 这种**带版本号的 URL 是安全的**：下次想换版本就得改 URL，缓存自然失效。

危险的是**不带版本的东西**：

```dockerfile
RUN apt-get update && apt-get install -y curl
RUN curl -fsSL https://example.com/install.sh | sh
```

这两行的文本永远不变，于是一旦命中缓存就**再也不会重新执行**——仓库里的 curl 有新版本了、`install.sh` 的内容换了，你的镜像都不知道。

### `--no-cache` 和 `ARG CACHE_BUST` 分别治什么

`docker build --no-cache` 的作用是**不读缓存、全部重跑**。所以它能拿到最新的远程内容——这正是它的用途。我原来写过"`--no-cache` 解决不了问题"，那是把它当成"只清某一层"了，说反了。它的代价是**全部重来**，包括那些本来好好的依赖层。

只想破一层的话，用一个显式变量：

```dockerfile
ARG CACHE_BUST=0
RUN apt-get update && apt-get install -y curl ${CACHE_BUST:+--reinstall}
```

**注意这里有个坑**：必须在 `RUN` 里**真的引用** `$CACHE_BUST`。如果只写 `ARG CACHE_BUST=2` 然后那一行不引用它，BuildKit 不会失效——**只有真正被指令引用的 ARG 才参与哈希**。我照着某个老帖子的写法加了一行 `ARG CACHE_BUST=2` 却毫无效果，就是因为那行没被用到。

## BuildKit 的第二种缓存：cache mount

层缓存解决不了"这一层必须重建、但依赖想留着"的情况。BuildKit 的 `--mount=type=cache` 解决它：

```dockerfile
# syntax=docker/dockerfile:1
FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build -o /out/app ./cmd/app
```

第一行的 `# syntax=docker/dockerfile:1` 建议都写：它让 BuildKit 拉取一个固定的前端镜像来解析 Dockerfile，避免"本机 Docker 版本太老、不认识某个新语法"。

两者的区别：

| | 层缓存 | cache mount |
| --- | --- | --- |
| 存在哪 | 镜像层里 | 构建器本地目录 |
| 进最终镜像吗 | 进 | **不进** |
| 该层失效后还在吗 | 没了 | **还在**，所以编译能续上 |
| 能被 `--cache-to` 导出到 registry 吗 | 能 | **不能** |

最后一行是我写错过的地方：我原来写"cache mount 跨机器用 `--cache-from` 复用"。**不对。** `--cache-to/--cache-from` 导出的是**层缓存**，cache mount 的内容不进导出物。换一台干净的构建机，cache mount 就是空的——它只在同一个 builder 实例上有效。

同理，`--cache-from` 也不能凭空造出层缓存：它需要之前用 `--cache-to,mode=max` 导出过，而且那次构建得真的执行过对应层。

Java 的等价写法是把 `~/.m2` 挂成 cache mount：

```dockerfile
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package
```

效果是：即便 `COPY src` 变了、这一层重建，jar 依赖仍在本地缓存里，不用重下。注意 `~/.m2` 挂成 cache mount 后**不进入镜像**，所以别指望"把依赖打进镜像以便离线启动"——那是另一个需求，得用 `dependency:go-offline` 显式落到镜像里。

### CI 里怎么把层缓存带过去

```text
docker buildx build \
  --cache-from type=registry,ref=registry.example.com/app:buildcache \
  --cache-to   type=registry,ref=registry.example.com/app:buildcache,mode=max \
  -t registry.example.com/app:1.0.0 --push .
```

`mode=max` 连**中间阶段**一起导出（缓存更全、体积更大、推送更慢）；默认的 `min` 只导出最终阶段。多阶段构建里想复用 builder 阶段的缓存，就得用 `max`。

## 怎么知道缓存命中没有

```text
docker build --progress=plain -t demo . 2>&1 | findstr CACHED
#6 [2/6] COPY pom.xml .
#6 CACHED
#7 [3/6] RUN mvn -q -DskipTests dependency:go-offline
#7 CACHED
```

`CACHED` 就是命中。看不到某层，说明它的输入变了——最常见的元凶是 **`.dockerignore` 没写**：`COPY . .` 的哈希把 `.git`、`node_modules`、`target` 全都算了进去，改一次 git 提交、跑一次本地构建，哈希就变。

一个容易误判的现象：**"我改了一行源码，命中数应该从 8 掉到 6，怎么掉到 3 了？"** 因为失效是链式的——`COPY src` 之后的每一层都重跑，再加上 BuildKit 里有些 `[internal]` 步骤会跟着变。数命中层数没有意义，**看"哪一层开始不是 CACHED"才有意义**。

## 动手

```text
# 1. 记下完整输出的层状态
docker build --progress=plain -t demo . > build-1.log 2>&1

# 2. 改一行源码，再构一次
docker build --progress=plain -t demo . > build-2.log 2>&1

# 3. 对比：从哪一层开始不再 CACHED
diff <(grep -E "^#[0-9]+ " build-1.log) <(grep -E "^#[0-9]+ " build-2.log)
```

期望结果：第一个出现差异的层应该是 `COPY src ./src`。如果它前面就出现了差异（比如 `COPY pom.xml` 不再 CACHED），说明构建上下文里有东西在动——去查 `.dockerignore`。

## 检查一下

```quiz
{
  "id": "docker-14-quiz",
  "title": "构建缓存三问",
  "questions": [
    {
      "question": "某一层缓存未命中，后面没变的层会怎样？",
      "options": ["全部失效并重新执行","继续用缓存","只在同一台机器上自动恢复"],
      "answer": 0,
      "explain": "缓存是链式的：父层哈希变了，后面每一层的输入就都变了，必须重跑。这就是「改一行源码要等十分钟」的机制。"
    },
    {
      "question": "cache mount 和层缓存最大的区别是什么？",
      "options": ["cache mount 的内容不进最终镜像，该层失效后它还在","cache mount 只能用于 Node 项目","层缓存不进镜像"],
      "answer": 0,
      "explain": "cache mount 挂在构建器本地，不写入镜像层；目的是让「这一层重建时，包缓存还在」。它也不会被 --cache-to 导出到 registry。"
    },
    {
      "question": "RUN 的缓存输入包含什么？",
      "options": ["命令行文本（含它引用的 ARG），不包含这行命令运行时从网上取到的内容","命令行文本加上它下载到的文件内容","整条命令加上当时的时间戳"],
      "answer": 0,
      "explain": "改了 URL 就会失效（文本变了）；同一个 URL 的远端内容变了不会失效。所以不带版本的 install.sh 一旦命中缓存就再也不会重新执行。"
    }
  ]
}
```

## 两件我原来搞混的事

**一、`--no-cache` 没用。** 说反了。它有用——它保证每层都重跑，因此能拿到最新的远端内容；代价是全部重来。真正没用的是"只加一个没用到的 `ARG` 想破缓存"。

**二、cache mount 可以用 `--cache-from` 跨机器复用。** 不行。`--cache-to/from` 只搬层缓存。cache mount 是构建器本地的目录，换机器就是空的。

反过来，真正安静地浪费时间的是这两件：

- **`COPY . .` 放在依赖安装之前**：每改一行源码，依赖全重下；
- **没写 `.dockerignore`**：`.git` 目录的每次变化都会打穿 `COPY . .`，更糟的是 `.env`、`*.pem` 可能被打进镜像。

## 这一课之后

缓存就三条规则：按层顺序匹配、内容哈希判定、断了就全断。所以写法只有一条纪律：**稳定在前、易变在后**。依赖清单单独占一层，`.dockerignore` 把无关文件挡在上下文外，需要"包缓存跨层存活"时用 BuildKit 的 cache mount——但记住它只在本机有效，CI 里要跨机器复用的是层缓存。

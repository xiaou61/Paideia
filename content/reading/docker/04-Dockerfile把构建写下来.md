---
title: Dockerfile：把构建过程写下来
category: docker
order: 4
minutes: 20
level: beginner
status: published
---

## 改一行 Controller，依赖层又跑了三分钟

这是练手项目里典型的 Dockerfile 写法：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q dependency:go-offline
RUN mvn -B -q package -DskipTests
```

改一行 `HelloController.java`，重新构建，BuildKit 的标准输出形态是这样：

```text
$ docker build --progress=plain -t app:v7 .
#5 [build 2/6] COPY . .
#5 DONE <耗时>
#6 [build 3/6] RUN mvn -B -q dependency:go-offline
#6 DONE <耗时>
#7 [build 4/6] RUN mvn -B -q package -DskipTests
#7 DONE <耗时>
```

（本机没装 Docker，节点号和 `DONE` 行是标准形态；节点号随 Dockerfile 和 Docker 版本变，哪些步骤会显示 `CACHED` 取决于你的改动，`<耗时>` 要你自己跑。这里没有一句是我这台机器上的回显。）

我原来的判断是：`pom.xml` 没变，Maven 依赖应该直接复用。实际不是。Docker 不看你命令里的 `pom.xml`，它按 Dockerfile 从上到下匹配缓存；`COPY . .` 把源码目录的内容哈希带进了依赖步骤之前，源码一变，这一层和它后面的 `RUN` 全部重来。

验证：按下一节把 `COPY . .` 拆成 `COPY pom.xml .` 和 `COPY src ./src`，再改一行源码，跑 `docker build --progress=plain`；看 `RUN mvn ... dependency:go-offline` 这一行下面有没有 `CACHED`。有，依赖层就复用了；没有，就继续找还有哪个输入在变。

## 稳定的放前面，易变的放后面

`RUN`、`COPY`、`ADD` 是真正往层里写文件内容的三条指令。缓存按层从前往后比：某一层的输入变了，这一层和它后面的所有层都不再命中。

所以顺序应该是：

```dockerfile
# 依赖清单很少变，放前面
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 源码天天变，放后面
COPY src ./src
RUN mvn -B -q package -DskipTests
```

改源码时，`COPY pom.xml` 和依赖安装仍然 `CACHED`，只有 `COPY src` 之后的层重建。Node 项目同理：先 `COPY package.json pnpm-lock.yaml`，再 `RUN pnpm install --frozen-lockfile`，最后 `COPY . .`。

验证：连续构建两次，第二次只改一个 `.java` 文件；`docker build --progress=plain` 里 `dependency:go-offline` 那一步应该显示 `CACHED`，`package` 那一步不是。

配套动作是 `.dockerignore`：

```text
.git
node_modules
target
*.log
.env
```

没有它，`COPY . .` 会把 `node_modules`、`target`、`.git` 整个送进构建上下文：上下文传输变慢、镜像变大，本地的 `.env` 也可能被拷进去。`.dockerignore` 只负责把文件挡在上下文外，不是密钥管理；已经进了某一层的密钥，后面再删文件也不会从镜像里消失，只能轮换。

如果你的 Dockerfile 是 `COPY target/app.jar app.jar` 这种预构建路线，别把 `target` 写进 `.dockerignore`——那是另一条路线，ignore 掉就复制不进去了。

验证：`docker build --progress=plain` 看 `[internal] load build context` 的 `transferring context` 大小；构建完再 `docker run --rm 镜像 ls -a /app`，看 `.git`、`node_modules` 在不在。

## ENTRYPOINT 只是默认入口

「`ENTRYPOINT` 不被覆盖」是错的。它只是镜像的默认入口，运行时可以用 `docker run --entrypoint` 换掉；换的时候还会清空镜像里的默认 `CMD`。

三种组合先分清：

- **exec 形式 `ENTRYPOINT` + `CMD`**：`docker run 镜像` 跑 `ENTRYPOINT + CMD`；`docker run 镜像 参数` 用参数替换 `CMD`；`docker run --entrypoint 新程序 镜像` 换掉 `ENTRYPOINT`，`CMD` 被清空，后面要自己给参数。
- **只有 `CMD`**：`docker run 镜像 命令` 直接替换整个 `CMD`。这就是「我 run 的时候程序没启动、跑的是 bash」的经典原因。
- **shell 形式 `ENTRYPOINT`**：Docker 把它包成 `/bin/sh -c "..."`；官方文档明确说，这种形式下 `CMD` 和 `docker run` 后面的参数都不会传给命令。

推荐的组合写法是 `ENTRYPOINT` 放命令、`CMD` 放默认参数：

```dockerfile
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
CMD ["--spring.profiles.active=prod"]
```

- `docker run app` → `java ... -jar /app/app.jar --spring.profiles.active=prod`
- `docker run app --spring.profiles.active=dev` → 后面的参数替换 `CMD`，profiles 变成 dev
- `docker run --entrypoint sh app -c 'ls /app'` → `sh` 覆盖入口，`-c 'ls /app'` 是给 `sh` 的参数

验证：`docker inspect app --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}}'`，看 `Entrypoint` 是不是 JSON 数组、`Cmd` 是不是默认参数；再跑一次 `docker run --rm --entrypoint sh app -c 'echo override'`，能打印 `override` 就说明入口确实被覆盖了。

### shell 形式为什么不推荐

```dockerfile
ENTRYPOINT java -jar app.jar
```

这行会被包成 `/bin/sh -c "java -jar app.jar"`。问题不是「PID 1 一定是 sh」——有些 shell 在 `-c` 只有一条简单命令时会直接 `exec`，PID 1 可能变成 java。问题是你不能依赖它：

- 命令是复合命令、带管道或重定向时，shell 会多一层 fork/wait，PID 1 还是 sh；
- sh 不保证把 SIGTERM 转发给子进程，`docker stop` 发的信号到不了 java；
- 这时 Docker 只能等超时，再发 SIGKILL。

准确的说法是：**shell 形式的信号转发不可靠**。要解决，优先用 exec 形式；确实需要 shell 做变量展开或拼命令时，写一个 entrypoint 脚本，最后一行用 `exec` 接 `$@`，或者给容器加 `--init`（Docker 会用 tini 当 PID 1，负责转发信号和回收僵尸进程）。

超时不是固定 10 秒：`docker stop` 对 Linux 容器默认等 10 秒（Windows 容器 30 秒），`-t 30` / `--time 30` 可以改；镜像里的 `STOPSIGNAL` 可以换停止信号，运行时 `--stop-signal` 也能覆盖。

验证：`docker run -d --name t app`，再 `docker exec t cat /proc/1/comm`，看是 `java` 还是 `sh`；`time docker stop t` 看是不是卡满默认的 10 秒；`docker inspect t --format '{{.Config.StopSignal}}'` 看镜像声明的停止信号。

## 哪些指令真的产生层，「同输入同镜像」吗

真正往层里写文件内容的只有 `RUN`、`COPY`、`ADD`；`ENV`、`CMD`、`EXPOSE`、`LABEL`、`ENTRYPOINT`、`USER` 这些只改镜像配置，`docker history` 里 `SIZE` 显示 `0B`。`WORKDIR` 是半个例外：目录不存在时它会建一个空目录，严格说也动了文件系统。

但这不代表元数据指令可以随便改：改 `ENV` 会让它之后的步骤缓存键变化，后面的 `RUN` 可能全部重跑。

验证：`docker history --no-trunc 镜像` 看每行的 `CREATED BY` 和 `SIZE`；`docker image inspect --format '{{len .RootFS.Layers}}' 镜像` 数内容层。`history` 的行数不等于层数，元数据行没有对应的文件系统层。

「同样的 Dockerfile 得到同样的镜像」也不是 Docker 的保证。至少还有四个变量：

1. **基础镜像的 tag 会动**。`FROM maven:3.9-eclipse-temurin-21` 今天和下周可能不是同一个 digest；要复现就写 `FROM maven:3.9-eclipse-temurin-21@sha256:...`。
2. **依赖版本没锁**。`pom.xml` 里的范围版本、`apt-get install` 没写版本、`ADD` 一个 URL，都会让今天和明天拉到不同的东西；Node 用 lockfile 加 `--frozen-lockfile`，Maven 至少把插件版本和关键依赖钉死。
3. **构建参数不同**。`--build-arg VERSION=1.0.1` 会改变镜像配置，也会改变 `RUN` 里看到的值；构建命令要跟 Dockerfile 一起记录。
4. **时间戳**。镜像的 `Created`、jar/zip 里的文件时间、某些构建工具写进去的当前时间，都会让字节不同。BuildKit 支持用 `SOURCE_DATE_EPOCH` 固定镜像创建时间；要逐字节复现，还要让打包工具不写入当前时间。

验证：同一个 Dockerfile 连跑两次 `docker build --no-cache`，比较 `docker image inspect --format '{{.Id}}'`；ID 不同就说明还有输入在变（时间戳、网络、版本或构建参数）。把基础镜像钉到 digest、依赖锁死、时间戳固定后再比，才有意义。

## 多阶段构建：构建工具不进运行镜像

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
RUN useradd --system --uid 10001 app
COPY --from=build /src/target/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
```

`COPY --from=build` 只把产物从第一阶段搬过来，Maven、源码、`~/.m2` 都留在第一阶段然后被丢弃。`target/app.jar` 这个名字取决于 `pom.xml` 的 `finalName`，照抄前先对一下。

四个细节：

- `AS build` 给阶段起名，后面才能 `--from=build`；也可以用数字 `--from=0`，但名字更好读。
- `USER 10001` 让容器不以 root 运行（第 12 关）；`useradd` 在 `eclipse-temurin` 的 Ubuntu 基础镜像里有，换 Alpine 要改用 `adduser`。
- `-XX:MaxRAMPercentage=70` 让 JVM 按容器内存上限的 70% 算堆；现代 JVM（8u191 及以后）默认能读 cgroup 限制，默认 `MaxRAMPercentage` 是 25%，不设只是堆偏小；8u191 之前的 JVM 读不到容器限制，才会按宿主内存算。堆太大加上其他内存超过 `--memory`，仍然会被 OOM Killer 干掉（`ExitCode 137`）。
- 构建期需要联网装依赖，运行期不需要；生产网络的出口策略可以只对构建机放开。

验证：`docker image ls` 看最终镜像大小；`docker run --rm 镜像 ls /` 看不到 `/src`；`docker history 镜像` 里没有 `mvn` 的 `RUN`。大小数字以你本机 `docker image ls` 为准，我不在这里编一个「从 800MB 降到 200MB」。

## ARG、ENV 和 EXPOSE

```dockerfile
ARG VERSION=1.0.0            # 构建期可覆盖：--build-arg VERSION=1.0.1
ENV APP_ENV=prod             # 运行期也在
RUN echo "building $VERSION" # ARG 在 RUN 里可见
```

`ARG` 不能用来传密钥：它会出现在镜像构建历史里，BuildKit 的 `max` 模式 provenance 里也可能带着。`ENV` 里的口令会被 `docker inspect` 的 `Config.Env` 读出来。密钥应该在运行时通过 `-e`、`--env-file` 或密钥管理注入；BuildKit 还有 `RUN --mount=type=secret`，构建期用密钥但不留在层里。

验证：`docker history --no-trunc 镜像` 看 `RUN` 那行的命令里有没有你的值；`docker inspect 镜像 --format '{{json .Config.Env}}'` 看 `ENV`。如果已经在层里，删 Dockerfile 里的行不够，镜像历史还在。

`EXPOSE 8080` 只是声明，不映射端口。`docker run -P` 会把所有 `EXPOSE` 的端口发布到宿主随机端口；真正的固定映射是 `-p 8080:8080`。

验证：`docker inspect 镜像 --format '{{json .Config.ExposedPorts}}'` 有 `8080/tcp`；只 `docker run` 不加 `-p` 时 `docker port 容器` 是空的；加了 `-p 8080:8080` 才有 `8080/tcp -> 0.0.0.0:8080`。

## 跑一遍：只看两个字段

```text
$ docker build --progress=plain -t app:v1 .
$ docker inspect app:v1 --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}}'
$ docker run --rm --entrypoint sh app:v1 -c 'echo override'
```

第一行跑两遍，第二遍看依赖步骤有没有 `CACHED`；第二行看 `Entrypoint` 是不是 JSON 数组、`Cmd` 是不是默认参数；第三行能打印 `override`，说明 `--entrypoint` 覆盖生效。

## 动手

```code-challenge
{"id":"docker-04-shell-form","kind":"bug","title":"这个镜像重启时可能卡满 10 秒，问题在哪一行？","language":"dockerfile",
 "code":"FROM eclipse-temurin:21-jre\nWORKDIR /app\nCOPY target/app.jar app.jar\nENTRYPOINT java -jar app.jar","bugLine":4,
 "explain":"shell 形式会把命令交给 /bin/sh -c；sh 不保证把 SIGTERM 转给 java，Docker 只能等到超时再 SIGKILL。改成 exec 形式（JSON 数组）：ENTRYPOINT 后用方括号，每个参数单独加双引号。也可以用 entrypoint 脚本，最后一行用 exec 接 $@，或者给容器加 --init。"}
```

## 检查一下

```quiz
{"id":"docker-04-quiz","title":"Dockerfile 三问","questions":[
 {"question":"`docker run --entrypoint sh 镜像` 会怎样？","options":["只是把 sh 追加到原 ENTRYPOINT 后面","覆盖 ENTRYPOINT，并清空镜像里的默认 CMD","覆盖 CMD，ENTRYPOINT 不变"],"answer":1,"explain":"`--entrypoint` 覆盖 ENTRYPOINT；Docker 文档明确它还会清空默认 CMD，所以后面要自己给参数。传空字符串可以清空入口，再在镜像名后面写命令。"},
 {"question":"哪种写法能让 java 直接成为 PID 1？","options":["`ENTRYPOINT java -jar app.jar`（shell 形式）","`CMD java -jar app.jar`","`ENTRYPOINT` 的 JSON 数组形式，每个参数单独加双引号"],"answer":2,"explain":"shell 形式会包一层 /bin/sh -c；CMD 只是默认命令，不解决 shell 形式的信号转发。exec 形式让 java 直接成为 PID 1。"},
 {"question":"只改 `ENV` 会增加一个内容层吗？","options":["不会，它只改镜像配置；但仍会影响后续步骤的缓存","会，每条指令都一层","不会，而且完全不影响缓存"],"answer":0,"explain":"内容层由 RUN/COPY/ADD 写文件；ENV 是元数据。但改 ENV 会让它之后的步骤缓存键变化，后面的 RUN 可能重跑。"}
]}
```

## 还差的

- 本机没装 Docker，所有 `docker build`/`docker run` 回显都是标准形态，没有实跑；`<耗时>` 没有数字。
- 依赖层缓存的实际耗时没有测；「3 分钟」是你描述的场景，不是这台机器上的测量值。
- `SOURCE_DATE_EPOCH` / `rewrite-timestamp` 只按 BuildKit 文档写，没有真的构建出两个逐字节相同的镜像。
- 没有验证不同 `sh`（dash/bash/busybox）在 `-c` 下是否 `exec` 简单命令；正文只说「可能」，没有把它当保证。
- 没有验证老 builder 与 BuildKit 在你那版 Docker 上的差异；`DOCKER_BUILDKIT=0` 在新版本可能已经不可用。

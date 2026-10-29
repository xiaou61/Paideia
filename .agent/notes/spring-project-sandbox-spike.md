# 尖刺：Spring Boot 小项目能不能在我们的沙箱里真跑（2026-10-29）

不是工作项，是一次可行性验证。触发问题：用户想加"从 0 用 Spring Boot 做一个小项目"这条线，
问"是不是很复杂"、"能不能在用户本地跑"。核心未知是**沙箱能不能离线跑 `mvn test`**——
因为容器是 `--network=none`，而 Maven 默认要联网解析依赖。

## 结论

**能，而且不用推翻"无网络"这条安全边界。** 把依赖烤进镜像后，在现有沙箱的完整约束
（`--network=none` + `--read-only` + 非 root uid 999 + 1g 内存）下，`mvn -o test` 正常跑完，
包括 `@SpringBootTest` 起上下文并打一次本地 HTTP。

## 实测数字（codenest-online，4 核 / 15.9G）

| 项 | 数值 | 备注 |
| --- | --- | --- |
| 基础镜像 | `maven:3.9-eclipse-temurin-21` | 自带 Maven 3.9 + JDK 21 |
| 烤完依赖的镜像 | **989 MB** | 含离线仓库（`~/.m2` 98 MB） |
| 镜像构建耗时 | **6 分 59 秒** | 含拉基础镜像 + 下载依赖，一次性 |
| 加约束层的二次构建 | 50 秒 | `COPY --from` 复用仓库，不重新下载 |
| 全量 `mvn -o -q test`（1 CPU） | **27.1 秒** | 2 个测试类：纯单测 + `@SpringBootTest` 打本地 HTTP |
| 同一套（2 CPU） | **14.6 秒** | 编译是 CPU 密集，加核几乎线性 |
| 只跑纯单测（1 CPU） | **16.4 秒** | 仍是全量编译，省下的是 Spring 上下文那 ~11 秒 |
| 沙箱约束版（2 CPU、只读根、uid 999、1g） | **14.1 秒** | 与不受约束时基本一致 |

结论性拆分：**Maven 启动 + 全量编译 ≈ 16 秒是地板价，Spring 上下文另加 ~11 秒**；
每次运行都是新容器，没有增量编译，改一个文件也要全量编。

## 跑通需要的三条约定（缺一条就失败）

1. **离线仓库要放在非 root 可读的路径**：镜像里 `~/.m2` 在 `/root` 下，uid 999 读不到。
   做法：`COPY --from=deps /root/.m2 /opt/m2 && chmod -R a+rX /opt/m2`，
   运行加 `-Dmaven.repo.local=/opt/m2/repository`（**注意是 repository 子目录**，
   指到 `/opt/m2` 会报 "parent POM absent"）。
2. **HOME 与 MAVEN_CONFIG 必须指向可写目录**：基础镜像把 `MAVEN_CONFIG=/root/.m2` 写死在 ENV 里，
   只改 HOME 不生效。要 `-e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2`，且 `/tmp` 可写
   （沙箱本来就给了 tmpfs）。
3. **`/app/target` 要给 tmpfs**：`--read-only` 下编译产物无处可写。

## 对方案的直接影响

- **超时档位**：现有 java 是 `timeoutMs = 10_000`，Spring 项目 14–27 秒是常态 →
  必须给这类单独一档（建议 60–90 秒）。`SandboxProperties.timeoutMsFor(language)` 已经是
  按语言分支的，加一档即可。
- **占槽时间**：一次运行从"毫秒级"变成"15–27 秒"。并发槽与每日配额的口径要重新算；
  想快就得像 SQL 池那样常驻容器（那是后续优化，不在尖刺范围）。
- **判分**：`mvn -q` 会吞掉 `Tests run:` 汇总，判分要读 `target/surefire-reports/` 或去掉 `-q`。
- **不需要开网络**，所以沙箱参数一个都不用放松。

## 复现步骤

```bash
# 1. 骨架：pom（spring-boot-starter-web + starter-test, Java 21）+ 一个 @RestController + 一个 @SpringBootTest
# 2. 烤镜像（需要联网，一次性）
cat > Dockerfile <<'EOF'
FROM maven:3.9-eclipse-temurin-21
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q test
EOF
docker build -q -t spring-probe:1 .

# 3. 把仓库挪到非 root 可读处（复用上一步的层，50 秒）
cat > Dockerfile.ro <<'EOF'
FROM spring-probe:1 AS deps
FROM maven:3.9-eclipse-temurin-21
COPY --from=deps /root/.m2 /opt/m2
RUN chmod -R a+rX /opt/m2
WORKDIR /app
COPY pom.xml .
COPY src ./src
EOF
docker build -q -f Dockerfile.ro -t spring-probe:2 .

# 4. 按沙箱约束跑
docker run --rm --network=none --read-only --cpus=2 --memory=1g \
  --tmpfs /app/target:rw,size=256m --tmpfs /tmp:rw,size=256m \
  --user 999:999 -e HOME=/tmp/home -e MAVEN_CONFIG=/tmp/home/.m2 \
  -v /tmp/spring-run/src:/app/src:ro spring-probe:2 \
  bash -c 'mkdir -p $HOME && cd /app && mvn -o -q -Dmaven.repo.local=/opt/m2/repository test'
```

## 未验证

- **增量编译**：每次运行都是新容器，改一个文件也全量编译（16 秒地板价）。要压下去得让容器常驻，
  与 SQL 池同思路，没验。
- **surefire 结果解析**：只确认测试真跑了（日志里有 Tomcat 起监听），没写解析与判分的代码。
- **并发**：只单跑过，没测两个用户同时跑时 1g 内存 / 2 核的争用。
- **镜像分发**：989 MB 在服务器本地构建没问题；若要多机部署要考虑 registry。

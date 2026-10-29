---
title: 实战：Jar + MySQL 一次编排起来
category: docker
order: 11
minutes: 40
level: beginner
status: published
---

## 这一关要做什么

把一个已经能跑的 Spring Boot jar 和 MySQL 8 用一份 Compose 文件起来：**应用在容器里跑、数据在命名卷里、初始化 SQL 自动执行、启动顺序由健康检查保证、日志有上限、只暴露一个端口。**

前面十关的零件在这里各就各位。这一关的每一步都给"为什么这样写"和"怎么验证"。

## 为什么先构建 jar 再进镜像

有两种做法：**镜像里跑 Maven 构建**，或**本地/CI 构建好 jar 再拷进去**。

推荐第二种（本关采用）：镜像更小、构建更快、Dockerfile 更简单，而且 CI 里构建 jar 与构建镜像本来就是两个阶段。想全在 Docker 里做也行，那就是第 04 关的多阶段写法——把 `mvn package` 放进第一阶段即可。

## 讲解

### 目录结构

```text
paideia-deploy/
├── docker-compose.yml
├── Dockerfile
├── .dockerignore
├── .env                 # 本地口令，进 .gitignore
├── app.jar              # 构建产物
└── sql/
    └── 01-schema.sql    # 首次启动时执行的初始化脚本
```

### 第一步：Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 app

COPY --chown=10001:10001 app.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Shanghai"

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

四个点：

- **`--chown`**：`COPY` 默认以 root 属主写入，应用用 10001 跑就改不了它。`--chown` 一次解决（第 05 关）。
- **`JAVA_TOOL_OPTIONS`** 让 JVM 认 cgroup 内存上限并按比例设堆（第 09 关）。不设时容器给 1G、JVM 以为有几十 G，一加压就 OOM。
- **`USER 10001`** 不以 root 运行（第 12 关）。
- **exec 形式 `ENTRYPOINT`**，让 java 直接是 PID 1，`docker stop` 时能收到 SIGTERM 做优雅停机（第 04 关）。

`.dockerignore`：

```text
.git
target
*.log
.env
sql
```

`sql/` 是给 MySQL 容器用的，不需要进应用镜像。

### 第二步：Compose

```yaml
services:
  app:
    build: .
    image: paideia-app:v1
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/paideia?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
      SPRING_DATASOURCE_USERNAME: paideia
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    depends_on:
      mysql:
        condition: service_healthy
    restart: unless-stopped
    stop_grace_period: 30s
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "3"

  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
      MYSQL_DATABASE: paideia
      MYSQL_USER: paideia
      MYSQL_PASSWORD: ${DB_PASSWORD}
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    volumes:
      - mysql-data:/var/lib/mysql
      - ./sql:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u root -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 30s
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "3"

volumes:
  mysql-data:
```

`.env`（**不进版本库**）：

```text
DB_ROOT_PASSWORD=换成你的
DB_PASSWORD=换成你的
```

逐个解释关键行：

| 位置 | 为什么这么写 |
| --- | --- |
| `127.0.0.1:8080:8080` | 只给本机（第 02 关）。外部访问走 Nginx 反代 |
| `jdbc:mysql://mysql:3306` | **用服务名**，不是 localhost（第 06 关） |
| `depends_on: condition: service_healthy` | 等 MySQL 真能接受连接（第 07 关） |
| `$$MYSQL_ROOT_PASSWORD` | Compose 里 `$$` 表示"给容器内的 shell 一个 `$`"，不让 Compose 自己替换 |
| `mysql-data:/var/lib/mysql` | 数据必须出卷（第 05 关），删容器不丢 |
| `./sql:/docker-entrypoint-initdb.d:ro` | 首次初始化时自动执行里面的 `.sql`（下面细讲） |
| `--character-set-server=utf8mb4` | 默认不是所有人的环境都对，显式写死省得中文变问号 |
| `stop_grace_period: 30s` | 给应用 30 秒收尾，默认 10 秒可能不够（第 09 关） |

### 第三步：`docker-entrypoint-initdb.d` 的行为（最容易踩）

MySQL 官方镜像会执行 `/docker-entrypoint-initdb.d/*.sql`，**但只在数据目录为空（第一次初始化）时执行**。

推论：

- 你的 `01-schema.sql` 改了，`docker compose up -d` **不会重新执行**——因为卷里已经有数据了。
- 想重跑：`docker compose down -v`（**删卷，数据全没**），或者手动 `docker compose exec -T mysql mysql -u root -p"$DB_ROOT_PASSWORD" < sql/01-schema.sql`。

生产上正确的做法是用迁移工具（Flyway/Liquibase）在应用启动时跑，而不是依赖初始化脚本——那样每次升级都受控。初始化脚本只适合"首次建库 + 种子数据"。

### 第四步：起

```text
$ docker compose config            # 先看变量替换后的最终配置
$ docker compose build
$ docker compose up -d
$ docker compose ps
```

`ps` 里应该看到 `mysql` 先变 `healthy`，然后 `app` 才 `Up`：

```text
NAME           IMAGE              STATUS                   PORTS
paideia-app-1  paideia-app:v1     Up 12 seconds            127.0.0.1:8080->8080/tcp
paideia-mysql-1 mysql:8.4         Up 40 seconds (healthy)  3306/tcp, 33060/tcp
```

### 第五步：验

```text
$ curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
200
$ docker compose logs -f app
$ docker compose exec mysql mysql -u paideia -p"$DB_PASSWORD" paideia -e 'show tables;'
```

三条一起看：**HTTP 能通**、**日志没有报错**、**数据库里表真的建了**。少一条都不算成功。

### 第六步：排三类最常见的问题

**1. app 起来就退出（`Exited (1)`）**

```text
$ docker compose logs app | tail -50
```

看到 `Communications link failure` 或 `Connection refused`：数据库没就绪或地址错了。检查 `DB_HOST` 用的是服务名、`depends_on` 有没有 `service_healthy`。

看到 `Access denied for user`：口令不一致。检查 `.env` 的变量名与 compose 里引用的是否同一个。注意 `MYSQL_PASSWORD` 只在**首次初始化**时生效（数据目录已有数据时改它没用，得进库改用户）。

看到 `Public Key Retrieval is not allowed`：MySQL 8 默认 `caching_sha2_password`，JDBC 需要 `allowPublicKeyRetrieval=true` 或启用 SSL（URL 里已经带了）。

**2. app 是 `Up` 但端口连不上**

```text
$ docker compose exec app ss -lntp        # 容器里有没有在听 8080
$ docker compose port app 8080            # 映射成了什么
```

容器里没在听：应用没起来（看日志）；在听但外面连不上：映射绑到了 `127.0.0.1` 而你在别处测（第 15 关那套分层判断）。

**3. 反复 `Restarting`**

```text
$ docker compose ps
$ docker inspect paideia-app-1 --format '{{.RestartCount}} {{.State.OOMKilled}}'
```

`OOMKilled=true` → 内存上限太小或 JVM 堆没按容器算（第 09 关）。退出码 `1` 且日志每轮一样 → 确定性 bug，`restart` 只会掩盖它。

### 第七步：更新一次

```text
$ # 构建新 jar
$ docker compose build app
$ docker compose up -d app        # 只重建 app，mysql 不动
$ docker compose ps
$ docker compose logs -f app
```

**`mysql` 不会被重启**，因为它的配置和镜像都没变。这就是 Compose 相对"一串 docker run"的实际收益：改一个服务不惊动其他服务。

### 第八步：备份数据库

```text
$ docker compose exec -T mysql \
    mysqldump -u root -p"$DB_ROOT_PASSWORD" --single-transaction --routines --triggers paideia \
    | gzip > backup-$(date +%F).sql.gz
```

`--single-transaction` 让 InnoDB 导出一致性快照，不必停写。恢复：

```text
$ gunzip -c backup-2026-10-29.sql.gz | docker compose exec -T mysql mysql -u root -p"$DB_ROOT_PASSWORD" paideia
```

**备份要演练恢复**（Linux 线第 19 关）。只看文件大小不算。

## 检查一下

```quiz
{"id":"docker-11-quiz","title":"实战三问","questions":[
 {"question":"改了 `sql/01-schema.sql`，`docker compose up -d` 之后数据库没有变化，为什么？","options":["initdb 脚本只在数据目录为空时执行","需要 docker compose build","需要重启宿主"],"answer":0,"explain":"已有数据的卷不会重新初始化。要么 down -v（会删数据），要么手动执行脚本；生产应该用迁移工具。"},
 {"question":"为什么 JDBC URL 里主机名写 `mysql` 而不是 `localhost`？","options":["服务名是 compose 网络里可解析的主机名；localhost 是 app 容器自己","localhost 更快","MySQL 不支持 localhost"],"answer":0,"explain":"每个容器有自己的网络命名空间（第 06 关），localhost 指向自己。Compose 会把服务名注册成 DNS 记录。"},
 {"question":"`MYSQL_PASSWORD` 改了但应用还是旧口令连不上，怎么解？","options":["该变量只在首次初始化生效，要进库改用户口令","删掉 compose 文件重建","改宿主的 hosts"],"answer":0,"explain":"MySQL 镜像的环境变量只用于初次创建用户；数据目录已存在时它们被忽略。"}
]}
```

## 常见误区

### 误区：把口令写在 compose 文件里就行，反正仓库是私有的

**你以为**：内网仓库，无所谓。
**其实**：compose 文件里的 `environment` 会出现在 `docker inspect`、`docker compose config`、镜像历史（如果写进 Dockerfile）以及**任何有权限读仓库的人**手里。至少用 `.env` + `.gitignore`，更好的做法是运行时注入（CI secret、`--env-file` 从受限文件读）。这个仓库本身是公开仓库时，一次误提交就等于公开口令（Linux 线第 05 关的信任边界问题）。
**怎么验证**：`docker inspect 容器 --format '{{json .Config.Env}}'` 能直接看到口令；`docker compose config` 会把变量替换后的明文打出来。

### 误区：数据卷挂上了就万无一失

**你以为**：`mysql-data` 卷在，数据就没风险。
**其实**：卷只防"容器被删"。它不防"误执行 `docker compose down -v`"、"宿主机磁盘损坏"、"误改表结构"。**备份是另一件事**，且必须演练恢复。另一个常见事故是 `down -v` 被写进了部署脚本。
**怎么验证**：`docker volume ls` 与 `docker compose down --help` 看 `-v` 的描述；把恢复流程真跑一次。

### 误区：容器里连数据库慢是网络问题

**你以为**：容器网络有损耗，所以慢。
**其实**：同宿主同 bridge 网络的容器间通信是内存里的虚拟交换，延迟微秒级。慢通常来自：DNS 解析（用 IP 试试）、连接池没复用、JDBC 没配 `connectionTimeZone` 导致每次连接做时区转换、或 MySQL 侧没索引。先量化再怀疑网络。
**怎么验证**：容器里 `time getent hosts mysql` 与 `time nc -zv mysql 3306` 分别测，再对比 `curl` 到应用接口的耗时。

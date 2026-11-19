---
title: 实战：Jar + MySQL 一次编排起来
category: docker
order: 11
minutes: 24
level: beginner
status: published
---

## 改完 `01-schema.sql`，表还是旧的

把 `users.password_hash` 从 `VARCHAR(100)` 改成 `VARCHAR(128)`（仓库 V1 建表就是 100，`backend/paideia-app/src/main/resources/db/migration/V1__create_account_tables.sql:18`），然后：

```bash
docker compose up -d
docker compose exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" paideia -e "DESCRIBE users"'
```

标准形态，未实跑（本机没有 Docker，`docker --version` 报 command not found）：

```text
Field           Type          Null  Key  Default  Extra
...
password_hash   varchar(100)  NO
```

`password_hash` 还是 `varchar(100)`。先问一句：脚本跑了没有？我第一反应是「脚本没挂进去」或「Compose 没重建」，实际都不是：`docker compose up -d` 只保证容器在跑，不会重放 `docker-entrypoint-initdb.d`。MySQL 官方镜像的 entrypoint 在数据目录里已经有系统库（就是 `mysql` 子目录）时跳过初始化；`mysql-data` 卷里已经有数据，所以 `01-schema.sql` 改了也白改。

这就是本文的起点：**初始化脚本只负责第一次建库**。后面四件事——依赖顺序、数据持久化与初始化、资源限制与 JVM、备份与恢复——都是围绕「这套东西能不能长期用」展开的。

### `docker-entrypoint-initdb.d` 的触发条件

- 只有数据目录为空、容器第一次初始化时执行。
- 挂载目录里的 `.sql`、`.sql.gz`、`.sh` 按文件名排序执行；`01-`、`02-` 这种前缀就是顺序。
- 脚本在 `MYSQL_DATABASE` 建好之后跑，但不要假设当前库就是它；脚本里显式 `USE paideia;` 最稳。
- 初始化失败，容器会退出，`service_healthy` 永远不会通过，app 也就不会启动——这是好事，别用 `restart` 把它盖过去。

要重来，只有两条路：

1. 删卷重来：`docker compose down -v`，再 `up -d`。命名卷里的数据全没，删完不可恢复；先按后面的备份脚本导一份。如果 `./data:/var/lib/mysql` 是绑定挂载，`-v` 不会删宿主目录，得自己处理。
2. 手动执行脚本：只适合幂等脚本。`01-schema.sql` 里如果是裸 `CREATE TABLE`，第二次跑会报 `Table 'users' already exists`；先备份，再在临时库验证。

生产上不要把 `01-schema.sql` 当迁移工具。本仓库的 schema 演进走 Flyway：`backend/paideia-app/src/test/resources/application-test.yml:27-53` 把 `db/migration` 列进 locations，注释写明生产只用 `db/migration`；初始化脚本只适合首次建库和种子数据。改表结构应该新增一个迁移，而不是改一个已经被执行过的 init 脚本。

## 一、依赖顺序：等到能查询，不是等到进程在

Compose 里最容易被写错的是 `depends_on`：

```yaml
services:
  app:
    depends_on:
      mysql:
        condition: service_healthy
  mysql:
    healthcheck:
      test: ["CMD-SHELL", "mysql -h 127.0.0.1 -u\"$$MYSQL_USER\" -p\"$$MYSQL_PASSWORD\" -e 'SELECT 1' >/dev/null 2>&1"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 30s
```

`depends_on` 的短写法 `depends_on: [mysql]` 只保证启动顺序，不保证 MySQL 能接受连接。`condition: service_healthy` 才会等健康检查通过；目标服务没有 `healthcheck` 时，Compose 不会启动 app，并报依赖没有 healthcheck。首次初始化脚本大时，`start_period` 要大于初始化耗时，否则健康检查会在初始化期间开始计失败。

`$$MYSQL_USER` 和 `$$MYSQL_PASSWORD` 里的 `$$` 是 Compose 的转义：让容器里的 shell 看到 `$MYSQL_USER`、`$MYSQL_PASSWORD`，而不是让 Compose 在宿主上替换。健康检查用应用账号跑 `SELECT 1`，不是 `mysqladmin ping`。原因很具体：`mysqladmin ping` 在认证失败时仍可能返回 0——它回答的是「mysqld 这个进程还在吗」，不是「paideia 这个账号能不能登录、能不能查」。MySQL 官方文档对退出码写得很清楚：即使 `Access denied`，服务器在运行，它也可能返回 0。于是你会看到一个很迷惑的组合：`docker compose ps` 里 MySQL 是 `healthy`，app 却一直 `Access denied for user 'paideia'`。

**错了会看到什么**

- 只写 `depends_on: [mysql]`：app 先起，日志里 `Communications link failure` 或 `Connection refused`，然后退出；`restart: unless-stopped` 让它反复重启，看起来像「应用起不来」，其实是在抢跑。
- `service_healthy` 但 mysql 没写 `healthcheck`：Compose 报依赖没有健康检查，直接不启动。
- 健康检查写 `mysqladmin ping`：MySQL 显示 `healthy`，app 仍然认证失败。先用应用账号手动验：

```bash
docker compose exec -T mysql \
  sh -c 'mysql -h 127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B -e "SELECT 1"'
```

标准形态，未实跑：

```text
1
```

命令退出码 0 才说明这个账号能连、能查。`docker compose ps` 的 `(healthy)` 不能替代这一步。

`depends_on` 只影响第一次启动。MySQL 在运行中挂掉，Compose 不会因此停掉 app；这部分要靠 `restart`、监控和告警，不是 `depends_on` 的职责。

## 二、数据持久化与初始化：卷里已有数据，脚本就不会再跑

```yaml
services:
  mysql:
    volumes:
      - mysql-data:/var/lib/mysql
      - ./sql:/docker-entrypoint-initdb.d:ro
volumes:
  mysql-data:
```

两行挂载各管一件事：

- `mysql-data:/var/lib/mysql` 把数据目录放到命名卷。`docker compose down` 删容器和网络，但保留卷；`down -v` 连卷一起删。
- `./sql:/docker-entrypoint-initdb.d:ro` 只在首次初始化时被 entrypoint 扫描。它不是「每次启动都执行」的钩子。

命名卷和绑定挂载的差别要写清楚：

- 命名卷由 Docker 管理，位置不在项目目录里；删卷用 `docker volume rm` 或 `down -v`，`docker volume prune` 也会删未使用卷，别乱跑。备份用 `docker compose exec` 最直接。
- 绑定挂载是宿主目录，`down -v` 不会删它；权限、SELinux、备份路径都要自己管。要清空时先确认绝对路径，别把项目目录写成 `/`。

仓库里有一个真实的对照：`backend/paideia-sandbox/images/mysql/Dockerfile:11-15` 把基础镜像钉到 digest，并把预初始化的 datadir 放在 `/opt/datadir-base`，注释写明 `/var/lib/mysql` 在容器里常被覆盖成卷、容易误挂。它不是本文这套 Compose，但它说明了一件事：**数据目录的位置和挂载方式会直接改变「初始化脚本跑不跑」**。命名卷为空时 Docker 会把镜像里该目录的内容复制进去；绑定挂载则会用宿主目录盖住镜像内容。所以初始化是否发生，取决于挂载后的目录里有没有 `mysql` 系统库。

**错了会看到什么**

- 没挂卷：`docker compose down` 再 `up`，数据库回到空库，`01-schema.sql` 又跑一遍；如果脚本没改，表还在，但数据没了。app 日志可能出现 `Table 'paideia.users' doesn't exist`，取决于初始化是否完成。
- 挂了卷但改了 `01-schema.sql`：就是开头那一幕，表结构不变，日志里也没有 `running /docker-entrypoint-initdb.d/01-schema.sql`。
- 初始化 SQL 写错：MySQL 容器退出，`docker compose ps` 里是 `Exited (1)`；`docker compose logs mysql` 里有 SQL 报错。因为健康检查永远不通过，app 不会启动。先备份、在临时库跑通，再决定是否重建卷。
- `MYSQL_PASSWORD` 改了但卷还在：应用还是旧口令。`MYSQL_USER`/`MYSQL_PASSWORD` 只在首次初始化时创建用户；已有数据目录时改环境变量不会改库里的口令。要么进库 `ALTER USER`，要么备份后删卷重来。

初始化脚本的定位要摆正：它是「首次建库 + 种子」；schema 的后续演进用 Flyway 迁移。两者混用，下一次改表就会回到开头的问题。

## 三、资源限制与 JVM：1G 容器不是默认 256M 堆

先把一个流传很广的说法纠正掉：

> 不设 JVM 参数时，1G 的容器会被 JVM 当成宿主几十 G。

这句话对 8u191 之前的 JVM 才成立。8u191 及以后默认开启容器支持（本文的 Java 21 也在内），会读 cgroup 的 memory limit；`MaxRAMPercentage` 的默认值是 25，所以 1G 容器的默认最大堆约 256M（1/4）。不是「几十 G」。

真正容易出事的是另一半：**app 容器没设 memory limit**。这时 `docker inspect` 里 `HostConfig.Memory=0`，JVM 看不到 cgroup 上限，`-XX:MaxRAMPercentage=70` 就按宿主内存算。16G 宿主算出来约 11.2G 堆上限（这是算术，不是实测）；容器没有上限，堆可以一路涨，最后要么拖慢整机，要么被宿主 OOM killer 杀。你看到的是「容器莫名没了」，不一定是 `OOMKilled=true`——那个字段主要反映 cgroup 内的 OOM。

所以两件事必须成对：

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: "1.0"
```

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
```

`memory: 1g` 是容器 cgroup 的上限，管的是整个进程组；`MaxRAMPercentage=70` 只是从 JVM 看到的这个上限里取 70% 做最大堆，不是「容器最多用 70%」。1G 上限、70% 堆，算出来最大堆约 716M，剩下约 300M 留给元空间、线程栈、直接内存、code cache 和 GC。Spring Boot 应用如果线程多、直接内存用得多，70% 可能偏紧；先从 60–70% 里选一个，跑起来看非堆占用再调。

不要同时写 `-Xmx` 和 `MaxRAMPercentage`。`-Xmx` 是绝对值，会盖掉百分比；两个都写，最后以 `-Xmx` 为准，百分比成了摆设。

**自查两步**

先看容器有没有上限：

```bash
docker inspect paideia-app-1 \
  --format 'Memory={{.HostConfig.Memory}} NanoCpus={{.HostConfig.NanoCpus}}'
```

标准形态，未实跑：

```text
Memory=1073741824 NanoCpus=1000000000
```

`1073741824` 就是 1 GiB；如果看到 `Memory=0`，就是没设上限，后面的百分比按宿主内存算。

再看 JVM 实际算出来的堆：

```bash
docker compose exec -T app \
  sh -c 'java -XX:+PrintFlagsFinal -version 2>&1 | grep -E "MaxHeapSize|MaxRAMPercentage"'
```

标准形态，未实跑：

```text
   size_t MaxHeapSize      = 751619276 {product} {ergonomic}
   double MaxRAMPercentage = 70.000000 {product} {command line}
```

`MaxHeapSize` 的单位是字节。751619276 约等于 716 MiB，对应 1 GiB 的 70%；如果这个数接近宿主内存的 70%，说明 `Memory=0`，limit 没生效。这个命令在 app 容器里跑，会继承 `JAVA_TOOL_OPTIONS`，所以看到的就是应用启动时的那组参数。

**错了会看到什么**

- 没设 `memory`，只写 `MaxRAMPercentage=70`：`docker inspect` 显示 `Memory=0`，`MaxHeapSize` 按宿主算；压测时整机变慢，容器可能被宿主 OOM killer 杀掉。
- `memory: 256m` 还想跑 Spring Boot 应用：容器可能 `Exited (137)`，`docker inspect --format '{{.State.OOMKilled}}'` 为 `true`，日志里看不到 Java 异常，因为进程是被内核杀的。
- `MaxRAMPercentage=90`：堆接近上限，元空间和线程栈一涨就越过 cgroup，表现同上。70% 不是银弹，是留出非堆空间的起点。
- MySQL 也一样要限。仓库里 SQL 沙箱给 mysqld 512m，注释写明 256m 会被 OOM 杀掉（`backend/paideia-app/src/main/resources/application.yml:220-229`）；这是那个镜像和负载的数字，不是所有 MySQL 的通用值，但说明「数据库也要留够非缓存内存」。

## 四、备份与恢复：别让 gzip 的成功掩盖 mysqldump 的失败

先看一条很常见的错命令：

```bash
docker compose exec -T mysql \
  mysqldump -u root -p"$DB_ROOT_PASSWORD" --single-transaction paideia \
  | gzip > backup-$(date +%F).sql.gz
```

在宿主 shell 里，除非你手动 export 过，`$DB_ROOT_PASSWORD` 是空的。`.env` 只被 Compose 用来替换 compose 文件里的 `${...}`，不会自动 `export` 到你的 shell。`mysqldump` 收到空口令，认证失败；如果脚本没开 `pipefail`，管道的退出码是 `gzip` 的 0，你会得到一个很小、能解压但没数据的「成功」备份。

正确做法是把变量展开放进容器：

```bash
docker compose exec -T mysql \
  sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction paideia' \
  | gzip > backup.sql.gz
```

单引号让宿主不展开，容器里的 `sh` 再把 `$MYSQL_ROOT_PASSWORD` 展开。`exec -T` 关掉 TTY，stdout 才是干净的 SQL 流；不加 `-T`，`docker compose exec` 可能报「input device is not a TTY」，或者往流里混入终端字符。

下面是完整脚本。放在项目目录，和 `docker-compose.yml` 同级；`backups/` 加进 `.gitignore`，别把数据文件提交进公开仓库。

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077

cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/paideia-$STAMP.sql.gz"
TMP="$TARGET.partial"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
trap 'rm -f "$TMP"' EXIT

docker compose exec -T mysql sh -c '
  exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
    --single-transaction --quick --routines --triggers --events \
    --default-character-set=utf8mb4 \
    --databases "$MYSQL_DATABASE"
' | gzip -9 > "$TMP"

gzip -t "$TMP"
mv "$TMP" "$TARGET"

find "$BACKUP_DIR" -maxdepth 1 -type f \
  -name 'paideia-*.sql.gz' -mtime +"$RETENTION_DAYS" -delete

echo "backup: $TARGET ($(stat -c %s "$TARGET") bytes)"
```

几个点：

- `set -euo pipefail`：`mysqldump` 失败会沿管道传出来；没有 `pipefail`，`gzip` 成功就把整条命令的失败盖掉了。
- `trap` 删除 `.partial`：失败时不留一个看起来完整的半截文件。
- `gzip -t` 只证明 gzip 容器没坏，不证明 SQL 完整；真正的验证是恢复。
- `--databases "$MYSQL_DATABASE"` 让 dump 里带 `CREATE DATABASE` 和 `USE`，恢复到空实例时不用先手工建库。
- `find ... -mtime +7` 只删本脚本命名规则的旧文件，不碰备份目录里的其他东西。
- `stat -c %s` 是 GNU 版本；macOS 上换成 `stat -f%z`。
- 这条命令把口令放在容器内的命令行上，容器里的 `ps` 能看到；生产上更稳的是把只读的 `client.cnf` 挂进容器，用 `--defaults-extra-file`，仓库的 `deploy/ops/paideia-backup.sh:5-6` 就是这么做的。这里用 `-p` 是为了让脚本短、能直接跑。

**恢复**

先恢复到临时容器，不要直接往正在服务的库上灌：

```bash
docker run --rm -d --name paideia-restore \
  -e MYSQL_ROOT_PASSWORD=restore-only \
  mysql:8.4

for i in $(seq 1 60); do
  docker exec paideia-restore \
    sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1"' >/dev/null 2>&1 && break
  sleep 1
done

gunzip -c ./backups/paideia-20261119-120000.sql.gz \
  | docker exec -i paideia-restore \
      sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

`docker exec -i` 保留 stdin，不能用 `-t`。dump 里带了 `--databases`，所以恢复命令不需要再写库名。

然后比对行数。用仓库里真实存在的两张表：`users`（`backend/paideia-app/src/main/resources/db/migration/V1__create_account_tables.sql:13-26`）和 `notes`（`V7__create_note_tables.sql:10-22`）。

```bash
docker compose exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "SELECT COUNT(*) FROM paideia.users"'

docker exec paideia-restore \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "SELECT COUNT(*) FROM paideia.users"'
```

标准形态，未实跑（42 只是示例，换成两边的实际计数）：

```text
42
42
```

两个数字一样，再对 `notes` 做一遍；更严格一点，再比对 `SHOW CREATE TABLE users` 的列类型。备份是快照，源库如果还在写，行数差几行是正常的——要么停写，要么在备份时刻记下计数再比。

验证完删掉临时容器：

```bash
docker rm -f paideia-restore
```

**错了会看到什么**

- 宿主变量为空：`Access denied for user 'root'@'localhost'`，但 gzip 文件已经生成；没有 `pipefail` 时脚本还返回 0。
- 忘了 `-T`：`the input device is not a TTY`，或者备份文件开头混入 `mysql: [Warning]` 之外的终端内容。
- 没加 `--databases`：dump 里没有 `CREATE DATABASE`/`USE`。`mysql paideia < dump.sql` 恢复空实例会报 `ERROR 1049 Unknown database 'paideia'`；不写库名则报 `No database selected`。
- 只跑 `gzip -t` 不做恢复：文件能解压，但 SQL 可能在中间断掉；只有恢复并比对行数才算验过。
- 备份留在同一块盘：卷删了、盘坏了，备份一起没。至少拷到另一块盘或对象存储。

## 跑一遍

目录结构：

```text
paideia-deploy/
├── docker-compose.yml
├── Dockerfile
├── .dockerignore
├── .env
├── app.jar
├── backup.sh
└── sql/
    └── 01-schema.sql
```

`.env` 只放本地口令，进 `.gitignore`：

```text
DB_ROOT_PASSWORD=换成你的
DB_PASSWORD=换成你的
```

`.dockerignore` 挡住构建上下文里不该进镜像的东西：

```text
.git
target
*.log
.env
sql
```

`.env` 是口令，`sql/` 是给 MySQL 的初始化脚本，都不该进 app 镜像。

我在仓库里没有找到部署用的 `docker-compose.yml`：我用 `git ls-files` 查了带 compose 的文件，只命中内容文章，没有部署文件。下面这份 Compose 是标准形态，不是从仓库里抄出来的生产配置；能对照的真实容器文件是沙箱 MySQL 的 Dockerfile（`backend/paideia-sandbox/images/mysql/Dockerfile:12-15`），它把基础镜像钉到 digest，并把 datadir 放到 `/opt` 避开 `/var/lib/mysql` 的挂载点。

```yaml
services:
  app:
    build: .
    image: paideia-app:v1
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/paideia?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=utf8
      SPRING_DATASOURCE_USERNAME: paideia
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      TZ: UTC
    depends_on:
      mysql:
        condition: service_healthy
    restart: unless-stopped
    stop_grace_period: 30s
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: "1.0"
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
      TZ: UTC
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --default-time-zone=+00:00
    volumes:
      - mysql-data:/var/lib/mysql
      - ./sql:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "mysql -h 127.0.0.1 -u\"$$MYSQL_USER\" -p\"$$MYSQL_PASSWORD\" -e 'SELECT 1' >/dev/null 2>&1"]
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 30s
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: "1.0"
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "3"

volumes:
  mysql-data:
```

`Dockerfile`：

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 app

COPY --chown=10001:10001 app.jar /app/app.jar

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"

USER 10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`--chown` 让 10001 用户能读 jar；`USER 10001` 不以 root 跑；exec 形式的 `ENTRYPOINT` 让 java 是 PID 1，`docker stop` 的 SIGTERM 能到 JVM。基础镜像 tag 会漂移，仓库里的 MySQL 沙箱镜像直接钉 digest（`backend/paideia-sandbox/images/mysql/Dockerfile:11-12`）；生产至少固定 minor tag，能用 digest 更好。

### 时区：`TZ` 只改系统时区，会话要单独定

`TZ: Asia/Shanghai` 只影响容器的系统时区，MySQL 的 `time_zone` 仍然是 `SYSTEM`。如果 JDBC 又写 `connectionTimeZone=UTC` 却没让驱动改会话，就会出现「驱动按 UTC 解释、会话按 +08:00 写」，时间整体差 8 小时。本仓库的测试配置就踩过这个坑：`backend/paideia-app/src/test/resources/application-test.yml:7-13` 的注释写明，只写 `connectionTimeZone=UTC` 是「驱动以为会话是 UTC、会话其实是 +08:00」，必须同时写 `forceConnectionTimeZoneToSession=true`；`README.md:147` 也把这条列为两处易踩配置之一。

三处要一起定：

1. MySQL 会话：`command: --default-time-zone=+08:00`（或者按本仓库的做法 `+00:00`）。
2. JDBC：`connectionTimeZone=+08:00`（URL 里写成 `%2B08:00`，避免 `+` 被当成空格）并配 `forceConnectionTimeZoneToSession=true`；如果选 UTC，就写 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。
3. 验证：`SHOW VARIABLES LIKE '%time_zone%'` 看 `time_zone` 不再是 `SYSTEM`，`SELECT NOW(), UTC_TIMESTAMP()` 看两者差 8 小时（选 UTC 时看 `NOW()` 是否等于 `UTC_TIMESTAMP()`）。

本文的 Compose 选 UTC：`TZ: UTC`、`--default-time-zone=+00:00`、JDBC `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。如果你的业务要求数据库会话就是北京时间：MySQL 的 `--default-time-zone` 用 `+08:00`，JDBC 用 `connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true`，并把两个服务的 `TZ` 和 JVM 的 `-Duser.timezone` 一起改成 `Asia/Shanghai`。不要只改 `TZ`。

验证命令：

```bash
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' <<'SQL'
SHOW VARIABLES LIKE '%time_zone%';
SELECT NOW(), UTC_TIMESTAMP();
SQL
```

标准形态，未实跑：

```text
Variable_name     Value
system_time_zone  UTC
time_zone         +00:00
```

`time_zone` 是 `SYSTEM` 就说明 `--default-time-zone` 没生效；`SYSTEM` 不是「没问题」，只是「跟系统时区走」。两列时间按你选的基准应相等（UTC）或差 8 小时（+08:00）。

起：

```bash
docker compose config
docker compose build
docker compose up -d
docker compose ps
```

`config` 会把 `.env` 里的口令展开成明文，别把输出贴到公开地方。

标准形态，未实跑：

```text
NAME             IMAGE            STATUS                    PORTS
paideia-mysql-1  mysql:8.4        Up 40 seconds (healthy)   3306/tcp, 33060/tcp
paideia-app-1    paideia-app:v1   Up 12 seconds             127.0.0.1:8080->8080/tcp
```

验三件事：HTTP 能通、应用账号能查、表真的建了。

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health

docker compose exec -T mysql \
  sh -c 'mysql -h 127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" paideia -e "SHOW TABLES"'
```

标准形态，未实跑：

```text
200
Tables_in_paideia
notes
users
...
```

三条一起看：`200`、`SHOW TABLES` 有表、`docker compose logs app` 没有连接/认证错误。少一条都不算「起来了」。如果 app 日志里是 `Public Key Retrieval is not allowed`，是 MySQL 8 的 `caching_sha2_password` 在非 SSL 连接下需要 `allowPublicKeyRetrieval=true` 或 TLS；URL 里已经带了，改 URL 时别丢。

改完 jar 更新：

```bash
docker compose up -d --build app
```

MySQL 不会重启，卷也不动。别用 `down -v` 做日常更新。

起不来时按 `docker compose ps` → `docker compose logs --tail=50 app` → `docker compose logs --tail=50 mysql` → `docker inspect` 的顺序看，不要先重启。

## 检查一下

```quiz
{"id":"docker-11-quiz","title":"实战三问","questions":[
 {"question":"改了 `sql/01-schema.sql`，`docker compose up -d` 之后表结构没变。先查什么？","options":["数据目录里已有 MySQL 系统库，entrypoint 跳过了初始化","镜像 tag 写错了","JVM 堆太小"],"answer":0,"explain":"初始化脚本只在数据目录为空、容器第一次初始化时执行。卷里已有数据时，改脚本再 up 不会重跑；要重来，要么备份后删卷，要么手动执行幂等脚本。"},
 {"question":"`mysqladmin ping` 返回 0，能说明应用账号一定可连吗？","options":["能，ping 就是登录测试","不能，认证失败时它也可能返回 0，只证明进程在","不能，因为它只检查磁盘"],"answer":1,"explain":"MySQL 文档写明，即使 Access denied，服务器在运行，mysqladmin ping 也可能返回 0。验证应用账号要用 `mysql -u<user> -p<pwd> -e 'SELECT 1'`。"},
 {"question":"`docker inspect` 看到 `Memory=0`，JVM 又设了 `-XX:MaxRAMPercentage=70`，堆上限按什么算？","options":["按 0 算，JVM 起不来","按 1G 算","按宿主内存的 70% 算"],"answer":2,"explain":"Memory=0 表示容器没有 memory limit；JVM 读不到 cgroup 上限，就按宿主内存乘 70%。先给容器设 limits.memory，再用 PrintFlagsFinal 核对 MaxHeapSize。"}
]}
```

## 还差的

- 本机没有 Docker（`docker --version` 报 command not found）。本文所有 `docker ...` 回显都标了「标准形态，未实跑」，不是在本机跑出来的。
- 1G 容器的 716M 堆是算术值，没有在真实容器里量 `MaxHeapSize`、RSS、非堆占用；不同 JDK/Spring Boot 版本的实际对齐值会有差异。
- 备份脚本没有在真实 MySQL 8.4 + Compose 上执行过；`--routines`/`--events` 所需权限、口令含特殊字符、`gzip` 版本差异都未验证。
- 没有实际跑 `down -v` 做删卷演练；命名卷与绑定挂载的删除行为按 Docker 语义说明，绑定挂载未在本机验证。
- 时区只核对了仓库的测试配置和 Connector/J 参数，没有在 +08:00 与 UTC 两套环境里用 `SELECT NOW()` 对照；本仓库实际选的是 UTC（`backend/paideia-app/src/test/resources/application-test.yml:7-13`、`README.md:147`）。
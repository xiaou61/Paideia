---
title: Compose：一份 yml 起整套环境
category: docker
order: 7
minutes: 26
level: beginner
status: published
---

## 这一关在解决什么

一个后端要跑起来，至少需要应用容器 + 数据库容器，外加网络、卷、环境变量、启动顺序。用七条 `docker run` 描述这些，读的人要对着七条命令拼出全貌，而且重来一次就得再拼一次。

Compose 把这些收进**一个 yml 文件**：`docker compose up -d` 起全部，`down` 收全部。

```sketch
{
  "title": "七条 run 收进一个 yml",
  "rows": [
    [
      { "id": "runs", "title": "七条 docker run", "sub": "读的人要自己拼全貌", "tone": "amber" },
      { "id": "yml", "title": "一份 compose.yml", "sub": "服务、网络、卷、顺序", "tone": "blue" },
      { "id": "up", "title": "up -d / down", "sub": "一键起停", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "runs", "to": "yml" },
    { "from": "yml", "to": "up" }
  ]
}
```

## 为什么用文件而不是脚本

因为 Compose 文件是**声明式的**：它描述"最终应该是什么状态"，而不是"依次执行这些命令"。带来三个实际好处：

- **能 diff**：环境配置进版本管理，改动看得见；
- **能重复**：任何一台机器上 `up -d` 得到同样的结构；
- **能干部分活**：改了一个服务的镜像，`up -d` 只重建那一个，其他的不动。

## 讲解

### 命令：注意是 `docker compose`

Compose V2 是 Docker 的插件，命令是**两个词**：

```text
$ docker compose up -d          # 起全部（-d 后台）
$ docker compose ps
$ docker compose logs -f api    # 跟某个服务的日志
$ docker compose exec api sh    # 进服务（不用记容器名）
$ docker compose down           # 停并删容器 + 网络
$ docker compose down -v        # 连卷一起删（数据会没！）
$ docker compose build          # 重建镜像
$ docker compose up -d --build  # 重建并重启
```

`docker-compose`（带连字符）是 V1，已经停止维护。老教程里的 `docker-compose up` 现在多半要装独立包，能用 `docker compose` 就用它。

### 一份能用的 `docker-compose.yml`

```yaml
services:
  api:
    build:
      context: .
      dockerfile: Dockerfile
    image: myapp:v1
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      DB_HOST: db
      DB_PORT: "5432"
    env_file:
      - .env.local
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:16
    environment:
      POSTGRES_PASSWORD: secret
      TZ: Asia/Shanghai
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 10
    restart: unless-stopped

volumes:
  pgdata:
```

逐段解释：

| 键 | 说明 |
| --- | --- |
| `services` | 一个服务 = 一类容器。名字（`api`、`db`）就是**同一个网络里的主机名** |
| `build` | 从 Dockerfile 构建；`context` 是构建上下文（第 04 关） |
| `image` | 用哪个镜像；和 `build` 同时写 = "构建并打上这个标签" |
| `ports` | 端口映射，格式与 `-p` 相同 |
| `environment` | 环境变量，**值是字符串**（`8080` 要加引号，否则 YAML 会当数字处理） |
| `env_file` | 从文件读环境变量，适合放本地配置（进 `.gitignore`） |
| `depends_on` | 启动顺序与依赖条件（下面细讲） |
| `volumes` | 卷或绑定挂载；顶层 `volumes:` 声明命名卷 |
| `healthcheck` | 健康检查，`docker compose ps` 会显示 `healthy` |
| `restart` | `no` / `on-failure` / `always` / `unless-stopped` |

`version: "3.8"` 那个顶层键**已经过时**，写了只会得到一条警告（"the attribute `version` is obsolete"）。删掉即可。

### `depends_on` 的真实含义

```yaml
depends_on:
  - db                  # 只保证「先启动 db 容器」，不等它 ready
```

```yaml
depends_on:
  db:
    condition: service_healthy   # 等 db 的 healthcheck 通过
```

这是最容易踩的坑：**第一种写法只保证容器被启动，不保证数据库能接受连接**。PostgreSQL 容器启动要几秒初始化，API 在这几秒里连不上就会崩溃，然后 `restart` 又把它拉起来——你看到的是"日志里一直连不上，过一会儿自己好了"。

要真正等就两个条件一起给：目标服务有 `healthcheck`，依赖方写 `condition: service_healthy`。

注意 `service_healthy` 需要目标有 `healthcheck`，否则报错；`service_started` 等价于普通列表写法。

### `.env` 与变量替换

Compose 会自动读**项目目录下的 `.env`**（不是 `env_file`，是另一个东西）做变量替换：

```yaml
services:
  api:
    image: myapp:${TAG:-latest}
```

```text
# .env
TAG=v1
POSTGRES_PASSWORD=secret
```

`${TAG:-latest}` 是"没定义时用 latest"。**`.env` 里常有口令，必须进 `.gitignore`**——`env_file` 指向的文件同理。

一个诊断命令：

```text
$ docker compose config
```

它会把变量替换后的**最终配置**打印出来。搞不清"到底用了哪个变量、哪个文件"时跑它，比猜快得多。

### 项目名与容器命名

Compose 会给所有容器加项目前缀（默认是目录名）：`paideia-api-1`。同一台机器上跑两个项目不会互相覆盖。

```text
$ docker compose -p other up -d      # 换一个项目名
```

这在"同一个 compose 文件起两套环境（测试/开发）"时很有用，但卷也会各自独立，别指望共享数据。

### `profiles`：默认不启动的服务

```yaml
services:
  api:
    image: myapp:v1
  adminer:
    image: adminer
    profiles: ["tools"]
```

```text
$ docker compose up -d                    # 只起 api
$ docker compose --profile tools up -d    # 连 adminer 一起
```

调试工具、一次性任务放进 profile，就不会每次 `up` 都拉起来。

## 动手

```code-challenge
{"id":"docker-07-healthy","kind":"fill","title":"让 api 等到数据库真的能连再启动","language":"yaml",
 "template":"services:\n  api:\n    build: .\n    depends_on:\n      db:\n        condition: service_{{0}}\n  db:\n    image: postgres:16\n    healthcheck:\n      test: [\"CMD-{{1}}\", \"pg_isready\", \"-U\", \"postgres\"]\n      interval: 5s\n      retries: 10",
 "options":[["healthy","started","running"],["SHELL","RUN","EXEC"]],"answers":[0,0],
 "explain":"只有 `condition: service_healthy` 会等检查通过；普通 depends_on 只保证容器启动，数据库还没 ready 时 api 会连不上并崩溃重启。`CMD-SHELL` 允许 shell 语法，`CMD` 是直接 exec。"}
```

## 检查一下

```quiz
{"id":"docker-07-quiz","title":"Compose 三问","questions":[
 {"question":"`depends_on: [db]` 保证了什么？","options":["只保证 db 容器先启动，不保证它可用","保证 db 健康后才启动 api","保证两者同时启动"],"answer":0,"explain":"要等可用必须写 condition: service_healthy，并且 db 要有 healthcheck。"},
 {"question":"`docker compose down -v` 与 `down` 的区别？","options":["-v 会把数据卷一起删掉","-v 只是输出更详细","没有区别"],"answer":0,"explain":"-v 删除 compose 定义的卷，数据库数据会一起消失；日常只该用 `down`。"},
 {"question":"compose 里两个服务互相访问应该用什么地址？","options":["服务名，例如 db:5432","宿主的 127.0.0.1","容器 IP"],"answer":0,"explain":"Compose 会为项目建一个自定义网络，服务名就是可解析的主机名；容器 IP 重建后会变。"}
]}
```

## 常见误区

### 误区：Compose 就是"把 docker run 抄成 yml"

**你以为**：只是换个写法，没有新东西。
**其实**：Compose 额外给了三样东西：**项目级网络**（服务名互解析，不用手动 `docker network create`）、**项目级生命周期**（一起起、一起收、`down` 干净清理）、**依赖与健康检查**。这些是 `docker run` 手工拼很难维护的部分。
**怎么验证**：`docker compose up -d` 之后 `docker network ls` 会多一个 `<项目名>_default`；`docker compose down` 之后它消失。

### 误区：`environment` 里写 `DB_PORT: 5432` 没问题

**你以为**：YAML 里数字就是数字。
**其实**：环境变量的值必须是字符串。有些场景 YAML 会把 `5432` 解析成整数、再被转成 `"5432"` 看起来没错，但 `true`/`false`、`8080` 这类值在某些工具链里会变成布尔或数字导致校验失败。**一律加引号**是零成本的保险。
**怎么验证**：`docker compose config` 看最终展开的值；`docker compose exec api env | grep DB_PORT` 看容器里真实拿到的内容。

### 误区：改了 yml 之后 `docker compose up -d` 一定生效

**你以为**：文件改了，up 一下就好。
**其实**：`up -d` 只重建**配置发生变化的**服务；改了 `Dockerfile` 或源码，要加 `--build` 才会重新构建镜像。只有 `environment`/`ports` 这类容器配置变了才会自动重建，镜像层里的代码不会自己更新。
**怎么验证**：`docker compose up -d --build`；或者先 `docker compose build` 再 `up -d`。看 `docker compose ps` 的 `CREATED` 时间有没有更新。

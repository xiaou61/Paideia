---
title: Compose 编排
category: docker-deploy
order: 4
minutes: 30
level: intermediate
status: published
---

## 场景

现在只有 app 一个服务：

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
```

你要把它补成一套能启动的环境：

- `app` 通过 `DB_HOST=db` 连数据库；
- 增加 `db` 服务，用 `mysql:8.0`；
- 数据库有健康检查（`mysqladmin ping`）；
- `app` 等数据库**健康**后再启动，而不是"容器起来了"就启动；
- 数据库数据落到命名卷 `db-data`。

打个比方：起服务像开饭，数据库是后厨，得等后厨真的能出菜，前厅才开门迎客。真实机制是：`depends_on` 默认只等容器进程起来，`condition: service_healthy` 才会等健康检查通过。

```sketch
{
  "title": "Compose 启动顺序",
  "rows": [
    [
      { "id": "db", "title": "db 服务", "sub": "mysql:8.0", "tone": "blue" },
      { "id": "hc", "title": "healthcheck", "sub": "mysqladmin ping", "tone": "blue" },
      { "id": "healthy", "title": "变成 healthy", "sub": "能接受连接", "tone": "green" }
    ],
    [
      { "id": "app", "title": "app 服务", "sub": "build .", "tone": "violet" },
      { "id": "dep", "title": "depends_on", "sub": "condition service_healthy", "tone": "violet" },
      { "id": "start", "title": "app 启动", "sub": "连得上数据库", "tone": "green" }
    ],
    [
      { "id": "vol", "title": "命名卷 db-data", "sub": "挂 /var/lib/mysql", "tone": "amber" },
      { "id": "persist", "title": "down 后还在", "sub": "down -v 才删", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "db", "to": "hc" },
    { "from": "hc", "to": "healthy" },
    { "from": "healthy", "to": "dep", "label": "等健康" },
    { "from": "app", "to": "dep" },
    { "from": "dep", "to": "start" },
    { "from": "vol", "to": "persist" }
  ]
}
```

## 先想清楚

`depends_on` 默认只等"容器启动"，不等"服务可用"。MySQL 容器启动后还要初始化几十秒，这时 app 连上去会报 `Connection refused`。

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: db
    depends_on:
      db:
        condition: service_healthy

  db:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: app
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-proot"]
      interval: 5s
      timeout: 3s
      retries: 10
    volumes:
      - db-data:/var/lib/mysql

volumes:
  db-data:
```

三个要点：

1. `condition: service_healthy` 才是"等健康"；
2. 健康检查命令要能在容器内执行，且真的能判断"可接受连接"；
3. 命名卷让 `docker compose down` 后数据还在（`down -v` 才会删）。

## 动手

```code-challenge
{
 "id": "docker-4-shell",
 "kind": "shell",
 "title": "补全 app + MySQL 的 Compose",
 "seed": [
  {
   "path": "docker-compose.yml",
   "content": "services:\n  app:\n    build: .\n    ports:\n      - \"8080:8080\"\n"
  }
 ],
 "starter": "# 目标：app 加 DB_HOST=db 与 depends_on(condition: service_healthy)；新增 db 服务（mysql:8.0、healthcheck、命名卷 db-data）\n# 可以整份重写： cat > docker-compose.yml <<'EOF' ... EOF\n",
 "judge": "set -e\n[ -f docker-compose.yml ] || { echo \"docker-compose.yml 不见了\"; exit 1; }\ngrep -q 'mysql:8' docker-compose.yml || { echo \"db 服务需要用固定版本 mysql:8.x，不要 latest\"; exit 1; }\ngrep -q 'depends_on:' docker-compose.yml || { echo \"app 缺少 depends_on\"; exit 1; }\ngrep -q 'condition: service_healthy' docker-compose.yml || { echo \"depends_on 需要 condition: service_healthy，光写服务名只等容器启动\"; exit 1; }\ngrep -q 'healthcheck:' docker-compose.yml || { echo \"db 缺少 healthcheck\"; exit 1; }\ngrep -q 'mysqladmin' docker-compose.yml || { echo \"健康检查建议用 mysqladmin ping\"; exit 1; }\ngrep -q 'DB_HOST' docker-compose.yml || { echo \"app 需要 DB_HOST=db\"; exit 1; }\ngrep -q 'db-data:/var/lib/mysql' docker-compose.yml || { echo \"数据库数据需要挂到命名卷 db-data\"; exit 1; }\ngrep -qE '^volumes:' docker-compose.yml || { echo \"缺少顶层 volumes 声明\"; exit 1; }",
 "explain": "app 里 `environment: DB_HOST: db` + `depends_on: db: condition: service_healthy`；db 用 `mysql:8.0`，healthcheck 用 `mysqladmin ping`，`volumes: db-data:/var/lib/mysql`，顶层声明 `volumes: db-data:`。"
}
```

## 常见误区

### 误区：`depends_on: [db]` 就够了

**你以为**：写了依赖，app 就会等 db 好。
**其实**：短语法只等容器启动，不等服务可用。要用长语法 `condition: service_healthy` 并给 db 配 healthcheck。
**怎么验证**：首次 `up` 时看 app 日志是否有连接拒绝。

### 误区：把密码写在 compose 里没问题

**你以为**：compose 文件在服务器上，别人看不到。
**其实**：它会进 Git、进镜像构建上下文、进备份。生产应该用 `env_file`/密钥管理，仓库里只放 `.env.example`。
**怎么验证**：`docker compose config` 会把变量展开成明文，注意别把输出贴到公开地方。

### 误区：不挂卷，数据也会留在容器里

**你以为**：容器不删，数据就在。
**其实**：`docker compose down`、重建容器、换镜像都会丢掉可写层。数据库必须挂卷。
**怎么验证**：`up` 后写一行数据，`down` 再 `up`，看数据是否还在。

## 带走一句

**healthcheck + `service_healthy` + 命名卷**——Compose 编排的三件事，少一件就会在启动顺序或数据上出事。

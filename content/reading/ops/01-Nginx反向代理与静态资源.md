---
title: Nginx：反向代理与静态资源
category: ops
order: 1
minutes: 26
level: intermediate
status: published
---

## 这一关在解决什么

服务在本机 `:8080` 跑得好好的，用域名访问却是 **404 / 502 / 白屏**，而你改了三遍 Nginx 配置，
每次结果都不一样。

**结论先说**：Nginx 的行为由**两条规则**决定——`location` 的**匹配顺序**，和 `proxy_pass`
**末尾有没有斜杠**。把这两条吃透，绝大多数"为什么请求没到我的服务"都有确定答案。

## 讲解

### 一个最小可用的站点

```nginx
server {
    listen 80;
    server_name example.com;

    # 静态文件：直接由 nginx 返回
    root /var/www/site;
    index index.html;

    # 接口：转给后端
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### 关键一：`location` 的匹配顺序

Nginx 不是"从上到下第一个匹配"，它有一套固定顺序：

1. `location = /exact` —— **精确匹配**，命中即停止
2. `location ^~ /prefix/` —— **前缀匹配且不再检查正则**
3. `location ~ regex` / `~* regex` —— 正则匹配（**按配置文件里的先后顺序**）
4. `location /prefix/` —— 普通前缀匹配（**取最长的那条**）

两个坑：

```nginx
location /api { ... }
location /api/ { ... }
```

请求 `/api/users`：只有第二条能命中（`/api` 不带斜杠只匹配路径恰好是 `/api`）。
请求 `/api` ：第一条命中。**这就是"有的接口 404、有的正常"的常见原因**。

```nginx
# 想让 /static 下的东西只走静态、不进后端：用 ^~ 提前截住
location ^~ /static/ { root /var/www; }
location / { proxy_pass http://127.0.0.1:8080; }
```

### 关键二：`proxy_pass` 末尾那个斜杠

```nginx
location /api/ { proxy_pass http://127.0.0.1:8080/; }   # 带斜杠
# 请求 /api/users  →  转成  /users

location /api/ { proxy_pass http://127.0.0.1:8080; }    # 不带斜杠
# 请求 /api/users  →  转成  /api/users
```

**规则**：`proxy_pass` 带 URI（末尾有 `/` 或路径）时，会把 `location` 匹配到的那部分**替换**成
proxy_pass 里的路径；不带 URI 时**原样透传**。

所以 Spring 项目通常写 `proxy_pass http://127.0.0.1:8080;`（后端自己有 `/api` 前缀）；
如果把后端挂在根路径、希望剥掉 `/api`，才用带斜杠的写法。

### 静态资源：给前端 SPA 的兜底

```nginx
location / {
    root /var/www/app;
    try_files $uri $uri/ /index.html;   # 前端路由兜底
}
```

`try_files` 依次尝试：文件存在就返回、目录存在就进目录、都不行返回 `index.html`。
React/Vue 的 history 路由刷新 404，就是漏了这一行。

同时要缓存哈希资源、不缓存入口 HTML：

```nginx
location ~* \.(js|css|png|jpg|svg|woff2)$ {
    expires 365d;
    add_header Cache-Control "public, immutable";
}
location = /index.html {
    add_header Cache-Control "no-cache";
}
```

### 502 / 504 / 404 怎么读

| 状态码 | 含义 | 先看哪 |
| --- | --- | --- |
| **502** Bad Gateway | nginx 连不上后端（拒绝连接/后端崩了） | `ss -lntp \| grep 8080`、`journalctl -u 你的服务` |
| **504** Gateway Timeout | 连上了但超时 | 后端慢（慢 SQL、线程池满），看应用指标 |
| **404** | nginx 没匹配到文件或 location | `nginx -T \| grep -A5 location` |

502 里最反直觉的一种：后端在 `127.0.0.1:8080`，而 nginx 在**容器里**跑——
容器里的 `127.0.0.1` 是容器自己。要么用宿主 IP，要么把两个服务放进同一网络用服务名。

## 动手

```text
# 1. 校验语法（改完必做，不然 reload 会失败但旧配置还在跑）
$ sudo nginx -t

# 2. 生效（reload 不中断连接；restart 会）
$ sudo nginx -s reload

# 3. 看最终生效配置（含 include 进来的所有文件）
$ sudo nginx -T | grep -n "location" | head

# 4. 直接验证转发
$ curl -i http://127.0.0.1/api/health
```

## 检查一下

```quiz
{"id":"ops-01-quiz","title":"Nginx 反向代理三问","questions":[
 {"question":"location /api/ 和 location /api 的区别是什么？","options":["前者匹配 /api 开头的所有路径，后者只匹配路径恰好等于 /api","两者完全等价","后者优先级更高"],"answer":0,"explain":"末尾斜杠让它成为前缀匹配；不带斜杠的普通前缀只匹配完全相同的路径（严格说 /api 也会被 /api/ 的请求命中的规则影响，但 /api 本身走第一条）。"},
 {"question":"proxy_pass http://127.0.0.1:8080/;（带斜杠）会把 /api/users 转发成什么？","options":["/users（剥掉 location 前缀）","/api/users","/"],"answer":0,"explain":"带 URI 的 proxy_pass 会替换掉 location 匹配到的部分；不带 URI 时原样透传。"},
 {"question":"前端 SPA 刷新页面 404，最可能漏了什么？","options":["location / 里的 try_files 兜底到 index.html","proxy_set_header Host","gzip 没开"],"answer":0,"explain":"history 路由的 URL 在磁盘上不存在，必须 try_files 兜底到 index.html，否则 nginx 直接 404。"}
]}
```

## 常见误区

### 误区：改了配置就 `restart`，反正能重启

**你以为**：`nginx -s reload` 与 `restart` 差不多。
**其实**：`restart` 会**断开所有连接**（正在上传文件、正在长轮询的请求全部失败），
而 `reload` 是平滑加载新配置、旧 worker 处理完再退出。线上永远用 `reload`，
而且**先 `nginx -t`**——配置写错时 reload 会失败但旧配置继续跑，你会以为"改了没生效"。

### 误区：502 就是后端挂了

**你以为**：502 一定是服务崩了。
**其实**：502 只是"nginx 没能从后端拿到有效响应"。常见还有：端口写错、
后端只监听 `127.0.0.1` 而 nginx 在另一台机器、容器网络里 `127.0.0.1` 指错、
后端启动中还没监听。**先 `ss -lntp` 确认端口在听、且监听地址对**。

## 小结

Nginx 的确定性只来自两处：`location` 的匹配顺序（精确 → `^~` → 正则 → 最长前缀）
和 `proxy_pass` 末尾的斜杠（带 = 剥前缀，不带 = 原样）。静态资源用 `try_files` 兜底 SPA，
哈希资源长缓存、`index.html` 不缓存。改完 `nginx -t` 再 `reload`，502 先查端口与监听地址。

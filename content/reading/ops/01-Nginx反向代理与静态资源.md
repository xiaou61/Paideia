---
title: Nginx：反向代理与静态资源
category: ops
order: 1
minutes: 16
level: intermediate
status: published
---

# Nginx：反向代理与静态资源

## 一次 404

服务在本机 `8080` 上跑着，`curl http://127.0.0.1:8080/api/health` 返回 `{"status":"UP"}`。换成域名就废了：

```bash
$ curl -i https://example.com/api/health
HTTP/2 404
server: nginx/1.24.0
content-type: text/html

<html>
<head><title>404 Not Found</title></head>
```

那个 `server: nginx` 和朴素的 nginx 错误页是关键线索：**请求根本没到你的后端**，是 nginx 自己回的 404。

再看配置（`nginx -T` 会把 include 进来的所有文件都打出来，比翻目录可靠）：

```nginx
server {
    listen 443 ssl;
    server_name example.com;

    root /var/www/site;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
    }
}
```

问题藏在一个字符里：`location /api/`（带斜杠）。它匹配 `/api/health`，**但不匹配 `/api`**。而我的健康检查打的是 `/api`——于是落到 `location` 一个都没命中的默认路径，去 `root` 里找名为 `api` 的文件，找不到，404。

把 `location /api/` 改成 `location /api` 就通了。这一关讲的就是这种"差一个字符"的确定性：`location` 到底怎么配对，`proxy_pass` 末尾的斜杠到底做什么。

## 前缀匹配的真相

先纠正一个最常见的误解。下面这段不是"带斜杠才匹配子路径"：

```nginx
location /api  { ... }   # 能匹配 /api、/api/、/api/users、/apifoo
location /api/ { ... }   # 能匹配 /api/、/api/users，但匹配不到 /api
```

`location /api` 是**普通前缀匹配**，语义就是"路径以 `/api` 这几个字符开头"——它不是"路径恰好等于 /api"。所以：

| 请求路径 | `location /api` | `location /api/` |
| --- | --- | --- |
| `/api` | 命中 | **不命中**（比配置短了一个字符） |
| `/api/users` | 命中 | 命中 |
| `/apifoo` | 命中（字符串前缀，不按 `/` 切段） | 不命中 |

这就是为什么很多人"加了斜杠反而 404"：Kubernetes 的 `readinessProbe` 打 `/healthz`、监控打 `/api`，这类**不带尾斜杠的精确路径**恰好是带斜杠前缀规则的盲区。反过来，你不带斜杠时又要小心 `/apifoo` 这种路径被一起吃掉。

想严格只匹配一级，用 `location = /api`（精确匹配）。

## 匹配优先级：四条规则，一个有顺序的算法

Nginx 不是"从上到下第一个匹配"。它的实际过程是：

1. 先把所有前缀型 location（`/xxx` 和 `^~ /xxx`）比一遍，**选出最长的那条**；
2. 如果最长前缀是 `location = /exact` 的精确匹配，直接用，结束；
3. 如果最长前缀带 `^~`，直接用，**不再看正则**；
4. 否则按配置文件里的**先后顺序**试正则（`~` 区分大小写、`~*` 不区分），第一个命中的就用；
5. 一个正则都没命中，才回到第 1 步选出的那条最长前缀。

于是有个反直觉的结论：**正则优先于普通前缀**。下面两条共存时，`/api/users` 会走正则那条：

```nginx
location ~ ^/api/(users|orders) { proxy_pass http://127.0.0.1:8081; }
location /api/                 { proxy_pass http://127.0.0.1:8080; }
```

而 `^~` 的作用是**在它是最长前缀时**跳过正则检查。注意这个前提——很多人以为写了 `^~` 就一定优先，实际上如果你的 `^~ /static/` 不是最长前缀（比如另有一条更长的 `location /static/css/`），`^~` 根本轮不到发言：

```nginx
location ^~ /static/  { root /var/www; }        # 想让静态文件不进后端
location /            { proxy_pass http://127.0.0.1:8080; }
```

这两条里 `/static/` 是最长前缀且带 `^~`，所以 `/static/app.css` 直接由 nginx 读盘，不进后端——这是对的。但如果再有人加一条 `location ~ \.css$`，`^~` 仍然会拦住它（因为 `^~` 命中就跳过正则），这才是 `^~` 真正省事的地方。

## `proxy_pass` 末尾那个斜杠

这是第二个确定性来源，规则只有一句：**`proxy_pass` 带 URI（末尾有 `/` 或带路径）时，会把 location 匹配到的那部分替换掉；不带 URI 时原样透传。**

```nginx
location /api/ { proxy_pass http://127.0.0.1:8080/; }
# /api/users  →  /users

location /api/ { proxy_pass http://127.0.0.1:8080; }
# /api/users  →  /api/users
```

所以选哪种，取决于**后端自己的路由**：

- Spring 应用的接口本身就写在 `/api/...` 下 → 用**不带斜杠**的写法，让前缀原样传过去；
- 后端挂在根路径（`/users`、`/orders`），想把网关前缀剥掉 → 用**带斜杠**的写法。

有个硬限制：**正则 location 里不能带 URI 的 `proxy_pass`**，写 `location ~ ^/api/(.*)$ { proxy_pass http://x/; }` 会让 `nginx -t` 直接报 `"proxy_pass" cannot have URI part in location given by regular expression`。要用正则又要改写路径，得用 `rewrite` 或 `proxy_pass` 不带 URI 再自行处理。

## 静态资源：SPA 的路由兜底

前端 history 路由（`/settings/profile` 这种）在磁盘上并不存在对应文件，刷新就 404。要兜底到 `index.html`：

```nginx
location / {
    root /var/www/app;
    try_files $uri $uri/ /index.html;
}
```

`try_files` 依次尝试：文件存在就返回；目录存在就交给 `index` 处理；都不行就返回最后一个参数（这里是 `index.html`，返回时状态码是 200，浏览器再按前端路由渲染）。

缓存要分两半——文件名带哈希的产物长缓存，入口 HTML 不缓存：

```nginx
location ~* \.(js|css|png|jpg|svg|woff2)$ {
    expires 365d;
    add_header Cache-Control "public, immutable";
}
location = /index.html {
    add_header Cache-Control "no-cache";
}
```

顺序上要注意：`~*` 是正则，会**赢过**普通前缀 `location /`（见上面的优先级算法）。如果 `index.html` 也被那条正则命中（不会，但它旁边的 `.json` 会），记得把例外写成精确匹配。

## 502 / 504 / 404 分别指向哪里

| 状态码 | nginx 这句话的意思 | 先查什么 |
| --- | --- | --- |
| **502** Bad Gateway | 没能从上游拿到**有效响应** | 上游进程在不在、端口对不对、监听地址、容器网络 |
| **504** Gateway Timeout | 连上了，但在 `proxy_read_timeout` 内没等到响应 | 后端为什么慢：慢 SQL、线程池满、下游阻塞 |
| **404** | 两种都可能：nginx 没配上 location，或后端自己返回 404 | 看响应头和响应体：`server: nginx` + nginx 错误页 = 前者 |

502 的成因比"后端挂了"多得多：端口写错、后端只监听 `127.0.0.1` 而 nginx 在另一台机器、后端启动中还没开始 listen、上游返回了 nginx 无法解析的响应。

最反直觉的一种是**容器里的 `127.0.0.1`**：nginx 跑在容器里，`proxy_pass http://127.0.0.1:8080` 指的是**容器自己**，不是宿主机。要么改成宿主机 IP 或 `host.docker.internal`，要么把两个服务放进同一个 Docker network 用服务名（`http://app:8080`）。这个坑的典型症状是：宿主机上 `curl 127.0.0.1:8080` 通，容器里 `curl 127.0.0.1:8080` 拒绝连接。

```bash
# 端口在不在听、监听在哪个地址（0.0.0.0 vs 127.0.0.1 差别很大）
$ ss -lntp | grep 8080

# 从 nginx 所在的网络命名空间里直接打一次
$ docker exec -it nginx curl -i http://app:8080/api/health
```

## 改完怎么确认

```bash
# 1. 先验语法。配置写错时 reload 会失败，而旧 worker 还在跑旧配置——
#    你会以为"改了没生效"，其实是根本没加载
$ sudo nginx -t

# 2. 平滑生效（reload 不中断连接；restart 会断开所有正在处理的请求）
$ sudo nginx -s reload

# 3. 看最终生效的配置，包含 include 进来的所有文件
$ sudo nginx -T | grep -n -A4 "location"

# 4. 直接验一次转发，看响应头里的 server 和上游加的 header
$ curl -i https://example.com/api/health
```

第 4 步比看日志快：如果响应头里出现你后端设置的 header（比如 Spring 的 `X-Application-Context` 或自定义 trace id），说明请求确实到了后端；如果只有 `server: nginx`，说明还停在 nginx 这一层。

## 留个尾巴

我这套配置里还有个没解决的问题：`location ~* \.(js|css|...)$` 那条正则也会命中 `/api/v1/report.css` 这种接口路径（虽然很少见），一旦命中就会去磁盘找文件而不是转发。彻底的修法是把静态资源挪到独立的子路径（`/assets/`）并用前缀匹配隔离，但我现在只是把正则写成 `^/assets/.*\.(js|css|...)$` 绕过去了——这属于"能用但不优雅"，等哪天接口真返回 `text/css` 再动手。

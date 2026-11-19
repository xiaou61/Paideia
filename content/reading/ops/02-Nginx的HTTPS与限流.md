---
title: Nginx：HTTPS、负载均衡与限流
category: ops
order: 2
minutes: 16
level: intermediate
status: published
---

# Nginx：HTTPS、负载均衡与限流

## 一次没能续上的证书

`certbot renew --dry-run` 输出到最后一屏是这样：

```text
Challenge failed for domain example.com
http-01 challenge for example.com
Certbot failed to authenticate some domains (authenticator: nginx).
Detail: Invalid response from
http://example.com/.well-known/acme-challenge/J9xQ2m...: 301
```

`301`。ACME 的 http-01 验证只能走 80 端口，它去取挑战文件，被我的 80 端口一脚踹到了 https。

我当时的 80 配置看着是"对的"：

```nginx
server {
    listen 80;
    server_name example.com;
    return 301 https://$host$request_uri;    # ← 罪魁祸首

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
}
```

`location` 写在 `return` 后面，但**位置不是原因**。真正的原因是 `return` 在 `server` 级别：它在 location 匹配之前就执行，任何请求——包括挑战文件——都被跳走了。

这篇就按"证书、分流、限流"三段讲这三件事，每段都带一个我踩过的具体形态。

## 先对齐几个词

- **证书链**：不是一张证书，是一串（你的 + 中间 CA）。少一张浏览器就报"不安全"。
- **ACME**：自动签发证书的协议，Let's Encrypt 用它。验证方式之一是"我能在你的 80 端口放个文件"。
- **漏桶**：限流模型——请求像水，桶底有个固定大小的洞（平均速率），桶满了才溢（拒绝）。
- **并发连接**：此刻还没结束的连接数。它和"每秒请求数"是两件不同的事。

## HTTPS：先修好 80 端口

正确的顺序是把跳转放进 location，让挑战目录有独立的匹配：

```nginx
server {
    listen 80;
    server_name example.com;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}
```

为什么这样就对了：`/.well-known/acme-challenge/` 是比 `/` 更长的前缀，Nginx 的**前缀 location 取最长匹配**，与书写顺序无关。所以挑战请求命中第一个 location，其余请求命中第二个。**顺序不重要，层级才重要**——把 `return` 放在 `server` 级别就绕过了这一切。

### 443 那段

```nginx
server {
    listen 443 ssl;
    http2 on;                      # nginx 1.25.1+ 的写法
    server_name example.com;

    ssl_certificate     /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:10m;

    location / {
        proxy_pass http://backend;
    }
}
```

`listen 443 ssl http2;` 这种写法在 **nginx 1.25.1 起已弃用**，启动会警告 `the "listen ... http2" directive is deprecated, use the "http2" directive instead`。老配置还能跑，但新配置别这么写。

顺便一个常被问到的点：`fullchain.pem` 里已经含中间证书，**不要**只指向 `cert.pem`。用 `cert.pem` 的表现是电脑上好好的、手机上某些机型报证书链不完整——因为部分系统自己缓存了中间 CA，部分没有。

### 续期与告警

```text
$ sudo certbot renew --dry-run
$ sudo systemctl list-timers | grep certbot
```

Let's Encrypt 的证书有效期是 **90 天**，不是一年。certbot 的计时器每天跑两次，只在剩余不足 30 天时真正续期。

到期检查（`-servername` 必须带，否则可能拿到默认站点的证书）：

```text
$ echo | openssl s_client -connect example.com:443 -servername example.com 2>/dev/null \
    | openssl x509 -noout -dates
notBefore=Nov 12 03:14:22 2026 GMT
notAfter=Feb 10 03:14:22 2027 GMT
```

自动续期也会失败——比如开头那个 301 一直没人发现，直到某天醒来整站报"不安全"。所以**到期前 14 天告警**是最后一道保险，它防的不是证书，是"续期这件事悄悄坏了"。

## 负载均衡：`upstream` 与一台挂掉会怎样

```nginx
upstream backend {
    server 10.0.0.11:8080 max_fails=3 fail_timeout=30s;
    server 10.0.0.12:8080 max_fails=3 fail_timeout=30s;

    keepalive 32;
}

server {
    location / {
        proxy_pass http://backend;
        proxy_http_version 1.1;              # 少了这两行
        proxy_set_header Connection "";       # keepalive 等于没开
    }
}
```

最后两行是配套的：`keepalive 32` 只是让 Nginx 保留 32 条到上游的空闲连接，但默认用的是 HTTP/1.0 且带 `Connection: close`，**不补这两行，连接一条都复用不了**。这是个很典型的"配了但没生效"，症状是上游看到的 TCP 连接数居高不下。

### "第一个打到坏机器的用户会失败"——这句不完全对

我原来一直这么写，后来发现它需要限定条件。

`max_fails` / `fail_timeout` 管的是**摘除**：30 秒内失败 3 次，就把这台摘掉 30 秒。但**换机器这件事是另一条配置管的**：

```nginx
proxy_next_upstream error timeout;    # 这是默认值
```

连接被拒（`error`）或超时（`timeout`）时，Nginx **默认就会换下一台**。所以后端进程已经死掉时，用户拿到的往往是成功响应——只是多花了一次连接的时间。

真正会"第一个用户失败"的是两种情况：

- 失败发生在 `proxy_next_upstream` **不覆盖**的阶段，比如请求已经发出去、响应体传到一半（`http_502` 这类要显式加，而且它对非幂等请求有风险）；
- 上游不是"连不上"而是"能连上但很慢"，那属于 `timeout`，超时时间本身就是要等满的。

所以准确的说法是：**被动健康检查让故障实例被逐步摘除，但摘除之前，打到它的请求要靠 `proxy_next_upstream` 兜底；兜不住的是"已经发出去一半"和"慢"。**

想要更稳就得主动探测（商业版 `health_check`，开源版 `nginx_upstream_check_module`），或者干脆交给服务发现（Consul、K8s Service）。这也是容器编排受欢迎的原因之一。

三种策略：

| 策略 | 适用 |
| --- | --- |
| 轮询（默认） | 后端能力相同 |
| `least_conn` | 请求耗时差异大（有的 5ms 有的 2s） |
| `ip_hash` | 有会话粘性需求（更好的做法是把会话挪到 Redis） |

## 限流：`limit_req` 是漏桶，不是令牌桶

```nginx
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

location /api/ {
    limit_req zone=api burst=20 nodelay;
    limit_req_status 429;
    proxy_pass http://backend;
}
```

- `rate=10r/s`：**平均**速率。Nginx 的实现是"每 100ms 放一个"。
- `burst=20`：桶容量，超出的才拒绝。
- `nodelay`：突发那 20 个立刻处理；不写则排队按速率放行，用户看到的是延迟。

**`rate=10r/s` 的真实含义是"两个相邻请求至少隔 100ms"**。这一点在低速率配置上会咬人：

```nginx
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;   # 每 12 秒一个

location /api/login {
    limit_req zone=login;                                       # burst 没写
    proxy_pass http://backend;
}
```

`burst` 默认是 0。于是间隔必须 ≥12 秒——用户输错密码、立刻重试，第二个请求直接 429。他只会觉得"这网站坏了"。

正确写法是给一个小桶：

```nginx
limit_req zone=login burst=5 nodelay;
```

含义是：允许瞬间 5 次（够正常人试错），之后每 12 秒补一个。**登录限流的目的不是拦正常用户，是让撞库脚本从"每秒几千次"变成"每分钟几次"。**

### 设多少

别凭感觉。先压测拿到单实例能扛的 QPS，限流设成它的 **70%**，留 30% 给正常突发。

还有一层是 `limit_conn` 和请求体：

```nginx
limit_conn_zone $binary_remote_addr zone=conn:10m;

limit_conn conn 20;            # 单 IP 最多 20 个并发连接
client_max_body_size 10m;      # 请求体上限（上传接口单独放宽）
client_body_timeout 30s;
```

`limit_req` 管"每秒几个请求"，`limit_conn` 管"同时几个连接"。**长连接、慢上传的场景下前者会漏**：一个客户端开 20 条连接每次发一个字节，请求速率很低，但连接全占着。

## 动手

```text
$ sudo nginx -t && sudo nginx -s reload

# 1. 验证限流：连打 100 个请求，看有多少 429
$ for i in $(seq 100); do
    curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1/api/health
  done | sort | uniq -c

# 2. 验证 keepalive 真的生效了：看和上游的 TIME_WAIT 是否减少
$ ss -s | head -5

# 3. 验证证书还剩多久（-servername 别漏）
$ echo | openssl s_client -connect example.com:443 -servername example.com 2>/dev/null \
    | openssl x509 -noout -dates
```

第 2 条要连跑两次（改配置前跑一次、改后跑一次）才有意义——单看一次看不出区别。

## 检查一下

```quiz
{
  "id": "ops-02-quiz",
  "title": "HTTPS 与限流三问",
  "questions": [
    {
      "question": "80 端口里 acme-challenge 为什么必须能被单独匹配到？",
      "options": ["否则验证请求被跳转到 https，ACME 拿不到挑战文件","否则证书无法安装","否则 443 端口起不来"],
      "answer": 0,
      "explain": "http-01 验证只能走 80，被 301 之后验证失败。真正致命的写法是把 return 301 放在 server 级别——它先于 location 执行；只放在 location / 里则靠最长前缀匹配就够。"
    },
    {
      "question": "limit_req 的 burst 不写 nodelay 会怎样？",
      "options": ["超出的请求排队按速率放行，用户看到延迟而不是 429","直接拒绝所有请求","突发请求全部丢弃"],
      "answer": 0,
      "explain": "漏桶默认排队；nodelay 让突发立刻处理，超出 burst 的才 429。"
    },
    {
      "question": "upstream 里 max_fails 的被动健康检查，真正不管的是什么？",
      "options": ["进程已经死掉、连接被拒的情况（默认会换下一台）","已经发出一半、响应中断的请求，以及能连上但很慢的上游","https 下的请求"],
      "answer": 1,
      "explain": "proxy_next_upstream 默认含 error timeout，连接失败会自动换一台；兜不住的是中间中断的响应与非幂等请求，以及「慢」本身——慢就得等满超时。"
    }
  ]
}
```

## 两件我原本记错的事

**一、"证书一年有效期。"** Let's Encrypt 是 **90 天**，提前 30 天自动续。所以"装上就不管"的窗口不是一年，是 60 天左右。

**二、"被动健康检查意味着第一个用户会失败。"** 只在 `proxy_next_upstream` 覆盖不到的场景成立。连接被拒默认就会换下一台，用户根本不知道有过一台坏的。我拿这句当过好几年的"常识"，写这篇时才发现它需要限定。

## 这一课之后

HTTPS 三件：证书链完整、80 端口给 ACME 留路（关键是把 `return` 关进 location）、到期告警兜底。负载均衡记住 `max_fails` 管摘除、`proxy_next_upstream` 管兜底，两者是两件事；`keepalive` 要配 `proxy_http_version 1.1` 与空的 `Connection` 才生效。限流是漏桶：`rate` 是平均间隔、`burst` 是容量、`nodelay` 决定突发是立刻处理还是排队——按压测值的 70% 设，登录这类接口单独一档并给 burst，否则正常用户先被拦。

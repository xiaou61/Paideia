---
title: 镜像仓库：命名、推拉与私有 registry
category: docker
order: 8
minutes: 20
level: beginner
status: published
---

## 这一关在解决什么

`myapp:v1` 和 `ghcr.io/lzf/myapp:v1` 有什么区别？为什么 `docker push myapp:v1` 报 `denied`？镜像怎么从构建机搬到服务器？

这一关讲清楚**镜像名的三段结构**，因为它是所有"推不上去、拉不下来"问题的源头。

## 为什么每个镜像名都长得像地址

因为镜像名本身就是**一个路径**：仓库地址 + 命名空间 + 名字 + 标签。

```text
ghcr.io / lzf / myapp : v1
  │        │      │      └─ 标签（默认 latest）
  │        │      └──────── 仓库名
  │        └─────────────── 命名空间（用户或组织）
  └──────────────────────── registry 地址（省略时是 Docker Hub）
```

`nginx:1.27` 只是简写，完整形式是 `docker.io/library/nginx:1.27`——`library` 是 Docker Hub 官方镜像的命名空间。

## 为什么这个结构重要

因为**推送的目标由镜像名决定**。`docker push myapp:v1` 里的名字没有 registry 地址，Docker 就认为你要推到 Docker Hub 的 `<你的账号>/myapp`；如果这个路径和你的账号对不上，就是 `denied: requested access to the resource is denied`。

**推之前必须先把镜像 tag 成目标全名**：

```text
$ docker tag myapp:v1 ghcr.io/lzf/myapp:v1
$ docker push ghcr.io/lzf/myapp:v1
```

`docker tag` 只是加了个名字（第 03 关），不复制内容。

## 讲解

### 登录与凭据

```text
$ docker login                        # Docker Hub
$ docker login ghcr.io -u lzf         # GitHub Container Registry（密码用 PAT）
$ docker logout
```

凭据存在 `~/.docker/config.json`，**默认只是 base64，不是加密**。拿到这个文件就能用你的仓库权限推拉镜像。所以：

- 构建机上把它当密钥对待；
- 有 `docker-credential-*` 助手时优先用（凭据进系统钥匙串，不落明文文件）；
- CI 里用短期令牌（`--password-stdin` 而不是 `-p`，后者会进 shell 历史与进程列表）。

```text
$ echo "$TOKEN" | docker login ghcr.io -u "$USER" --password-stdin
```

### 拉取策略

| 策略 | 行为 | 用在 |
| --- | --- | --- |
| `always` | 每次启动都去仓库查一次 | 生产（配合固定 digest） |
| `if-not-present`（默认） | 本地有就不拉 | 开发 |
| `never` | 只用本地 | 离线环境 |

Compose 里可以按服务写：

```yaml
services:
  api:
    image: ghcr.io/lzf/myapp:v1
    pull_policy: always
```

默认的 `if-not-present` 是"改了 tag 指向的新内容但本地有旧 tag，于是永远跑旧的"这类事故的来源。生产用 `always` 或直接用 digest（第 03 关）才稳。

### 私有仓库

小团队最省事的自建方式就是官方 `registry:2` 镜像：

```text
$ docker run -d -p 5000:5000 --name registry \
    -v /data/registry:/var/lib/registry \
    registry:2
```

推拉：

```text
$ docker tag myapp:v1 10.0.0.12:5000/myapp:v1
$ docker push 10.0.0.12:5000/myapp:v1
```

三个必须知道的点：

- **默认是 HTTP，不是 HTTPS**。Docker 默认只接受 HTTPS 的 registry，所以推 HTTP 的会报 `http: server gave HTTP response to HTTPS client`。要么在 `/etc/docker/daemon.json` 里加 `"insecure-registries": ["10.0.0.12:5000"]` 然后重启 docker（重新加载 daemon 配置必须重启守护进程），要么给它配一个带证书的反代——**生产必须配证书**。
- **它没有鉴权也没有界面**。要账号密码得自己加 nginx + htpasswd，或者直接用 Harbor（带 UI、RBAC、镜像扫描、保留策略）。
- **本地路径要挂卷**（`-v /data/registry`），否则容器一删镜像全没——这就是第 05 关的道理。

### 不经过仓库搬镜像

生产机不能连外网、又不想建 registry 时：

```text
$ docker save myapp:v1 | gzip > myapp-v1.tar.gz      # 构建机导出
$ scp myapp-v1.tar.gz prod:/tmp/
$ gunzip -c /tmp/myapp-v1.tar.gz | docker load       # 生产机导入
```

`save` 会把镜像的所有层打成一个 tar（**体积大**，不做增量）。适合偶尔手工部署，不适合频繁发布——每次都传几百 MB。

另一个常见做法是构建机与生产机共用一个内网 registry，`save/load` 只用于应急。

### 清理与保留

```text
$ docker system df                          # 镜像/容器/卷/缓存各占多少
$ docker image prune -a                     # 删所有没有容器在用的镜像
$ docker builder prune                      # 删构建缓存（经常是几个 G）
```

私有 registry 的磁盘会持续增长：每个 tag 都占一层空间，即使层是共享的，旧 tag 也不会自己消失。`registry:2` 的清理要两步（`registry garbage-collect` + 删 manifest），Harbor 则直接配保留策略。不做这一步的话，一年后 registry 会吃掉几百 G。

## 动手

```code-challenge
{"id":"docker-08-push","kind":"fill","title":"把本地镜像推到私有仓库","language":"bash",
 "template":"# 1. 打上仓库全名\ndocker {{0}} myapp:v1 10.0.0.12:5000/myapp:v1\n# 2. 登录（令牌从 stdin 读）\necho \"$TOKEN\" | docker login 10.0.0.12:5000 -u deploy {{1}}\n# 3. 推\ndocker push 10.0.0.12:5000/myapp:v1",
 "options":[["tag","build","save"],["--password-stdin","-p","--token"]],"answers":[0,0],
 "explain":"推送目标由镜像名决定，所以先 tag 成 `registry/namespace/name:tag`；`--password-stdin` 避免口令出现在进程列表与 shell 历史里。"}
```

## 检查一下

```quiz
{"id":"docker-08-quiz","title":"仓库三问","questions":[
 {"question":"`nginx:1.27` 的完整镜像名是什么？","options":["docker.io/library/nginx:1.27","docker.io/nginx:1.27","library.io/nginx:1.27"],"answer":0,"explain":"省略 registry 时默认 Docker Hub（docker.io），省略命名空间时官方镜像用 library。"},
 {"question":"推到自建 HTTP registry 报 `server gave HTTP response to HTTPS client`，怎么解？","options":["把它加进 daemon.json 的 insecure-registries 并重启 docker，或给它配证书","换端口","先 docker login"],"answer":0,"explain":"Docker 默认要求 HTTPS。临时方案是 insecure-registries（要重启守护进程），生产应配证书。"},
 {"question":"`docker save` 与 `docker push` 的区别？","options":["save 导出为文件，push 推到仓库；save 是全量、体积大","两者等价","save 只导出元数据"],"answer":0,"explain":"save/load 适合离线搬运，全量打包；push/pull 只传缺少的层，更省带宽。"}
]}
```

## 常见误区

### 误区：镜像名可以随便起，push 的时候再改

**你以为**：先 `myapp:v1`，推到哪儿到时候看。
**其实**：`push` 完全依赖镜像名里的 registry 与命名空间，名字不对就是 `denied`，而且错误信息不会提示"你没 tag 对"。反过来 `docker tag` 之后再 push 就正常了——所以规则是：**构建时就用目标全名打标签**（`docker build -t ghcr.io/lzf/myapp:v1 .`），少一步手工 tag。
**怎么验证**：`docker image inspect myapp:v1 --format '{{.RepoTags}}'` 看它到底有哪些名字。

### 误区：私有仓库不配鉴权也没关系，反正内网

**你以为**：内网访问，不需要账号。
**其实**：没鉴权的 registry 意味着**内网任何能连上它的人和进程都能拉你的镜像**——镜像里有你的代码、配置、有时还有烤进去的密钥（第 12 关）。而且它能被用来存任意内容（变成公共网盘）。至少加一层 htpasswd，或者把监听绑到回环 + 只让反代访问。
**怎么验证**：从另一台内网机器直接 `docker pull 10.0.0.12:5000/任意镜像` 试试能不能拉——能拉说明谁都能拉。

### 误区：`latest` 推上去就等于"最新版本"

**你以为**：推 latest 就是发布。
**其实**：`latest` 只是一个 tag（第 03 关），推它不会改变任何语义。真正的发布标记是**不可变的版本号或 digest**：`v1.2.3` 加 digest 才能回答"上周二生产上跑的是哪个镜像"。用 `latest` 做生产部署，回滚时你连"上一个版本是什么"都不知道。
**怎么验证**：`docker image ls --digests` 看同 tag 不同 digest 的历史；`docker inspect 镜像 --format '{{index .RepoDigests 0}}'`。

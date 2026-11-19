---
title: 镜像仓库：命名、推拉与私有 registry
category: docker
order: 8
minutes: 22
level: beginner
status: published
---

## denied 不一定是你没登录

（标准形态，未实跑）

```text
$ docker push myapp:v1
The push refers to repository [docker.io/library/myapp]
...
denied: requested access to the resource is denied
```

本机没有 Docker：`docker --version` 实际报的是 PowerShell 找不到 `docker` 这个命令。下面所有 `docker` / registry 回显都是标准形态，不是本机实测；仓库里的行号证据在后面单独列。

看到 `denied`，第一反应是登录过期。于是 `docker login` 成功，再推，还是同一句。问题不在登录，而在第一行：`docker.io/library/myapp`。`library` 是 Docker Hub 官方镜像的命名空间，个人账号没有写权限。`myapp:v1` 只有一段名字，Docker 把它补成了 `docker.io/library/myapp:v1`；你以为在推自己的仓库，实际在推官方命名空间。

`docker login` 只回答「你是谁」，`push` 还要回答「这个名字归谁」。两个都过，才不是 denied。

把名字改成自己的 namespace：

（标准形态，未实跑）

```text
$ docker tag myapp:v1 yourid/myapp:v1
$ docker push yourid/myapp:v1
The push refers to repository [docker.io/yourid/myapp]
...
v1: digest: sha256:... size: ...
```

第一行变了，目标才是你的仓库。`docker tag` 不复制层，只给同一个镜像再加一个名字（第 03 关）。更省一步：`docker build -t yourid/myapp:v1 .`

## 名字是路由，不是备注

镜像名的结构是：

```text
[HOST[:PORT]/]NAMESPACE/NAME[:TAG][@DIGEST]
```

| 你写的 | Docker 实际解析 | 结果 |
| --- | --- | --- |
| `myapp:v1` | `docker.io/library/myapp:v1` | 个人账号无权 push |
| `yourid/myapp:v1` | `docker.io/yourid/myapp:v1` | 你的 Docker Hub namespace |
| `ghcr.io/yourorg/myapp:v1` | host 是 `ghcr.io` | 要该组织的 package 写权限 |
| `10.0.0.12:5000/team/myapp:v1` | host 是 `10.0.0.12:5000` | 私有 registry 的 `team` 命名空间 |

判断规则只有两条：

- 第一段含 `.` 或 `:`，或者是 `localhost`，这一段就是 registry 地址；否则是 Docker Hub。
- 只有一段名字时，namespace 补 `library`。`nginx:1.27` 是 `docker.io/library/nginx:1.27` 的简写。

`docker push` 的第一行 `The push refers to repository [...]` 就是它真正要去的地方。推之前先看这一行；如果里面是 `library`，名字还没改对。

另一个 denied 来源是登录错了 registry：`docker login ghcr.io` 不会给 `docker.io` 写权限。名字里的 host、`docker login` 的 host、凭据的 host，三处要一致。

私有 registry 的名字必须带 host；端口不是 443 时还要带端口。只写 `team/myapp:v1` 会去 Docker Hub 的 `team/myapp`，不是你的内网 registry。

## tag 是别名，digest 才是身份证

| 对象 | 是什么 | 会不会变 | 什么时候用 |
| --- | --- | --- | --- |
| tag | 人可读的别名，如 `v1.2.3`、`latest`；指向一个 manifest digest，多架构时指向 index digest | 会。同一个 tag 可以重新 push 到新 digest | 日常开发、临时环境、给人看的版本号 |
| digest | manifest 内容的 `sha256:...` 哈希 | 不会。内容变，digest 就变 | 生产部署、回滚、审计 |
| manifest list / image index | 多架构索引，自己的 digest 里列出各平台 manifest 的 digest | 索引内容变就变；子 manifest 各自也有 digest | 一份名字同时支持 amd64 / arm64 |

日常开发用 tag，生产部署钉 digest。CI 里可以两者都留：`yourid/myapp:git-a1b2c3d` 给人看，部署清单写 `yourid/myapp@sha256:...` 给机器用。回滚时按 digest 回，不按「上周的 v1」猜。

`latest` 只是省略 tag 时的默认名，不是「最新版本」。推 `latest` 不会让它变成不可变版本；下次再推，它照样换 digest。

`docker image ls --digests` 只显示本地当前每个 tag 的一行 `DIGEST`。同一个 tag 被覆盖后，旧 digest 不会在这里留一行；多架构的 manifest list digest 也不等于本地 image ID。要看 registry 当前 tag 指向哪个 digest，用：

（标准形态，未实跑）

```text
$ docker buildx imagetools inspect registry.example.com:5000/team/myapp:v1
Name:      registry.example.com:5000/team/myapp:v1
MediaType: application/vnd.oci.image.index.v1+json
Digest:    sha256:...
Manifests:
  Name:      registry.example.com:5000/team/myapp:v1@sha256:...
  Platform:  linux/amd64
  ...
```

`Digest` 是 index 的 digest；下面的每个 `Name` 是平台子 manifest 的 digest。钉 index digest 可以跨平台；钉子 manifest digest 只锁一个平台，换架构会拿不到匹配的平台镜像（可能报 no matching manifest）。

历史 digest 不在 Docker 本地。要看一个 tag 过去指向过什么，去 registry 的 artifact 列表、审计日志，或 `HEAD /v2/<name>/manifests/<tag>` 读 `Docker-Content-Digest`。本地没有「同一 tag 的历史 digest 列表」这种命令。

## 推拉与认证：凭据到底存哪

```bash
docker login                              # Docker Hub
docker login ghcr.io -u yourid            # GHCR，密码用 PAT
docker login registry.example.com:5000 -u deploy
docker logout registry.example.com:5000
```

凭据存哪里，取决于客户端：

- Docker Desktop 把密码放进操作系统钥匙串（Windows Credential Manager、macOS Keychain、Linux 的 secret service）。`%USERPROFILE%\.docker\config.json` 里仍有 `auths` 的服务器键，但值不在文件里。
- 非 Desktop 客户端，且没有配置 `credsStore` / `credHelpers` 时，才把 `user:password` 做 base64 写进 `~/.docker/config.json`。Windows 是 `%USERPROFILE%\.docker\config.json`；Linux / macOS 是 `~/.docker/config.json`。
- base64 是编码，不是加密。拿到这个文件就等于拿到仓库权限；泄漏后要轮换令牌，不是删文件就完事。

CI 里用 `--password-stdin`：

```bash
echo "$TOKEN" | docker login registry.example.com:5000 -u deploy --password-stdin
```

`-p` 会把口令放进 shell 历史和进程列表，别用。令牌只给需要的 registry 和最短有效期；`docker logout` 只删本机这一份凭据。

完全离线的机器可以用 `docker save` / `docker load` 应急：

```bash
docker save myapp:v1 | gzip > myapp-v1.tar.gz
gunzip -c myapp-v1.tar.gz | docker load
```

它是全量 tar，不做增量，体积大；适合偶尔搬一次，不适合频繁发布。

## pull_policy 的默认值是 missing

Compose 规范里，`pull_policy` 不写时默认是 `missing`：

| 值 | 行为 | 用在 |
| --- | --- | --- |
| `missing`（默认） | 本地没有才拉；本地有同 tag 就直接用 | 日常开发、已经钉 digest 的部署 |
| `always` | 每次启动都去 registry 查一次 | 必须跟可变 tag 的场景 |
| `never` | 只用本地，没有就失败 | 离线 / 气隙环境 |
| `if_not_present` | `missing` 的兼容别名，下划线写法 | 旧配置迁移 |

`if-not-present`（连字符）不是合法值，Compose V2 校验可能直接报 invalid。写错这一处，问题不在镜像，在配置解析。

`missing` 的坑是：本地有 `v1`，registry 的 `v1` 已经指向新 digest，Compose 不会去查，继续跑旧镜像。生产用 digest；如果非要用可变 tag，就写 `pull_policy: always`，或者部署前显式 `docker compose pull`。别指望 `missing` 帮你更新。

规范还有一个细节：`latest` 即使策略是 `missing` 也会被拉。不要拿 `latest` 当稳定版本，否则你同时踩了可变 tag 和隐式拉取两个坑。

```yaml
services:
  api:
    image: registry.example.com:5000/team/myapp@sha256:...
    pull_policy: missing
```

钉了 digest 后，`missing` 就够：本地没有才拉，digest 不会变。用 tag 时，`missing` 只保证「本地没有」，不保证「和 registry 一致」。

## 私有 registry：先能推，再谈保留

测试期最省事的是官方 `registry:2`，但它默认是 HTTP，没有认证：

```bash
docker run -d --name registry -p 5000:5000 \
  -v registry-data:/var/lib/registry \
  registry:2
```

Docker 客户端默认要求 HTTPS，推 HTTP 会报 `http: server gave HTTP response to HTTPS client`。临时办法是在 `/etc/docker/daemon.json` 加：

```json
{
  "insecure-registries": ["10.0.0.12:5000"]
}
```

然后重启 Docker daemon；Docker Desktop 在 Settings → Docker Engine 改同一段 JSON。这个选项允许 HTTP、跳过证书校验，但不加认证；同网段任何人都能推拉。只许在隔离测试网段用，测试完删掉。

生产最小配置：让 registry 自己终止 TLS，并用 htpasswd 认证。`/etc/registry/config.yml`：

```yaml
version: 0.1
storage:
  filesystem:
    rootdirectory: /var/lib/registry
  delete:
    enabled: true
http:
  addr: :5000
  tls:
    certificate: /certs/fullchain.pem
    key: /certs/privkey.pem
auth:
  htpasswd:
    realm: Registry
    path: /auth/htpasswd
```

启动：

```bash
docker run -d --name registry --restart unless-stopped \
  -p 5000:5000 \
  -v registry-data:/var/lib/registry \
  -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
  -v /etc/registry/auth:/auth:ro \
  -v /etc/registry/certs:/certs:ro \
  registry:2
```

`/etc/registry/auth/htpasswd` 用 bcrypt 生成（`htpasswd` 由 apache2-utils 或 httpd-tools 提供）：

```bash
htpasswd -Bc /etc/registry/auth/htpasswd deploy
```

distribution 的 htpasswd 认证只认 bcrypt 格式；省掉 `-B` 生成别的格式，登录会全部失败。证书的 SAN 必须包含 `registry.example.com`，客户端要信任签发它的 CA；否则会看到 `x509: certificate signed by unknown authority`。自签证书不是加 `insecure-registries` 就完事，那只是把校验关掉。

推拉：

```bash
echo "$TOKEN" | docker login registry.example.com:5000 -u deploy --password-stdin
docker tag myapp:v1 registry.example.com:5000/team/myapp:v1
docker push registry.example.com:5000/team/myapp:v1
```

Harbor 要先建 project；裸 distribution 会在首次 push 时自动建 repository 路径。`registry:2` 没有 UI、RBAC、镜像扫描、保留策略；团队用 Harbor 更省事。自建最小版至少要有 TLS、认证、防火墙、数据卷备份和定时 GC。

## 清理：tag 不是层，GC 顺序不能反

「每个 tag 占一层」是错的。tag 只是指向 manifest 的指针；层按 digest 存一次，多个 tag、多个 manifest 共享同一份 blob。删 tag 不会立刻释放磁盘，GC 只删「没有任何 manifest 引用」的 blob。

正确的顺序是：

1. 先让旧 manifest 没有 tag 引用。Harbor 里删 tag；裸 distribution 按 digest 删 manifest。
2. 停写（只读模式或停 registry），避免 GC 和 push 并发。
3. 先 `--dry-run`，确认要删的 digest。
4. 再真正 GC。

裸 distribution 删 manifest 只能按 digest。配置里的 `storage.delete.enabled: true` 就是让这个 DELETE 生效；没开只能读/推，删不了。

```bash
curl -u deploy -X DELETE \
  https://registry.example.com:5000/v2/team/myapp/manifests/sha256:...
```

删掉这个 manifest，所有指向同一 digest 的 tag 会一起失效。先 GC 再删引用，GC 看不到可回收空间；反过来先开 `--delete-untagged`，或者在 push 还没写完时跑 GC，可能把仍被 index 引用、但暂时没有 tag 的子 manifest 当垃圾删掉，连带清掉这些层。

GC 命令（先停 registry，再用同一份配置和卷跑一次性容器）：

```bash
docker stop registry
docker run --rm \
  -v registry-data:/var/lib/registry \
  -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
  registry:2 garbage-collect --dry-run /etc/docker/registry/config.yml
```

去掉 `--dry-run` 才真删；只跑 dry-run 也要记得 `docker start registry`。要清「没有 tag、但 manifest 还在」的旧版本，先把 `--delete-untagged` 也放进 dry-run：

```bash
docker run --rm \
  -v registry-data:/var/lib/registry \
  -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
  registry:2 garbage-collect --dry-run --delete-untagged /etc/docker/registry/config.yml
```

确认输出里的 digest 都是要删的，再执行下面这条；它会真删，先备份卷：

```bash
docker run --rm \
  -v registry-data:/var/lib/registry \
  -v /etc/registry/config.yml:/etc/docker/registry/config.yml:ro \
  registry:2 garbage-collect --delete-untagged /etc/docker/registry/config.yml
docker start registry
```

这里有一个版本坑：distribution 2.x 的 `--delete-untagged` 不会沿着多架构 index 往下保留子 manifest，会把它们当 untagged 删掉；tag 还在，但按平台拉会 `manifest unknown`。这个行为在 3.x 才修。`registry:2` 就是 2.x（含 2.8.3），所以多架构镜像不要直接开这个开关；先 dry-run、备份卷，或者升级到 `registry:3` / 用 Harbor 的保留策略。不同版本行为不完全一致，我没在 2026-11 的 `registry:2` 上实跑确认，生产按你的版本再验一次。`registry:3` 的默认配置路径是 `/etc/distribution/config.yml`，上面的挂载路径要跟着换。

GC 期间 registry 不能接受写。能停机就停；不能停就在现有 config 的 `storage` 下加只读，别把这段当整份配置替换：

```yaml
storage:
  maintenance:
    readonly:
      enabled: true
```

改完重启 registry，GC 完再关掉只读并重启。GC 不会自动跑，要自己放 cron / systemd timer；跑之前备份 registry 数据卷。

本机清理是另一回事：

```bash
docker system df
docker image prune
docker image prune -a
```

- `docker image prune` 不带 `-a`：只删 dangling 镜像（`<none>:<none>`），不动有 tag 的镜像。
- `docker image prune -a`：删所有没有被容器引用的镜像，包括 `myapp:v1` 这种有 tag 的。下次运行要重新构建或重新拉，代价不一样，命令要分开写，别在生产构建机上随手 `-a`。
- `docker builder prune` 清的是构建缓存，不是镜像；`docker system prune -a` 还会动停止的容器和网络，不要拿它代替上面两条。

`docker system df` 不会给 registry 的 blob/manifest 明细。远程 registry 用 `du -sh /var/lib/registry` 或对象存储控制台看；本机镜像和 registry 存储是两本账。

## 仓库里真实的镜像命名

`scripts/release.ps1` 不发镜像。它的流程写在第 4–5 行：本地增量打包 → 一个部署包 → 一次上传 → 远端脚本换文件、重启、等健康检查。第 41 行的产物是固定的 `paideia-app-0.0.1-SNAPSHOT.jar`；第 394–395 行从 HEAD 取前 7 位当 `$version`；第 397–404 行把它写进 Prometheus 的 `paideia_build_info`；第 593–596 行上传 bundle 并在远端执行 `deploy.sh`；第 621–627 行写 `.last-release-commit` 和 jar 指纹，供下一次跳过打包。

所以它没有「镜像 tag」可谈：全文没有 `docker build` / `docker push`，也没有 registry 地址。`$version` 不是镜像 digest，不能拿来 `docker pull`，它只是监控标签。真正部署的是服务器上的 `app.jar` 和 `web/`，靠 `exploded.manifest` 做增量。

风险有三个：

- 第 41 行的 jar 名固定，效果类似一个永远被覆盖的 `latest`。服务器上同一路径的文件换过，外部没有不可变引用。
- 第 394 行取 HEAD，但打包读的是工作区。工作区有未提交改动时，监控里的 `version` 仍是 HEAD，和实际跑的内容对不上。
- 回滚靠 `app.jar.prev`，不是 registry 里可复现的 digest。换一台机器，无法只凭版本号拉到同一份产物。

如果要把发布变成可审计的，最小改法是：构建镜像 → tag 用 git commit SHA（不要只用 `latest`）→ push → 部署清单写 digest。semver tag 可以留给人看，机器按 digest 拉。这样「上周二生产跑的是哪个镜像」能从 digest 回答，而不是从服务器目录猜。

仓库里已有的镜像命名在沙箱脚本：

- `backend/paideia-sandbox/images/mysql/build.sh:9` 默认 `paideia-sandbox-mysql:8.0`，`:12` 执行 `docker build -t "$TAG" .`
- `backend/paideia-sandbox/images/shell/build.sh:11` 默认 `paideia-sandbox-shell:bookworm`，`:14` 同样构建
- `backend/paideia-app/src/main/resources/application.yml:224` 的 `sql-image` 用 digest 钉住；`:207–208` 的 java/python 跟上游 tag，注释自己说 `docker pull` 会漂移；`:240` 的 shell 镜像默认留空
- 两个 Dockerfile 的 `FROM` 也按 digest 钉住：`mysql/Dockerfile:12`、`shell/Dockerfile:13`

但 `build.sh` 打印的 digest 不能直接信。`mysql/build.sh:16–18` 先取 `RepoDigests`，兜底模板是 `sha256:{{.Id}}`；`shell/build.sh:18–22` 的兜底是 `{{.Id}}`。本地 build 没有 RepoDigest 时，输出可能只是 `<no value>`，也可能拿到 `.Id`；`.Id` 是 image config digest，不是 manifest digest，mysql 的模板还会把 `sha256:` 拼两次。先 push，再用 `docker buildx imagetools inspect` 或 registry API 取 manifest digest，回填才有效。这是仓库里现成的坑。

仓库根目录没有 `.github/`，也没有 `.gitlab-ci.yml` / `Jenkinsfile`。没有 CI 镜像发布配置可引用；镜像命名只停在沙箱脚本层，没有统一的 tag 规范。

## 动手

1. 用 `docker build -t yourid/myapp:v1 .` 构建，确认 `docker image inspect yourid/myapp:v1 --format '{{.RepoTags}}'` 里是目标全名。
2. `docker push yourid/myapp:v1`，把输出第一行 `The push refers to repository [...]` 抄下来；如果还是 `library`，名字没改对。
3. push 成功后 `docker buildx imagetools inspect yourid/myapp:v1`，把 `Digest` 填进 Compose 的 `image: ...@sha256:...`，再 `docker compose config` 看解析结果。

```code-challenge
{"id":"docker-08-push","kind":"fill","title":"把本地镜像推到私有仓库","language":"bash",
 "template":"# 1. 打上仓库全名\ndocker {{0}} myapp:v1 registry.example.com:5000/team/myapp:v1\n# 2. 登录（令牌从 stdin 读）\necho $TOKEN | docker login registry.example.com:5000 -u deploy {{1}}\n# 3. 推\ndocker push registry.example.com:5000/team/myapp:v1",
 "options":[["tag","build","save"],["--password-stdin","-p","--token"]],"answers":[0,0],
 "explain":"推送目标由镜像名决定，所以先 tag 成 `registry/namespace/name:tag`；`--password-stdin` 避免口令出现在进程列表与 shell 历史里。"}
```

## 检查一下

```quiz
{"id":"docker-08-quiz","title":"仓库三问","questions":[
 {"question":"`docker push myapp:v1` 返回 denied，登录是好的。第一步应该做什么？","options":["重新 docker login","docker tag myapp:v1 yourid/myapp:v1 后再推","把 daemon.json 改成 insecure-registries"],"answer":1,"explain":"只有一段名字的 myapp 会被补成 docker.io/library/myapp；library 是官方命名空间，个人账号无权推送。先 tag 到自己的 namespace。"},
 {"question":"Compose 里不写 pull_policy，默认行为是什么？","options":["每次都去 registry 查","永远不拉","本地没有才拉，等价 missing"],"answer":2,"explain":"Compose 规范默认是 missing；if_not_present 是它的下划线别名。写成 if-not-present 不是合法值，可能直接校验失败。"},
 {"question":"私有 registry 要回收旧版本占用的磁盘，正确顺序是什么？","options":["先删 manifest/tag 引用，再跑 GC","先跑 GC，再删 tag","每个 tag 手动删一层"],"answer":0,"explain":"GC 只清没有被任何 manifest 引用的 blob；先删引用才会释放。GC 期间要停写，带 --delete-untagged 前先 dry-run，确认多架构子 manifest 不会被误删。"}
]}
```

## 还差的

- 本机没有 Docker，所有 docker/registry 回显都没有实跑；`--delete-untagged` 在多架构镜像上的行为按 registry 版本不同，生产先 `--dry-run` 并备份数据卷。
- 仓库没有 CI 镜像发布配置，`release.ps1` 仍是 jar + 文件增量部署。要真正做到「构建一次、按 digest 发布」，还缺一条镜像构建/推送流水线。
- 沙箱 `build.sh` 的 digest 回填没有闭环：本地构建拿不到 RepoDigest，`.Id` 又不是 manifest digest；`application.yml` 里的 digest 由谁 push、何时校验，仓库里没有自动检查。
- 私有 registry 只写到单机 TLS + htpasswd；高可用、对象存储后端、备份恢复、审计、配额、镜像扫描都没展开。
- 多架构只讲了怎么识别 manifest list，没给 `buildx build --platform linux/amd64,linux/arm64 --push` 的完整流水线；团队要同时支持两种架构，这一块还得补。

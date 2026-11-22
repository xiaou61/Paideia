---
artifact: design
work_id: WORK-007
work: 对象存储接入
status: approved
workflow: full
depends_on: [proposal]
related_to: [WORK-004]
created: 2026-10-15
updated: 2026-10-15
signed_by: xiaou61 (CTO)
signed_at: 2026-10-15
approved_by: xiaou61
approved_at: 2026-10-15 18:01:34 +0800
approver_role: CTO
---

# 设计 · 对象存储接入

## 1. 装配：一处配置二选一

```yaml
paideia:
  knowledge:
    # local = 本地目录（现状，默认）；s3 = 自建 S3 兼容对象存储。
    # 换实现 = 改这一行 + 重启（用户裁决 5）。
    storage: ${PAIDEIA_KNOWLEDGE_STORAGE:local}
    upload-dir: ${PAIDEIA_KNOWLEDGE_UPLOAD_DIR:}     # 仅 local 用
    s3:
      endpoint: ${PAIDEIA_KNOWLEDGE_S3_ENDPOINT:}    # 例 http://127.0.0.1:9000
      bucket: ${PAIDEIA_KNOWLEDGE_S3_BUCKET:paideia-attachments}
      region: ${PAIDEIA_KNOWLEDGE_S3_REGION:us-east-1}
      access-key: ${PAIDEIA_KNOWLEDGE_S3_ACCESS_KEY:}
      secret-key: ${PAIDEIA_KNOWLEDGE_S3_SECRET_KEY:}
      # 自建 S3 一律 path-style（虚拟主机风格需要 DNS 通配）
      path-style: ${PAIDEIA_KNOWLEDGE_S3_PATH_STYLE:true}
      # 预签名有效期：远端拿到 URL 的**最晚可用时刻**。
      presign-ttl: ${PAIDEIA_KNOWLEDGE_S3_PRESIGN_TTL:7d}
      # 302 本身的缓存时长：让浏览器在一小时内重复用同一个签名 URL，而不是每次刷新都回后端换签名。
      # **必须小于 presign-ttl**，否则会跟着一个已过期的签名跑。
      redirect-cache: ${PAIDEIA_KNOWLEDGE_S3_REDIRECT_CACHE:1h}
```

`FileStorageConfiguration` 保持"只做装配"的定位，改成两个方法按属性二选一：

```java
@Bean @ConditionalOnProperty(name = "paideia.knowledge.storage", havingValue = "local", matchIfMissing = true)
FileStorage localFileStorage(KnowledgeStorageProperties properties) { ... 现状不变 ... }

@Bean @ConditionalOnProperty(name = "paideia.knowledge.storage", havingValue = "s3")
FileStorage s3FileStorage(KnowledgeStorageProperties properties, S3Client client) { ... }
```

`S3Client` 也由配置提供（`endpointOverride` + 静态凭据 + `pathStyleAccessEnabled`），凭据只从环境变量来——与数据库口令同一纪律：**仓库里只有占位**。

## 2. 端口：加一个 default 方法（本项唯一的接口变化）

```java
/** 能直读就给一个有签名的 URL；不能就给空（调用方自行流字节）。 */
default Optional<String> presignedGetUrl(String id, Duration ttl) {
    return Optional.empty();
}
```

- `LocalFileStorage` **不覆写** → 空 → 现状行为不变。
- `S3FileStorage` 覆写 → `S3Presigner.presignGetObject(...)`。
- 为什么 default 而不是抽象方法：端口对实现的要求仍是三个必答项，"能不能直读"是**能力**不是**义务**。

## 3. `S3FileStorage` 与本地实现的逐条对齐

| 方法 | 本地实现 | S3 实现 | 对齐要点 |
| --- | --- | --- | --- |
| `put(content, contentType)` | 写文件，返回 UUID | `PutObject(key=UUID, body, contentType)` | **key 就是 id**；`contentType` 成为对象元数据 |
| `read(id)` | 读文件，不存在 → 空 | `GetObject`，`NoSuchKey` → 空 | "文件没了"仍是**可预期状态**，不抛异常 |
| `delete(id)` | 删文件，不存在 → false | `HeadObject` 判存在 → 存在则 `DeleteObject` → true；否则 false | **S3 的 delete 幂等且不报不存在，必须 head 一次**才能给出与本地一致的布尔语义 |
| id 形状 | UUID（`UUID.fromString` 校验） | 同一校验（复用同一个工具方法，不抄两份） | **读端点免鉴权，id 不可枚举是安全前提** |

**对象键布局**：扁平，`key = UUID`。`ponytail:` 不做两级前缀分片——附件量级（当前 0 条）远够不到单前缀的性能天花板；真要分片时改 `put` 一处即可。

## 4. 读路径：302 到预签名 URL

```
GET /api/v1/knowledge/files/{id}
  ├─ 实现给出 presignedGetUrl？ ── 是 → 302 Location=<签名 URL>，Cache-Control: private, max-age=<redirect-cache>
  └─ 否（本地实现）──────────────→ 200 + 字节（现状：嗅探出的 Content-Type、nosniff、一年缓存）
```

**为什么是 302 而不是"前端另调接口换 URL"**：正文 markdown 里存的就是 `/api/v1/knowledge/files/{uuid}`，302 让浏览器自己跟过去，**正文、前端、`resolveMediaUrl` 全部零改动**，安全规则也一个字不改（仍只放行 GET）。

### ⚠️ 这条改动会吃掉现在那层"一年浏览器缓存"（必须写清）

现在的读端点显式带 `Cache-Control: max-age=365d, public, immutable`（代码注释里写明这是"端点公开而非前端转 blob"换来的好处：内容按 id 不变，所以能长缓存）。

改成直读后，**每次重新请求 `/files/{id}` 都会拿到一个带新签名的新 URL**，而浏览器缓存按**完整 URL** 索引 → 缓存命中率归零。于是：

- 首次读取：省掉了后端带宽（真收益）。
- 重复读取（刷新、再次打开同一页）：本该命中缓存，现在会**再去对象存储取一次**；对象存储是本机的、很快，所以代价主要是"白白多一次请求"，不是"慢"。
- 折中参数（本设计采用）：**签名 TTL 7 天 + 302 只缓存 1 小时**。同一小时内重复加载页面，浏览器直接用缓存里的 302（签名还有效），既不回后端也不回对象存储；超过 1 小时才重新换签名。

若要拿回长缓存，唯一办法是回到"后端代理 + 长缓存头"（即裁决 3 的另一条路）。**这条取舍建议在验收时用真实流量看一眼**再定参数。

## 5. 安全

- **对象存储对公网开放**是本设计的直接后果（浏览器要能直连）。加固：**控制台关闭**、桶私有（无匿名策略，读取一律靠签名）、凭据 24/40 位随机十六进制、只存 `/etc/rustfs/rustfs.env`(600)、签名短时效。
- 读端点仍**只放行 GET**，仍靠"id 不可枚举"；签名 URL 泄漏的影响面被 TTL 限住。
- 上传仍走 `/api/v1/knowledge/admin/files`（需管理员）；写路径的鉴权不变。
- **不写**任何凭据进仓库：`application.yml` 里只有 `${...}` 占位；线上值写在服务器上的 `/opt/paideia-app/application.yml`（root-only）。

## 6. 启动与失败语义

- **应用启动不依赖对象存储可达**（不做启动期探活）：附件是"用到才读"，启动时连不上不该让整站起不来。
- 读取失败（连不上/权限错）→ 500 并有明确日志；对象不存在 → 404（现状语义不变）。
- RustFS 侧 `Restart=always`；应用侧不做重试（一次请求一次尝试，失败让用户重试，避免掩盖配置错误）。

## 7. 部署与回滚（有损，必须写明）

1. 服务器：RustFS systemd ✓（**已就绪**，当前回环）→ 切公网监听 + 关控制台。
2. 应用：`/opt/paideia-app/application.yml` 加 `paideia.knowledge.storage: s3` 与四个 S3 值（root-only），`systemctl restart paideia`。
3. 首跑验证：管理端上传一张图 → 学习者端条目里看到图（这条同时验了上传、302、签名直读三段）。
4. **回滚**：改回 `local` + 重启即可，但 **S3 期间上传的附件在切回本地后会 404**（字节在对象存储里，本地目录没有）。当前线上附件为 **0 条**，所以本次切换的实际损失面为零；上线后再回滚才有损——这条要写进部署说明。

## 8. 测试

| 层次 | 内容 | 前置 |
| --- | --- | --- |
| 单元 | id 校验复用同一实现；本地实现的 `presignedGetUrl` 返回空（能力缺省的证明） | 无 |
| 集成（本地） | 既有附件用例（上传/读/删/类型嗅探）**不改一行**，在 `storage=local` 下全过 | 现有隧道 |
| 集成（S3） | 同一批用例在 `storage=s3` 下全过 + `put→head→get→delete` 往返 + 302 分支 | 需要 S3 端点：**由环境变量 `PAIDEIA_KNOWLEDGE_S3_ENDPOINT` 给**，未配置则整类跳过（与数据库口令同一做法）；本机经由 SSH 隧道把服务器的 9000 映射过来 |
| 端到端 | 线上（storage=s3）：管理端上传 → 学习者端渲染出图 | 部署后 |

**"换得动"的证明就是上表第二、三行**：同一批用例、两种实现、都不改测试代码。

## 9. 未决/待观察

- 预签名参数（TTL 7d / 302 缓存 1h）先按上面的折中给，验收时看真实流量再调。
- `S3FileStorage` 的失败分支（网络抖动、桶不存在、权限错）在设计里只定了"不吞异常"，具体日志文案在实现时定。

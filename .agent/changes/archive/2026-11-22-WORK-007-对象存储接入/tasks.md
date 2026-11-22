---
artifact: tasks
work_id: WORK-007
work: 对象存储接入
status: approved
workflow: full
depends_on: [design]
related_to: [WORK-004]
created: 2026-10-15
updated: 2026-10-15
signed_by: xiaou61 (CTO)
signed_at: 2026-10-15
approved_by: xiaou61
approved_at: 2026-10-15 18:01:35 +0800
approver_role: CTO
---

# 任务 · 对象存储接入

**排序原则**：先把"装配可切换"落地并让**既有用例在 local 下全过**（证明重构没破坏现状），再加 S3 实现、再让**同一批用例在 s3 下全过**（这就是"换得动"的证明），最后才动线上。服务器侧的公网暴露放在**代码就绪之后**——先开暴露面、再上功能是反的。

| # | 任务 | 依赖 | 验证 |
| --- | --- | --- | --- |
| T-01 | 配置项落地：`paideia.knowledge.storage` + `s3.*`（全部走环境变量占位），`KnowledgeStorageProperties` 扩出 s3 段 | — | `mvn -pl paideia-knowledge -am test`；配置绑定有单测 |
| T-02 | 端口加 `default Optional<String> presignedGetUrl(id, ttl)`；`LocalFileStorage` **不覆写** | T-01 | 单测：本地实现该方法是空（"能力缺省"的证明）；既有用例全过 |
| T-03 | `FileStorageConfiguration` 改成 `@ConditionalOnProperty` 二选一 + 提供 `S3Client` bean | T-01 | `mvn verify`（默认 local 时上下文照旧起来） |
| T-04 | `S3FileStorage`：`put/read/delete` 三方法按设计逐条对齐（含 head-then-delete 与 NoSuchKey→空） | T-03 | 集成测试（在 S3 端点可用时）：往返 + 不存在语义 |
| T-05 | `S3FileStorage.presignedGetUrl`：`S3Presigner` + path-style + TTL | T-04 | 单测断言 URL 形状（端点/桶/key/签名参数/TTL） |
| T-06 | 控制器 302 分支 + 两处缓存头（302 用 `redirect-cache`、本地路径保持现状的一年 immutable） | T-02, T-05 | 集成测试：s3 下 GET 返回 302 且 Location 带签名；local 下仍 200 字节 |
| T-07 | 集成测试补"两种实现同一批用例"：抽出共用断言类，`storage=local` 与 `storage=s3` 各跑一遍；S3 那组以 `PAIDEIA_KNOWLEDGE_S3_ENDPOINT` 为开关（未配置整类跳过） | T-04, T-06 | **两条都绿**——这是本项的验收核心 |
| T-08 | 服务器正式化：RustFS 切公网监听 + **关控制台** + 桶私有化核对；（服务本身已 systemd 化） | T-07 | `ss -lntp` 看到对公网监听、`systemctl is-enabled/active`；换机器直连 9000 探一次 |
| T-09 | 部署：`/opt/paideia-app/application.yml` 加 `storage: s3` 与四个值（root-only），重启应用 | T-08 | 启动日志打印生效的后端与桶名；**不打印凭据** |
| T-10 | 端到端验收：管理端上传图片 → 学习者端条目里看到（上传、302、签名直读三段一次验完） | T-09 | 线上截图 + 浏览器 network 里能看到对象存储的直连请求 |
| T-11 | 文档：部署形态、切换与**有损回滚**步骤、预签名参数的含义，写进 `.agent/notes/dev-workflow.md` 与 `always.md` 的 D-15 段 | T-10 | 文档与实际命令一致（照文档跑一遍） |

## 验收要点（对应 AC）

- **AC-1 可换性**：同一批附件用例在 `local` 与 `s3` 两种实现下都不改测试代码地通过（T-07）。
- **AC-2 契约不变**：正文里的 `/api/v1/knowledge/files/{id}` 形状不变；该端点仍只放行 GET（T-06）。
- **AC-3 不可枚举**：id 仍是 UUID，实现仍校验（T-02/T-04）。
- **AC-4 元数据不搬家**：`knowledge_files` 表结构与写入路径不变（T-04）。
- **AC-5 生产切换**：线上附件可上传、可在学习者端渲染（T-10）。
- **AC-6 回滚路径明确**：改回 `local` + 重启可回到现状，且文档写明"有损"（T-11）。
- **AC-7 暴露面受控**：控制台关闭、桶无匿名策略、凭据只在 600 文件里（T-08/T-09）。

## 风险与默认处置

- **预签名吃掉浏览器长缓存**（设计 §4）：先按 TTL 7d + 302 缓存 1h 落地，验收时看流量再定；若要长缓存就退回代理方案。
- **RustFS GA 仅约四周**：桶私有 + 数据目录可备份；真出问题时的退路是切回 `local`（有损）。
- **本机到 GitHub 不通**：服务器侧走 gh-proxy 镜像下载（已验证）；
- **测试依赖两处远端**：MySQL(3307) + S3(9000) 都要隧道；两条隧道都断时相关用例**跳过而不是失败**（与现有做法一致）。

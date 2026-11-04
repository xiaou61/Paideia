---
artifact: test-report
work_id: WORK-068
work: 从零开始体验优化与内容扩充
status: partial
evidence: required
created: 2026-11-04
updated: 2026-11-04
---

# 验证报告

| 检查项 | 命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 前端类型检查（全包） | `cd frontend && pnpm -r typecheck` | passed | 9 个包全部 Done，0 错 |
| 学习页 e2e | `scripts/test-db.sh pnpm --filter @paideia/app test:e2e e2e/learn.spec.ts`（端口 18080/15173，`E2E_CHANNEL=chrome`） | passed | 9 通过 / 1 跳过（跳过项要推荐卡有 pick，测试库没有；属既有条件跳过）。覆盖折叠、岗位总览、继续卡前提、章节与只看未通关 |
| 知识库 e2e（条目页改动） | 同上 `e2e/knowledge.spec.ts` | passed | 10/10：分类→条目→正文、目录、上下篇、搜索、自评、浏览计数、AI 出题 |
| 练习块盘点 | `node scripts/check-practice-coverage.mjs` | passed | 34 条线 / 313 关 / 455 个练习块 / **纯阅读 0**；quiz JSON 全部可解析（脚本内校验，失败退出码 1） |
| 内容格式 | 同一脚本的 quiz 解析 | passed | 新增 13 篇补充 + 12 关前线共 25 个 quiz 块，无 JSON 错误 |
| 线上内容导入 | `import-course.mjs --dir content/reading/frontend-from-zero` | passed | created 13；五个书籍/开源线 updated 13（4+3+2+2+2） |
| 线上 UI（ego） | 前端岗位 → 前端线 → 第 3 关 | passed | 总览「35 关」、核心「1 条/12 关」、选读折叠、12 关地图、3 个章节 chip、条目页「第 3/12 关·未通关」；截图 `F:/caches/tmp/ego-w068-1.png`、`-2.png` |
| 三条理论线导入 | `import-course.mjs --dir ...` | passed | data-analysis created 12、web-security 9、testing-from-zero 9；岗位映射部署 188.8 秒、health 200 |
| 数据分析线线上渲染 | ego：数据岗位 → 数据分析从零开始 → 第 6 关 | passed | 总览「52 关」、11 关地图/3 章节、第 6/11 关、练习册 3 题可打开；截图 `F:/caches/tmp/ego-w068b-1.png` |
| Python 实战真跑 | ego：python-scripting 第 1 关点运行 | passed | 沙箱执行 5 个用例：前两例 ok、坏行用例按预期失败（starter 未过滤），证明判题链路真实；截图 `F:/caches/tmp/ego-w068c-2.png` |
| Linux 实战真判 | ego：linux-troubleshooting 第 1 关点判定 | passed | 服务端容器 1636 ms 返回「app/secrets.env 现在是 644，期望 600」；截图 `F:/caches/tmp/ego-w068d-1.png` |
| 判题容器能力探测 | `POST /api/v1/sandbox/shell/run`，judge 打印 docker 能力 | passed | `command -v docker` → `no-docker`；`docker info` → `daemon-no`（644 ms）；据此 Docker 线只做结构判定 |
| Docker 实战真判 | ego：docker-deploy 第 1 关点运行 | passed | 服务端 997 ms 返回「运行阶段需要 COPY --from=build ... 来拿产物」；截图 `F:/caches/tmp/ego-w068e-1.png` |
| Docker 判定自检 | 本地用正确解/种子坏解各跑四道 judge | passed | 4/4：正确解 exit 0，坏解 exit 1（脚本 `check-docker.mjs`，未入库） |

## 未覆盖与已知限制

- **岗位总览行在测试库没有数据**：e2e 种子轨道全在选读里，核心+进阶为 0，用例按"没有可数关就不渲染"双向断言；真实数据的数字要线上核验。
- **章节跳转没有 e2e**：断言滚动位置在 headless 下不稳定，只做了结构断言（chip 存在）与手工检查。
- **entry-place 的"第 N/M 关"** 由 `trackMap` 数据推导，测试库能覆盖（learn 用例链），但"直接开链接不显示"这一分支没有单独用例。
- 其余内容线（TASK-008..011）尚未开始，因此本项整体仍是 in_progress。

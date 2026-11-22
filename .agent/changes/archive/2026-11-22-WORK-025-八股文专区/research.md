---
artifact: research
work_id: WORK-025
work: 八股文专区
status: active
created: 2026-10-20
updated: 2026-10-20
---

# 八股文专区 · 牛客可抓性实测

本文件记录 2026-10-20 对牛客网爬取通路的全部实测过程与结果。`requirements.md` 只保留结论，细节在这里。

## 测试条件

- 本机：Windows / Git Bash，`curl` + Playwright（复用 `frontend` 已装的 `playwright@1.64.0`，headless Chromium）
- 生产：`codenest-online`
- 全程**匿名**，不带 Cookie、不带 token、不登录

## 一、通路逐条结论

| 通路 | 结果 | 细节 |
| --- | --- | --- |
| **面经列表 API** ✅ 核心通路 | **可用** | `POST https://gw-c.nowcoder.com/api/sparta/job-experience/experience/job/list`，body `{"page":1,"pageSize":20,"jobId":639}`。返回 `total:2000`、`totalPage:100` |
| **列表直接带正文** ✅ | **可用** | 每条记录 `momentData.content` 就是**完整正文**（样例 1136 字），`momentData.createdAt` 是毫秒时间戳，`momentData.title` 是标题。**不需要再逐条请求详情页** |
| 列表字段 | 结构完整 | `contentId`、`contentType`（74=面经帖、250=其它）、`interviewExp.message`（如「查看19道真题和解析」）、`userBrief`、`voteData`、`momentData` |
| `pageSize` 上限 | **20** | 20 正常；50/100/200 一律 `{"success":false,"code":1,"msg":"系统未知错误"}` |
| 限流 | **未观察到** | 连续 6 次请求全部 200 且业务成功，间隔 0.3s |
| 登录态 | **不需要** | 全程无 Cookie 无 token，纯匿名可读 |
| **日增量** | **每天 3–7 条** | Java 面经合集（`jobId=639`）：page1 覆盖 10-19~10-17、page2 覆盖 10-17~10-15、page3 覆盖 10-15~10-13，约 6-7 条/天；剔除无时间戳的 `contentType=250` 后，带时间的约 3-5 条/天 |
| 内容技术密度 | **混合** | 同日 14 条里，技术词命中 ≥8 的只有 5 条（拼多多服务端、国轩高科JAVA、得物AI面八股等），其余是「终于找到实习了！」「如何提高工程能力」这类纯信息帖 |
| **正文页 SEO 版可抓** | **可用** | `/feed/main/detail/<uuid>?urlSource=sitemap` 服务端完整渲染，样例 5599 字真实正文。可作为兜底或补全 |
| 站点地图枚举 | **可用** | `sitemap.txt` → `sitemap1.txt`（13737 行，含 12732 条 `/feed/main/detail`）+ `sitemap2.txt`（605 条） |
| `sitemap/question/questionurl*.txt` | 可用但用途有限 | 6 个文件 × 约 4.5 万条 URL，指向 `/practice`（算法题，非八股）与 `/questionTerminal` |
| **合集内题目枚举** ❌ | **不通** | `get-paper-list` 能列出 30 个「牛客面经八股」合集（Java基础 23 题、JVM 10 题、Java多线程 27 题、Redis 16 题、Spring 15 题…），但合集**题目清单接口未找到**：`/exam/paper/<id>` 是 404 骨架、`/exam/test/<id>/detail` 调 `test/summary?paperId=0` 返回空、卡片点击不跳转、JS 包里没有 API 字符串 |
| 题库首页性质 | 不是八股文 | `/exam/intelligent` 是「专项练习」，全是算法题（数组 190 题、链表 193 题…），不是八股 |
| **生产服务器可达** ✅ | **已验证** | 在 `codenest-online` 上直接 POST 该 API：`200`，耗时 `0.375s` |
| 单题页正文即标题 | 已确认 | `/questionTerminal/<uuid>` 服务端渲染标题、题型、收藏/浏览数，`answer-brief` 仅是摘要（样例一个词 `redo`） |
| `robots.txt` | 未禁止相关路径 | 只禁 `/search`、`/nccommon`、`/ab/ab-test-flow`；`/feed`、`/discuss`、`/questionTerminal`、`/practice` 均未禁 |

## 二、走过的弯路（记录以免重走）

1. **静态解析 HTML**：`/discuss/experience?tagId=639` 的内联 `window.__INITIAL_STATE__` 里有 `ssrInitList` 20 条，但只有 `contentId`/标题，**没有正文**。
2. **猜详情页**：`/discuss/2928416` 服务端只回骨架，`contentData` 是 `{"showMessage":{"message":"内容不存在!"}}`。
3. **猜 API**：`/ncup/api/discuss/detail?discussId=` 返回首页 HTML；`gw-c.nowcoder.com/api/sparta/discuss/detail` 返回 404。**猜路径这条路走了两轮才放弃**。
4. **静态分析 JS 包**：下载 `common.js`（1MB）与 `main.entry.js`（472KB），grep `api`、`ncup`、`gw-c`、`axios`、`baseURL` —— **一条都没命中**，被我 grep 到的 `(/api/...)` 也是 0 条。
5. **试合集 URL 变体**：`/exam/paper/<id>`、`/exam/paper/<id>/detail`、`/exam/test/<id>/detail`、`/exam/intelligent/<id>` —— 全部 404 骨架或空数据。
6. **点卡片**：被登录弹窗 `login-dialog` 挡住；`force:true` 点下去也不跳转（Vue 事件绑定，无 href）。
7. ✅ **Playwright 抓包**：这一步才拿到真 API。打开 `/exam/interview` 监听所有 XHR，直接看到 `company-question/get-paper-list`；再打开 `/discuss/experience?tagId=639`（重定向到 `/experience/639`），拿到 `job-experience/experience/job/list`。

**教训**：对 SPA 站点，静态分析与猜路径的命中率极低；直接开浏览器抓网络请求是最快的一步。前六步加起来的时间远超第七步。

## 三、面试帖样例（用于判断内容形态）

`contentId=2928961`「终于找到实习了！」，`createdAt` 对应 2026-10-19 12:38，正文 1136 字，开头是：

> 今日战绩：1面 1offer / 一家小厂的后端实习 / 面经： / 1、关于学校的一些有的没的 / 2、现在来实习，学业是怎么安排的 / … / 7、习惯用java几；各版本之间的差异和特性有了解过吗；虚拟机有了解吗 / …

**形态判断**：题目是编号列表，但混着大量非技术问题（学校安排、职业规划）。AI 抽取时必须能只留下技术题，这正是 REQ-008 的由来。

## 四、未测的事

- **AI 从正文抽题的准确率**：未测。需要真跑一批帖子才知道，纯 HTTP 测试做不到。这是实施阶段第一个要验的事。
- **长期稳定性**：接口是内部接口（`/api/sparta/`），随时可能改路径、加签名或加风控。这是长期风险，端口化设计就是为了吸收它。
- **`jobId=639` 之外的合集**：只测了 Java 工程师这一个，其他语种/岗位的 `jobId` 没逐个确认（本期不需要）。

---
artifact: test-run
run_id: 2026-10-26T1749
layer: walkthrough
target: local
status: failed
started: 2026-10-26 17:49:46
---

# 全接口自查 2026-10-26 17:49:46

合计 **167** 步：通过 164，失败 3；覆盖端点 **170** 条。

| # | 检查项 | 方法 | 路径 | 状态码 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 图形验证码 | POST | `/api/v1/auth/captcha` | 200 | 通过 |
| 2 | 图形验证码返回 id | - | `-` | 0 | 通过 |
| 3 | 发邮箱验证码 | POST | `/api/v1/auth/email-code` | 200 | 通过 |
| 4 | 注册 | POST | `/api/v1/auth/register` | 200 | 通过 |
| 5 | 登录 | POST | `/api/v1/auth/login` | 200 | 通过 |
| 6 | 刷新令牌 | POST | `/api/v1/auth/refresh` | 200 | 通过 |
| 7 | 当前身份 | GET | `/api/v1/me` | 200 | 通过 |
| 8 | 第三方平台列表 | GET | `/api/v1/auth/oauth/providers` | 200 | 通过 |
| 9 | 我的登录方式 | GET | `/api/v1/account/oauth/identities` | 200 | 通过 |
| 10 | 读画像 | GET | `/api/v1/account/profile` | 200 | 通过 |
| 11 | 写画像 | PUT | `/api/v1/account/profile` | 200 | 通过 |
| 12 | 画像可读可写 | - | `-` | 0 | 通过 |
| 13 | 建分类 | POST | `/api/v1/knowledge/admin/categories` | 200 | 通过 |
| 14 | 管理端分类列表 | GET | `/api/v1/knowledge/admin/categories` | 200 | 通过 |
| 15 | 改分类 | PUT | `/api/v1/knowledge/admin/categories/901` | 200 | 通过 |
| 16 | 建条目 | POST | `/api/v1/knowledge/admin/entries` | 200 | 通过 |
| 17 | 再建一条（供关系用） | POST | `/api/v1/knowledge/admin/entries` | 200 | 通过 |
| 18 | 管理端条目列表 | GET | `/api/v1/knowledge/admin/entries?size=5` | 200 | 通过 |
| 19 | 管理端条目详情 | GET | `/api/v1/knowledge/admin/entries/11` | 200 | 通过 |
| 20 | 改条目 | PUT | `/api/v1/knowledge/admin/entries/11` | 200 | 通过 |
| 21 | 建关系 | POST | `/api/v1/knowledge/admin/relations` | 200 | 通过 |
| 22 | 删关系 | DELETE | `/api/v1/knowledge/admin/relations/3` | 200 | 通过 |
| 23 | 建概念 | POST | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 24 | 改概念 | PUT | `/api/v1/knowledge/admin/concepts/1` | 200 | 通过 |
| 25 | 概念列表 | GET | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 26 | 挂条目 | POST | `/api/v1/knowledge/admin/concepts/1/entries` | 200 | 通过 |
| 27 | 解关联 | DELETE | `/api/v1/knowledge/admin/concepts/1/entries/11` | 200 | 通过 |
| 28 | 建概念二 | POST | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 29 | 建概念关系 | POST | `/api/v1/knowledge/admin/concept-edges` | 200 | 通过 |
| 30 | 删概念关系 | DELETE | `/api/v1/knowledge/admin/concept-edges/1` | 200 | 通过 |
| 31 | 删概念 | DELETE | `/api/v1/knowledge/admin/concepts/2` | 200 | 通过 |
| 32 | 上传附件 | POST | `/api/v1/knowledge/admin/files` | 200 | 通过 |
| 33 | 分类树 | GET | `/api/v1/knowledge/categories` | 200 | 通过 |
| 34 | 条目列表 | GET | `/api/v1/knowledge/entries?size=5` | 200 | 通过 |
| 35 | 条目详情 | GET | `/api/v1/knowledge/entries/11` | 200 | 通过 |
| 36 | 附件读取（免鉴权） | GET | `/api/v1/knowledge/files/5e641a92-4743-4758-85e7-9913d388f173` | 200 | 通过 |
| 37 | 标记自评 | PUT | `/api/v1/knowledge/entries/11/self-assessment` | 200 | 通过 |
| 38 | 我标记过的 | GET | `/api/v1/knowledge/self-assessments` | 200 | 通过 |
| 39 | 取消自评 | DELETE | `/api/v1/knowledge/entries/11/self-assessment` | 200 | 通过 |
| 40 | 记一次浏览 | POST | `/api/v1/knowledge/entries/11/view` | 200 | 通过 |
| 41 | 学习线清单 | GET | `/api/v1/learning/lines` | 200 | 通过 |
| 42 | 我的学习记录 | GET | `/api/v1/learning/progress` | 200 | 通过 |
| 43 | 单站记录 | GET | `/api/v1/learning/entries/11` | 200 | 通过 |
| 44 | 上报得分 | PUT | `/api/v1/learning/entries/11` | 200 | 通过 |
| 45 | 我的知识图谱 | GET | `/api/v1/learning/graph` | 200 | 通过 |
| 46 | 重置本站 | DELETE | `/api/v1/learning/entries/11` | 200 | 通过 |
| 47 | 到期复习列表 | GET | `/api/v1/learning/reviews` | 200 | 通过 |
| 48 | 开始复习会话 | POST | `/api/v1/learning/reviews/sessions` | 200 | 通过 |
| 49 | 交卷（会话不存在 → 404） | POST | `/api/v1/learning/reviews/sessions/999999999/answers` | 404 | 通过 |
| 50 | 我的掌握状态 | GET | `/api/v1/learning/mastery` | 200 | 通过 |
| 51 | 补课建议 | GET | `/api/v1/learning/diagnosis?conceptId=1` | 200 | 通过 |
| 52 | 管理端题目清单 | GET | `/api/v1/learning/admin/questions?entryId=11` | 200 | 通过 |
| 53 | 管理端替换标注（题目不存在 → 400） | PUT | `/api/v1/learning/admin/question-concepts` | 400 | 通过 |
| 54 | 概念候选（题目不存在 → 400） | GET | `/api/v1/learning/admin/question-concepts/suggestions?entryId=11&blockKey=smoke-missing&questionKey=smoke-missing%230` | 400 | 通过 |
| 55 | 逐题作答（题目不存在 → 400） | POST | `/api/v1/learning/attempts` | 400 | 通过 |
| 56 | 练习选题 | GET | `/api/v1/learning/practice/next?conceptIds=1` | 200 | 通过 |
| 57 | AI 概念候选 | POST | `/api/v1/learning/admin/question-concepts/suggest-ai?entryId=11` | 200 | 通过 |
| 58 | 评估导出 | GET | `/api/v1/learning/admin/evaluation/export` | 200 | 通过 |
| 59 | 导入学习轨道 | POST | `/api/v1/knowledge/admin/tracks` | 200 | 通过 |
| 60 | 轨道清单 | GET | `/api/v1/learn/tracks` | 200 | 通过 |
| 61 | 轨道关卡地图 | GET | `/api/v1/learn/tracks/walk-track-879328300` | 200 | 通过 |
| 62 | 建笔记 | POST | `/api/v1/notes` | 200 | 通过 |
| 63 | 笔记列表 | GET | `/api/v1/notes` | 200 | 通过 |
| 64 | 笔记详情 | GET | `/api/v1/notes/1` | 200 | 通过 |
| 65 | 改笔记 | PUT | `/api/v1/notes/1` | 200 | 通过 |
| 66 | 分享笔记 | POST | `/api/v1/notes/1/share` | 200 | 通过 |
| 67 | 我的分享 | GET | `/api/v1/notes/1/shares` | 200 | 通过 |
| 68 | 公开读分享（免鉴权） | GET | `/api/v1/public/shares/075270c1-72e4-4cc7-955a-b12cd9092747` | 200 | 通过 |
| 69 | 举报分享 | POST | `/api/v1/reports` | 200 | 通过 |
| 70 | 管理端举报列表 | GET | `/api/v1/notes/admin/reports` | 200 | 通过 |
| 71 | 下架（处理举报） | POST | `/api/v1/notes/admin/reports/1/takedown` | 200 | 通过 |
| 72 | 再分享一次（供撤回用） | POST | `/api/v1/notes/1/share` | 200 | 通过 |
| 73 | 撤回分享 | DELETE | `/api/v1/shares/9dbf4ad7-6128-4c11-a2d0-18a69cd5a0fb` | 200 | 通过 |
| 74 | 删笔记 | DELETE | `/api/v1/notes/1` | 200 | 通过 |
| 75 | AI 凭据状态 | GET | `/api/v1/ai/credentials` | 200 | 通过 |
| 76 | 我的积分 | GET | `/api/v1/ai/credits` | 200 | 通过 |
| 77 | 新建 MCP 凭据 | POST | `/api/v1/ai/mcp-tokens` | 200 | 通过 |
| 78 | MCP 凭据列表 | GET | `/api/v1/ai/mcp-tokens` | 200 | 通过 |
| 79 | 吊销 MCP 凭据 | DELETE | `/api/v1/ai/mcp-tokens/1` | 200 | 通过 |
| 80 | 读设置 | GET | `/api/v1/ai/settings` | 200 | 通过 |
| 81 | 写设置 | PUT | `/api/v1/ai/settings` | 200 | 通过 |
| 82 | 写记忆 | POST | `/api/v1/ai/memories` | 200 | 通过 |
| 83 | 读记忆 | GET | `/api/v1/ai/memories` | 200 | 通过 |
| 84 | 删单条记忆 | DELETE | `/api/v1/ai/memories/text/1` | 200 | 通过 |
| 85 | 写第二条记忆 | POST | `/api/v1/ai/memories` | 200 | 通过 |
| 86 | 清空并关闭记忆 | DELETE | `/api/v1/ai/memories` | 200 | 通过 |
| 87 | 重新打开记忆 | PUT | `/api/v1/ai/settings` | 200 | 通过 |
| 88 | 读精简（可能为空） | GET | `/api/v1/ai/summaries/11` | 200 | 通过 |
| 89 | 生成精简（桩） | POST | `/api/v1/ai/summaries` | 200 | 通过 |
| 90 | 新建会话 | POST | `/api/v1/ai/conversations` | 200 | 通过 |
| 91 | 会话列表 | GET | `/api/v1/ai/conversations` | 200 | 通过 |
| 92 | 会话消息（空） | GET | `/api/v1/ai/conversations/1/messages` | 200 | 通过 |
| 93 | 发消息（SSE） | POST | `/api/v1/ai/conversations/1/messages` | 200 | 通过 |
| 94 | 生成面试点评（桩） | POST | `/api/v1/ai/interviews/1/review` | 200 | 通过 |
| 95 | 我历次点评 | GET | `/api/v1/ai/interviews/evaluations` | 200 | 通过 |
| 96 | 上传并解析简历（桩） | POST | `/api/v1/ai/resumes/parse` | 200 | 通过 |
| 97 | 新建主简历 | POST | `/api/v1/ai/resumes` | 200 | 通过 |
| 98 | 简历列表 | GET | `/api/v1/ai/resumes` | 200 | 通过 |
| 99 | 读简历 | GET | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 100 | 改简历标题 | PUT | `/api/v1/ai/resumes/1/title` | 200 | 通过 |
| 101 | 设为默认 | PUT | `/api/v1/ai/resumes/1/default` | 200 | 通过 |
| 102 | 体检 | GET | `/api/v1/ai/resumes/1/checkup` | 200 | 通过 |
| 103 | 保存简历 | PUT | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 104 | 编辑历史 | GET | `/api/v1/ai/resumes/1/history` | 200 | 通过 |
| 105 | 编辑历史（取列表） | GET | `/api/v1/ai/resumes/1/history` | 200 | 通过 |
| 106 | 读某一版 | GET | `/api/v1/ai/resumes/1/history/1` | 200 | 通过 |
| 107 | 回滚到某一版 | POST | `/api/v1/ai/resumes/1/history/1/restore?revision=2` | 200 | 通过 |
| 108 | 建岗位版本 | POST | `/api/v1/ai/resumes/1/versions` | 200 | 通过 |
| 109 | 版本列表 | GET | `/api/v1/ai/resumes/1/versions` | 200 | 通过 |
| 110 | 分析岗位（桩） | POST | `/api/v1/ai/versions/1/analysis` | 200 | 通过 |
| 111 | 生成提案（桩） | POST | `/api/v1/ai/versions/1/proposals` | 200 | 通过 |
| 112 | 提案列表 | GET | `/api/v1/ai/versions/1/proposals` | 200 | 通过 |
| 113 | 采纳一条提案 | PATCH | `/api/v1/ai/proposals/1` | 200 | 通过 |
| 114 | 定稿 | POST | `/api/v1/ai/versions/1/finalize` | 200 | 通过 |
| 115 | 读版本 | GET | `/api/v1/ai/versions/1` | 200 | 通过 |
| 116 | 删版本 | DELETE | `/api/v1/ai/versions/1` | 200 | 通过 |
| 117 | 删简历 | DELETE | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 118 | 回滚后的 revision=3（保存与回滚都各推进一次） | - | `-` | 0 | 通过 |
| 119 | 训练营用的简历 | POST | `/api/v1/ai/resumes` | 200 | 通过 |
| 120 | 开训练营（桩） | POST | `/api/v1/ai/camps` | 200 | 通过 |
| 121 | 我的训练营 | GET | `/api/v1/ai/camps` | 200 | 通过 |
| 122 | 营概览 | GET | `/api/v1/ai/camps/1` | 200 | 通过 |
| 123 | 取诊断卷 | GET | `/api/v1/ai/camps/1/items?form=diagnostic` | 200 | 通过 |
| 124 | 交诊断卷 | POST | `/api/v1/ai/camps/1/submit` | 200 | 通过 |
| 125 | 诊断结果 | GET | `/api/v1/ai/camps/1/result?form=diagnostic` | 200 | 通过 |
| 126 | 出计划 | POST | `/api/v1/ai/camps/1/plan` | 200 | 通过 |
| 127 | 读计划 | GET | `/api/v1/ai/camps/1/plan` | 200 | 通过 |
| 128 | 勾选任务 | PATCH | `/api/v1/ai/camps/1/tasks/1` | 200 | 通过 |
| 129 | 复测出卷（桩） | POST | `/api/v1/ai/camps/1/retest` | 200 | 通过 |
| 130 | 取复测卷 | GET | `/api/v1/ai/camps/1/items?form=retest` | 200 | 通过 |
| 131 | 交复测卷 | POST | `/api/v1/ai/camps/1/submit` | 200 | 通过 |
| 132 | 复测结果（并排） | GET | `/api/v1/ai/camps/1/result?form=retest` | 200 | 通过 |
| 133 | 删营 | DELETE | `/api/v1/ai/camps/1` | 200 | 通过 |
| 134 | 管理端用量流水 | GET | `/api/v1/ai/admin/credits/audit` | 200 | 通过 |
| 135 | 管理端用量流水（分页筛选） | GET | `/api/v1/ai/admin/credits/ledger?page=1&size=10` | 200 | 通过 |
| 136 | 管理端查某人积分 | GET | `/api/v1/ai/admin/credits/3` | 200 | 通过 |
| 137 | 管理端用户列表 | GET | `/api/v1/account/admin/users?page=1&size=10` | 200 | 通过 |
| 138 | 管理端用户详情 | GET | `/api/v1/account/admin/users/4` | 200 | 通过 |
| 139 | 管理端改角色 | POST | `/api/v1/account/admin/users/4/role` | 200 | 通过 |
| 140 | 管理端调积分（用户管理） | POST | `/api/v1/account/admin/users/4/credits` | 200 | 通过 |
| 141 | 管理端删号 | DELETE | `/api/v1/account/admin/users/4` | 200 | 通过 |
| 142 | 管理端调分 | POST | `/api/v1/ai/admin/credits/3/adjust` | 200 | 通过 |
| 143 | AI 调用概览 | GET | `/api/v1/ai/admin/runs/overview` | 500 | 失败 code=50000 服务器内部错误 |
| 144 | AI 调用明细 | GET | `/api/v1/ai/admin/runs` | 200 | 通过 |
| 145 | AI 调用筛选项 | GET | `/api/v1/ai/admin/runs/meta` | 500 | 失败 code=50000 服务器内部错误 |
| 146 | AI 趋势序列 | GET | `/api/v1/ai/admin/runs/series?days=7` | 200 | 通过 |
| 147 | AI 采集健康 | GET | `/api/v1/ai/admin/runs/health` | 200 | 通过 |
| 148 | AI 账实对账 | GET | `/api/v1/ai/admin/runs/reconcile?days=7` | 500 | 失败 code=50000 服务器内部错误 |
| 149 | AI 调用详情 | GET | `/api/v1/ai/admin/runs/selfcheck879328300` | 200 | 通过 |
| 150 | 管理端回答反馈 | GET | `/api/v1/ai/feedback/admin` | 200 | 通过 |
| 151 | 我的数据集 | GET | `/api/v1/sql-datasets/mine` | 200 | 通过 |
| 152 | 数据集表结构 | GET | `/api/v1/sandbox/datasets/__nonexistent__/schema` | 404 | 通过 |
| 153 | 管理端数据集 | GET | `/api/v1/sandbox/admin/sql-datasets` | 200 | 通过 |
| 154 | 管理端数据集举报 | GET | `/api/v1/sandbox/admin/sql-datasets/reports` | 200 | 通过 |
| 155 | 沙箱状态 | GET | `/api/v1/sandbox/status` | 200 | 通过 |
| 156 | 逐步录制 | POST | `/api/v1/sandbox/trace` | 503 | 通过 |
| 157 | 运行代码 | POST | `/api/v1/sandbox/run` | 503 | 通过 |
| 158 | 登出 | POST | `/api/v1/auth/logout` | 200 | 通过 |
| 159 | 注销账号 | POST | `/api/v1/me/deletion` | 200 | 通过 |
| 160 | 注销后 access 仍可用（JWT 固有取舍） | GET | `/api/v1/me` | 200 | 通过 |
| 161 | 注销后 access 仍返回 200（预期行为，不是缺陷） | - | `-` | 0 | 通过 |
| 162 | 注销后 refresh 失效 | POST | `/api/v1/auth/refresh` | 401 | 通过 |
| 163 | 运维摘要 | GET | `/api/v1/ops/admin/summary` | 200 | 通过 |
| 164 | 速递当日 | GET | `/api/v1/digest` | 200 | 通过 |
| 165 | 速递日期列表 | GET | `/api/v1/digest/dates` | 200 | 通过 |
| 166 | 速递周报 | GET | `/api/v1/digest/weekly` | 200 | 通过 |
| 167 | 管理端速递清单 | GET | `/api/v1/digest/admin/items` | 200 | 通过 |

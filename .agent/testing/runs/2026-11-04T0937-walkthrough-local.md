---
artifact: test-run
run_id: 2026-11-04T0937
layer: walkthrough
target: local
status: passed
started: 2026-11-04 09:37:09
---

# 全接口自查 2026-11-04 09:37:09

合计 **189** 步：通过 189，失败 0；覆盖端点 **200** 条。

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
| 15 | 改分类 | PUT | `/api/v1/knowledge/admin/categories/9203` | 200 | 通过 |
| 16 | 建条目 | POST | `/api/v1/knowledge/admin/entries` | 200 | 通过 |
| 17 | 再建一条（供关系用） | POST | `/api/v1/knowledge/admin/entries` | 200 | 通过 |
| 18 | 管理端条目列表 | GET | `/api/v1/knowledge/admin/entries?size=5` | 200 | 通过 |
| 19 | 管理端条目详情 | GET | `/api/v1/knowledge/admin/entries/9204` | 200 | 通过 |
| 20 | 改条目 | PUT | `/api/v1/knowledge/admin/entries/9204` | 200 | 通过 |
| 21 | 建关系 | POST | `/api/v1/knowledge/admin/relations` | 200 | 通过 |
| 22 | 删关系 | DELETE | `/api/v1/knowledge/admin/relations/3` | 200 | 通过 |
| 23 | 建概念 | POST | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 24 | 改概念 | PUT | `/api/v1/knowledge/admin/concepts/1` | 200 | 通过 |
| 25 | 概念列表 | GET | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 26 | 挂条目 | POST | `/api/v1/knowledge/admin/concepts/1/entries` | 200 | 通过 |
| 27 | 解关联 | DELETE | `/api/v1/knowledge/admin/concepts/1/entries/9204` | 200 | 通过 |
| 28 | 建概念二 | POST | `/api/v1/knowledge/admin/concepts` | 200 | 通过 |
| 29 | 建概念关系 | POST | `/api/v1/knowledge/admin/concept-edges` | 200 | 通过 |
| 30 | 删概念关系 | DELETE | `/api/v1/knowledge/admin/concept-edges/1` | 200 | 通过 |
| 31 | 删概念 | DELETE | `/api/v1/knowledge/admin/concepts/2` | 200 | 通过 |
| 32 | 上传附件 | POST | `/api/v1/knowledge/admin/files` | 200 | 通过 |
| 33 | 分类树 | GET | `/api/v1/knowledge/categories` | 200 | 通过 |
| 34 | 条目列表 | GET | `/api/v1/knowledge/entries?size=5` | 200 | 通过 |
| 35 | 条目详情 | GET | `/api/v1/knowledge/entries/9204` | 200 | 通过 |
| 36 | 附件读取（免鉴权） | GET | `/api/v1/knowledge/files/622c057f-8d6f-4c6b-8cc7-4bf898e509d5` | 200 | 通过 |
| 37 | 标记自评 | PUT | `/api/v1/knowledge/entries/9204/self-assessment` | 200 | 通过 |
| 38 | 我标记过的 | GET | `/api/v1/knowledge/self-assessments` | 200 | 通过 |
| 39 | 取消自评 | DELETE | `/api/v1/knowledge/entries/9204/self-assessment` | 200 | 通过 |
| 40 | 记一次浏览 | POST | `/api/v1/knowledge/entries/9204/view` | 200 | 通过 |
| 41 | 学习线清单 | GET | `/api/v1/learning/lines` | 200 | 通过 |
| 42 | 我的学习记录 | GET | `/api/v1/learning/progress` | 200 | 通过 |
| 43 | 单站记录 | GET | `/api/v1/learning/entries/9204` | 200 | 通过 |
| 44 | 上报得分 | PUT | `/api/v1/learning/entries/9204` | 200 | 通过 |
| 45 | 我的知识图谱 | GET | `/api/v1/learning/graph` | 200 | 通过 |
| 46 | 重置本站 | DELETE | `/api/v1/learning/entries/9204` | 200 | 通过 |
| 47 | 今日计划 | GET | `/api/v1/learning/plan-items?date=2026-11-04` | 200 | 通过 |
| 48 | 采用一条计划 | POST | `/api/v1/learning/plan-items` | 200 | 通过 |
| 49 | 计划项打勾 | PATCH | `/api/v1/learning/plan-items/1` | 200 | 通过 |
| 50 | 删计划项 | DELETE | `/api/v1/learning/plan-items/1` | 200 | 通过 |
| 51 | 到期复习列表 | GET | `/api/v1/learning/reviews` | 200 | 通过 |
| 52 | 开始复习会话 | POST | `/api/v1/learning/reviews/sessions` | 200 | 通过 |
| 53 | 交卷（会话不存在 → 404） | POST | `/api/v1/learning/reviews/sessions/999999999/answers` | 404 | 通过 |
| 54 | 我的掌握状态 | GET | `/api/v1/learning/mastery` | 200 | 通过 |
| 55 | 补课建议 | GET | `/api/v1/learning/diagnosis?conceptId=1` | 200 | 通过 |
| 56 | 管理端题目清单 | GET | `/api/v1/learning/admin/questions?entryId=9204` | 200 | 通过 |
| 57 | 管理端替换标注（题目不存在 → 400） | PUT | `/api/v1/learning/admin/question-concepts` | 400 | 通过 |
| 58 | 概念候选（题目不存在 → 400） | GET | `/api/v1/learning/admin/question-concepts/suggestions?entryId=9204&blockKey=smoke-missing&questionKey=smoke-missing%230` | 400 | 通过 |
| 59 | 逐题作答（题目不存在 → 400） | POST | `/api/v1/learning/attempts` | 400 | 通过 |
| 60 | 练习选题 | GET | `/api/v1/learning/practice/next?conceptIds=1` | 200 | 通过 |
| 61 | 我已答过的题 | GET | `/api/v1/learning/entries/9204/attempts` | 200 | 通过 |
| 62 | AI 概念候选 | POST | `/api/v1/learning/admin/question-concepts/suggest-ai?entryId=9204` | 200 | 通过 |
| 63 | 评估导出 | GET | `/api/v1/learning/admin/evaluation/export` | 200 | 通过 |
| 64 | 导入学习轨道 | POST | `/api/v1/knowledge/admin/tracks` | 200 | 通过 |
| 65 | 轨道清单 | GET | `/api/v1/learn/tracks` | 200 | 通过 |
| 66 | 轨道关卡地图 | GET | `/api/v1/learn/tracks/walk-track-124064000` | 200 | 通过 |
| 67 | 建笔记 | POST | `/api/v1/notes` | 200 | 通过 |
| 68 | 笔记列表 | GET | `/api/v1/notes` | 200 | 通过 |
| 69 | 笔记详情 | GET | `/api/v1/notes/1` | 200 | 通过 |
| 70 | 改笔记 | PUT | `/api/v1/notes/1` | 200 | 通过 |
| 71 | 分享笔记 | POST | `/api/v1/notes/1/share` | 200 | 通过 |
| 72 | 我的分享 | GET | `/api/v1/notes/1/shares` | 200 | 通过 |
| 73 | 公开读分享（免鉴权） | GET | `/api/v1/public/shares/05a3b4b9-4c50-4053-831d-4137603ca928` | 200 | 通过 |
| 74 | 举报分享 | POST | `/api/v1/reports` | 200 | 通过 |
| 75 | 管理端举报列表 | GET | `/api/v1/notes/admin/reports` | 200 | 通过 |
| 76 | 下架（处理举报） | POST | `/api/v1/notes/admin/reports/1/takedown` | 200 | 通过 |
| 77 | 再分享一次（供撤回用） | POST | `/api/v1/notes/1/share` | 200 | 通过 |
| 78 | 撤回分享 | DELETE | `/api/v1/shares/0c8fdafd-5a59-4fa1-b9ef-af3aa1713786` | 200 | 通过 |
| 79 | 删笔记 | DELETE | `/api/v1/notes/1` | 200 | 通过 |
| 80 | AI 凭据状态 | GET | `/api/v1/ai/credentials` | 200 | 通过 |
| 81 | 我的积分 | GET | `/api/v1/ai/credits` | 200 | 通过 |
| 82 | 新建 MCP 凭据 | POST | `/api/v1/ai/mcp-tokens` | 200 | 通过 |
| 83 | MCP 凭据列表 | GET | `/api/v1/ai/mcp-tokens` | 200 | 通过 |
| 84 | 吊销 MCP 凭据 | DELETE | `/api/v1/ai/mcp-tokens/1` | 200 | 通过 |
| 85 | 读设置 | GET | `/api/v1/ai/settings` | 200 | 通过 |
| 86 | 写设置 | PUT | `/api/v1/ai/settings` | 200 | 通过 |
| 87 | 写记忆 | POST | `/api/v1/ai/memories` | 200 | 通过 |
| 88 | 读记忆 | GET | `/api/v1/ai/memories` | 200 | 通过 |
| 89 | 删单条记忆 | DELETE | `/api/v1/ai/memories/text/1` | 200 | 通过 |
| 90 | 写第二条记忆 | POST | `/api/v1/ai/memories` | 200 | 通过 |
| 91 | 清空并关闭记忆 | DELETE | `/api/v1/ai/memories` | 200 | 通过 |
| 92 | 重新打开记忆 | PUT | `/api/v1/ai/settings` | 200 | 通过 |
| 93 | 记忆候选 | GET | `/api/v1/ai/memories/candidates` | 200 | 通过 |
| 94 | 采纳不存在的候选 → 400 | POST | `/api/v1/ai/memories/candidates/999999999/accept` | 400 | 通过 |
| 95 | 拒绝不存在的候选 → 400 | POST | `/api/v1/ai/memories/candidates/999999999/reject` | 400 | 通过 |
| 96 | 读精简（可能为空） | GET | `/api/v1/ai/summaries/9204` | 200 | 通过 |
| 97 | 生成精简（桩） | POST | `/api/v1/ai/summaries` | 200 | 通过 |
| 98 | AI 讲解错题（选的已是答案 → 400） | POST | `/api/v1/ai/explain/review-item` | 400 | 通过 |
| 99 | AI 下一步（缺进度 → 400） | POST | `/api/v1/ai/next-step` | 400 | 通过 |
| 100 | AI 概念解释（缺名字 → 400） | POST | `/api/v1/ai/concepts/explain` | 400 | 通过 |
| 101 | AI 笔记要点（缺正文 → 400） | POST | `/api/v1/ai/notes/points` | 400 | 通过 |
| 102 | AI 出题（缺正文 → 400） | POST | `/api/v1/ai/quiz` | 400 | 通过 |
| 103 | AI 数据集草稿（缺 topic → 400） | POST | `/api/v1/sql-datasets/draft` | 400 | 通过 |
| 104 | 新建会话 | POST | `/api/v1/ai/conversations` | 200 | 通过 |
| 105 | 会话列表 | GET | `/api/v1/ai/conversations` | 200 | 通过 |
| 106 | 会话消息（空） | GET | `/api/v1/ai/conversations/1/messages` | 200 | 通过 |
| 107 | 发消息（SSE） | POST | `/api/v1/ai/conversations/1/messages` | 200 | 通过 |
| 108 | 生成面试点评（桩） | POST | `/api/v1/ai/interviews/1/review` | 200 | 通过 |
| 109 | 我历次点评 | GET | `/api/v1/ai/interviews/evaluations` | 200 | 通过 |
| 110 | 上传并解析简历（桩） | POST | `/api/v1/ai/resumes/parse` | 200 | 通过 |
| 111 | 新建主简历 | POST | `/api/v1/ai/resumes` | 200 | 通过 |
| 112 | 简历列表 | GET | `/api/v1/ai/resumes` | 200 | 通过 |
| 113 | 读简历 | GET | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 114 | 改简历标题 | PUT | `/api/v1/ai/resumes/1/title` | 200 | 通过 |
| 115 | 设为默认 | PUT | `/api/v1/ai/resumes/1/default` | 200 | 通过 |
| 116 | 体检 | GET | `/api/v1/ai/resumes/1/checkup` | 200 | 通过 |
| 117 | 保存简历 | PUT | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 118 | 编辑历史 | GET | `/api/v1/ai/resumes/1/history` | 200 | 通过 |
| 119 | 编辑历史（取列表） | GET | `/api/v1/ai/resumes/1/history` | 200 | 通过 |
| 120 | 读某一版 | GET | `/api/v1/ai/resumes/1/history/1` | 200 | 通过 |
| 121 | 回滚到某一版 | POST | `/api/v1/ai/resumes/1/history/1/restore?revision=2` | 200 | 通过 |
| 122 | 建岗位版本 | POST | `/api/v1/ai/resumes/1/versions` | 200 | 通过 |
| 123 | 版本列表 | GET | `/api/v1/ai/resumes/1/versions` | 200 | 通过 |
| 124 | 分析岗位（桩） | POST | `/api/v1/ai/versions/1/analysis` | 200 | 通过 |
| 125 | 生成提案（桩） | POST | `/api/v1/ai/versions/1/proposals` | 200 | 通过 |
| 126 | 提案列表 | GET | `/api/v1/ai/versions/1/proposals` | 200 | 通过 |
| 127 | 采纳一条提案 | PATCH | `/api/v1/ai/proposals/1` | 200 | 通过 |
| 128 | 定稿 | POST | `/api/v1/ai/versions/1/finalize` | 200 | 通过 |
| 129 | 读版本 | GET | `/api/v1/ai/versions/1` | 200 | 通过 |
| 130 | 删版本 | DELETE | `/api/v1/ai/versions/1` | 200 | 通过 |
| 131 | 删简历 | DELETE | `/api/v1/ai/resumes/1` | 200 | 通过 |
| 132 | 回滚后的 revision=3（保存与回滚都各推进一次） | - | `-` | 0 | 通过 |
| 133 | 训练营用的简历 | POST | `/api/v1/ai/resumes` | 200 | 通过 |
| 134 | 开训练营（桩） | POST | `/api/v1/ai/camps` | 200 | 通过 |
| 135 | 我的训练营 | GET | `/api/v1/ai/camps` | 200 | 通过 |
| 136 | 营概览 | GET | `/api/v1/ai/camps/1` | 200 | 通过 |
| 137 | 取诊断卷 | GET | `/api/v1/ai/camps/1/items?form=diagnostic` | 200 | 通过 |
| 138 | 交诊断卷 | POST | `/api/v1/ai/camps/1/submit` | 200 | 通过 |
| 139 | 诊断结果 | GET | `/api/v1/ai/camps/1/result?form=diagnostic` | 200 | 通过 |
| 140 | 出计划 | POST | `/api/v1/ai/camps/1/plan` | 200 | 通过 |
| 141 | 读计划 | GET | `/api/v1/ai/camps/1/plan` | 200 | 通过 |
| 142 | 勾选任务 | PATCH | `/api/v1/ai/camps/1/tasks/1` | 200 | 通过 |
| 143 | 复测出卷（桩） | POST | `/api/v1/ai/camps/1/retest` | 200 | 通过 |
| 144 | 取复测卷 | GET | `/api/v1/ai/camps/1/items?form=retest` | 200 | 通过 |
| 145 | 交复测卷 | POST | `/api/v1/ai/camps/1/submit` | 200 | 通过 |
| 146 | 复测结果（并排） | GET | `/api/v1/ai/camps/1/result?form=retest` | 200 | 通过 |
| 147 | 删营 | DELETE | `/api/v1/ai/camps/1` | 200 | 通过 |
| 148 | 管理端用量流水 | GET | `/api/v1/ai/admin/credits/audit` | 200 | 通过 |
| 149 | 管理端用量流水（分页筛选） | GET | `/api/v1/ai/admin/credits/ledger?page=1&size=10` | 200 | 通过 |
| 150 | 管理端查某人积分 | GET | `/api/v1/ai/admin/credits/3` | 200 | 通过 |
| 151 | 管理端用户列表 | GET | `/api/v1/account/admin/users?page=1&size=10` | 200 | 通过 |
| 152 | 管理端用户详情 | GET | `/api/v1/account/admin/users/4` | 200 | 通过 |
| 153 | 管理端改角色 | POST | `/api/v1/account/admin/users/4/role` | 200 | 通过 |
| 154 | 管理端调积分（用户管理） | POST | `/api/v1/account/admin/users/4/credits` | 200 | 通过 |
| 155 | 管理端删号 | DELETE | `/api/v1/account/admin/users/4` | 200 | 通过 |
| 156 | 管理端调分 | POST | `/api/v1/ai/admin/credits/3/adjust` | 200 | 通过 |
| 157 | AI 调用概览 | GET | `/api/v1/ai/admin/runs/overview` | 200 | 通过 |
| 158 | AI 调用明细 | GET | `/api/v1/ai/admin/runs` | 200 | 通过 |
| 159 | AI 调用筛选项 | GET | `/api/v1/ai/admin/runs/meta` | 200 | 通过 |
| 160 | AI 趋势序列 | GET | `/api/v1/ai/admin/runs/series?days=7` | 200 | 通过 |
| 161 | AI 采集健康 | GET | `/api/v1/ai/admin/runs/health` | 200 | 通过 |
| 162 | AI 账实对账 | GET | `/api/v1/ai/admin/runs/reconcile?days=7` | 200 | 通过 |
| 163 | AI 调用详情 | GET | `/api/v1/ai/admin/runs/selfcheck124064000` | 200 | 通过 |
| 164 | 管理端回答反馈 | GET | `/api/v1/ai/feedback/admin` | 200 | 通过 |
| 165 | 我的数据集 | GET | `/api/v1/sql-datasets/mine` | 200 | 通过 |
| 166 | 数据集目录 | GET | `/api/v1/sql-datasets/catalog` | 200 | 通过 |
| 167 | 改可见性（不存在 → 404） | POST | `/api/v1/sql-datasets/__nonexistent__/visibility` | 404 | 通过 |
| 168 | 数据集表结构 | GET | `/api/v1/sandbox/datasets/__nonexistent__/schema` | 404 | 通过 |
| 169 | 管理端数据集 | GET | `/api/v1/sandbox/admin/sql-datasets` | 200 | 通过 |
| 170 | 管理端数据集举报 | GET | `/api/v1/sandbox/admin/sql-datasets/reports` | 200 | 通过 |
| 171 | 沙箱状态 | GET | `/api/v1/sandbox/status` | 200 | 通过 |
| 172 | 逐步录制 | POST | `/api/v1/sandbox/trace` | 503 | 通过 |
| 173 | 运行代码 | POST | `/api/v1/sandbox/run` | 503 | 通过 |
| 174 | Linux 判题（未配置镜像 → 503） | POST | `/api/v1/sandbox/shell/run` | 503 | 通过 |
| 175 | 登出 | POST | `/api/v1/auth/logout` | 200 | 通过 |
| 176 | 注销账号 | POST | `/api/v1/me/deletion` | 200 | 通过 |
| 177 | 注销后 access 仍可用（JWT 固有取舍） | GET | `/api/v1/me` | 200 | 通过 |
| 178 | 注销后 access 仍返回 200（预期行为，不是缺陷） | - | `-` | 0 | 通过 |
| 179 | 注销后 refresh 失效 | POST | `/api/v1/auth/refresh` | 401 | 通过 |
| 180 | 运维摘要 | GET | `/api/v1/ops/admin/summary` | 200 | 通过 |
| 181 | 运维数据总览 | GET | `/api/v1/ops/admin/analytics/overview` | 200 | 通过 |
| 182 | 诊断列表 | GET | `/api/v1/ops/admin/diagnoses` | 200 | 通过 |
| 183 | 诊断统计 | GET | `/api/v1/ops/admin/diagnoses/stats` | 200 | 通过 |
| 184 | 事件列表 | GET | `/api/v1/ops/admin/incidents` | 200 | 通过 |
| 185 | 关闭不存在的事件 → 404 | POST | `/api/v1/ops/admin/incidents/999999999/close` | 404 | 通过 |
| 186 | 速递当日 | GET | `/api/v1/digest` | 200 | 通过 |
| 187 | 速递日期列表 | GET | `/api/v1/digest/dates` | 200 | 通过 |
| 188 | 速递周报 | GET | `/api/v1/digest/weekly` | 200 | 通过 |
| 189 | 管理端速递清单 | GET | `/api/v1/digest/admin/items` | 200 | 通过 |

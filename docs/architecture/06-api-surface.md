<!--
  本文件由 scripts/gen-arch-diagram.mjs 生成，**不要手改**。
  手改会让 `node scripts/gen-arch-diagram.mjs --check` 失败（那是刻意的：这张图必须等于控制器的事实）。
  要改图就改控制器或改生成脚本，然后重跑生成。
-->

# API 端点清单

由后端 47 个 Controller 生成：**178 个端点**，分布在 8 个模块。

这是**结构性的清单**，不是手写文档——端点加一个这里就多一行，
所以它不会像手写的接口文档那样过期。真正的授权规则在 `SecurityConfiguration`，
下表最后一列只是按前缀推出的**粗分类**，碰到具体端点的判定以代码为准。

| 模块 | 前缀 | 控制器 | 鉴权（按前缀推测） | 端点数 |
| --- | --- | --- | --- | --- |
| `paideia-account` | `/api/v1/account/oauth` | `OAuthIdentityController` | 登录即可 | 3 |
| `paideia-account` | `/api/v1/account/profile` | `LearnerProfileController` | 登录即可 | 2 |
| `paideia-account` | `/api/v1/auth` | `AuthController` | 免鉴权 | 6 |
| `paideia-account` | `/api/v1/auth/oauth` | `OAuthController` | 免鉴权 | 4 |
| `paideia-account` | `/api/v1/me` | `AccountDeletionController` | 登录即可 | 1 |
| `paideia-ai` | `/api/v1/ai` | `ResumeOptimizationController` | 登录即可 | 9 |
| `paideia-ai` | `/api/v1/ai/admin/credits` | `AiCreditAdminController` | 仅管理员 | 4 |
| `paideia-ai` | `/api/v1/ai/admin/runs` | `AiRunAdminController` | 仅管理员 | 7 |
| `paideia-ai` | `/api/v1/ai/camps` | `CampController` | 登录即可 | 11 |
| `paideia-ai` | `/api/v1/ai/conversations` | `AssistantConversationController` | 登录即可 | 4 |
| `paideia-ai` | `/api/v1/ai/credentials` | `AiCredentialController` | 登录即可 | 1 |
| `paideia-ai` | `/api/v1/ai/credits` | `AiCreditController` | 登录即可 | 1 |
| `paideia-ai` | `/api/v1/ai/feedback` | `AiFeedbackAdminController` | 登录即可 | 1 |
| `paideia-ai` | `/api/v1/ai/feedback` | `AiFeedbackController` | 登录即可 | 1 |
| `paideia-ai` | `/api/v1/ai/interviews` | `InterviewController` | 登录即可 | 2 |
| `paideia-ai` | `/api/v1/ai/memories` | `AiMemoryController` | 登录即可 | 4 |
| `paideia-ai` | `/api/v1/ai/resumes` | `ResumeController` | 登录即可 | 12 |
| `paideia-ai` | `/api/v1/ai/settings` | `AiSettingsController` | 登录即可 | 2 |
| `paideia-ai` | `/api/v1/ai/summaries` | `AiSummaryController` | 登录即可 | 2 |
| `paideia-ai` | `/api/v1/digest` | `DigestController` | 登录即可 | 4 |
| `paideia-ai` | `/api/v1/digest/admin` | `DigestAdminController` | 仅管理员 | 2 |
| `paideia-app` | `(根)` | `OpsGrafanaProxyController` | 登录即可 | 2 |
| `paideia-app` | `/api/v1/account/admin/users` | `AdminUserController` | 仅管理员 | 5 |
| `paideia-app` | `/api/v1/ops/admin` | `OpsSummaryController` | 仅管理员 | 1 |
| `paideia-app` | `/api/v1/ops/admin/analytics` | `AnalyticsController` | 仅管理员 | 1 |
| `paideia-app` | `/api/v1/ops/admin/diagnoses` | `OpsDiagnosisController` | 仅管理员 | 5 |
| `paideia-app` | `/api/v1/ops/admin/incidents` | `OpsIncidentController` | 仅管理员 | 3 |
| `paideia-app` | `/api/v1/ops/hooks` | `OpsHookController` | 登录即可 | 1 |
| `paideia-knowledge` | `/api/v1/knowledge` | `EntryViewController` | 登录即可 | 1 |
| `paideia-knowledge` | `/api/v1/knowledge` | `KnowledgeFileController` | 登录即可 | 2 |
| `paideia-knowledge` | `/api/v1/knowledge` | `KnowledgeReadController` | 登录即可 | 3 |
| `paideia-knowledge` | `/api/v1/knowledge` | `SelfAssessmentController` | 登录即可 | 3 |
| `paideia-knowledge` | `/api/v1/knowledge/admin` | `KnowledgeAdminController` | 仅管理员 | 20 |
| `paideia-knowledge` | `/api/v1/learn` | `LearnTrackController` | 登录即可 | 2 |
| `paideia-knowledge` | `/api/v1/learning` | `LearningController` | 登录即可 | 7 |
| `paideia-knowledge` | `/api/v1/learning` | `QuestionAttemptController` | 登录即可 | 4 |
| `paideia-knowledge` | `/api/v1/learning/admin` | `QuestionConceptAdminController` | 仅管理员 | 4 |
| `paideia-mcp` | `/api/v1/ai/mcp-tokens` | `McpTokenController` | 登录即可 | 3 |
| `paideia-note` | `/api/v1` | `NoteShareController` | 登录即可 | 3 |
| `paideia-note` | `/api/v1/notes` | `NoteController` | 登录即可 | 5 |
| `paideia-note` | `/api/v1/notes/admin/reports` | `NoteReportAdminController` | 仅管理员 | 2 |
| `paideia-note` | `/api/v1/public/shares` | `PublicShareController` | 免鉴权 | 1 |
| `paideia-note` | `/api/v1/reports` | `ReportController` | 登录即可 | 1 |
| `paideia-sandbox` | `/api/v1/sandbox` | `SandboxController` | 登录即可 | 4 |
| `paideia-sandbox` | `/api/v1/sandbox/admin/sql-datasets` | `SqlDatasetAdminController` | 仅管理员 | 4 |
| `paideia-sandbox` | `/api/v1/sql-datasets` | `SqlDatasetController` | 登录即可 | 7 |
| `paideia-security` | `(根)` | `MeController` | 登录即可 | 1 |

## 按 HTTP 方法统计

| 方法 | 数量 |
| --- | --- |
| `GET` | 84 |
| `POST` | 60 |
| `DELETE` | 20 |
| `PUT` | 12 |
| `PATCH` | 2 |

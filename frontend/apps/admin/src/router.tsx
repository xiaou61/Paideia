import { Navigate, createHashRouter } from 'react-router'

import { AdminHomePage } from './pages/AdminHomePage'
import { AnalyticsPage } from './pages/AnalyticsPage'
import { AiCreditsAuditPage } from './pages/AiCreditsAuditPage'
import { AiRunsListPage } from './pages/AiRunsListPage'
import { AiFeedbackPage } from './pages/AiFeedbackPage'
import { AiRunsOverviewPage } from './pages/AiRunsOverviewPage'
import { KnowledgeCategoriesPage } from './pages/KnowledgeCategoriesPage'
import { KnowledgeConceptsPage } from './pages/KnowledgeConceptsPage'
import { KnowledgeEntriesPage } from './pages/KnowledgeEntriesPage'
import { AdminUsersPage } from './pages/AdminUsersPage'
import { NoteReportsPage } from './pages/NoteReportsPage'
import { SqlDatasetReportsPage } from './pages/SqlDatasetReportsPage'
import { SqlDatasetsPage } from './pages/SqlDatasetsPage'
import { OpsPage } from './pages/OpsPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { RequireAdmin } from './require-admin'

/**
 * 管理端路由。
 *
 * <p>用 Hash 路由是为了与学习者端保持一致（同一套约定、同一份经验）；管理端只在 Web 上跑，
 * 所以这里不是被桌面壳逼的，而是"两端不要各有一套路由习惯"。
 *
 * <p>每个页面都包在 `RequireAdmin` 里，门只有一处。
 *
 * <p>分类与条目各占一条路由（对应侧栏两个菜单项）。`/knowledge` 留一条转向条目的重定向：
 * 拆页之前只有这一个地址，留着免得已经打开的旧标签刷新后是一片空白。
 */
export const router = createHashRouter([
  {
    path: '/',
    element: (
      <RequireAdmin>
        <AdminHomePage />
      </RequireAdmin>
    ),
  },
  { path: '/knowledge', element: <Navigate to="/knowledge/entries" replace /> },
  {
    path: '/knowledge/categories',
    element: (
      <RequireAdmin>
        <KnowledgeCategoriesPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/knowledge/concepts',
    element: (
      <RequireAdmin>
        <KnowledgeConceptsPage />
      </RequireAdmin>
    ),
  },
  {
    // 用户管理（WORK-030）：调积分、改角色、删号都在这里，取代了原先的「积分调整」页。
    path: '/users',
    element: (
      <RequireAdmin>
        <AdminUsersPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/reports',
    element: (
      <RequireAdmin>
        <NoteReportsPage />
      </RequireAdmin>
    ),
  },
  { path: '/sql-datasets', element: <Navigate to="/sql-datasets/list" replace /> },
  {
    path: '/sql-datasets/list',
    element: (
      <RequireAdmin>
        <SqlDatasetsPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/sql-datasets/reports',
    element: (
      <RequireAdmin>
        <SqlDatasetReportsPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/knowledge/entries',
    element: (
      <RequireAdmin>
        <KnowledgeEntriesPage />
      </RequireAdmin>
    ),
  },
  // AI 积分（WORK-009 / WORK-030）。`/ai-credits` 转向审计页：父菜单只分组、不指向页面，
  // 但没有它时手敲这个地址就是一片空白。**「积分调整」那一页已按用户裁决删除**
  // （U-04）——调整积分并入用户管理，这里不再有独立入口。
  { path: '/ai-credits', element: <Navigate to="/ai-credits/audit" replace /> },
  // AI 调用统计（WORK-022）。父项只分组，子项才是页面。
  { path: '/ai-runs', element: <Navigate to="/ai-runs/overview" replace /> },
  {
    path: '/ai-runs/overview',
    element: (
      <RequireAdmin>
        <AiRunsOverviewPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/ai-runs/list',
    element: (
      <RequireAdmin>
        <AiRunsListPage />
      </RequireAdmin>
    ),
  },
  // 回答反馈（WORK-036）：与本组其它页同属「AI 调用」。
  {
    path: '/ai-runs/feedback',
    element: (
      <RequireAdmin>
        <AiFeedbackPage />
      </RequireAdmin>
    ),
  },
  // 运维面板（WORK-028）。单页，不需要父项分组；`/ops` 一条转向只是为了
  // 手敲地址时不落到空白。
  { path: '/ops', element: <Navigate to="/ops/panel" replace /> },
  {
    path: '/ops/panel',
    element: (
      <RequireAdmin>
        <OpsPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/ops/incidents',
    element: (
      <RequireAdmin>
        <IncidentsPage />
      </RequireAdmin>
    ),
  },
  // 数据大屏（WORK-045）：横向视角——数据从哪来、经过哪几步、在哪一步掉了。
  // 与运维页（看"现在怎么样"）互补，所以单开一页而不是塞进 /ops。
  {
    path: '/analytics',
    element: (
      <RequireAdmin>
        <AnalyticsPage />
      </RequireAdmin>
    ),
  },
  {
    path: '/ai-credits/audit',
    element: (
      <RequireAdmin>
        <AiCreditsAuditPage />
      </RequireAdmin>
    ),
  },
])

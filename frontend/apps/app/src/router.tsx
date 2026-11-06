import { RequireAuth } from '@paideia/auth'
import { createHashRouter } from 'react-router'

import { HomePage } from './pages/HomePage'
import { BooksPage } from './pages/BooksPage'
import { BookChaptersPage } from './pages/BookChaptersPage'
import { GraphPage } from './pages/GraphPage'
import { ProgressPage } from './pages/ProgressPage'
import { ReviewPage } from './pages/ReviewPage'
import { KnowledgeEntryPage } from './pages/KnowledgeEntryPage'
import { CampDetailPage } from './pages/CampDetailPage'
import { CampListPage } from './pages/CampListPage'
import { CategoryEntriesPage } from './pages/CategoryEntriesPage'
import { KnowledgeCategoriesPage } from './pages/KnowledgeCategoriesPage'
import { DigestPage } from './pages/DigestPage'
import { LearnPage } from './pages/LearnPage'
import { TrackMapPage } from './pages/TrackMapPage'
import { SqlPracticePage } from './pages/SqlPracticePage'
import { LoginPage } from './pages/LoginPage'
import { InterviewPage } from './pages/InterviewPage'
import { ProfilePage } from './pages/ProfilePage'
import { McpTokensPage } from './pages/McpTokensPage'
import { MyAssessmentsPage } from './pages/MyAssessmentsPage'
import { NoteNewPage } from './pages/NoteNewPage'
import { NotesPage } from './pages/NotesPage'
import { OAuthCallbackPage } from './pages/OAuthCallbackPage'
import { RegisterPage } from './pages/RegisterPage'
import { ResumeEditorPage } from './pages/ResumeEditorPage'
import { ResumeImportReviewPage } from './pages/ResumeImportReviewPage'
import { ResumeListPage, ResumeCreatePage } from './pages/ResumeListPage'
import { ResumeOptimizePage } from './pages/ResumeOptimizePage'
import { ResumePreviewPage } from './pages/ResumePreviewPage'
import { ResumePrintPage } from './pages/ResumePrintPage'
import { SharedNotePage } from './pages/SharedNotePage'
import { SourceChapterPage } from './pages/SourceChapterPage'
import { SourceProjectPage } from './pages/SourceProjectPage'
import { SourceProjectsPage } from './pages/SourceProjectsPage'

/**
 * 使用 Hash 路由：桌面壳从自定义协议加载页面，基于浏览器历史的路径路由在那种来源下会失效。
 * Hash 路由让同一套路由同时服务 Web 与桌面端，不必为两端维护两份路由配置。
 *
 * <p>学习者端**整体要求登录**：内容页统一包在 `RequireAuth` 里，登录页与注册页是仅有的两个
 * 不需要登录的路由。公开页不在本应用里——它是独立的 apps/public。
 *
 * <p>这条守卫只做界面分流，**不是授权边界**：真正的拦截在后端每个接口上。
 *
 * <p><b>Hash 路由的一个副作用</b>：地址栏的 hash 就是路由，所以正文目录里的章节锚点
 * **不能**写成 `href="#章节"`——那会被当成一次路由跳转，跳到空白页。目录用的是按钮 +
 * `scrollIntoView`，见 `packages/knowledge` 的 `EntryToc`。
 */
export const router = createHashRouter([
  {
    // 分享页**不包 RequireAuth**：它是给站外的人看的（用户裁决 12）。
    // 代价是这个路由也加载了学习者端的整包（含认证代码）——比为此单开一个前端产物便宜得多，
    // 而且登录门之外这唯一的一个页面，正是匿名访问者真正会走到的入口
    // （apps/public 在当前部署里并没有被服务，线上根路径就是学习者端）。
    path: '/share/:shareId',
    element: <SharedNotePage />,
  },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  // 第三方登录回调（WORK-037）。它**不包 RequireAuth**——此刻还没登录，
  // 正是这个页面把用户变成已登录的。也不包 RequireGuest：已登录时从回调页进来
  // 是正常的（比如在另一个标签页里刚登录过），不该被弹回首页。
  { path: '/oauth/callback', element: <OAuthCallbackPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <HomePage />
      </RequireAuth>
    ),
  },
  // 从零开始（WORK-024）：轨道列表 → 关卡地图。**与知识库无关的独立入口**。
  {
    path: '/learn',
    element: (
      <RequireAuth>
        <LearnPage />
      </RequireAuth>
    ),
  },
  // 学习记录（WORK-053）：四块汇总 + 三个 AI 入口。它不是第二个 /learn，地图还在那边。
  {
    path: '/progress',
    element: (
      <RequireAuth>
        <ProgressPage />
      </RequireAuth>
    ),
  },
  // 复习（WORK-034）：到期列表；S1 只读，不生成新题。
  {
    path: '/review',
    element: (
      <RequireAuth>
        <ReviewPage />
      </RequireAuth>
    ),
  },
  {
    path: '/learn/:slug',
    element: (
      <RequireAuth>
        <TrackMapPage />
      </RequireAuth>
    ),
  },

  // 书籍详解（WORK-046）：按书读的独立入口。地址用固定路径 + slug，
  // **不用分类的数字 id**——id 换库就变，slug 由内容源决定。
  {
    path: '/books',
    element: (
      <RequireAuth>
        <BooksPage />
      </RequireAuth>
    ),
  },
  {
    path: '/books/:slug',
    element: (
      <RequireAuth>
        <BookChaptersPage />
      </RequireAuth>
    ),
  },

  // 源码精读（WORK-057）：独立入口，读第三方项目。slug 与 entryId 都放在地址里，
  // 与书籍详解同形；章节正文仍是知识库条目。
  {
    path: '/source',
    element: (
      <RequireAuth>
        <SourceProjectsPage />
      </RequireAuth>
    ),
  },
  {
    path: '/source/:project',
    element: (
      <RequireAuth>
        <SourceProjectPage />
      </RequireAuth>
    ),
  },
  {
    path: '/source/:project/:entryId',
    element: (
      <RequireAuth>
        <SourceChapterPage />
      </RequireAuth>
    ),
  },

  // SQL 自由练习（WORK-032）：不计分、不写进度，与 /learn 并列。
  // 需要登录（执行用户写的 SQL 要能追溯），所以同样包在 RequireAuth 里。
  {
    path: '/practice/sql',
    element: (
      <RequireAuth>
        <SqlPracticePage />
      </RequireAuth>
    ),
  },
  // 每日速递（WORK-027）：当天有什么值得知道的事，一屏看完。
  {
    path: '/digest',
    element: (
      <RequireAuth>
        <DigestPage />
      </RequireAuth>
    ),
  },
  // 个人知识图谱（WORK-021）：2D 主入口，3D 是切换视角。
  {
    path: '/graph',
    element: (
      <RequireAuth>
        <GraphPage />
      </RequireAuth>
    ),
  },
  // 知识库是两级：先分类（卡片网格），点进去才是条目列表。
  // 原来把分类塞进一个 16rem 侧栏、条目挤在旁边，一屏又空又单调（用户反馈）。
  {
    path: '/knowledge',
    element: (
      <RequireAuth>
        <KnowledgeCategoriesPage />
      </RequireAuth>
    ),
  },
  {
    path: '/knowledge/entries',
    element: (
      <RequireAuth>
        <CategoryEntriesPage />
      </RequireAuth>
    ),
  },
  {
    path: '/knowledge/categories/:categoryId',
    element: (
      <RequireAuth>
        <CategoryEntriesPage />
      </RequireAuth>
    ),
  },
  {
    path: '/knowledge/entries/:id',
    element: (
      <RequireAuth>
        <KnowledgeEntryPage />
      </RequireAuth>
    ),
  },
  {
    path: '/notes',
    element: (
      <RequireAuth>
        <NotesPage />
      </RequireAuth>
    ),
  },
  // 新建是**独立的一页**而不是"点一下生成一条空的"：这里以后还要长模板、标签、AI 摘要
  {
    path: '/notes/new',
    element: (
      <RequireAuth>
        <NoteNewPage />
      </RequireAuth>
    ),
  },
  // AI 训练营（WORK-029）：简历 → 出卷 → 判分 → 计划 → 复测。
  // 「还没有学习记录的人」的入口，所以与 /learn 并列，不塞进知识库。
  {
    path: '/camp',
    element: (
      <RequireAuth>
        <CampListPage />
      </RequireAuth>
    ),
  },
  {
    path: '/camp/:id',
    element: (
      <RequireAuth>
        <CampDetailPage />
      </RequireAuth>
    ),
  },
  {
    path: '/interview',
    element: (
      <RequireAuth>
        <InterviewPage />
      </RequireAuth>
    ),
  },
  // 简历（WORK-018）。三条页面 + 一条打印视图：
  // 列表/导入 → 核对（未落库）→ 编辑 → 按岗位优化 → 打印。
  // 打印页**不包 AppShell**（它自己就是不带头尾的 sheet），但仍要求登录：
  // 它是"我的某个版本"的视图，不是公开分享页。
  {
    path: '/resume',
    element: (
      <RequireAuth>
        <ResumeListPage />
      </RequireAuth>
    ),
  },
  {
    path: '/resume/new',
    element: (
      <RequireAuth>
        <ResumeImportReviewPage />
      </RequireAuth>
    ),
  },
  {
    path: '/resume/create',
    element: (
      <RequireAuth>
        <ResumeCreatePage />
      </RequireAuth>
    ),
  },
  {
    path: '/resume/:id',
    element: (
      <RequireAuth>
        <ResumeEditorPage />
      </RequireAuth>
    ),
  },
  {
    path: '/resume/:id/optimize',
    element: (
      <RequireAuth>
        <ResumeOptimizePage />
      </RequireAuth>
    ),
  },
  // 主简历预览：不必先跑一次优化就能看版式、存 PDF
  {
    path: '/resume/:id/preview',
    element: (
      <RequireAuth>
        <ResumePreviewPage />
      </RequireAuth>
    ),
  },
  {
    path: '/resume/versions/:versionId/print',
    element: (
      <RequireAuth>
        <ResumePrintPage />
      </RequireAuth>
    ),
  },
  {
    path: '/profile',
    element: (
      <RequireAuth>
        <ProfilePage />
      </RequireAuth>
    ),
  },
  {
    path: '/my-assessments',
    element: (
      <RequireAuth>
        <MyAssessmentsPage />
      </RequireAuth>
    ),
  },
  {
    // MCP 接入凭据（WORK-019）：和"我的身份"一样属于设置，但它是外部助手的入口，
    // 单独一页比塞进 ProfilePage 清楚——那页讲的是推荐偏好，这页讲的是一把钥匙。
    path: '/settings/mcp',
    element: (
      <RequireAuth>
        <McpTokensPage />
      </RequireAuth>
    ),
  },
])

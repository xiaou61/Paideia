import { expect, test, type Page } from '@playwright/test'

import { ADMIN, ADMIN_ENTRY, LEARNER } from './env'

/**
 * 管理端知识库的端到端检查：**录入 → 预览 → 发布 → 学习者能看到**。
 *
 * <p>这条链路是本项的核心交付，也是唯一能把"管理端写、学习者读"两侧的契约同时钉住的地方。
 * 发布的动作就是一次把状态置为已发布的保存，管理端没有单独的发布接口。
 *
 * <p>数据用带时间戳的唯一名字，跑完删掉：种子库是共享的，重跑时不能让上一次的残留
 * （分类 slug 唯一）把自己顶掉。
 *
 * <p>分类与条目是**两个页面**（侧栏两个菜单项），所以这条链路要跨页走：先在分类管理建分类，
 * 切到条目管理录入与发布，最后回分类管理删掉造出来的分类（有条目挂在上面时会被拒）。
 *
 * <p>选择器跟着界面走：列表是表格（行是 `<tr>`），下拉是设计系统的 `Select`（不是原生
 * `<select>`，所以 `selectOption()` 用不了，要点开再选），删除有确认弹窗。
 */

async function signIn(page: Page, account: { identifier: string; password: string }) {
  await page.goto(ADMIN_ENTRY)
  await expect(page.getByTestId('admin-sign-in')).toBeVisible()
  await page.getByTestId('login-identifier').fill(account.identifier)
  await page.getByTestId('login-password').fill(account.password)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('admin-console')).toBeVisible()
}

/** 在侧栏菜单里切换页面。 */
async function openMenu(page: Page, label: string) {
  await page.getByRole('link', { name: label }).click()
}

/** 在表格里按文本定位一行：**限定在对应表格内**，因为条目行也会显示分类名。 */
function rowIn(page: Page, table: string, text: string) {
  return page.getByTestId(table).locator('tr', { hasText: text }).first()
}

test('非管理员进不了管理区', async ({ page }) => {
  await page.goto(ADMIN_ENTRY)
  await page.getByTestId('login-identifier').fill(LEARNER.identifier)
  await page.getByTestId('login-password').fill(LEARNER.password)
  await page.getByTestId('login-submit').click()

  await expect(page.getByTestId('admin-denied')).toBeVisible()
  await expect(page.getByTestId('admin-console')).toHaveCount(0)
})

test('管理员从首页进知识库管理区，完成录入到发布的链路', async ({ page }) => {
  const stamp = Date.now()
  const categoryName = `e2e 分类 ${stamp}`
  const entryTitle = `e2e 条目 ${stamp}`

  await signIn(page, ADMIN)

  // 门与路由：首页的入口真的能到分类管理
  await page.getByTestId('admin-categories-link').click()
  await expect(page.getByTestId('admin-category-list')).toBeVisible()

  // 建分类（弹窗表单）
  await page.getByTestId('admin-category-new').click()
  await page.getByTestId('admin-category-name').fill(categoryName)
  await page.getByTestId('admin-category-slug').fill(`e2e-${stamp}`)
  await page.getByTestId('admin-category-create').click()
  await expect(page.getByTestId('admin-category-list')).toContainText(categoryName)

  // 切到条目管理（分类与条目是两个页面）
  await openMenu(page, '条目管理')
  await expect(page.getByTestId('admin-entry-list')).toBeVisible()

  // 建草稿
  await page.getByTestId('admin-entry-new').click()
  await page.getByTestId('admin-entry-title').fill(entryTitle)
  // 分类下拉按学习线类型分组：所有学习线都是并列一级，平铺要滚十几屏；
  // 这条用例刚建的分类没有 lineKind，所以必须落在「其他分类」组里
  await page.getByTestId('admin-entry-category').click()
  await expect(page.getByText('其他分类', { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByTestId('admin-entry-body').fill('## 小节标题\n\n这是正文。')
  await page.getByTestId('admin-entry-save').click()

  const row = rowIn(page, 'admin-entry-list', entryTitle)
  await expect(row).toContainText('草稿')

  // 草稿预览：与学习者端共用**同一个 MarkdownField**（编辑/预览两个标签），
  // 因此这里能看到渲染后的标题，而不是 markdown 源码
  await row.getByRole('button', { name: '编辑' }).click()
  await page.getByTestId('admin-entry-tab-preview').click()
  await expect(page.getByTestId('admin-entry-preview')).toContainText('小节标题')
  await expect(page.getByTestId('admin-entry-preview')).not.toContainText('## 小节标题')
  await page.getByTestId('admin-entry-tab-edit').click()

  // 发布：把状态改成已发布再保存（设计系统的 Select：点开触发器再选，exact 避开"仅已发布"）
  await page.getByTestId('admin-entry-status').click()
  await page.getByRole('option', { name: '已发布', exact: true }).click()
  await page.getByTestId('admin-entry-save').click()
  await expect(rowIn(page, 'admin-entry-list', entryTitle)).toContainText('已发布')

  // 收尾：删掉本次造的数据（删除要过确认弹窗）
  await rowIn(page, 'admin-entry-list', entryTitle).getByRole('button', { name: '删除' }).click()
  await page.getByTestId('admin-delete-confirm').click()
  await expect(page.getByTestId('admin-entry-list')).not.toContainText(entryTitle)

  // 回分类管理删分类：分类下还有条目时删不掉，所以顺序是"先条目后分类"
  await openMenu(page, '分类管理')
  await expect(page.getByTestId('admin-category-list')).toBeVisible()
  await rowIn(page, 'admin-category-list', categoryName).getByRole('button', { name: '删除' }).click()
  await page.getByTestId('admin-delete-confirm').click()
  await expect(page.getByTestId('admin-category-list')).not.toContainText(categoryName)
})


/**
 * 源码解读块在管理端预览里也要能看见（WORK-014 AC-015）。
 *
 * <p>预览与学习者页共用同一个 `MarkdownField` / `Markdown`，所以这条用例真正钉住的是
 * **"块只在文档模式启用、预览也走文档模式"**：一旦有人把注册表接到流式分支上、或者预览
 * 换了另一条渲染路径，这里会红。
 *
 * <p>不依赖种子：临时造一条草稿，验完删掉。
 */
test('管理端预览能渲染源码解读的块', async ({ page }) => {
  const stamp = Date.now()
  const entryTitle = `e2e 源码解读 ${stamp}`

  await signIn(page, ADMIN)
  await openMenu(page, '条目管理')
  await expect(page.getByTestId('admin-entry-list')).toBeVisible()

  await page.getByTestId('admin-entry-new').click()
  await page.getByTestId('admin-entry-title').fill(entryTitle)
  const tour = {
    id: 'preview-demo',
    repo: 'facebook/react',
    commit: 'd75b0697',
    license: 'MIT',
    file: 'packages/react-reconciler/src/ReactFiberWorkLoop.js',
    steps: [{ title: '第一步', line: 2794, code: 'a();', explain: '解释。' }],
  }
  const body = [
    '介绍',
    '',
    '```code-tour',
    JSON.stringify(tour, null, 2),
    '```',
    '',
    '```quiz',
    JSON.stringify({ questions: [{ question: '问?', options: ['A', 'B'], answer: 0 }] }),
    '```',
  ].join('\n')
  await page.getByTestId('admin-entry-body').fill(body)
  await page.getByTestId('admin-entry-save').click()

  await rowIn(page, 'admin-entry-list', entryTitle).getByRole('button', { name: '编辑' }).click()
  await page.getByTestId('admin-entry-tab-preview').click()
  await expect(page.getByTestId('code-tour')).toBeVisible()
  await expect(page.getByTestId('quiz')).toBeVisible()
  // 预览里没有问 AI 的能力：按钮不该出现
  await expect(page.getByTestId('block-ask')).toHaveCount(0)

  // 关闭编辑弹窗回到列表，才能删掉本次造的数据
  await page.getByRole('button', { name: '取消' }).click()
  await rowIn(page, 'admin-entry-list', entryTitle).getByRole('button', { name: '删除' }).click()
  await page.getByTestId('admin-delete-confirm').click()
  await expect(page.getByTestId('admin-entry-list')).not.toContainText(entryTitle)
})

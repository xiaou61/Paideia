import { expect, test } from '@playwright/test'

import { signIn } from './session'

/**
 * MCP 接入凭据页（WORK-019 AC-013）。
 *
 * <p>验收点只有三条，都是"用户视角能观察到的"：创建后明文出现一次、刷新后只剩前缀、
 * 吊销后该行变成失效。
 *
 * <p>**不断言 token 的具体值**：它是随机的，断言只会让用例在别处变红。
 */
test('MCP 凭据：创建后明文只出现一次，刷新后只显示前缀', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/settings/mcp')

  await expect(page.getByText('MCP 接入')).toBeVisible()
  const name = `e2e-${String(Date.now())}`
  await page.getByTestId('mcp-token-name').fill(name)
  await page.getByTestId('mcp-token-create').click()

  // 明文只在创建成功那一刻出现，且以固定前缀开头
  const plaintext = page.getByTestId('mcp-token-plaintext')
  await expect(plaintext).toBeVisible()
  await expect(plaintext).toContainText('pd_mcp_')

  // 刷新后明文消失、列表里有这一条（只剩前缀）
  await page.reload()
  await expect(page.getByTestId('mcp-token-plaintext')).toHaveCount(0)
  await expect(page.getByTestId('mcp-token-row').filter({ hasText: name })).toBeVisible()
  await expect(page.getByTestId('mcp-token-row').filter({ hasText: name })).toContainText('pd_mcp_')
})

/**
 * 2026-10-22：权限原来只有「只读/写入」两档，工具组只能靠改 URL 挑。
 * 这条钉死"能按组勾选、且勾选结果回显在列表里"。
 */
test('MCP 凭据：可以只授权部分工具组，列表里回显勾了哪些', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/settings/mcp')

  const name = `scoped-${String(Date.now())}`
  await page.getByTestId('mcp-token-name').fill(name)
  await page.getByTestId('mcp-toolset-knowledge').click()
  await page.getByTestId('mcp-toolset-note').click()
  await page.getByTestId('mcp-token-create').click()
  await expect(page.getByTestId('mcp-token-plaintext')).toBeVisible()

  const row = page.getByTestId('mcp-token-row').filter({ hasText: name })
  await expect(row).toContainText('知识库')
  await expect(row).toContainText('笔记')
  // 没勾的组不该出现
  await expect(row).not.toContainText('学习进度')
})

test('MCP 凭据：名称留空时创建按钮禁用并给出说明', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/settings/mcp')

  const create = page.getByTestId('mcp-token-create')
  await expect(create).toBeDisabled()
  await expect(page.getByText('先填一个名称，下方的「创建凭据」才会亮起来。')).toBeVisible()

  await page.getByTestId('mcp-token-name').fill('tmp')
  await expect(create).toBeEnabled()
})

test('MCP 凭据：吊销后该行显示已失效', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/settings/mcp')

  const name = `revoke-${String(Date.now())}`
  await page.getByTestId('mcp-token-name').fill(name)
  await page.getByTestId('mcp-token-create').click()
  await expect(page.getByTestId('mcp-token-plaintext')).toBeVisible()

  // window.confirm 是原生弹窗：e2e 必须先接受它，否则点击会被挂住
  page.on('dialog', (dialog) => { void dialog.accept() })
  const row = page.getByTestId('mcp-token-row').filter({ hasText: name })
  await row.getByTestId('mcp-token-revoke').click()

  await expect(row).toContainText('已失效')
  await expect(row.getByTestId('mcp-token-revoke')).toHaveCount(0)
})

test('MCP 凭据：390px 宽下不出现横向滚动', async ({ page }) => {
  await signIn(page)
  await page.setViewportSize({ width: 390, height: 800 })
  await page.goto('/#/settings/mcp')

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  // 允许 1px 的亚像素误差；真溢出通常是几十像素
  expect(overflow).toBeLessThanOrEqual(1)
})

/**
 * WORK-038：管理工具组与 admin 权限范围只对管理员出现。
 *
 * <p>服务端不依赖前端隐藏（非管理员直接 POST `scopes:["admin"]` 会 400），但界面不该让人
 * 勾一个注定失败的选项。这里钉的是"普通人看不到"，管理员一侧由后端集成测试钉。
 */
test('MCP 凭据：非管理员看不到管理工具组与管理范围', async ({ page }) => {
  await signIn(page)
  await page.goto('/#/settings/mcp')

  await expect(page.getByTestId('mcp-toolset-knowledge')).toBeVisible()
  await expect(page.getByTestId('mcp-toolset-admin_content')).toHaveCount(0)
  await expect(page.getByTestId('mcp-toolset-admin_users')).toHaveCount(0)
  await expect(page.getByTestId('mcp-token-admin')).toHaveCount(0)
})

import { createAssistantApi, CreditsPanel } from '@paideia/assistant'
import { AppShell, PageHeader } from '@paideia/ui'
import { useMemo } from 'react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/**
 * 积分（WORK-066 起从助手面板搬出来的独立入口）。
 *
 * <p>记忆面板已随通用助手下线一并删除；这里只剩 AI 用量的余额与流水。
 */
export function SettingsPage() {
  const assistantApi = useMemo(() => createAssistantApi(api), [])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="积分"
        description="AI 用量的余额与流水。数据都还在，这里只是换了个入口。"
      />
      <section className="border-border bg-card max-w-2xl rounded-xl border p-4" data-testid="settings-credits">
        <h2 className="mb-3 text-sm font-medium">积分</h2>
        <CreditsPanel api={assistantApi} />
      </section>
    </AppShell>
  )
}

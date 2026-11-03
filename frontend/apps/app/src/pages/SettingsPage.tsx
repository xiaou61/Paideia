import { createAssistantApi, CreditsPanel, MemoryPanel } from '@paideia/assistant'
import { AppShell, PageHeader } from '@paideia/ui'
import { useMemo } from 'react'

import { api } from '../api'
import { LearnerHeader } from '../components/LearnerHeader'

/**
 * 记忆与积分（WORK-066）。
 *
 * <p>原来它们挤在助手面板的两个页签里；助手整体下线后，这两块需要自己看得见的位置。
 * 面板组件本身与具体接口无关（只吃 `AssistantApi`），这里直接复用，不重写。
 */
export function SettingsPage() {
  const assistantApi = useMemo(() => createAssistantApi(api), [])

  return (
    <AppShell title="Paideia" actions={<LearnerHeader />} wide>
      <PageHeader
        title="记忆与积分"
        description="助手记住的东西、以及 AI 用量的余额与流水。数据都还在，这里只是换了个入口。"
      />
      <div className="grid gap-6 lg:grid-cols-2">
        <section className="border-border bg-card rounded-xl border p-4" data-testid="settings-memory">
          <h2 className="mb-3 text-sm font-medium">记忆</h2>
          <MemoryPanel api={assistantApi} />
        </section>
        <section className="border-border bg-card rounded-xl border p-4" data-testid="settings-credits">
          <h2 className="mb-3 text-sm font-medium">积分</h2>
          <CreditsPanel api={assistantApi} />
        </section>
      </div>
    </AppShell>
  )
}

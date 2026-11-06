import { useSearchParams } from 'react-router'

import { AiRunsListPage } from './AiRunsListPage'
import { AiRunsOverviewPage } from './AiRunsOverviewPage'

/**
 * AI 调用（WORK-022；2026-11-06 做减法合并）。
 *
 * <p>原「调用概览 / 调用明细 / 调用大屏」三页合并成一页：概览与明细用页签切换，
 * 大屏按减法直接删除（它的信息与明细页的步骤瀑布重叠，且需要单独维护一套渲染）。
 *
 * <p>页签状态放在 URL 的 `tab` 参数里：从分析大屏或旧书签跳进来时能落到正确的视图。
 */
export function AiRunsPage() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'list' ? 'list' : 'overview'

  const choose = (next: 'overview' | 'list') => {
    setParams(next === 'list' ? { tab: 'list' } : {}, { replace: true })
  }

  return (
    <div className="space-y-4">
      <div
        role="tablist"
        aria-label="AI 调用视图"
        className="border-border bg-card inline-flex rounded-lg border p-1 text-sm"
      >
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'overview'}
          data-testid="ai-runs-tab-overview"
          onClick={() => choose('overview')}
          className={`rounded-md px-3 py-1 ${
            tab === 'overview' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'
          }`}
        >
          概览
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'list'}
          data-testid="ai-runs-tab-list"
          onClick={() => choose('list')}
          className={`rounded-md px-3 py-1 ${
            tab === 'list' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'
          }`}
        >
          调用明细
        </button>
      </div>

      {tab === 'overview' ? <AiRunsOverviewPage /> : <AiRunsListPage />}
    </div>
  )
}

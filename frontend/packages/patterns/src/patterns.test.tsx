import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'

import { DetailPage } from './detail-page'
import { FormWizard, type FormWizardStep } from './form-wizard'
import { ListPage, type ListPageColumn } from './list-page'

afterEach(cleanup)

interface Row {
  id: number
  name: string
  score: number
}

const columns: ListPageColumn<Row>[] = [
  { key: 'name', header: '名称', render: (row) => row.name },
  { key: 'score', header: '得分', align: 'right', render: (row) => row.score },
]

const rows: Row[] = [
  { id: 1, name: '甲', score: 90 },
  { id: 2, name: '乙', score: 80 },
]

describe('ListPage', () => {
  it('渲染页头、筛选与表格行', () => {
    render(
      <ListPage<Row>
        title="用例列表"
        description="共两行"
        filters={<span>筛选区</span>}
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
      />,
    )
    expect(screen.getByText('用例列表')).toBeTruthy()
    expect(screen.getByText('筛选区')).toBeTruthy()
    expect(screen.getByText('甲')).toBeTruthy()
    expect(screen.getByText('乙')).toBeTruthy()
    expect(screen.getByRole('table')).toBeTruthy()
  })

  it('空数据时展示空状态而不是表格', () => {
    render(
      <ListPage<Row>
        title="用例列表"
        columns={columns}
        rows={[]}
        getRowKey={(row) => row.id}
        emptyTitle="一条都没有"
      />,
    )
    expect(screen.getByText('一条都没有')).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('加载态优先于空态', () => {
    render(
      <ListPage<Row>
        title="用例列表"
        columns={columns}
        rows={[]}
        getRowKey={(row) => row.id}
        loading
        loadingLabel="正在拉取"
      />,
    )
    expect(screen.getByText('正在拉取')).toBeTruthy()
  })

  it('错误态展示错误并可重试', () => {
    const onRetry = vi.fn()
    render(
      <ListPage<Row>
        title="用例列表"
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.id}
        error={new Error('boom')}
        onRetry={onRetry}
      />,
    )
    expect(screen.getByText('加载失败')).toBeTruthy()
    fireEvent.click(screen.getByTestId('error-retry'))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})

describe('DetailPage', () => {
  it('渲染页头与每个分块', () => {
    render(
      <DetailPage
        title="详情标题"
        sections={[
          { key: 'basic', title: '基本信息', content: <span>内容一</span> },
          { key: 'meta', title: '元数据', content: <span>内容二</span> },
        ]}
      />,
    )
    expect(screen.getByText('详情标题')).toBeTruthy()
    expect(screen.getByText('基本信息')).toBeTruthy()
    expect(screen.getByText('内容一')).toBeTruthy()
    expect(screen.getByText('内容二')).toBeTruthy()
  })
})

describe('FormWizard', () => {
  const steps: FormWizardStep[] = [
    { key: 'a', title: '第一步' },
    { key: 'b', title: '第二步' },
  ]

  it('展示当前步骤并可下一步', () => {
    const onNext = vi.fn()
    render(
      <FormWizard
        steps={steps}
        current={0}
        renderStep={(step) => <div>{`正在填 ${step.title}`}</div>}
        onNext={onNext}
      />,
    )
    expect(screen.getByText('正在填 第一步')).toBeTruthy()
    fireEvent.click(screen.getByText('下一步'))
    expect(onNext).toHaveBeenCalledTimes(1)
  })

  it('最后一步显示提交按钮', () => {
    const onSubmit = vi.fn()
    render(
      <FormWizard
        steps={steps}
        current={1}
        renderStep={(step) => <div>{step.title}</div>}
        onSubmit={onSubmit}
      />,
    )
    fireEvent.click(screen.getByText('提交'))
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })
})

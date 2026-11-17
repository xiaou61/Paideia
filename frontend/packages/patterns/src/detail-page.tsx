import type { ReactNode } from 'react'

import { Card, CardContent, PageHeader, Section } from '@paideia/ui'

export interface DetailPageSection {
  key: string
  title: string
  description?: ReactNode
  content: ReactNode
}

export interface DetailPageProps {
  title: string
  description?: ReactNode
  actions?: ReactNode
  /** 页头下方的信息分块；每块渲染成「标题 + 卡片内容」 */
  sections: DetailPageSection[]
}

/**
 * 详情页模板：页头 + 若干信息分块。
 *
 * <p>分块内容由调用方渲染（键值对、时间线、描述列表都行），模板只负责一致的分块结构与间距，
 * 让「详情页」长得一样，而不用每个页面重复写 Section + Card + PageHeader 的叠法。
 */
export function DetailPage({ title, description, actions, sections }: DetailPageProps) {
  return (
    <div>
      <PageHeader title={title} description={description} actions={actions} />
      {sections.map((section) => (
        <Section key={section.key} title={section.title} description={section.description}>
          <Card>
            <CardContent className="pt-6">{section.content}</CardContent>
          </Card>
        </Section>
      ))}
    </div>
  )
}

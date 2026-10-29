import { describe, expect, it } from 'vitest'

import { PAGE_CONTEXT_LIMITS, truncateContext, type PageContext } from './context'
import { extractSqlBlock, serializePageContext } from './page-context'

/**
 * 页面场景（WORK-035 T-15）。
 *
 * <p>三条要守的：
 * <ol>
 *   <li>**截断必须生效**——草稿可能几千行，整段塞进提示词既贵又可能送出不必要的内容；</li>
 *   <li>**不放数据行**——结果摘要只该是"报错原文"或行数；</li>
 *   <li>**取不到 SQL 代码块就返回 null，不猜**——把解释文字塞进编辑器比不做更糟。</li>
 * </ol>
 */

const CONTEXT: PageContext = {
  kind: 'sql-workbench',
  datasetKey: 'orders-public',
  draftSql: 'SELECT * FROM customers;',
  lastResult: '',
}

describe('truncateContext', () => {
  it('不超限时原样返回', () => {
    expect(truncateContext('short', 100)).toBe('short')
  })

  /** 超限时必须截断并**说明还有多少**——否则用户以为助手看完了全部。 */
  it('超限时截断并标注剩余长度', () => {
    const long = 'x'.repeat(250)
    const result = truncateContext(long, 100)
    expect(result.startsWith('x'.repeat(100))).toBe(true)
    expect(result).toContain('后面还有 150 个字符')
  })
})

describe('serializePageContext', () => {
  it('null 返回 undefined（不往请求体里放空字段）', () => {
    expect(serializePageContext(null)).toBeUndefined()
  })

  it('带上数据集与草稿', () => {
    const text = serializePageContext(CONTEXT)
    expect(text).toContain('orders-public')
    expect(text).toContain('SELECT * FROM customers;')
    // 必须说明"这是没提交的草稿"
    expect(text).toContain('没提交')
  })

  it('没有结果时不写结果段', () => {
    expect(serializePageContext(CONTEXT)).not.toContain('最近一次运行的结果')
  })

  it('有结果时写进去', () => {
    const text = serializePageContext({ ...CONTEXT, lastResult: '报错：Unknown column' })
    expect(text).toContain('最近一次运行的结果')
    expect(text).toContain('Unknown column')
  })

  /** 草稿超长必须被截断——这是"不要把整篇塞进提示词"的硬约束。 */
  it('超长草稿被截断', () => {
    const text = serializePageContext({ ...CONTEXT, draftSql: 'SELECT 1;\n'.repeat(1000) })
    expect(text!.length).toBeLessThan(PAGE_CONTEXT_LIMITS.draftSql + 500)
    expect(text).toContain('后面还有')
  })

  it('超长结果被截断', () => {
    const text = serializePageContext({ ...CONTEXT, lastResult: 'e'.repeat(5000) })
    expect(text!.length).toBeLessThan(PAGE_CONTEXT_LIMITS.lastResult + 700)
  })
})

describe('extractSqlBlock', () => {
  it('取出 sql 代码块', () => {
    expect(extractSqlBlock('你可以这样写：\n\n```sql\nSELECT 1;\n```\n\n这样更快。')).toBe('SELECT 1;')
  })

  it('认 mysql 围栏', () => {
    expect(extractSqlBlock('```mysql\nSELECT 2;\n```')).toBe('SELECT 2;')
  })

  it('大小写不敏感', () => {
    expect(extractSqlBlock('```SQL\nSELECT 3;\n```')).toBe('SELECT 3;')
  })

  it('取第一个代码块', () => {
    expect(extractSqlBlock('```sql\nfirst\n```\n\n```sql\nsecond\n```')).toBe('first')
  })

  /** 没有代码块返回 null——不能把整段解释文字塞进编辑器。 */
  it('没有代码块返回 null', () => {
    expect(extractSqlBlock('你这段 SQL 写得不错。')).toBeNull()
    expect(extractSqlBlock('')).toBeNull()
  })

  it('空代码块返回 null', () => {
    expect(extractSqlBlock('```sql\n\n```')).toBeNull()
  })

  /** 非 sql 语言的代码块不该被当成 SQL。 */
  it('非 sql 语言的代码块被忽略', () => {
    expect(extractSqlBlock('```java\nSystem.out.println(1);\n```')).toBeNull()
  })
})

describe('学习记录页上下文（WORK-053）', () => {
  const studyLog: PageContext = {
    kind: 'study-log',
    lines: 'Java 从零开始 3/37，下一关 循环与方法',
    failing: '第 12 关 数组与字符串（挑战 1/3）',
    dueReviews: '依赖注入、事务边界',
    weakConcepts: '并发可见性 0.31；索引最左前缀 0.42',
  }

  it('四块都进提示词，且标明是平台算出的事实', () => {
    const text = serializePageContext(studyLog) ?? ''
    expect(text).toContain('页面：学习记录')
    expect(text).toContain('3/37')
    expect(text).toContain('第 12 关')
    expect(text).toContain('依赖注入')
    expect(text).toContain('0.31')
  })

  it('按 studyLogBlock 上限截断并标注剩余长度', () => {
    const long = '甲'.repeat(PAGE_CONTEXT_LIMITS.studyLogBlock + 50)
    const text = serializePageContext({ ...studyLog, lines: long }) ?? ''
    expect(text).toContain(`后面还有 50 个字符没显示`)
  })

  it('不把敏感字段带进上下文——没有邮箱与令牌的痕迹', () => {
    const text = serializePageContext(studyLog) ?? ''
    expect(text).not.toMatch(/@\w+\./)
    expect(text).not.toContain('token')
    expect(text).not.toContain('password')
  })
})

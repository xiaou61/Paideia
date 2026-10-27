import type { ApiClient } from '@paideia/core'

/**
 * MCP 凭据（WORK-019）。
 *
 * 三个端点、一个纪律：**创建响应里是明文唯一一次出现的地方**。列表只有前缀，
 * 服务端不保存明文——刷新之后拿不回来，这是设计而不是缺陷。
 */
export interface McpToken {
  id: number
  name: string
  prefix: string
  scopes: string[]
  /** 工具组白名单；空数组 = 不限制（与改动前的凭据一致）。 */
  toolsets: string[]
  /** 每日调用上限；null = 不限（WORK-044）。 */
  dailyCallLimit: number | null
  /** 今日已调用次数（服务端按 UTC 算，与配额判定同一处）。 */
  callsToday: number
  /** 历史累计调用与失败次数。 */
  totalCalls: number
  totalErrors: number
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  createdAt: string | null
  active: boolean
}

export interface CreatedMcpToken {
  id: number
  token: string
  prefix: string
  scopes: string[]
  toolsets: string[]
  dailyCallLimit: number | null
  expiresAt: string | null
}

export interface McpTokenInput {
  name: string
  scopes: string[]
  /** 工具组白名单；不传或空数组 = 全部工具组。 */
  toolsets?: string[]
  /** 0 表示永不过期；不传走服务端默认（90 天）。 */
  expiresInDays?: number
  /** 每日调用上限；0 或不传 = 不限。 */
  dailyCallLimit?: number
}

export interface McpTokensApi {
  list(): Promise<McpToken[]>
  create(input: McpTokenInput): Promise<CreatedMcpToken>
  revoke(id: number): Promise<void>
}

export function createMcpTokensApi(client: ApiClient): McpTokensApi {
  return {
    list: () => client.requestEnvelope<McpToken[]>('/api/v1/ai/mcp-tokens'),
    create: (input) =>
      client.requestEnvelope<CreatedMcpToken>('/api/v1/ai/mcp-tokens', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),
    revoke: async (id) => {
      await client.requestEnvelope<null>(`/api/v1/ai/mcp-tokens/${id}`, { method: 'DELETE' })
    },
  }
}

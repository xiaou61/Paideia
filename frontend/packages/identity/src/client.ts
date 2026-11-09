import type { ApiClient } from '@paideia/core'

import type { LearnerProfile, LearnerProfileInput } from './types'
import { JSON_HEADERS } from '@paideia/core'

/**
 * 身份与画像的客户端（WORK-010）。
 *
 * <p>路径只有一条：`/api/v1/account/profile`。**它不传 userId**——归属一律由令牌决定，
 * 所以这个客户端在构造上就调不到别人的画像。
 */
export interface IdentityApi {
  profile(): Promise<LearnerProfile>
  saveProfile(input: LearnerProfileInput): Promise<LearnerProfile>
}

export function createIdentityApi(client: ApiClient): IdentityApi {
  return {
    profile: () => client.requestEnvelope<LearnerProfile>('/api/v1/account/profile'),
    saveProfile: (input) =>
      client.requestEnvelope<LearnerProfile>('/api/v1/account/profile', {
        method: 'PUT',
        headers: JSON_HEADERS,
        body: JSON.stringify(input),
      }),
  }
}

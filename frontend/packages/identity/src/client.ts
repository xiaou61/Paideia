import type { ApiClient } from '@paideia/core'

import type { Identity, LearnerProfile, LearnerProfileInput } from './types'
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

/** 身份的可读名。后端也给了 `identityLabel`，这份是给"还没有画像时"的兜底显示用。 */
export const IDENTITY_LABELS: Record<Identity, string> = {
  job_seeker: '找工作',
  postgrad: '考研',
  civil_service: '考公',
  daily_learning: '日常学习',
}

import type { Skin } from '../skins'

/** 霓虹暗夜族的注册信息（WORK-079）。颜色在 `styles/theme/skins/night.css`。 */
export const NIGHT_SKINS: readonly Skin[] = [
  {
    id: 'neon',
    name: '霓虹夜',
    summary: '黑底青紫两团光晕，卡面是发光描边',
    family: 'night',
    tags: ['暗色', '霓虹', '高饱和', '氛围'],
  },
  {
    id: 'midnight',
    name: '深蓝夜',
    summary: '深蓝底一层冷光，电光蓝主色，暗而不吵',
    family: 'night',
    tags: ['暗色', '蓝色', '冷静', '氛围'],
  },
]

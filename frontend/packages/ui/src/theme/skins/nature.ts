import type { Skin } from '../skins'

/** 护眼自然族的注册信息（WORK-079）。颜色在 `styles/theme/skins/nature.css`。 */
export const NATURE_SKINS: readonly Skin[] = [
  {
    id: 'forest',
    name: '森林',
    summary: '低饱和绿、不用纯黑，盯久了眼睛不紧',
    family: 'nature',
    tags: ['浅色', '绿色', '护眼', '低饱和'],
  },
  {
    id: 'sand',
    name: '沙丘',
    summary: '暖灰陶土色，像午后的水泥墙，柔和但不发黄',
    family: 'nature',
    tags: ['浅色', '暖色', '护眼', '低饱和'],
  },
  {
    id: 'ocean',
    name: '海洋',
    summary: '浅蓝灰底、青色主色，冷静清爽',
    family: 'nature',
    tags: ['浅色', '蓝色', '护眼', '清爽'],
  },
]

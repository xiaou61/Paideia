import type { Skin } from '../skins'

/** 粉彩族的注册信息（WORK-079）。颜色在 `styles/theme/skins/pastel.css`。 */
export const PASTEL_SKINS: readonly Skin[] = [
  {
    id: 'pastel',
    name: '粉彩',
    summary: '柔和的粉紫背景光斑、大圆角，正文仍是深色',
    family: 'pastel',
    tags: ['浅色', '粉彩', '柔和', '圆角'],
  },
  {
    id: 'sakura',
    name: '樱花',
    summary: '浅粉底、低饱和红主色，春天那种粉',
    family: 'pastel',
    tags: ['浅色', '粉色', '柔和', '季节'],
  },
  {
    id: 'candy',
    name: '糖果',
    summary: '奶油黄底配玫红按钮，明快但不过分',
    family: 'pastel',
    tags: ['浅色', '明快', '多彩', '圆角'],
  },
]

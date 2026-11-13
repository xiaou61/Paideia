import type { Skin } from '../skins'

/** 玻璃拟态族的注册信息（WORK-079）。颜色在 `styles/theme/skins/glass.css`。 */
export const GLASS_SKINS: readonly Skin[] = [
  {
    id: 'glass',
    name: '玻璃',
    summary: '半透明卡面压在冷色光斑上，卡片像浮在光里',
    family: 'glass',
    tags: ['浅色', '玻璃', '半透明', '冷调'],
  },
  {
    id: 'glass-warm',
    name: '暖玻璃',
    summary: '同样的半透明，光换成暖黄与粉',
    family: 'glass',
    tags: ['浅色', '玻璃', '半透明', '暖调'],
  },
]

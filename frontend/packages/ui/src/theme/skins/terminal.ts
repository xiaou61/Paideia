import type { Skin } from '../skins'

/** 终端像素族的注册信息（WORK-079）。颜色在 `styles/theme/skins/terminal.css`。 */
export const TERMINAL_SKINS: readonly Skin[] = [
  {
    id: 'terminal',
    name: '终端',
    summary: '全等宽字体、零圆角、硬边线，像在终端里学习',
    family: 'terminal',
    tags: ['暗色', '终端', '等宽', '像素'],
  },
]

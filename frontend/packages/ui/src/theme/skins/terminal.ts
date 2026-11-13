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
  {
    id: 'pixel',
    name: '像素',
    summary: '看得见的边线、实心下落的阴影、无圆角，复古游戏机手感',
    family: 'terminal',
    tags: ['像素', '等宽', '硬边框', '复古'],
  },
]

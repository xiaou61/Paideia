import type { Skin } from '../skins'

/**
 * 图版族（WORK-080）：这一族不只换色，还换**形状与排版**。
 *
 * <p>为什么单独一族：前面八个族改的是"同一套骨架换个配色和质感"，骨架始终是圆角卡片 +
 * 常规阴影 + 无衬线标题。这一族把骨架也换掉——零圆角的硬边块、衬线大标题、outset 立体边。
 * 三者都是"一眼就知道换了站"的强度，代价是每加一个都要人眼抽查布局没散（白名单测试
 * 只能证明没碰布局属性，证明不了"好不好看"）。
 *
 * <p>颜色在 `styles/theme/skins/graphic.css`。
 */
export const GRAPHIC_SKINS: readonly Skin[] = [
  {
    id: 'brutal',
    name: '粗野',
    summary: '纯黑白撞正黄、2px 硬边、无圆角、投影是实心方块偏移',
    family: 'brutalist',
    tags: ['亮色', '粗野主义', '高饱和', '硬边框', '零圆角', '海报'],
  },
  {
    id: 'editorial',
    name: '铅印',
    summary: '纸白底、衬线大标题、细线分隔、背景有排版网格',
    family: 'editorial',
    tags: ['亮色', '杂志', '衬线', '排版', '纸感', '长文'],
  },
  {
    id: 'win98',
    name: '九八',
    summary: '银灰窗体、立体凸边、深蓝标题栏、Tahoma 字体',
    family: 'retro',
    tags: ['复古', '90年代', '立体边框', '零圆角', '拟物'],
  },
]

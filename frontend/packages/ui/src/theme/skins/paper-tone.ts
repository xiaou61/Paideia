import type { Skin } from '../skins'

/**
 * 暖纸复古族的注册信息（WORK-079）。
 *
 * <p>这里的字段只服务"让人挑得出来"：分组、搜索、卡片上的一句话。真实颜色一律在
 * `styles/theme/skins/paper-tone.css` 里——**不要在 TS 里写颜色**：那会撞
 * `tokens.test.ts` 的"颜色只能来自令牌"，而且 TS 里的色板必然与 CSS 漂移。
 *
 * <p>`summary` 写给用户看，不写"低饱和""中性色阶"这类只有设计师读得懂的词。
 */
export const PAPER_TONE_SKINS: readonly Skin[] = [
  {
    id: 'paper',
    name: '暖纸',
    summary: '米色纸感、衬线标题，像在读一本印得不错的书',
    family: 'paper',
    tags: ['浅色', '纸感', '衬线', '阅读'],
  },
]

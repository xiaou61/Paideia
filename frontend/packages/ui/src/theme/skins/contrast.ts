import type { Skin } from '../skins'

/**
 * 高对比无障碍族的注册信息（WORK-079）。颜色在 `styles/theme/skins/contrast.css`。
 *
 * <p>只有一个皮肤，但它值得单独一族：这一族的目标不是"风格"，而是"在低视力、
 * 强光下、廉价屏幕上也读得清"。它也是整套体系的下限测试。
 */
export const CONTRAST_SKINS: readonly Skin[] = [
  {
    id: 'contrast',
    name: '高对比',
    summary: '纯黑纯白、边线看得见、关掉所有装饰，只求读得清',
    family: 'contrast',
    tags: ['无障碍', '高对比', '黑白', '阅读'],
  },
]

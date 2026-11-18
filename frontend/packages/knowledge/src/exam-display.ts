/**
 * 难度标签的显示文本（WORK-083）。
 *
 * <p>后端 `question_index.difficulty_label` 与难度统计的 `label` 都是小写
 * `easy | medium | hard`（见 `DifficultyLabel`）；这里大小写无关，顺带容忍历史/外部
 * 传入的大写值，避免「大小写不一致导致徽标永不渲染」。
 */
export function difficultyText(label: string | null | undefined): string {
  switch ((label ?? '').toLowerCase()) {
    case 'easy':
      return '易'
    case 'medium':
      return '中'
    case 'hard':
      return '难'
    default:
      return ''
  }
}

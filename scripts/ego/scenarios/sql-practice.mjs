export const meta = { module: 'sql-practice', side: 'learner' }

export const cases = [
  {
    id: 'sql-practice-page',
    title: 'SQL 练习：页面可开，编辑器与运行按钮在',
    write: false,
    async run(s) {
      await s.step('打开 SQL 练习', async () => {
        await s.goto('/#/practice/sql')
        await s.expectText('写一段 SQL')
      })
      await s.step('编辑器与运行按钮可见', async () => {
        await s.waitFor('[data-testid="sql-practice-editor"]')
        await s.waitFor('[data-testid="sql-practice-run"]')
      })
    },
  },
  {
    id: 'sql-practice-run',
    title: 'SQL 练习：执行 SELECT 1 能看到结果表',
    write: false,
    async run(s) {
      await s.goto('/#/practice/sql')
      await s.waitFor('[data-testid="sql-practice-editor"]')
      await s.step('输入并运行', async () => {
        await s.focus('[data-testid="sql-practice-editor"] .cm-content')
        await s.hotkey('Control+A')
        await s.type('SELECT 1')
        await s.clickCss('[data-testid="sql-practice-run"]', '运行 SQL')
        await s.waitFor('[data-testid="result-table"]', 'visible', 30_000)
      })
    },
  },
  {
    id: 'sql-practice-error',
    title: 'SQL 练习：坏语句给错误提示而不是白屏',
    write: false,
    async run(s) {
      await s.goto('/#/practice/sql')
      await s.waitFor('[data-testid="sql-practice-editor"]')
      await s.step('输入坏语句并运行', async () => {
        await s.focus('[data-testid="sql-practice-editor"] .cm-content')
        await s.hotkey('Control+A')
        await s.type('SELECT FROM')
        await s.clickCss('[data-testid="sql-practice-run"]', '运行 SQL')
        await s.waitFor('[data-testid="sql-practice-error"]', 'visible', 30_000)
      })
    },
  },
]

---
artifact: test-plan
work_id: WORK-051
work: 手绘风配图（sketch 围栏块）
status: ready
created: 2026-10-28
updated: 2026-10-28
---

# 手绘风配图（sketch 围栏块）测试计划

## 测试范围

新增的 `sketch` 围栏块：契约解析、布局、绘制确定性、组件降级与字体时序、主题配色，
以及"只在长文生效"这条边界。不覆盖 mermaid、mermaid handDrawn、其他围栏块。

## 环境与前置条件

- 前端单测与类型检查在 `frontend/` 内即可，无需数据库。
- e2e 需要后端 jar（`mvn -B -DskipTests -pl paideia-app -am package`）与一个**独占**测试库，
  一律走 `scripts/test-db.sh`；端口用 `PAIDEIA_BACKEND_PORT` / `PAIDEIA_APP_PORT` 覆写，
  避免与并发会话共用。
- 深浅两套截图由一条本地页面产出（真字体、真 canvas 量宽、真 rough.js），用于人工看观感与对比度。

## 验收矩阵

| AC | 检查手段 | 预期 |
| --- | --- | --- |
| AC-001 框数=节点数、箭头数=连线条数 | e2e 断言 `[data-node]` 计数 + 单测断言 `data-node` | 3 个节点 / 2 条箭头 |
| AC-002 手写字体生效、缺字回退不出方框 | e2e 读 `getComputedStyle(svg).fontFamily` 与 `document.fonts.check` | 含 `Paideia Sketch` 且 `check` 为真 |
| AC-003 行内等距居中 | `sketch-layout.test.ts` | 间距差 ≤ 1px |
| AC-004 两次刷新 `path` 的 `d` 一致 | e2e 刷新前后取 `d` 数组比较 | 完全相等 |
| AC-005 坏 JSON 降级 | `sketch.test.tsx` | 出现 `block-fallback` 且含"不是合法 JSON" |
| AC-006 流式不执行 sketch | `markdown.test.tsx` | 无 `[data-testid=sketch-block]` |
| AC-007 双主题对比度 ≥ 4.5:1 | 令牌值直接算 WCAG 比值 + 两张截图人工看 | 全部 ≥ 4.5 |
| AC-008 入口 chunk 体积持平 | 构建一次基线（撤下改动）与一次实施后，比较 gzip | **实测 +2.42 kB，未达标** |
| AC-009 单测覆盖解析/布局/降级 | `pnpm --filter @paideia/ui test sketch` | 全部通过 |
| AC-010 样例与 e2e | devdata 条目 61 + `e2e/sketch.spec.ts` | 1 passed |

## 自动化检查

```bash
pnpm --filter @paideia/ui typecheck
pnpm --filter @paideia/ui test sketch
pnpm --filter @paideia/ui test                        # 全量，含既有用例回归
PAIDEIA_BACKEND_PORT=8091 PAIDEIA_APP_PORT=5181 \
  scripts/test-db.sh pnpm --filter @paideia/app exec playwright test e2e/sketch.spec.ts
pnpm build                                            # 体积
```

## 人工检查

- 深浅两套主题各看一张图（`testing/sketch-light.png`、`testing/sketch-dark.png`）：
  框是否像手画、中文是否手写、箭头是否指向正确、标签有没有压线。

## 回归范围

- `@paideia/ui` 全量单测（227 条）：确认新增块没有影响既有五种块与 markdown 管线。
- 学习者端只跑本项新增的那一条 e2e：改动落在共享包但**不改**既有渲染路径，
  不跑全量 61 条（AGENTS.md 的 e2e 选取口径）。

## 已知缺口

- AC-005 的"正文其它段落不受影响"只有组件级证据，没有 e2e 覆盖（e2e 只验正常渲染路径）。
- 手写中文在读者实际阅读尺寸下的可读性没有读者样本，只有开发侧截图判断。

-- 手绘图围栏块（WORK-051）的样板条目。
--
-- 只加载在 e2e 的测试库里（本目录不在生产 Flyway locations 中）。
-- 这里刻意只放**一个** sketch 块：e2e 要断言"框数 = 节点数"，多块会让计数变成两块的合计。
--
-- 内容口径：讲清这张图是代码画的，改文案只改 JSON；顺带演示 tone 与跨行。

INSERT INTO knowledge_categories (id, parent_id, name, slug, sort_order, created_at, updated_at)
VALUES (61, NULL, '手绘图示例', 'sketch-demo', 90, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO knowledge_entries (id, category_id, title, body, status, published_at, created_at, updated_at)
VALUES (61, 61, '一张手绘图是怎么画出来的',
CONCAT(
'## 这张图不是截图

下面的图由一段 JSON 生成：框的位置由文字量出来，歪线和手写体由渲染器加上去。
改文案只要改 JSON，不用打开任何画图软件。

```sketch
{
  "title": "agent 是怎么看代码的",
  "rows": [
    [
      { "id": "a", "title": "上下文装不下", "sub": "整个仓库读不完", "tone": "gray" },
      { "id": "b", "title": "grep 定位", "sub": "只读那几百行", "tone": "violet" },
      { "id": "c", "title": "用完就丢", "sub": "需要时再检索", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "a", "to": "b" },
    { "from": "b", "to": "c" }
  ]
}
```

## 写坏了会怎样

不会白屏。块会显示"这块内容的数据格式有误"加一条能定位到字段的原因，并把原始 JSON 一起给你，
复制回去改就行。
'),
'published', '2026-01-03 00:00:00.000', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

---
title: 草稿为什么读不出来：过滤写在 SQL 里
category: code-reading
order: 3
minutes: 15
level: intermediate
status: published
---

## 这一关要读什么

同一条查询里做「存在性 + 可见性」，而不是查出来再在代码里判断。

```sketch
{
  "title": "过滤写在 SQL 里，别查出来再判",
  "rows": [
    [
      { "id": "sql", "title": "WHERE 带可见性条件", "sub": "一次就筛干净", "tone": "blue" },
      { "id": "code", "title": "查出来再 if", "sub": "不该给的数据已经出库了", "tone": "amber" },
      { "id": "risk", "title": "列表接口最容易漏判", "sub": "一处漏就等于公开", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "sql", "to": "code" },
    { "from": "code", "to": "risk" }
  ]
}
```

代码取自仓库 `xiaou61/Paideia` 的提交 `93989e0`，点标题旁的链接能对上原文与行号。

```code-tour
{
 "id": "tour-entry-3",
 "title": "条目查询：WHERE 里带状态",
 "repo": "xiaou61/Paideia",
 "commit": "93989e0",
 "license": "保留所有权利（本项目）",
 "file": "backend/paideia-knowledge/src/main/java/io/github/xiaou61/knowledge/internal/entry/EntryMapper.java",
 "steps": [
  {
   "title": "草稿为什么读不出来：过滤写在 SQL 里",
   "line": 82,
   "code": "    @Select(\"\"\"\n            SELECT id, category_id, sort_order, title, body, source_url, fingerprint, status,\n                   published_at, created_at, updated_at\n            FROM knowledge_entries WHERE id = #{id} AND status = 'published'\n            \"\"\")\n    Optional<Entry> findPublishedById(@Param(\"id\") Long id);",
   "language": "java",
   "explain": "`WHERE id = #{id} AND status = 'published'` 一次完成两件事：按主键取行、并且只接受已发布的。把 `status` 判断挪到 Java 里，就会出现「先查出来再判断」的写法——多一次网络往返、多一份草稿正文进了进程内存，而且很容易在别处漏掉这个判断。"
  }
 ]
}
```

## 检查一下

```code-challenge
{
 "id": "read-entry-3-fill",
 "kind": "fill",
 "title": "补全可见性条件",
 "language": "java",
 "template": "            FROM knowledge_entries WHERE id = #{id} AND status = {{0}}\n            \"\"\")\n    Optional<Entry> findPublishedById(@Param(\"id\") Long id);",
 "options": [
  [
   "'draft'",
   "'published'",
   "1"
  ]
 ],
 "answers": [
  1
 ],
 "explain": "方法名里的 Published 与 SQL 里的 `status = 'published'` 必须一致；名字说 published 而 SQL 不筛，是最容易骗过 code review 的一类错。"
}
```


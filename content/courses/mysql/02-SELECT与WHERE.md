---
title: 02 · SELECT 与 WHERE：按条件取数
category: mysql-from-zero
order: 2
chapter: 入门
level: beginner
minutes: 22
prerequisite: 01 · 装库与第一张表
status: published
---

# 02 · SELECT 与 WHERE：按条件取数

## 这一课你要会

- 用比较、逻辑组合、`IN`、`LIKE`、`BETWEEN` 把需要的行挑出来；
- 说清 `WHERE score = NULL` 为什么一行都查不到；
- 知道 `AND` 和 `OR` 混用时为什么必须加括号。

## 为什么

真实的表有几百万行，而一次请求通常只要几十行。`WHERE` 就是那个"把范围缩小"的动作。

它看起来简单，但它是 SQL 里最容易写出**静默错误**的地方：语句不报错、结果也不为空、只是数量不对。这类 bug 比报错的 bug 危险得多。

## 讲解

### 比较：`=` `<>` `>` `<` `>=` `<=`

```sql
SELECT id, name FROM products WHERE price < 200;
```

注意"不等于"有两个写法：`<>` 和 `!=`。MySQL 两个都支持，SQL 标准是 `<>`。

### 逻辑组合：`AND` / `OR` / `NOT`

`AND` 要求两边都成立，`OR` 只要一边成立。

**优先级陷阱**：`AND` 的优先级高于 `OR`。所以

```sql
WHERE stock > 0 OR price > 1000 AND note = '促销中'
```

实际等价于 `stock > 0 OR (price > 1000 AND note = '促销中')`，而不是你想要的 `(stock > 0 OR price > 1000) AND note = '促销中'`。

**规则：混用 `AND` 和 `OR` 时一律加括号。** 这不是风格问题，是正确性问题，而且它不会报错。

### `IN`：等于其中任意一个

```sql
WHERE note IN ('促销中', '清仓')
```

等价于 `note = '促销中' OR note = '清仓'`，但更短、更容易读，而且列表可以用参数逐个绑定。

`NOT IN` 有一个致命陷阱：**如果列表里有 `NULL`，整个条件对所有行都返回"未知"，结果一行都查不到。** 下一关会细讲。

### `LIKE`：模糊匹配

| 写法 | 含义 |
| --- | --- |
| `LIKE '键盘%'` | 以"键盘"开头 |
| `LIKE '%线'` | 以"线"结尾 |
| `LIKE '%数据%'` | 包含"数据" |
| `LIKE '_盘'` | 正好两个字，第二个是"盘" |

`%` 匹配任意长度（含 0 个字符），`_` 匹配正好一个字符。

**性能要点**：以 `%` 开头的模式（`'%线'`）用不上普通索引，只能全表扫描。原因很直白——索引按前缀排序，你不知道前缀就无从定位。数据量大时要靠全文索引或搜索引擎，不是靠调参。

### `BETWEEN`：闭区间

```sql
WHERE price BETWEEN 100 AND 300
```

含两个端点，等价于 `price >= 100 AND price <= 300`。这个"含端点"经常被人记反。

对时间列用 `BETWEEN` 要格外小心：`BETWEEN '2026-01-01' AND '2026-01-31'` 会漏掉 1 月 31 日当天带时分的记录，因为 `'2026-01-31'` 被当成 `2026-01-31 00:00:00`。正确写法是用左闭右开：

```sql
WHERE created_at >= '2026-01-01' AND created_at < '2026-02-01'
```

### `NULL`：三值逻辑

SQL 的比较结果是三种：真、假、**未知**。`NULL` 参与任何比较，结果都是"未知"，而 `WHERE` 只保留"真"的行。

```sketch
{
  "title": "跟 NULL 比，结果既不是真也不是假",
  "rows": [
    [
      { "id": "cmp", "title": "age > 18", "sub": "这一行的 age 是 NULL", "tone": "gray" },
      { "id": "unk", "title": "结果是 UNKNOWN", "sub": "不是 TRUE 也不是 FALSE", "tone": "amber" },
      { "id": "filter", "title": "WHERE 只留 TRUE", "sub": "这一行被过滤掉", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "cmp", "to": "unk" },
    { "from": "unk", "to": "filter" }
  ]
}
```

所以：

```sql
WHERE note = NULL      -- 一行都查不到
WHERE note <> NULL     -- 一行都查不到
WHERE note IS NULL     -- 正确写法
WHERE note IS NOT NULL -- 不为空的
```

这也解释了 `NOT IN` 的陷阱：`x NOT IN (1, NULL)` 等价于 `x <> 1 AND x <> NULL`，后半句是"未知"，整个条件就永远不成立。

## 跑一遍

<!-- run -->
```sql
CREATE TABLE products (
  id    INT PRIMARY KEY AUTO_INCREMENT,
  name  VARCHAR(30) NOT NULL,
  price DECIMAL(8,2) NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  note  VARCHAR(20)
);
INSERT INTO products (name, price, stock, note) VALUES
  ('键盘',   199.00, 12, NULL),
  ('鼠标',    89.50,  0, '促销中'),
  ('显示器', 1099.00,  3, NULL),
  ('耳机',   299.00,  8, '促销中'),
  ('数据线',  19.90, 50, NULL);

SELECT name, price FROM products WHERE price < 200;
SELECT name FROM products WHERE price BETWEEN 100 AND 300;
SELECT name FROM products WHERE name LIKE '%线';
SELECT name, price FROM products WHERE stock > 0 AND price < 300;
SELECT name FROM products WHERE note IN ('促销中');
SELECT COUNT(*) AS wrong_way FROM products WHERE note = NULL;
SELECT COUNT(*) AS right_way FROM products WHERE note IS NULL;
```
```text
name=键盘 | price=199.00
name=鼠标 | price=89.50
name=数据线 | price=19.90
name=键盘
name=耳机
name=数据线
name=键盘 | price=199.00
name=耳机 | price=299.00
name=数据线 | price=19.90
name=鼠标
name=耳机
wrong_way=0
right_way=3
```

最后两行是这一关最重要的一段：同样想找"没有备注"的商品，`= NULL` 得到 0 行，`IS NULL` 得到 3 行。**语句不报错，只是结果不对。**


## 动手

这道题会在真实的 MySQL 里跑。写错不会扣分，可以反复试——
但**结果对不上会明确告诉你哪个数据集不一致**。

```code-challenge
{
  "id": "my-02-write",
  "kind": "sql-write",
  "title": "列出「没有填写备注」的客户",
  "goal": "能用 IS NULL 取空值，并知道 = NULL 与空字符串都不是它",
  "trap": "`WHERE` 写错了会报错，能马上发现",
  "dataset": [
    "orders-public",
    "orders-hidden-a"
  ],
  "normalize": {
    "rowOrder": "ignore",
    "columnOrder": "ignore"
  },
  "expectHash": {
    "orders-public": "f36c0a2c4282fe8e84bf06cee06c54a3b51f5629693692f29b58c75c424722b8",
    "orders-hidden-a": "8dbe3fca6e0138046069d108f907ba7a4c704b5a8765f9b920caca9d09e9997f"
  },
  "explain": "NULL 参与任何比较的结果都是「未知」，WHERE 只保留为真的行，所以 = NULL 永远返回空。空字符串也不是 NULL：它是一格有值但长度为零的数据。数据集里两者刻意各有一条。",
  "starter": "SELECT\n  \nFROM ;"
}
```

## 检查一下

```quiz
{"id":"my-02-quiz","title":"条件筛选三问","questions":[
 {"question":"WHERE note = NULL 会返回什么？","options":["所有 note 为空的行","一行都没有，因为比较结果是未知"],"answer":1,"explain":"NULL 参与任何比较都得未知，而 WHERE 只保留结果为真的行。要用 IS NULL。"},
 {"question":"WHERE a OR b AND c 的实际含义是？","options":["(a OR b) AND c","a OR (b AND c)","a AND b OR c","取决于数据库版本"],"answer":1,"explain":"AND 的优先级高于 OR，所以不加括号时会先算 b AND c。"},
 {"question":"LIKE '%线' 为什么用不上普通索引？","options":["因为字符串比较慢","因为索引按前缀排序，以通配符开头时无法定位起点","因为 LIKE 不支持索引","因为数据量太大"],"answer":1,"explain":"普通 B+ 树索引依赖前缀定位；以 % 开头就无法确定从哪一段开始扫。"}
]}
```

## 常见误区

### 误区：`WHERE` 写错了会报错，能马上发现

**你以为**：语法错误会让语句失败，所以不用太小心。
**其实**：`= NULL`、`NOT IN` 里带 `NULL`、漏括号，全都是**合法的 SQL**，只会让结果悄悄变少或变多。
**怎么验证**：写出条件后，用 `SELECT COUNT(*)` 跑一次，再手工算一遍预期行数，对不上就查。

### 误区：`BETWEEN` 是左闭右开

**你以为**：`BETWEEN a AND b` 不含 b。
**其实**：两端都含。对时间列尤其容易漏最后一天的数据。
**怎么验证**：插一条时间正好等于上界的记录，看 `BETWEEN` 是否把它查出来。

### 误区：`LIKE '%词%'` 灵活又好用，可以随便用

**你以为**：模糊搜索就该这么做。
**其实**：它必然全表扫描。百万行表上一次可能就是几百毫秒，并发一上来直接打满。
**怎么验证**：用 `EXPLAIN` 看 `type`，会看到 `ALL`（全表扫描）。

## 小结

`WHERE` 把范围缩小。比较、`IN`、`LIKE`、`BETWEEN` 覆盖了绝大多数条件。三件事必须记住：混用 `AND`/`OR` 加括号、`NULL` 要用 `IS NULL`、`BETWEEN` 是闭区间。这三个坑都不报错，只让结果不对。

下一关：把结果排序、切成页，以及 `DISTINCT` 真正的作用范围。

**延伸阅读**：[MySQL 8.0 参考手册 · 运算符与函数](https://dev.mysql.com/doc/refman/8.0/en/functions.html)（`LIKE`、`IN`、`BETWEEN` 的完整语义与 NULL 处理）。

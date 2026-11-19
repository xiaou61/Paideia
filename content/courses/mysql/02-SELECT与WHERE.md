---
title: 02 · SELECT 与 WHERE：按条件取数
category: mysql-from-zero
order: 2
chapter: 入门
level: beginner
minutes: 18
prerequisite: 01 · 装库与第一张表
status: published
---

# 02 · SELECT 与 WHERE：按条件取数

同一张 `products` 表，想找「没有备注」的商品：

```text
WHERE note = NULL      -> wrong_way=0
WHERE note IS NULL     -> right_way=3
```

`WHERE note = NULL` 匹配 0 行；`WHERE note IS NULL` 匹配 3 行。两条都合法，MySQL 不会报错。先把表建出来，再逐条对照。

## 跑一遍

下面这一整段会建 `products`、插入 5 行，再按顺序跑 7 条查询。输出里的 `列=值 | 列=值` 是课程统一格式，不是 MySQL 客户端的样子；客户端给的是表格。7 条查询一共 13 行输出，不是一张表。

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

按出现顺序，7 条查询与输出行的对应关系是：

| # | 查询在做什么 | 输出 |
| --- | --- | --- |
| 1 | `price < 200` | 3 行：键盘、鼠标、数据线 |
| 2 | `price BETWEEN 100 AND 300` | 2 行：键盘、耳机 |
| 3 | `name LIKE '%线'` | 1 行：数据线 |
| 4 | `stock > 0 AND price < 300` | 3 行：键盘、耳机、数据线 |
| 5 | `note IN ('促销中')` | 2 行：鼠标、耳机 |
| 6 | `note = NULL` 计数 | `wrong_way=0` |
| 7 | `note IS NULL` 计数 | `right_way=3` |

第 6、7 条是这一课的主线：同一个意图、同一张表，一个返回 0，一个返回 3。语句不报错，结果也不为空，只是数量不对。

## `= NULL` 不是「等于空」

`NULL` 在 SQL 里不是「一个空字符串」，是「没有值」。普通比较运算符 `=`、`<>`、`<`、`>`、`<=`、`>=` 遇到它，结果不是 TRUE 也不是 FALSE，而是第三种值：UNKNOWN。`WHERE` 只保留结果为 TRUE 的行，所以 `note = NULL` 匹配 0 行。

但 `IS NULL` 不是普通比较。它是专门判断空值的谓词：

- `NULL IS NULL` 返回 1；
- `NULL IS NOT NULL` 返回 0；
- `NULL <=> NULL` 返回 1，`1 <=> NULL` 返回 0（`<=>` 是 NULL 安全的等于）。

所以「NULL 参与任何比较都是未知」这句话不准确。准确的说法是：普通比较运算符遇到 NULL 才是 UNKNOWN；`IS NULL`、`IS NOT NULL`、`<=>` 是专门处理 NULL 的谓词，不会被 NULL 传染。

`note = ''` 又是另一回事。空字符串是一个有值、长度为 0 的字符串；`= ''` 会命中它，不会命中 NULL。`products` 这 5 行里没有空串，所以 `note = ''` 是 0 行；共享数据集里的 bob 就是空串，到动手题时会看到区别。

`NOT IN` 的坑也在这里。`x NOT IN (1, NULL)` 等价于 `x <> 1 AND x <> NULL`；后半句是 UNKNOWN，整个 `AND` 就永远不为 TRUE，所以一行都查不到。`IN` 列表里有 NULL 时，`x IN (1, NULL)` 仍可能因为 `x = 1` 命中；危险的是 `NOT IN`。第 05 课会用到 `NOT IN`，第 14 课才细讲它的排查。

## AND 和 OR 混用：括号不是风格

`AND` 要求两边都成立，`OR` 只要一边成立。优先级是 `NOT` > `AND` > `OR`，所以下面这句：

```sql
WHERE stock > 0 OR price > 1000 AND note = '促销中'
```

实际等价于：

```sql
WHERE stock > 0 OR (price > 1000 AND note = '促销中')
```

而不是：

```sql
WHERE (stock > 0 OR price > 1000) AND note = '促销中'
```

用上面的 `products` 数据算一下：第一种留下 4 行（键盘、显示器、耳机、数据线），第二种只留下耳机。差了三行，而且不报错。

**规则：混用 `AND` 和 `OR` 时一律加括号。** 这不是风格问题，是正确性问题。`NOT` 也一样，别靠记忆判断它管到哪一段。

## IN：等于列表里的任意一个

```sql
WHERE note IN ('促销中', '清仓')
```

等价于 `note = '促销中' OR note = '清仓'`，但更短，也更容易用参数逐个绑定。`IN` 列表里可以放表达式，不一定是常量；这里先用常量把语义看清。

`NOT IN` 的 NULL 陷阱上面已经说过：列表里只要有一个 NULL，`NOT IN` 就可能一行都查不到。写 `NOT IN` 之前先确认子查询或列表不会返回 NULL。

## LIKE：`%` 和 `_`

| 写法 | 含义 |
| --- | --- |
| `LIKE '键盘%'` | 以「键盘」开头 |
| `LIKE '%线'` | 以「线」结尾 |
| `LIKE '%数据%'` | 包含「数据」 |
| `LIKE '_盘'` | 正好两个字，第二个是「盘」 |

`%` 匹配任意长度（含 0 个字符），`_` 匹配正好一个字符。`LIKE '键盘%'` 的前缀是确定的，普通 B+ 树索引还能用来定位；`LIKE '%线'` 以通配符开头，索引按前缀排序，你不知道前缀就无从定位。

如果你给 `name` 建一个普通索引，`EXPLAIN SELECT name FROM products WHERE name LIKE '%线'` 可能是 `type=index` 加 `Using index`（全索引扫描），也可能是 `type=ALL`（全表扫描），取决于优化器和覆盖索引；两种都不会是 `range` 或 `ref`。所以「只能全表扫描」说得太死，准确说是全表或全索引扫描，都是按行数增长的成本。

数据量大时，全文索引是一个方向，但对中文不是照做就能替代 `LIKE`：MySQL 默认 FULLTEXT 解析器会把连续中文当成一个词，`MATCH(name) AGAINST('数据')` 查不到 `数据线`；要用 ngram 解析器。而且 `MATCH` 是词项匹配，不是 `LIKE` 的子串匹配。先把这条记住，别在中文列上直接建个 FULLTEXT 就以为能搜子串。

## BETWEEN：闭区间

```sql
WHERE price BETWEEN 100 AND 300
```

含两个端点，等价于 `price >= 100 AND price <= 300`。跑一遍里的第 2 条查询就是它，结果留下键盘（199）和耳机（299）。

对时间列用 `BETWEEN` 要小心：`BETWEEN '2026-01-01' AND '2026-01-31'` 会把上界当成 `2026-01-31 00:00:00`，漏掉 1 月 31 日当天 00:00:00 之后的记录。正确写法是左闭右开：

```sql
WHERE created_at >= '2026-01-01' AND created_at < '2026-02-01'
```

这一课的表没有时间列，这条先当规则记住；以后遇到 `DATETIME` 列直接用左闭右开，不要用 `BETWEEN` 赌当天最后一秒。

## 动手

这道题用的是共享数据集，不是你上面的 `products`。它叫 `orders-public` / `orders-hidden-a`，但题面只用到里面的 `customers` 表：

| 表 | 列 |
| --- | --- |
| `customers` | `id INT`、`name VARCHAR(20)`、`city VARCHAR(20)`、`note VARCHAR(20) NULL` |
| `orders` | `id INT`、`customer_id INT`、`created DATETIME` |

`orders` 也在数据集里，这一题不需要；`order_items` 也在，但同样用不上。**只返回 `name` 一列**；判题按列名和值比对，`SELECT *` 会把 `id`、`city`、`note` 一起带出来，列名对不上直接判错。列名保持 `name`，不要写成 `AS customer`。

两个数据集里都同时存在 NULL 和空串：公开集 `bob` 的 `note` 是空串、`cid` 是 NULL；隐藏集也有空串和两个 NULL。你要找的是「NULL」，不是「空串」。在公开集上，`note IS NULL` 命中 1 行；隐藏集命中 2 行。

```code-challenge
{
  "id": "my-02-write",
  "kind": "sql-write",
  "title": "列出「没有填写备注」的客户",
  "goal": "用 IS NULL 取空值；知道 = NULL 与 = '' 都不是它；只返回 name 一列",
  "trap": "`= NULL` 和 `IS NULL` 等价，只是写法不同",
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
  "explain": "普通比较运算符 `=` 遇到 NULL 返回 UNKNOWN，WHERE 只保留 TRUE，所以 `note = NULL` 永远是 0 行；判断空值要用 `IS NULL`。空串 `''` 是一个有值但长度为 0 的字符串，`= ''` 只命中它，不会命中 NULL。两个数据集里都同时存在 NULL 和空串。",
  "starter": "SELECT name\nFROM customers\nWHERE "
}
```

## 检查一下

```quiz
{"id":"my-02-quiz","title":"条件筛选三问","questions":[
 {"question":"`WHERE note = NULL` 会返回什么？","options":["所有 note 为空的行","一行都没有，因为普通比较遇到 NULL 得到 UNKNOWN"],"answer":1,"explain":"WHERE 只保留结果为 TRUE 的行；判断空值要用 IS NULL。"},
 {"question":"`WHERE a OR b AND c` 的实际含义是？","options":["(a OR b) AND c","a OR (b AND c)","a AND b OR c","取决于数据库版本"],"answer":1,"explain":"AND 的优先级高于 OR，所以不加括号时会先算 b AND c。"},
 {"question":"`LIKE '%线'` 为什么用不上普通索引的前缀定位？","options":["因为字符串比较慢","因为索引按前缀排序，以通配符开头时无法确定起点","因为 LIKE 不支持索引","因为数据量太大"],"answer":1,"explain":"普通 B+ 树索引依赖前缀定位；以 % 开头只能做全表或全索引扫描。"}
]}
```

## 还差的

- **`EXPLAIN` 只看了 `type`**。`rows`、`filtered`、`Extra` 里还有信息；这一课没有展开，也没有给出不同数据量下的耗时对比。
- **中文全文索引只停在警告**。没有给出 ngram 的建索引语句和实际召回对比；`MATCH` 与 `LIKE` 的差异只在概念上说清。
- **`NOT IN` 只讲了 NULL 陷阱**。它和 `NOT EXISTS` 在 NULL、重复值、执行计划上的差异没展开，第 14 课再补。
- **时间列只给了规则**。`DATETIME` 与时区、`TIMESTAMP` 的差别没碰；这一课的表里也没有时间列可以实测。

下一关：排序、分页与去重——`ORDER BY` 的并列值为什么会把两次查询的顺序翻过来。

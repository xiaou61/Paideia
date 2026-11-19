---
title: 14 · 常见 SQL 错误与排查
category: mysql-from-zero
order: 14
chapter: 排错
level: intermediate
minutes: 20
prerequisite: 13 · 综合案例：设计一个博客库
status: published
---

# 14 · 常见 SQL 错误与排查

## 两个页面，两个数

对账页上写「订单 5 笔」，点进明细只有 3 条。金额那一栏更离谱：页面合计 `370.00`，支付流水是 `220.00`。

两句查询都在同一张表上，都跑出了结果，都没有报错。**这一关要练的就是这种场面**：语法错误其实是最安全的问题，它会立刻拦住你；真正贵的是"能跑、有结果、结果是错的"。

下面按我实际排查的顺序走一遍——顺序不是学 SQL 的顺序，是**先缩小范围、再决定看哪里**的顺序。

## 第一步：先不连接，把数算一遍

看到"多了/少了"，第一个动作不是看查询，是**换一个更笨的算法算同一个数**：

```sql
-- 报表在用的：连接之后数
SELECT COUNT(*) AS n, SUM(o.amount) AS total
  FROM orders o JOIN payments p ON p.uid = o.uid;

-- 笨办法：不连接，直接数
SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders;
```

```text
joined=5 | joined_sum=370.00
plain =3 | plain_sum =220.00
```

差在两个地方同时发生：行数 5 对 3，金额 370 对 220。**先把"差多少"算清，比猜原因快得多。** 这里 5 = 2×2 + 1×1，一眼能看出是相乘。

## 第二步：多出来的行——连接放大

`orders` 和 `payments` 都是一对多（一个用户多单、多笔支付）。按 `uid` 连接时，用户 1 的 2 单 × 2 笔 = 4 行，用户 2 的 1 单 × 1 笔 = 1 行，共 5 行。

行数相乘，金额自然也相乘：`100+50` 被重复算了两遍。

两条修法，选哪条看你怕哪种错：

```sql
-- 修法一：先各自聚合，再连接（不怕多加一个聚合）
SELECT o.uid, o.total, p.paid
  FROM (SELECT uid, SUM(amount) AS total FROM orders   GROUP BY uid) o
  LEFT JOIN (SELECT uid, SUM(amount) AS paid FROM payments GROUP BY uid) p
    ON p.uid = o.uid;

-- 修法二：每个聚合都套 DISTINCT（少一层派生表，但漏一个就错）
SELECT COUNT(DISTINCT o.id) AS orders_cnt,
       COUNT(DISTINCT p.id) AS payments_cnt
  FROM orders o JOIN payments p ON p.uid = o.uid;
```

修法一多一层派生表，但**新增聚合字段时不会写错**；修法二的结果集小一点，代价是每个聚合都要记得加 `DISTINCT`，漏一个就静默出错。计数类报表我一般用修法二，金额类用修法一——金额错一次就是对账事故。

顺带说清 `COUNT` 的三个版本，它们数的不是一回事：

| 写法 | 数什么 | 什么时候会坑你 |
| --- | --- | --- |
| `COUNT(*)` | 结果集行数 | 连接放大后偏大 |
| `COUNT(col)` | `col` 非 `NULL` 的行数 | 以为它在数"所有行" |
| `COUNT(DISTINCT col)` | `col` 去重后的非空值个数 | 以为它能去掉整行重复 |

## 第三步：变少的行——`NULL` 与 `LEFT JOIN`

如果行数方向反过来（报表比实际少），先怀疑两个地方。

**第一处：`NULL` 不是值，是"未知"。**

```sql
SELECT * FROM users WHERE phone = NULL;      -- 永远是 0 行
SELECT * FROM users WHERE phone != NULL;     -- 也永远是 0 行
SELECT * FROM users WHERE phone IS NULL;     -- 这样才对
```

`未知 = 未知` 的结果仍是未知，`WHERE` 只留结果为真的行。更隐蔽的是 `NOT IN`：

```sql
-- 黑名单里只要有一个 NULL，这一句就一行都查不出来
SELECT * FROM users WHERE phone NOT IN (SELECT phone FROM blacklist);
```

`x NOT IN (1, NULL)` 展开是 `x != 1 AND x != NULL`，后半句恒为未知。改成 `NOT EXISTS` 就没这个坑。

**第二处：`LEFT JOIN` 之后把右表条件写进 `WHERE`。**

```sql
-- 退化成 INNER JOIN，没支付的订单整条消失
SELECT o.id FROM orders o
LEFT JOIN payments p ON p.order_id = o.id
WHERE p.status = 'paid';
```

左连接保留左表全部行，但 `WHERE p.status = 'paid'` 会把 `p` 为 `NULL` 的行过滤掉——那正是"没支付"的订单。条件要放 `ON` 里：

```sql
SELECT o.id FROM orders o
LEFT JOIN payments p ON p.order_id = o.id AND p.status = 'paid';
```

## 第四步：行数看着对，金额不对——隐式转换

`code` 是 `VARCHAR`，你写 `WHERE code = 1001`（没有引号）。MySQL 会**把列转成数字**再比：

| 列里的值 | 转成数字 | `= 1001` 是否命中 |
| --- | --- | --- |
| `'1001'` | 1001 | 是 |
| `'01001'` | 1001 | 是 |
| `'1001 '` | 1001 | 是 |
| `'abc'` | 0 | 否 |

于是 `'01001'` 这种"看起来完全不同"的行也被算进来了。同时因为转换发生在**列**上，索引也用不上。

```sql
SELECT id, code FROM codes WHERE code = 1;    -- 命中 '001'、'1'、'01' 三行
SELECT id, code FROM codes WHERE code = '1';  -- 只命中一行
```

注意方向的区分：**判断标准是"谁被转换了"**。`phone` 是 `BIGINT` 而参数传字符串 `'13800138000'` 时，转换发生在常量一侧，索引照用——它不属于这一类。列被转换才失效。

日期上还有同一类问题的两个变体：

```sql
-- 只匹配 00:00:00 那一秒，不是"这一天"
SELECT * FROM orders WHERE created_at = '2026-10-01';

-- 半开区间：能走索引，也不漏当天最后一秒
SELECT * FROM orders WHERE created_at >= '2026-10-01'
                     AND created_at <  '2026-10-02';
```

`WHERE DATE(created_at) = '2026-10-01'` 结果是对的，但函数包在列上，索引同样用不上。

## 第五步：翻页会重、也会漏——游标方向

`ORDER BY score DESC LIMIT 2 OFFSET 2` 这种写法，排序键有并列值时两次查询可以给出不同排列；翻页期间有人插入新行还会整体错位。

正道是游标（keyset）分页：用"上一页最后一行的排序键"当起点，而不是用 `OFFSET` 数行。

```sql
SELECT id, score FROM articles ORDER BY score DESC, id ASC LIMIT 2;
-- 上一页最后一行是 (score=10, id=3)，下一页从它之后开始
```

按 `(score, id)` 做行值比较看起来很像，但**方向混了就会错**：

```sql
-- 错：id 在 ORDER BY 里是 ASC，这里却写了 <
SELECT id, score FROM articles
 WHERE (score, id) < (10, 3)
 ORDER BY score DESC, id ASC
 LIMIT 2;
-- 会把 score=10 里 id 小于 3 的行再取一遍，id=1、id=2 重复出现

-- 对：降序用 <，升序用 >，拆成 OR 写清楚
SELECT id, score FROM articles
 WHERE score < 10 OR (score = 10 AND id > 3)
 ORDER BY score DESC, id ASC
 LIMIT 2;
```

行值比较 `(a, b) < (x, y)` 的取值范围只有一种方向（两个分量同向），而 `ORDER BY score DESC, id ASC` 是混向的——所以这里必须拆成 `OR`。这个错误很隐蔽：结果少了行、又多了行，看起来像是"数据在变"。

## 第六步：这几类会直接报错，反而好办

`WHERE` 里写聚合函数：

```sql
SELECT city, COUNT(*) FROM users WHERE COUNT(*) > 1 GROUP BY city;
-- ERROR 1111 (HY000): Invalid use of group function
```

执行顺序是 `FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT`，`WHERE` 在分组**之前**执行，那时还没有"每组的计数"。过滤分组结果用 `HAVING`。

反过来把行级条件写进 `HAVING` 能跑，但先分组再过滤，白算一堆组：

```sql
-- 能跑，但不必要
SELECT city, COUNT(*) FROM users GROUP BY city HAVING city = '北京';
-- 更好：先筛行
SELECT city, COUNT(*) FROM users WHERE city = '北京' GROUP BY city;
```

`ONLY_FULL_GROUP_BY`（MySQL 5.7 起默认开启）会拦住这一句：

```sql
SELECT id, name, COUNT(*) FROM orders GROUP BY uid;
-- ERROR 1055: Expression #1 of SELECT list is not in GROUP BY clause ...
```

`id`、`name` 既不在 `GROUP BY` 里也不是聚合值，数据库不知道取哪一行。旧版本会随便挑一行返回——那种"成功"比报错危险得多。

修法按推荐顺序：把列补进 `GROUP BY`；只选聚合值；确实不在乎取哪行才用 `ANY_VALUE(col)`。一个例外：`GROUP BY` 的是主键时，MySQL 能识别函数依赖，同表其他列可以直接选。

## 第七步：卡住不返回——锁与死锁

两个错误码，处理方式完全不同：

| 错误码 | 名称 | 含义 | 应用该做什么 |
| --- | --- | --- | --- |
| `1205` | Lock wait timeout | 等锁超过 `innodb_lock_wait_timeout`（默认 50 秒） | 回滚、缩短事务、查谁持有锁 |
| `1213` | Deadlock found | InnoDB 检测到循环等待，主动回滚其中一个事务 | 捕获后回滚整个事务，退避后有限重试 |

最小复现是两个会话按相反顺序更新两行：

```text
会话 A：BEGIN; UPDATE accounts SET v=v+1 WHERE id=1;   -- 锁住 1
会话 B：BEGIN; UPDATE accounts SET v=v+1 WHERE id=2;   -- 锁住 2
会话 A：UPDATE accounts SET v=v+1 WHERE id=2;          -- 等 B
会话 B：UPDATE accounts v=v+1 WHERE id=1;              -- 等 A → 死锁
```

InnoDB 让其中一个报 `1213` 并回滚。**死锁不是数据库的 bug，是两个事务访问顺序不一致的必然结果。** 把 `innodb_lock_wait_timeout` 调大治不了它——那个参数管的是普通等锁，死锁是循环等待。

四个修法：统一加锁顺序（都按主键升序）、缩短事务（别在事务里调远程接口）、让 `WHERE` 走索引（没索引的更新会锁更多行）、对 `1213` 做有限重试。排查用 `SHOW ENGINE INNODB STATUS`，里面的 `LATEST DETECTED DEADLOCK` 段会给出两个事务分别持有什么锁、在等什么锁。

## 最后：`EXPLAIN` 判断"慢"，不判断"错"

前面六步解决的都是**结果对不对**。只剩"慢"才轮到 `EXPLAIN`。

```sketch
{
  "title": "排查顺序：先错，后慢",
  "rows": [
    [ { "id": "sym", "title": "页面上的现象", "sub": "多了 / 少了 / 金额不对", "tone": "gray" } ],
    [
      { "id": "count", "title": "1 换个笨算法算同一个数", "sub": "不连接、DISTINCT、IS NULL", "tone": "blue" },
      { "id": "why", "title": "2 按差值猜原因", "sub": "倍数→连接；少行→NULL", "tone": "blue" }
    ],
    [
      { "id": "explain", "title": "3 只有慢才看 EXPLAIN", "sub": "key / type / rows", "tone": "amber" },
      { "id": "lock", "title": "4 卡住看锁", "sub": "INNODB STATUS", "tone": "amber" }
    ]
  ],
  "arrows": [
    { "from": "sym", "to": "count" },
    { "from": "count", "to": "why" },
    { "from": "why", "to": "explain" },
    { "from": "why", "to": "lock" }
  ]
}
```

```text
id=1 | select_type=SIMPLE | table=posts | type=ref | key=idx_posts_status_created
rows=120 | filtered=100.00 | Extra=Using where
```

（数值是示意。）四列够用：

| 列 | 看什么 |
| --- | --- |
| `type` | 访问方式，从好到坏：`system` > `const` > `eq_ref` > `ref` > `range` > `index` > `ALL` |
| `key` | 实际用了哪个索引；`NULL` 表示没用。`possible_keys` 有值而 `key` 为 `NULL`，是优化器主动放弃 |
| `rows` | **估计**要扫多少行；和结果行数差几个数量级就要警惕 |
| `Extra` | `Using index`（覆盖索引，好）、`Using filesort`、`Using temporary`（可能落盘） |

两个容易读错的点：

- `type=index` 不是"走了索引所以没事"，它是**扫整个索引**，通常也不快；
- `status != 'done'` 这种写法在 MySQL 8 里能走 `range`，不是失效。它真正的问题是**选择性低**（大部分行都满足，优化器可能干脆扫全表），以及 `NULL` 行会被静默丢掉（`!=` 对 `NULL` 返回未知）。要排除某个值又要保留空值，得写 `status <> 'done' OR status IS NULL`。

`SELECT *` 本身不会让索引失效，但会让**覆盖索引**失效：本来只读索引就能返回，变成每行都要回表。

估计值可能和实际差很远：

```sql
EXPLAIN ANALYZE SELECT ...;
```

它真的执行查询、给实际耗时与实际行数（MySQL 8.0.18+）。所以别在生产上对 `UPDATE`/`DELETE` 用它。

## 跑一遍

五段：`NULL` 比较、隐式转换、`COUNT` 跳过 `NULL`、连接放大行数、放大后金额被重复累加。输出每行是 `列=值 | 列=值` 的对照格式（命令行的原始排版随客户端不同，这里统一压成一行便于对照）。

<!-- run -->
```sql
CREATE TABLE users (
  id    INT PRIMARY KEY,
  name  VARCHAR(10) NOT NULL,
  phone VARCHAR(20),
  city  VARCHAR(10)
);
INSERT INTO users VALUES
  (1,'ann','13800000001','北京'),
  (2,'bob',NULL,'上海'),
  (3,'cid','13800000003',NULL),
  (4,'dan','','北京');

SELECT COUNT(*) AS eq_null FROM users WHERE phone = NULL;
SELECT COUNT(*) AS is_null FROM users WHERE phone IS NULL;
SELECT COUNT(*) AS empty_string FROM users WHERE phone = '';

CREATE TABLE codes (id INT PRIMARY KEY, code VARCHAR(10) NOT NULL);
INSERT INTO codes VALUES (1,'001'),(2,'1'),(3,'01'),(4,'abc');
SELECT id, code FROM codes WHERE code = 1 ORDER BY id;
SELECT id, code FROM codes WHERE code = '1' ORDER BY id;

CREATE TABLE accounts (
  id        INT PRIMARY KEY,
  city_code INT NOT NULL,
  city      VARCHAR(10) NOT NULL,
  note      VARCHAR(20)
);
INSERT INTO accounts VALUES
  (1,1,'北京','a'),
  (2,1,'北京',NULL),
  (3,2,'上海','b');

SELECT city_code, city, COUNT(*) AS rows_all, COUNT(note) AS rows_with_note
  FROM accounts
  GROUP BY city_code, city
  ORDER BY city_code;

CREATE TABLE orders (id INT PRIMARY KEY, uid INT NOT NULL, amount DECIMAL(10,2) NOT NULL);
CREATE TABLE payments (id INT PRIMARY KEY, uid INT NOT NULL, amount DECIMAL(10,2) NOT NULL);
INSERT INTO orders VALUES (10,1,100.00),(11,1,50.00),(12,2,70.00);
INSERT INTO payments VALUES (100,1,100.00),(101,1,50.00),(102,2,70.00);

SELECT COUNT(*) AS joined_rows,
       COUNT(DISTINCT o.id) AS orders_cnt,
       COUNT(DISTINCT p.id) AS payments_cnt
  FROM orders o JOIN payments p ON p.uid = o.uid;

SELECT SUM(o.amount) AS wrong_sum FROM orders o JOIN payments p ON p.uid = o.uid;
SELECT SUM(amount) AS true_sum FROM orders;
```
```text
eq_null=0
is_null=1
empty_string=1
code=1（不加引号，列被转成数字）
id=1 | code=001
id=2 | code=1
id=3 | code=01
code='1'（加引号，按字符串比）
id=2 | code=1
city_code=1 | city=北京 | rows_all=2 | rows_with_note=1
city_code=2 | city=上海 | rows_all=1 | rows_with_note=1
joined_rows=5 | orders_cnt=3 | payments_cnt=3
wrong_sum=370.00
true_sum=220.00
```

逐段对照：

1. `phone = NULL` 返回 **0** 行，`IS NULL` 才返回 1；空字符串 `''` 是另一个值，单独数出 1 行。
2. `code = 1` 命中 `1,2,3` 三行（`'001'`、`'1'`、`'01'` 都被转成数字 1）；加引号的 `code = '1'` 只命中 `2`。多出来的两行就是这么来的。
3. 北京两行、只有一行 `note` 非空：`COUNT(*)` 是 2，`COUNT(note)` 是 1。
4. 连接后 5 行，订单和支付各只有 3 条——`COUNT(DISTINCT 主键)` 才能还原真实数量。
5. 同一份 `orders`，连接后求和 `370.00`，不连接 `220.00`。多出的 150 是重复累加，数据一条没变。

## 动手

下面这条查询能跑，但可能多返回行。点出有问题的那一行。

```code-challenge
{
  "id": "my-14-bug",
  "kind": "bug",
  "title": "找出这条查询为什么可能多返回行",
  "language": "sql",
  "code": "SELECT id, code, name\nFROM products\nWHERE code = 1001\nORDER BY id;",
  "bugLine": 3,
  "explain": "code 是 VARCHAR，右边的 1001 是数字。MySQL 把列转成数字再比：'01001'、'1001 ' 都会命中，'abc' 变成 0；同时列上做了转换，索引也用不上。改成 code = '1001'。"
}
```

## 检查一下

```quiz
{"id":"my-14-quiz","title":"SQL 排错三问","questions":[
 {"question":"SELECT city, COUNT(*) FROM users WHERE COUNT(*) > 1 GROUP BY city 为什么报错？","options":["因为 city 没有索引","因为 WHERE 在分组之前执行，那时还没有每组的计数","因为 COUNT 不能和 GROUP BY 一起用","因为表名写错了"],"answer":1,"explain":"执行顺序是 FROM → WHERE → GROUP BY → HAVING，聚合条件要放到 HAVING 里。"},
 {"question":"code 是 VARCHAR，WHERE code = 1001 除了可能多匹配行，还有什么后果？","options":["结果会自动去重","列被隐式转换成数字，索引用不上","MySQL 会拒绝执行","会锁住整张表"],"answer":1,"explain":"转换发生在列上，索引无法按原类型定位；同时 '001'、'01' 这类值也会被命中。写成 code = '1001'。"},
 {"question":"ORDER BY score DESC, id ASC 配 WHERE (score, id) < (10, 3) 分页，问题是什么？","options":["没有任何问题","行值比较只支持同向，id 这一维应该是 >，现在会重复取到 score=10 里 id 更小的行","应该把 LIMIT 去掉","score 上必须有唯一索引"],"answer":1,"explain":"行值比较 (a,b) < (x,y) 两个分量同向；ORDER BY 是混向的，要拆成 score < 10 OR (score = 10 AND id > 3)。"}
]}
```

## 一句话

排查顺序固定：**先看数对不对，再看快不快。** 数不对时换个笨算法算同一个数（不连接、`DISTINCT`、`IS NULL`），按差值猜原因——倍数差是连接放大，行变少先查 `NULL` 与 `LEFT JOIN` 的 `WHERE`；数值诡异先查隐式转换；翻页重复一定是排序键不唯一或游标方向写反。`EXPLAIN` 只用来判断慢，不判断错。

到这里 MySQL 从零开始的十四关结束。你现在应该能：把需求拆成表、写出正确的查询、判断索引有没有用上、说清并发下会发生什么、以及在结果不对时按顺序缩小范围。

**延伸阅读**：[MySQL 8.0 参考手册 · 优化查询](https://dev.mysql.com/doc/refman/8.0/en/optimization.html)（`EXPLAIN` 每一列的官方定义，以及优化器为什么会放弃索引）。

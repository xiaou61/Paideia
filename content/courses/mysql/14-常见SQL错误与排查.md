---
title: 14 · 常见 SQL 错误与排查
category: mysql-from-zero
order: 14
chapter: 排错
level: intermediate
minutes: 30
prerequisite: 13 · 综合案例：设计一个博客库
status: published
---

# 14 · 常见 SQL 错误与排查

## 这一课你要会

- 结果不对时，按"条件 → 类型 → 分组 → 连接 → 索引 → 锁"的顺序定位，而不是凭感觉改；
- 认出十类高频错误：`WHERE`/`HAVING` 混用、`NULL` 比较、隐式类型转换、`GROUP BY` 严格模式、分页丢行、`COUNT` 放大、连接放大、索引失效、锁等待、`EXPLAIN` 误读；
- 对每一种错误，能说出它为什么"不报错但结果错"，以及最小修法；
- 会用 `EXPLAIN` 的 `type`、`key`、`rows`、`Extra` 判断一条查询大概怎么执行。

## 为什么

语法错误会立刻报错，反而是最安全的。真正麻烦的是那些**能跑、也有结果，但结果是错的** SQL：`= NULL` 返回空集、隐式转换多匹配几行、连接把金额翻倍。它们不会在开发时提醒你，通常等到报表对不上、用户投诉少了一条数据才暴露。

这一关是一张排查清单。它不追求覆盖所有错误，而是把最常遇到的十类摆出来：每条都给出错误写法、为什么错、以及怎么改。

## 讲解

### 先分类，再动手

看到问题先别改 SQL，先判断它属于哪一类。不同类的排查入口完全不同：

| 症状 | 先怀疑 | 第一条命令 |
| --- | --- | --- |
| 行数不对（多了/少了） | 连接、`WHERE`、`NULL` | 去掉聚合，直接 `SELECT *` 看明细 |
| 数值不对 | 重复计数、类型转换、精度 | 把 `COUNT(*)` 换成 `COUNT(DISTINCT 主键)` 对比 |
| 直接报错 | 严格模式、聚合位置、权限 | 读错误码与报错里指出的列 |
| 慢 | 索引失效、深分页、回表 | `EXPLAIN` 看 `type` 与 `rows` |
| 卡住不返回 | 锁等待、长事务、元数据锁 | `SHOW PROCESSLIST`、`SHOW ENGINE INNODB STATUS` |

**先确定是哪一类，再改一行验证一次。** 一次改五处，出了新问题就不知道是哪一处造成的。

### 错误一：`WHERE` 与 `HAVING` 混用

错误写法：

```sql
SELECT city, COUNT(*) AS n
  FROM users
 WHERE COUNT(*) > 1
 GROUP BY city;
```

MySQL 直接报 `1111 Invalid use of group function`。原因是执行顺序：

```text
FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT
```

`WHERE` 在分组之前执行，那时还没有"每组的计数"，所以聚合函数不能出现在 `WHERE` 里。过滤分组结果要用 `HAVING`：

```sql
SELECT city, COUNT(*) AS n
  FROM users
 GROUP BY city
HAVING COUNT(*) > 1;
```

反过来也常错：普通行条件写进 `HAVING`。

```sql
-- 能跑，但先分组再过滤，白算了很多组
SELECT city, COUNT(*) AS n
  FROM users
 GROUP BY city
HAVING city = '北京';

-- 更好：先筛行，再分组
SELECT city, COUNT(*) AS n
  FROM users
 WHERE city = '北京'
 GROUP BY city;
```

一句话：**行级条件放 `WHERE`，组级条件放 `HAVING`。** 聚合函数只能出现在 `HAVING`、`SELECT`、`ORDER BY` 里（窗口函数除外）。

### 错误二：拿 `NULL` 做普通比较

错误写法：

```sql
SELECT * FROM users WHERE phone = NULL;
SELECT * FROM users WHERE phone != NULL;
SELECT * FROM users WHERE phone NOT IN (SELECT phone FROM blacklist);
```

第一条和第二条永远返回空集。`NULL` 的含义是"未知"，`未知 = 未知` 的结果还是未知，`WHERE` 只保留结果为真的行。第三条更隐蔽：只要子查询里出现一个 `NULL`，整个 `NOT IN` 就一行都查不到——`x NOT IN (1, NULL)` 等价于 `x != 1 AND x != NULL`，后半句永远是未知。

正确写法：

```sql
SELECT * FROM users WHERE phone IS NULL;
SELECT * FROM users WHERE phone IS NOT NULL;
SELECT * FROM users
 WHERE NOT EXISTS (SELECT 1 FROM blacklist b WHERE b.phone = users.phone);
```

还有三个连带规则：

- `COUNT(phone)` 跳过 `NULL`，`COUNT(*)` 不跳；
- `SUM`/`AVG` 会忽略 `NULL`；全是 `NULL` 时 `SUM` 返回 `NULL`，不是 0；
- `NULL + 1`、`CONCAT(NULL, 'x')` 结果都是 `NULL`，需要 `COALESCE` 兜底。

### 错误三：隐式类型转换

错误写法（`code` 是 `VARCHAR`）：

```sql
SELECT id, code FROM products WHERE code = 1001;
```

看起来只查一个编号，实际 MySQL 会把字符串列转成数字再比：

| 列里的值 | 转成数字 | `= 1001` 是否命中 |
| --- | --- | --- |
| `'1001'` | 1001 | 是 |
| `'01001'` | 1001 | 是 |
| `'1001 '` | 1001 | 是 |
| `'abc'` | 0 | 否 |

结果是多返回了 `'01001'` 这种"看起来不一样"的行。更糟的是**列上套了转换，索引失效**，本来能走 `ref` 的查询变成全表扫描。

修法只有一句：**字面量的类型与列的类型对齐。**

```sql
SELECT id, code FROM products WHERE code = '1001';
```

日期也有同样的问题：

```sql
-- 只匹配 00:00:00 那一刻，不是"这一天"
SELECT * FROM orders WHERE created_at = '2026-10-01';

-- 正确：半开区间，能走索引
SELECT * FROM orders
 WHERE created_at >= '2026-10-01'
   AND created_at <  '2026-10-02';
```

`WHERE DATE(created_at) = '2026-10-01'` 的结果是对的，但函数包在列上，索引同样用不上。

### 错误四：`GROUP BY` 严格模式

MySQL 5.7 起默认开启 `ONLY_FULL_GROUP_BY`。这条查询会报 `1055`：

```sql
SELECT id, name, COUNT(*)
  FROM orders
 GROUP BY uid;
```

`id`、`name` 既不在 `GROUP BY` 里，也不是聚合结果，数据库不知道要取这一组的哪一行，于是拒绝执行。旧版本或关闭严格模式时它会"成功"，随便挑一行返回——这种结果比报错危险得多。

三种修法，按推荐顺序：

1. **把要选的列补进 `GROUP BY`**（最清楚）：
   ```sql
   SELECT uid, COUNT(*) FROM orders GROUP BY uid;
   ```
2. **只选聚合值**：如果业务只要计数，就别把 `id`、`name` 拖进来；
3. **`ANY_VALUE(col)`**：明确表示"这一组里随便取一个"，适合你确实不在乎取哪行的情况。不要用它来消除报错却假装结果有意义。

一个例外：如果 `GROUP BY` 的是主键，MySQL 能识别函数依赖，同表其他列可以直接选。例如 `GROUP BY p.id` 时选 `p.title` 是合法的——这也是第 13 关的列表查询能那么写的原因。

### 错误五：`ORDER BY` + `LIMIT` 分页丢行

先看一张有并列值的表：

| id | score |
| --- | --- |
| 1 | 10 |
| 2 | 10 |
| 3 | 10 |
| 4 | 5 |
| 5 | 1 |

查询写 `ORDER BY score DESC LIMIT 2 OFFSET 0`，再查 `OFFSET 2`。三条 `score=10` 的行之间没有规定顺序，数据库两次可以给出不同排列；再叠加"翻页期间有人插入了一条 score=10 的新行"，第一页出现过的 id 可能在第二页再出现一次，也可能有 id 一次都没出现。

```sql
-- 错：排序键不唯一，翻页结果不稳定
SELECT id, score FROM articles ORDER BY score DESC LIMIT 2 OFFSET 0;
SELECT id, score FROM articles ORDER BY score DESC LIMIT 2 OFFSET 2;

-- 对：加唯一列做第二排序键
SELECT id, score FROM articles ORDER BY score DESC, id ASC LIMIT 2 OFFSET 0;
SELECT id, score FROM articles ORDER BY score DESC, id ASC LIMIT 2 OFFSET 2;
```

修法两层：

1. **加唯一列做第二排序键**，让顺序确定：
   ```sql
   ORDER BY score DESC, id ASC
   ```
2. **深翻页改用游标**，不要用大 `OFFSET`：
   ```sql
   SELECT id, score FROM articles
    WHERE (score, id) < (10, 3)
    ORDER BY score DESC, id ASC
    LIMIT 2;
   ```

`OFFSET 100000` 的代价不是"跳过"，而是先读出前 10 万行再丢掉；游标从索引上的位置继续，页深不影响代价。

### 错误六：`COUNT` 用错对象

三种写法结果不同：

| 写法 | 数什么 | 典型错误 |
| --- | --- | --- |
| `COUNT(*)` | 结果集的行数 | 连接放大后偏大 |
| `COUNT(col)` | `col` 非 `NULL` 的行数 | 以为它数"所有行" |
| `COUNT(DISTINCT col)` | `col` 去重后的非空值个数 | 以为它能去掉整行的重复 |

最常见的组合错误是"连接后数订单"：一个订单有多条明细，`JOIN` 之后一个订单出现多次，`COUNT(o.id)` 会把一单数成多单。

```sql
-- 错：一个订单有 3 条明细，就被数成 3 单
SELECT COUNT(o.id) AS n
  FROM orders o JOIN order_items i ON i.order_id = o.id;

-- 对：按订单主键去重
SELECT COUNT(DISTINCT o.id) AS n
  FROM orders o JOIN order_items i ON i.order_id = o.id;
```

注意 `COUNT(DISTINCT a, b)` 在 MySQL 里是合法的，表示按 `(a, b)` 组合去重；但 `COUNT(DISTINCT *)` 不合法。

### 错误七：`JOIN` 放大行数

两张一对多的表同时连进来，行数会相乘：

```text
orders（3 条，属于 2 个用户）
payments（3 条，属于同样的 2 个用户）
JOIN ON uid → 用户 1 的 2 单 × 2 笔支付 = 4 行，用户 2 的 1 单 × 1 笔支付 = 1 行，共 5 行
```

此时 `COUNT(*)` 是 5，`SUM(orders.amount)` 也被重复累加。两条修法：

1. **先分别聚合，再连接**（最稳）：
   ```sql
   SELECT o.uid, o.total, p.paid
     FROM (SELECT uid, SUM(amount) AS total FROM orders GROUP BY uid) o
     LEFT JOIN (SELECT uid, SUM(amount) AS paid FROM payments GROUP BY uid) p
       ON p.uid = o.uid;
   ```
2. **用 `DISTINCT` 修正计数**：
   ```sql
   SELECT COUNT(DISTINCT o.id) AS orders_cnt,
          COUNT(DISTINCT p.id) AS payments_cnt
     FROM orders o JOIN payments p ON p.uid = o.uid;
   ```

第二种更快，但每个聚合都要记得加 `DISTINCT`，漏一个就错；第一种更不容易写错。

另一个连接陷阱是 `LEFT JOIN` 之后把右表条件写进 `WHERE`：

```sql
-- 退化成 INNER JOIN，没支付的订单消失
SELECT o.id FROM orders o
LEFT JOIN payments p ON p.order_id = o.id
WHERE p.status = 'paid';
```

要保留左表全部行，条件应放在 `ON` 里，或者改成 `WHERE p.status = 'paid' OR p.id IS NULL`（后者语义不同，想清楚再写）。

### 错误八：索引失效的常见写法

| 写法 | 为什么失效 | 改法 |
| --- | --- | --- |
| `WHERE DATE(created_at) = '2026-10-01'` | 列被函数包住 | 改成半开区间 |
| `WHERE id + 1 = 100` | 列参与运算 | `WHERE id = 99` |
| `WHERE name LIKE '%abc'` | 前导通配符无法定位 | 改后缀匹配或全文索引 |
| `WHERE code = 1001`（`code` 是字符串） | 隐式转换 | `code = '1001'` |
| `WHERE a = 1 OR b = 2` | 两个列各自的条件，常退化为全表 | 拆成两条 `UNION ALL`，或建联合索引 |
| `WHERE status != 'done'` | 取反、选择性低 | 改成枚举正向值，或建覆盖索引 |
| 索引 `(a, b)`，查询 `WHERE b = ?` | 不满足最左前缀 | 补 `a` 的条件，或另建 `(b)` |
| `ORDER BY` 的列与索引顺序不一致 | 无法用索引排序 | 让排序顺序匹配索引，或接受 `filesort` |

**`SELECT *` 本身不会让索引失效**，但会让覆盖索引失效：本来只读索引就能返回，变成每行都要回表。

判断有没有失效不要猜，跑 `EXPLAIN` 看 `key` 是不是 `NULL`、`type` 是不是 `ALL`。有时优化器明知有索引也选择全表扫描——因为符合条件的行太多，走索引再回表更慢。这是正常决策，不是失效。

### 错误九：锁等待与死锁

两个不同的错误码，处理方式不同：

| 错误码 | 名称 | 含义 | 应用该做什么 |
| --- | --- | --- | --- |
| `1205` | Lock wait timeout | 等锁超过 `innodb_lock_wait_timeout`（默认 50 秒） | 回滚、缩短事务、检查谁持有锁 |
| `1213` | Deadlock found | InnoDB 检测到循环等待，主动回滚其中一个事务 | 捕获后**重试整个事务** |

死锁的最小复现是两个会话以相反顺序更新两行：

```text
会话 A：BEGIN; UPDATE accounts SET v=v+1 WHERE id=1;   -- 锁住 1
会话 B：BEGIN; UPDATE accounts SET v=v+1 WHERE id=2;   -- 锁住 2
会话 A：UPDATE accounts SET v=v+1 WHERE id=2;          -- 等 B
会话 B：UPDATE accounts SET v=v+1 WHERE id=1;          -- 等 A → 死锁
```

InnoDB 会让其中一个报 `1213` 并回滚，另一个继续。**死锁不是数据库 bug，是应用访问顺序不一致的必然结果。**

四个修法：

1. **统一加锁顺序**：所有事务都按主键升序更新；
2. **缩短事务**：别在事务里调远程接口、发消息、等用户输入；
3. **让 `WHERE` 走索引**：没有索引的更新会锁住更多行，甚至接近全表；
4. **对 `1213` 做有限重试**：退避几十毫秒再试，重试次数要有上限。

排查用：

```sql
SHOW ENGINE INNODB STATUS;
```

输出里的 `LATEST DETECTED DEADLOCK` 段会给出两个事务分别持有什么锁、在等什么锁，比猜有效得多。

### 错误十：`EXPLAIN` 怎么看

`EXPLAIN` 不是"执行计划说明书"，它是优化器**估计**的执行方式。先看四列：

| 列 | 看什么 |
| --- | --- |
| `type` | 访问方式，从好到坏：`system` > `const` > `eq_ref` > `ref` > `range` > `index` > `ALL` |
| `key` | 实际用了哪个索引；`NULL` 表示没用 |
| `rows` | 估计要扫多少行；和结果行数差几个数量级就要警惕 |
| `Extra` | `Using index`（覆盖索引，好）、`Using filesort`、`Using temporary`（可能慢）、`Using where` |

一行典型的输出长这样（数值是示意，不是真实统计）：

```text
id=1 | select_type=SIMPLE | table=posts | type=ref | key=idx_posts_status_created
rows=120 | filtered=100.00 | Extra=Using where
```

`type=ALL` 是全表扫描。小表无所谓，大表要处理。`type=index` 是扫整个索引，通常也不快，别把它当成"走了索引"就放心。

`Extra` 里出现 `Using temporary; Using filesort` 通常意味着分组或排序没法用索引完成，数据量大时会落磁盘。`Using index` 是好事，说明查询只读索引就够，不用回表。

估计值可能与实际差很远。MySQL 8.0 提供：

```sql
EXPLAIN ANALYZE SELECT ...;
```

它会真正执行查询并给出实际耗时与行数，适合对比"优化器以为"和"实际发生"。

### 跑一遍

下面五段分别演示：`NULL` 比较、隐式转换、`COUNT` 跳过 `NULL`、连接放大行数、以及放大后金额被重复累加。

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
id=1 | code=001
id=2 | code=1
id=3 | code=01
id=2 | code=1
city_code=1 | city=北京 | rows_all=2 | rows_with_note=1
city_code=2 | city=上海 | rows_all=1 | rows_with_note=1
joined_rows=5 | orders_cnt=3 | payments_cnt=3
wrong_sum=370.00
true_sum=220.00
```

逐段对照：

1. `phone = NULL` 返回 **0**，`IS NULL` 才返回 1；空字符串 `''` 是另一个值，被单独数出 1 行。
2. `code = 1` 命中了三行：`'001'`、`'1'`、`'01'` 都被转成数字 1；加引号的 `code = '1'` 只命中一行。多出来的两行就是这么来的。
3. 北京有两行，但只有一行 `note` 非空：`COUNT(*)` 是 2，`COUNT(note)` 是 1。
4. 连接后 5 行，但订单和支付各只有 3 条：`COUNT(DISTINCT 主键)` 才能还原真实数量。
5. 同一份 `orders`，连接后求和是 `370.00`，不连接是 `220.00`。多出来的 150 是重复累加，不是数据变了。

## 动手

下面这条查询能跑，但结果可能多返回行。点出有问题的那一行。

```code-challenge
{
  "id": "my-14-bug",
  "kind": "bug",
  "title": "找出这条查询为什么可能多返回行",
  "language": "sql",
  "code": "SELECT id, code, name\nFROM products\nWHERE code = 1001\nORDER BY id;",
  "bugLine": 3,
  "explain": "code 是 VARCHAR，右边的 1001 是数字。MySQL 会把两边都转成数字比较：'01001'、'1001' 都会命中，'abc' 会变成 0；同时列上做了隐式转换，索引也用不上。改成 code = '1001'。"
}
```

## 检查一下

```quiz
{"id":"my-14-quiz","title":"SQL 排错三问","questions":[
 {"question":"SELECT city, COUNT(*) FROM users WHERE COUNT(*) > 1 GROUP BY city 为什么会报错？","options":["因为 city 没有索引","因为 WHERE 在分组之前执行，那时还没有每组的计数","因为 COUNT 不能和 GROUP BY 一起用","因为表名写错了"],"answer":1,"explain":"执行顺序是 FROM → WHERE → GROUP BY → HAVING，聚合条件必须放到 HAVING 里。"},
 {"question":"code 是 VARCHAR，WHERE code = 1001 除了可能多匹配行，还有什么后果？","options":["结果会自动去重","列被隐式转换成数字，索引用不上","MySQL 会拒绝执行","会锁住整张表"],"answer":1,"explain":"隐式转换发生在列上，索引无法按原类型定位；同时 '001'、'01' 这类值都会被命中。写成 code = '1001'。"},
 {"question":"事务收到 1213（Deadlock found）时，应用层最合理的处理是？","options":["无限重试同一条语句","捕获后回滚整个事务，退避一小段时间再重试有限次","把隔离级别调成读未提交","忽略错误继续提交"],"answer":1,"explain":"死锁时 InnoDB 已回滚其中一个事务，应用应回滚后按有限次数重试；同时检查加锁顺序与事务长度。"}
]}
```

## 常见误区

### 误区：SQL 不报错就说明结果对

**你以为**：能执行、有结果，逻辑就没问题。
**其实**：`= NULL`、隐式转换、连接放大、`COUNT(*)` 全都不会报错，只会安静地给出错的结果。数据库只保证语法和约束，不保证你的业务语义。
**怎么验证**：对每个关键数字，用另一种写法算一遍（`COUNT(DISTINCT 主键)`、不连接直接聚合、加上 `IS NULL`），两个结果不一致就说明至少有一个错。

### 误区：加了索引，查询就一定会走它

**你以为**：`CREATE INDEX` 之后 `WHERE` 里用到这列就算优化完成。
**其实**：列上套函数、隐式类型转换、前导 `%` 的模糊匹配、联合索引不满足最左前缀，都会让索引失效；优化器也可能因为符合条件的行太多而主动选择全表扫描。
**怎么验证**：`EXPLAIN` 看 `key` 与 `type`；再用 `EXPLAIN ANALYZE` 对比估计行数与实际行数。别用"我感觉快了"当证据。

### 误区：死锁是数据库的 bug，调大超时就好了

**你以为**：报死锁说明 MySQL 有问题，把 `innodb_lock_wait_timeout` 调大就能避免。
**其实**：超时时间管的是"等锁"，管不了死锁；死锁来自两个事务以不同顺序争同一批行。调大超时只会让普通锁等待拖得更久。
**怎么验证**：`SHOW ENGINE INNODB STATUS` 看 `LATEST DETECTED DEADLOCK`，把两个事务的 SQL 按顺序抄出来，通常能一眼看到相反的顺序；把顺序统一后，死锁会消失。

## 小结

排查的顺序固定：先看行数对不对，再看数值对不对，然后才看快不快。

十条错误可以归成三组：

- **语义错**：`WHERE`/`HAVING` 混用、`NULL` 比较、`GROUP BY` 严格模式；
- **数量错**：隐式转换多匹配、`COUNT` 用错、连接放大、分页顺序不稳；
- **执行错**：索引失效、锁等待与死锁、`EXPLAIN` 读错。

每条都有一个共同点：**先用最小改动验证一次，再继续。** 改五处然后"好像对了"，等于把下一次排查的难度又提高一层。

到这里 MySQL 从零开始的十四关结束。你现在应该能独立完成：把需求拆成表、写出正确的查询、判断索引有没有用上、说清并发下会发生什么，以及在结果不对时按清单定位。

**延伸阅读**：[MySQL 8.0 参考手册 · 优化查询](https://dev.mysql.com/doc/refman/8.0/en/optimization.html)（`EXPLAIN` 输出每一列的官方定义，以及优化器为什么会放弃索引）。

---
title: 09 · 事务与 ACID
category: mysql-from-zero
order: 9
chapter: 事务与并发
level: advanced
minutes: 18
prerequisite: 08 · 范式与表设计
status: published
---

# 09 · 事务与 ACID

## 两个请求都"成功"了，库存变成了 -1

这段可以直接自己复现。建一张只有一件商品的库存表，然后开两个会话：

```sql
-- 会话 A
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
BEGIN;
SELECT qty FROM stock WHERE id = 1;   -- 看到 1
-- （先不提交，停在这里）

-- 会话 B（另开一个窗口）
BEGIN;
SELECT qty FROM stock WHERE id = 1;   -- 也看到 1，因为 A 还没提交
UPDATE stock SET qty = qty - 1 WHERE id = 1;
COMMIT;

-- 回到会话 A
UPDATE stock SET qty = qty - 1 WHERE id = 1;
COMMIT;
```

```text
mysql> SELECT qty FROM stock WHERE id = 1;
+------+
| qty  |
+------+
|   -1 |
+------+
```

一件商品卖出了两件，库存 -1。两条 `UPDATE` 都返回"成功"，日志里一条错误都没有。

问题不在事务不够长——两个请求各自的事务都完整、都提交了。问题在于**"查库存"和"扣库存"是两个动作，中间那段窗口没有任何东西守着**，而且扣的那句不带条件。

这一课要解决的就是这类事：事务能保证什么、保证不了什么，以及边界该划在哪。

## 事务：一组操作要么全做、要么全不做

```sql
START TRANSACTION;   -- 或 BEGIN
-- 一组操作
COMMIT;              -- 全部生效
ROLLBACK;            -- 全部撤销
```

MySQL 默认 **自动提交**（`autocommit = 1`）：不写 `BEGIN` 时，每条语句自己就是一个事务，执行完立刻生效。

所以"忘了写 `BEGIN`"不会有任何提示——只是你的三条语句变成了三个独立事务。前两条成功了、第三条失败，前两条照样在库里。

更隐蔽的是**隐式提交**：`CREATE TABLE`、`ALTER TABLE`、`DROP`、`TRUNCATE` 这类 DDL 在执行前会把当前事务提交掉。你在 `BEGIN` 里做了一堆改，插一条 `CREATE TEMPORARY TABLE`（临时表不算），前面那些改动就提前落盘了，后面再 `ROLLBACK` 撤不回它们。

## ACID 四个字母，各自防哪一类故障

| 字母 | 名字 | 它到底承诺了什么 | 破坏它会长什么样 |
| --- | --- | --- | --- |
| A | 原子性 | 一组改动要么整体生效、要么整体不存在 | 转账扣了钱没加钱，钱消失 |
| C | 一致性 | 事务把数据从一个**满足已声明约束**的状态带到另一个 | 唯一索引被绕过；但"总额守恒"这种业务规则**它不管** |
| I | 隔离性 | 并发事务之间的可见性按隔离级别约定 | 读到别人还没提交的脏值；或者同一事务里两次读不一样 |
| D | 持久性 | `COMMIT` 返回成功即已落盘（redo log） | 提交成功、断电后数据没了 |

四个词里最容易误解的是 **C**。

教科书常写"一致性：数据满足约束"，但业务代码里真正关心的是"库存不能为负""转账前后总额不变"这类**不变量**——数据库不知道你的不变量是什么，除非你把它写成约束（`CHECK`、唯一索引、外键）或者写进语句条件（`WHERE qty > 0`）。

一笔订单扣了库存、另一笔没扣成，但从"每行都满足约束"的角度看数据完全合法。**C 要你自己守。**

## 隔离性是针对"别人"的

在同一个事务里，`COMMIT` 之前你查询**能看到自己刚改的值**。这不是 bug——隔离性约束的是别的事务能看见什么，不是你自己。

<!-- run -->
```sql
BEGIN;
UPDATE accounts SET balance = balance - 500 WHERE id = 1;
SELECT name, balance AS after_debit_inside_tx FROM accounts WHERE id = 1;
ROLLBACK;
SELECT name, balance AS after_rollback FROM accounts WHERE id = 1;
```

```text
name=ann | after_debit_inside_tx=300.00
name=ann | after_rollback=800.00
```

同一会话里刚扣完就查到 300，`ROLLBACK` 之后回到 800。

那"别人看不到未提交的改动"这句话什么时候成立？**取决于隔离级别**：

| 隔离级别 | 别人能读到未提交的改动吗 | 同一事务内两次读结果 |
| --- | --- | --- |
| READ UNCOMMITTED | **能**（脏读） | 可能不同 |
| READ COMMITTED | 不能 | 可能不同（不可重复读） |
| REPEATABLE READ（MySQL 默认） | 不能 | 相同（快照） |
| SERIALIZABLE | 不能 | 相同，但读写互相阻塞 |

所以"提交前只有我自己看得到"这句，要限定在 READ COMMITTED 及以上。第四课那个 `-1` 的例子之所以能复现，正是利用了 REPEATABLE READ 的快照读：B 的 `SELECT` 从快照里读，永远看不到 A 后来的提交。

## 回滚撤不掉的三种东西

改数据时，InnoDB 先把**原值**写进 undo log，改的是页面；回滚就按 undo log 反着做回去。所以 `ROLLBACK` 撤销的是**数据库里的数据改动**。

它撤不掉：

1. **已经发出去的邮件、微信通知**——收件人不会因为你的回滚而忘掉；
2. **第三方接口的调用**——下单成功、支付成功的回调已经打到别人系统了；
3. **自增主键的消耗**——`AUTO_INCREMENT` 不回退，回滚了 id 也不会还给你。

所以规则很硬：**事务里只做数据库操作，外部动作放到提交之后。**

需要"提交成功就必须发出去"的场合，用**事务性发件箱**：在同一个事务里往 `outbox` 表插一行"待发消息"，提交后再由独立的发送进程读表、发送、标记已发。这样"数据改了但消息没发"和"消息发了但数据没改"都不会出现——最坏情况是消息发两次，用幂等键兜住。

## 边界划在哪

**一个事务 = 一个业务动作。** "下单"包含扣库存、建订单、建订单明细、扣积分——这四件事必须一起成功。下单之后"发一条通知"不是这个业务动作的一部分，放事务外。

**事务里不做网络调用。** 外部接口可能超时几十秒，这段时间事务占着行锁和连接。并发上来，连接池先耗尽——看起来像"数据库慢"，其实是把网络延迟搬进了事务。

**事务尽量短。** 不要在事务中间做大量计算、循环处理上千行。锁的持有时长和事务长度成正比。

## 那句 UPDATE 应该怎么写

回到开头的 `-1`。修法不是"把事务拉长"，而是两件事：

**第一，把判断放进 `UPDATE` 自己的 `WHERE` 里。**

```sql
UPDATE stock SET qty = qty - 1 WHERE id = 1 AND qty > 0;
```

这一句的执行是原子的：`WHERE` 的判断和更新在同一个语句里，中间不会有别的语句插进来。InnoDB 在拿到行锁之后读的是**最新已提交的值**（当前读，不走快照），所以第二个请求会看到 `qty = 0`，`WHERE` 不匹配，改 0 行。

**第二，检查影响行数。**

<!-- run -->
```sql
BEGIN;
UPDATE stock SET qty = qty - 1 WHERE id = 1 AND qty > 0;
SELECT ROW_COUNT() AS first_buyer_affected;
COMMIT;

BEGIN;
UPDATE stock SET qty = qty - 1 WHERE id = 1 AND qty > 0;
SELECT ROW_COUNT() AS second_buyer_affected;
COMMIT;

SELECT qty AS final_qty FROM stock WHERE id = 1;
```

```text
first_buyer_affected=1
second_buyer_affected=0
final_qty=0
```

- 返回 1：扣成功了；
- 返回 0：条件不满足，什么都没改。

**这个 0 是应用必须处理的信号。** 如果代码不看返回值，就会在库存不足时照样"下单成功"——那是另一种超卖。

库存没有变成 -1，是因为 `WHERE qty > 0` 守住了，**不是因为数据库帮你判断了业务**。

## 动手：找出这段扣库存代码的 bug

这段 JDBC 代码在"库存不足"时会静默成功，把订单记成已支付。找出有问题的那一行。

```code-challenge
{
  "id": "my-09-bug",
  "kind": "bug",
  "title": "库存不足却下单成功",
  "goal": "认出「条件更新的影响行数没被检查」",
  "trap": "SQL 没报错就说明改成功了",
  "code": "public boolean buy(Connection conn, int sku, int qty) throws SQLException {\n    try (PreparedStatement ps = conn.prepareStatement(\n            \"UPDATE stock SET qty = qty - ? WHERE id = ? AND qty >= ?\")) {\n        ps.setInt(1, qty);\n        ps.setInt(2, sku);\n        ps.setInt(3, qty);\n        ps.executeUpdate();\n        return true;\n    }\n}",
  "bugLine": 7,
  "explain": "第 7 行 `executeUpdate()` 的返回值被丢掉了。SQL 的 WHERE 不匹配时不会报错，只是改 0 行——方法仍然 `return true`，调用方以为扣库存成功，接着就把订单写成已支付。正确做法是接收返回值：`int affected = ps.executeUpdate(); if (affected == 0) { conn.rollback(); throw new OutOfStockException(sku); }`。注意这里必须回滚，否则同一事务里已经做过的其他改动会一起提交。"
}
```

## 检查一下

```quiz
{"id":"my-09-quiz","title":"事务三问","questions":[
 {"question":"`UPDATE stock SET qty = qty - 1 WHERE id = 1 AND qty > 0` 返回影响行数 0，说明什么？","options":["语句出错了，要重试","id=1 这行的 qty 已经不满足 `> 0`（或该行不存在），什么都没改","已经扣成功，但统计延迟","连接断了，需要重连"],"answer":1,"explain":"条件更新在条件不满足时匹配 0 行，语句本身不报错。应用必须检查这个 0 并回滚——这正是防超卖的关键一步。"},
 {"question":"为什么事务里不该调第三方接口？","options":["因为接口不支持事务语法","因为网络延迟会拉长事务、长时间占着锁与连接，而接口调用本身又回滚不了","因为接口会跟着回滚数据库","因为会影响调用统计"],"answer":1,"explain":"两头都不对：耗时不可控，把网络延迟搬进了锁持有时间；并且外部副作用不在 undo log 里，回滚撤不掉。"},
 {"question":"回滚能撤销下面哪一项？","options":["事务里发出的一封通知邮件","事务里的一条 INSERT","事务里写的一个本地文件","事务里调用的一次外部接口"],"answer":1,"explain":"undo log 只记录数据页的原值，能撤销的只有数据库改动。外部副作用（邮件、文件、接口调用）都不在 undo log 里。"}
]}
```

## 一句话

事务保证的是**原子、隔离、持久**，以及"声明的约束不被人从中间插一脚破坏"；它不保证你的业务不变量。"查再改"中间永远有窗口，所以判断要写进 `UPDATE` 的条件里，并且**必须检查影响行数**——那个 0，就是库存不足在代码里的唯一回声。

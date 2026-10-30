---
title: 09 · 事务与 ACID
category: mysql-from-zero
order: 9
chapter: 事务与并发
level: advanced
minutes: 24
prerequisite: 08 · 范式与表设计
status: published
---

# 09 · 事务与 ACID

## 这一课你要会

- 用 `BEGIN` / `COMMIT` / `ROLLBACK` 控制边界；
- 说清原子性、一致性、隔离性、持久性各自防的是什么；
- 知道事务边界该划在哪，以及为什么"操作完立刻提交"经常是错的。

## 为什么

转账要扣一个人、加一个人。如果扣完钱、加钱之前服务崩了，钱就消失了。

事务就是让这一组操作"要么全做、要么全不做"。听起来是个很基础的工具，但真正难的是**边界划在哪**——划小了中间状态会被别人看到，划大了锁住的东西太多，并发全被卡死。

## 讲解

### 三个命令

```sql
START TRANSACTION;   -- 或 BEGIN，开始
...                  -- 一组操作
COMMIT;              -- 全部生效
ROLLBACK;            -- 全部撤销
```

在 `COMMIT` 之前，这些改动**只有你自己能看到**；别人读到的还是旧值。这就是隔离性。

注意 MySQL 默认是**自动提交**（`autocommit = 1`）：不写 `BEGIN` 时，每一条语句自己就是一个事务，执行完立刻生效。所以"忘了写 BEGIN"不会有任何提示，只是你的三条语句变成了三个独立事务。

```sketch
{
  "title": "不写 BEGIN，每条语句自己就是一个事务",
  "rows": [
    [
      { "id": "s1", "title": "第一条语句", "sub": "自动提交，立刻生效", "tone": "amber" },
      { "id": "s2", "title": "第二条失败了", "sub": "第一条已经改完了", "tone": "amber" },
      { "id": "begin", "title": "用 BEGIN 包起来", "sub": "这时才谈得上回滚", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "s1", "to": "s2" },
    { "from": "s2", "to": "begin" }
  ]
}
```

### ACID 各自防什么

| 字母 | 名字 | 一句话 | 破坏它会怎样 |
| --- | --- | --- | --- |
| A | 原子性 | 要么全做、要么全不做 | 转账扣了钱没加钱 |
| C | 一致性 | 事务前后数据都满足约束 | 库存变成 -1、余额对不上总额 |
| I | 隔离性 | 并发事务互不看到中间状态 | 读到别人还没提交的脏数据 |
| D | 持久性 | 提交了就一定在磁盘上 | 提交成功但断电后数据不见了 |

这四个词经常被当成口号背，但每一个都对应一类真实故障。**写业务代码时最常踩的是 C 和 I**：

- **C** 不是数据库能自动保证的。它保证的是"约束不被违反"（唯一索引、非空、类型），但"转账前后总额相等"这种业务规则数据库不知道。所以要么在事务里用条件更新守住（比如 `WHERE qty > 0`），要么写校验。
- **I** 与隔离级别直接相关，下一关专门讲。

### 原子性靠什么实现

改数据时，MySQL 先把**原值**写进 undo log，再改。回滚就是按 undo log 反着做回去。所以 `ROLLBACK` 撤销的是"数据改动"，**不撤销磁盘上的其他副作用**。

这条推论很实用：**事务里不要做不可撤销的外部动作**——发邮件、调第三方接口、写文件、发消息。因为那些操作回滚不了。

正确做法是"事务只包数据库操作，外部动作放到事务提交之后做"，或者用"事务性发件箱"模式（把要发的消息写进表，由另一个进程读表发送）。

### 边界该划在哪

这是本关的实战核心。三条原则：

**原则一：一个事务 = 一个业务动作**

"下单"这个动作包含：扣库存、建订单、建订单明细、扣积分。这四件事必须一起成功，所以在一个事务里。

但"下单"之后**发一条通知**不是业务动作的一部分，它应该放在事务外。

**原则二：事务内不要做网络调用**

外部接口可能超时几十秒。在这段时间里，事务占着锁、占着连接。并发一上来，连接池先耗尽——这不是数据库慢，是你把网络延迟搬进了事务。

**原则三：事务尽量短**

即使是纯数据库操作，也要注意不要在事务中间做大量计算、或者循环处理上千行。锁的持有时间与事务长度成正比。

### 一个必须知道的坑：条件更新要检查影响行数

库存扣减最常见的写法：

```sql
UPDATE stock SET qty = qty - 1 WHERE id = 1 AND qty > 0;
```

这句本身是原子的——`WHERE` 的判断与更新在同一个语句里，不会被别的语句插进来。但**它扣没扣成功，看的是影响行数**：

- 返回 1：扣成功了；
- 返回 0：库存不足，什么都没改。

如果代码不检查返回值，就会在库存不足时也认为"下单成功"。这就是超卖的经典成因。

**所以：条件更新的返回值必须检查。** 应用层拿到 0 应该回滚并返回"库存不足"。

这比"先 `SELECT` 查库存、再 `UPDATE`"可靠得多——后者在两个请求之间会有窗口期，两个都查到"有库存"，然后都扣。

## 跑一遍

<!-- run -->
```sql
CREATE TABLE accounts (
  id      INT PRIMARY KEY,
  name    VARCHAR(20) NOT NULL,
  balance DECIMAL(10,2) NOT NULL
);
INSERT INTO accounts VALUES (1,'ann',1000.00), (2,'bob',500.00);

SELECT SUM(balance) AS total_before FROM accounts;

BEGIN;
UPDATE accounts SET balance = balance - 200 WHERE id = 1;
UPDATE accounts SET balance = balance + 200 WHERE id = 2;
SELECT name, balance FROM accounts ORDER BY id;
COMMIT;

SELECT name, balance FROM accounts ORDER BY id;
SELECT SUM(balance) AS total_after FROM accounts;

BEGIN;
UPDATE accounts SET balance = balance - 500 WHERE id = 1;
SELECT name, balance AS after_debit_inside_tx FROM accounts WHERE id = 1;
ROLLBACK;

SELECT name, balance AS after_rollback FROM accounts WHERE id = 1;
SELECT SUM(balance) AS total_final FROM accounts;
```
```text
total_before=1500.00
name=ann | balance=800.00
name=bob | balance=700.00
name=ann | balance=800.00
name=bob | balance=700.00
total_after=1500.00
name=ann | after_debit_inside_tx=300.00
name=ann | after_rollback=800.00
total_final=1500.00
```

看三件事：

1. 事务内查询已经能看到自己改的值（`800` 与 `700`）——隔离性是针对**别人**的，自己的改动自己立刻可见。
2. `total_before = total_after = total_final = 1500.00`：转账与回滚都没有改变总额。
3. `after_rollback=800.00`：`ROLLBACK` 把 500 那次扣款完整撤销了。

### 条件更新与影响行数

<!-- run -->
```sql
CREATE TABLE stock (
  id   INT PRIMARY KEY,
  name VARCHAR(20) NOT NULL,
  qty  INT NOT NULL
);
INSERT INTO stock VALUES (1, '手机', 1);

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

`second_buyer_affected=0` 就是超卖被挡住了的证据。**如果代码不检查这个 0，就会卖出第二台。** 库存没有变成 -1，是因为 `WHERE qty > 0` 守住了，而不是因为数据库帮你判断了业务。


## 动手

这道题会在真实的 MySQL 里跑。写错不会扣分，可以反复试——
但**结果对不上会明确告诉你哪个数据集不一致**。

```code-challenge
{
  "id": "my-09-fix",
  "kind": "sql-fix",
  "title": "让扣库存真正扣得动",
  "goal": "能识别「条件更新不检查影响行数」与「事务边界写错」两种坑",
  "trap": "数据库保证了业务的一致性",
  "dataset": [
    "orders-public",
    "orders-hidden-a"
  ],
  "normalize": {
    "rowOrder": "ignore",
    "columnOrder": "ignore",
    "types": {
      "remaining": "int"
    }
  },
  "expectHash": {
    "orders-public": "cd92fcbf32d17b7a4e1ed63ae562d348e90a4b4c4cb7477ca6a870813a6002d7",
    "orders-hidden-a": "cd92fcbf32d17b7a4e1ed63ae562d348e90a4b4c4cb7477ca6a870813a6002d7"
  },
  "explain": "本题演示的是「条件更新必须检查影响行数」：`UPDATE … WHERE id > 0` 在条件不满足时返回 0 行，但语句本身不报错。应用若把这当成成功，就会记一笔没有库存支撑的订单。数据库只保证约束不被违反，保证不了你的业务规则。",
  "starter": "BEGIN; UPDATE customers SET id = id - 1 WHERE id = 1 AND id > 0; COMMIT; SELECT id AS remaining FROM customers WHERE id = 1;"
}
```

## 检查一下

```quiz
{"id":"my-09-quiz","title":"事务三问","questions":[
 {"question":"UPDATE ... WHERE id=1 AND qty>0 返回影响行数 0，说明什么？","options":["语句出错了","条件不满足（库存不足），什么都没改","已经扣成功","连接断了"],"answer":1,"explain":"条件更新在条件不满足时不匹配任何行，影响行数为 0，必须由应用判断并回滚。"},
 {"question":"为什么事务里不该调第三方接口？","options":["因为接口不支持事务语法","因为网络延迟会拉长事务、长时间占着锁与连接","因为接口会回滚数据库","因为会影响统计"],"answer":1,"explain":"外部调用不可回滚，且耗时不可控，会让锁与连接被占用更久。"},
 {"question":"回滚能撤销下面哪一项？","options":["事务里发出的一封邮件","事务里的一条 INSERT","事务里写的一个本地文件","事务里调用的一次外部接口"],"answer":1,"explain":"undo log 只能撤销数据库改动，外部副作用不可回滚。"}
]}
```

## 常见误区

### 误区：写了 `BEGIN` 就一定有事务范围

**你以为**：语句会自动被包起来。
**其实**：MySQL 默认自动提交，不写 `BEGIN` 时每条语句各自成事务。而且某些语句（如 DDL）会**隐式提交**当前事务，把前面的操作提前落盘。
**怎么验证**：在 `BEGIN` 之后执行一条 `CREATE TABLE`，再 `ROLLBACK`，看前面的改动是否被撤销。

### 误区：事务越长越安全

**你以为**：把所有操作包进一个大事务最保险。
**其实**：事务越长，锁持有越久、并发越差、回滚代价越大，冲突也越多。
**怎么验证**：观察长事务期间其他会话对同一行的写入是否被阻塞。

### 误区：数据库保证了业务的一致性

**你以为**：有事务就不用管业务规则。
**其实**：数据库只保证约束（唯一、非空、类型）不被违反。"总额守恒""库存不为负"这类规则得靠条件更新或应用校验。
**怎么验证**：不加 `AND qty > 0`，并发扣两次库存，看是否变成 -1。

## 小结

事务让一组操作要么全做要么全不做。ACID 各自对应一类故障，其中一致性与隔离性最需要业务自己配合。事务内只做数据库操作，外部动作放到提交之后。条件更新必须检查影响行数——这是防超卖的关键。

下一关：两个人同时读写同一批行时，各自能看到什么。这就是隔离级别。

**延伸阅读**：[MySQL 8.0 参考手册 · InnoDB 事务模型](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-model.html)（undo log、隔离级别与锁的完整说明）。

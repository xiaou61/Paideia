# 课程源文件

这里的每一课都是**知识库条目**的源文件。改内容走这里，不进后台手抄。

## 目录

```
content/courses/python/00-总览.md
content/courses/python/01-环境与第一行代码.md
content/courses/java/00-总览.md
content/courses/java/01-编译运行与变量.md
content/courses/mysql/00-总览.md
content/courses/mysql/01-第一张表.md
```

一条线 = 1 个总览 + N 课。目录名对应分类 slug：`python` → `python-from-zero`，`java` → `java-from-zero`，`mysql` → `mysql-from-zero`。

## 文件格式

```markdown
---
title: 03 · 循环
category: python-from-zero
order: 3
minutes: 22
prerequisite: 02 · 运算与条件
status: published
---

## 这一课你要会
...
```

| 字段 | 说明 |
| --- | --- |
| `title` | 条目标题。**导入按「分类 + 标题」匹配**，改名等于新建一条（旧条目不会被删） |
| `category` | 分类 slug |
| `order` | 站序，从 0（总览）到 12；写进 `knowledge_entries.sort_order` |
| `minutes` | 预计时长，用于作者自查（当前不参与接口） |
| `prerequisite` | 前置课的**标题**；导入后建 `prerequisite` 关系 |
| `status` | 只允许 `published`。草稿请先别放进来，避免线上出现半成品 |

## 七个小节（顺序固定，校验脚本会查）

1. `## 这一课你要会` —— 3 条以内，可检验的目标
2. `## 为什么` —— 这个东西解决什么问题；不写"很重要"
3. `## 讲解` —— 正文 + 至少一个 `<!-- run -->` 示例
4. `## 动手` —— 一个 `code-challenge` 围栏块
5. `## 检查一下` —— 一个 3 题 `quiz` 围栏块
6. `## 常见误区` —— 至少两条，格式固定
7. `## 小结` —— 一段话 + 下一课

## 可运行示例

```markdown
<!-- run -->
```python
print("你好")
```
```text
你好
```
```

`<!-- run -->` 后面紧跟的代码块会被**真的执行**，输出必须与紧接着的 ```text 块逐字相同。
Python 示例不得依赖第三方库；Java 示例必须是**单个 `Main` 类**（校验脚本按 `Main.java` 编译运行）。

SQL 示例（```sql）在**一次性库**里执行，执行完即删，绝不碰 `paideia_test`。它需要本机 MySQL 与 `pymysql`，凭据从 `PAIDEIA_TEST_DB_USER` / `PAIDEIA_TEST_DB_PASSWORD` 读（`source backend/config/remote-db.local.env`）；缺凭据时**跳过执行**，不报错。输出按 `列=值 | 列=值` 逐行比对——所以 SQL 示例里只查结果，不要查带时间戳或自增 id 的列，那些每次都不一样。

## 动手题的形状

````markdown
```code-challenge
{"id":"py-03-order","kind":"order","title":"把打印 1 到 3 的循环排好",
 "lines":["for i in range(1, 4):","    print(i)","print(\"结束\")"],
 "answer":[0,1,2],
 "explain":"range(1, 4) 不含 4。"}
```
````

四种 `kind`：`fill`（填空）、`order`（行序）、`bug`（找 bug 行）、`run`（改代码后运行比对）。
**课程必须有非执行题**（前三种之一），因为线上沙箱可能没开。

## 小测的形状

````markdown
```quiz
{"id":"py-03-quiz","title":"循环三问","questions":[
  {"question":"range(1,4) 会产出哪些数？","options":["1,2,3","1,2,3,4","0,1,2,3"],"answer":0,
   "explain":"range 的右端是开区间。"}
]}
```
````

每课固定 3 题；答案必须能从本课正文推出来。

## 误区

```markdown
### 误区：缩进只是排版

**你以为**：空格多少无所谓，对齐好看就行。
**其实**：缩进参与语法，决定哪些语句属于同一块。
**怎么验证**：把某一行少缩进一格再运行，看是否报 `IndentationError`。
```

## 校验与导入

```bash
node scripts/check-content.mjs --scope content  # 统一静态校验（schema + 块结构 + 引用 + order）
node scripts/check-content.mjs --exec           # 同上，再真跑 verify_course.py
python scripts/verify_course.py                 # 结构 + 真跑代码 + 块 JSON
python scripts/verify_course.py --dir content/courses/python
node scripts/import-course.mjs --dry-run        # 只看要做哪些写入（导入前会自动跑统一校验）
node scripts/import-course.mjs                  # 真的导入（走管理端 API）
```

`check-content.mjs` 依 `content/schema/lesson.schema.json` 校验——schema 是内容合法性的唯一权威；
`content/schema/whitelist.json` 是过渡白名单（`maxEntries` 只减不增）。`verify_course.py` 不连数据库、不连网络；只用本机的 `python` 与 `javac`/`java`。

# 内容质量整改进度（去 AI 味 / 补质量）

> 这个文件是给「隔几天回来接着干」用的交接记录。规则与写法在 `docs/content-rewrite-playbook.md`，改稿前先读它。

## 为什么做

全库 480 篇（478 篇可校验）内容诊断结论：**读得懂，但 79% 是同一个骨架，作者不在场。**

- 模板化：`常见误区` 378/480（79%）、`检查一下` 245、`小结` 212、`讲解` 207、`先把结论摆出来` 147、`这一课你要会` 97、`带走一句` 90。
- 词汇层套话几乎没有（`值得注意的是` 0 处）——上一轮已经清过表面套话，所以这次要动的是**结构**。
- 人味指标极低：第一人称经验词（`我第一次`/`我当时`/`我踩过`/`我们线上`）全库仅 6 处、`我原来以为` 0 处、`踩坑` 31 处。

## 改法（已定的流水线）

```
reader agent（站在人的角度读，找读不懂 / 照做会学错）
  → writer agent 或我按 playbook 改写
  → 门禁（check-content + verify_course）
  → 抽查 diff（expectHash / run 块输出 / 未跑过的实测声明 / 课时依赖顺序）
  → 只 git add content/ 提交
```

三条硬规则（细则见 playbook）：

1. 第一屏必须是**可跑工件**：真实报错、真实计时、真实探针输出。前 300 字不出现比喻与分类。
2. 数字要么本机实测、要么标出处与年代；**不许编造真实事故**，没有真实经历就写「我在练手项目里」并承认是玩具例子。
3. 删掉「这一课你要会 / 常见误区 / 小结」三件套，结尾换成「还差的（这次没搞清楚的）」。

## 门禁命令

```bash
node scripts/check-content.mjs                      # 全库：frontmatter + 结构化块 JSON，必须 0 error
python scripts/verify_course.py --dir content/courses/<线>   # 课程：真跑 run 块并逐字比对输出
python scripts/verify_course.py --no-run --dir content/courses/mysql   # mysql 本机跑不了，只能结构校验
```

已知限制：

- `paideia_e2e` 账号建不了库，`sql_cases.py --check` 与 mysql 的 SQL 示例**本机无法验证**（报 `1044 Access denied ... paideia_cases_*`）。
- `check-content.mjs` 要求结构化块 JSON 里**不能出现裸 ASCII 双引号**，中文引号用「」。
- `content/` 下文件多为 CRLF，跨多行替换用整篇 `write`，别用 `apply_patch`。
- 本机 `javac 25`，课程基线 Java 21：新语法示例要么 `--release 21` 编译，要么在正文注明版本。

## 进度

- 起点提交：`cd14f5bd` 之前。
- 已改写：`git diff --name-only cd14f5bd~1 HEAD -- content` 统计（含少量 case JSON）。
- 打分分布（`node scripts/audit-ai-flavor.mjs --all`，478 篇）：

| 档位 | 整改前 | 现在 |
| --- | --- | --- |
| 10 分（最模板化） | 64 篇 | 8 篇 |
| ≥9 分 | — | 8 篇 |
| 6–8 分 | — | 209 篇 |
| 3–5 分 | — | 101 篇 |
| 0–2 分 | — | 89 篇 |
| 负分（人味与硬证据都在） | 33 篇 | 71 篇 |

- 已整线过一遍（reader 全审 + 逐篇改）：java 01/03/10/20/28/31/32/33/35/36、python 03/04/05/12/13/14/16/17/18、mysql 01/02/03/04/05/07/08/09/10/11/12/13/14、spring 01/02/03/09/10/11/16、reading 的 agents 01/03、docker 01/02/03/13/14/17/18/19/20、ops 01/02/03/04/05/06/07/08、linux-from-zero 01/02/03/04、network 01、algorithms 01、books-ddia 02。

- 下一步：6–8 分档还有 209 篇，优先 `content/courses/python`（02/07/08/10/11 仍是最高档）与 `content/reading/os`、`content/reading/linux`、`content/reading/git`、`content/reading/agents` 剩余篇目。

## 逐篇改稿时最常踩的六类错（都是本系列实测抓到的）

1. **方向性讲反**：`while` 条件不成立是零次执行（不是卡死）；`nohup` 让进程在关终端后还活着（不是死掉）；`location /api` 能匹配 `/api/users`（不是只匹配 `/api`）。
2. **缓存与默认值记成语言保证**：`Integer` 只保证 `-128..127`；`mysqldump` 不带 `--databases` 恢复必撞 `1049`；Let's Encrypt 是 90 天不是一年。
3. **`NULL` 与空集**：`COUNT(*)` 数所有行、`COUNT(列)` 跳过 NULL；空集 `SUM` 是 `NULL`；`x NOT IN (1, NULL)` 永不为真。
4. **索引与排序语义**：并列行顺序未定义；行值比较要求同向；`possible_keys` 有值而 `key` 为 NULL 是优化器主动放弃。
5. **课时依赖**：`record` 第 35 课、装饰器第 15 课、`LEFT JOIN` 第 05 课、列表推导式第 18 课、Lambda 第 21 课——提前用到就要标注「先照抄」。
6. **假实测**：agent 声称「我用 XX 跑过」先查环境（本机有没有 docker / promtool / Linux），查不到就改成诚实表述或换成本机能跑的验证。

---
title: 拆解 Superpowers（下）：跑一遍完整链路
category: vibe-coding-from-zero
order: 7
chapter: Skills 与工程纪律
minutes: 20
level: intermediate
status: published
---

## 第一次红，红在加载器里

```text
$ node --test
node:internal/modules/esm/resolve:275
    throw new ERR_MODULE_NOT_FOUND(
          ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find module 'C:\Users\Lenovo\AppData\Local\Temp\sp-tdd\slug.mjs'
 imported from C:\Users\Lenovo\AppData\Local\Temp\sp-tdd\slug.test.mjs

Node.js v24.14.1
✖ slug.test.mjs (126.8803ms)
ℹ tests 1
ℹ fail 1
ℹ duration_ms 137.0528

✖ failing tests:
test at slug.test.mjs:1:1
✖ slug.test.mjs (126.8803ms)
  'test failed'
```

（路径中间截了几段；本机 Node v24.14.1，PowerShell 7。）

这是照着 `writing-plans` 的 Task 结构做第一步的结果：Step 1 写测试，Step 2 跑一遍看它红。红是红了，可它红在模块解析——测试函数一行都没执行，`node --test` 只报出一条名叫 `slug.test.mjs` 的失败，正文是 `'test failed'`，你分不出挂的是哪条断言。

TDD 的 SKILL.md 把这件事单列成一条检查项：失败必须是 `Test fails (not errors)`，原因得是「功能缺失」而不是别的，后面紧跟一句 `Test errors? Fix error, re-run until it fails correctly.` 所以在 Node 这种 ESM 项目里，「先把测试写出来」只写了一半：被测的符号得先存在。补个空壳，红才落到断言上：

```text
$ node --test
✖ 英文标题：空格折成连字符、全小写 (1.1282ms)
ℹ tests 1
ℹ pass 0
ℹ fail 1
ℹ duration_ms 139.4182

✖ failing tests:
test at slug.test.mjs:5:1
✖ 英文标题：空格折成连字符、全小写 (1.1282ms)
  AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:
  '' !== 'hello-world'
```

两条红的差别：第一条只证明我文件写错了；第二条证明函数在、断言跑了、失败原因就是「返回空串」。整条链路都在拿产物说话，产物得先是那个东西，而不是它的回声。

| 你在 `node --test` 看到 | 它说明 | 下一步 |
| --- | --- | --- |
| `ERR_MODULE_NOT_FOUND` / `Cannot find module` | 断言一行没跑，被测符号不存在 | 先建函数返回空串，再跑一次 |
| `AssertionError … '' !== 'hello-world'` | 函数在、断言跑了、功能没实现 | 这次的红能算数，去写实现 |
| `✖ 文件名 (…)` 加 `'test failed'` | 整个文件崩了，不是某条断言 | 单独跑这个文件，往上翻第一段栈 |
| 绿了，但 `ℹ fail 1` | 挂的是别的测试或环境问题 | 找那条 ✖，别改自己刚写的 |

上一篇把七步和技能清单铺开了；这一篇只回答一个问题：照着它做，第一次真的跑起来会发生什么。引的 README 与两份 SKILL.md 取自 `obra/superpowers` 的 main 分支，2026-11-19 拉取。

## 一条函数走完全程

链路收窄到一个纯函数：笔记标题 → `/notes/<slug>`。规格是聊天里确认的两句——英文标题小写、空格折成连字符；标题里出现中文时不能整段丢掉。第二句是我漏掉、被复述时才补上的。如果没复述，到测试阶段才发现，就得判断该改测试还是改实现，而这两种改法当天看起来都对。

计划落到文件里（`docs/superpowers/plans/2026-11-19-note-slug.md`，这个路径格式是 `writing-plans` 的默认值），Task 1 长这样：

```markdown
### Task 1: 标题 → slug 的基础转换

**Files:**
- Create: `slug.mjs`
- Test: `slug.test.mjs`

**Interfaces:**
- Produces: `slugify(title: string) -> string`

- [ ] **Step 1: Write the failing test**
- [ ] **Step 2: Run test to verify it fails** — Run: `node --test`，Expected: FAIL
- [ ] **Step 3: Implement `slugify(title: string): string` in `slug.mjs`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**
```

`Interfaces` 那段别跳过：它写着后面几个 task 只能看见自己的任务，靠这一段知道邻居用什么名字、什么类型。SKILL.md 的自查项里专门点了一个例子——Task 3 叫 `clearLayers()`、Task 7 写成 `clearFullLayers()`，就是这种错。

注意计划头部还有一个 `## Review Focus`，我写的是「（空）」。SKILL.md 要求这节列「规格隐含、但没有任何 task 的测试会覆盖到」的失败模式，并说明空的一节等于「查过了，没有」，不是「跳过检查」。这两句的差别，下一节回来算账。

## 先绿一条，再让它红

第一条实现按最省事的写法来：

```js
export function slugify(title) {
  return title
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}
```

T1 绿了。加中文那条，红了：

```text
✖ 中文标题不能被清成空串 (0.741ms)
  AssertionError [ERR_ASSERTION]: Expected values to be strictly equal:
  + actual - expected

  + 'superpowers'
  - '拆解-superpowers-下'
```

不是我预判的「返回空串」，是「只剩 superpowers」——`[^a-z0-9]` 是 ASCII 白名单，四个汉字连同全角括号一起被当成分隔符吞掉。想换 `\w` 也救不了：

```text
$ node -e "console.log(/\w/.test('拆'), /\w/u.test('拆'), /\p{L}/u.test('拆'), /\p{L}/.test('拆'))"
false false true false
```

`\w` 是 `[A-Za-z0-9_]`，加 `u` flag 不改这个集合，它改的是码点解析方式；Unicode 字母要走 `\p{L}`。最后那个 `false` 更值得记住：少写 `u` 不报错，`\p` 退化成字面 `p`，`{L}` 也不再是量词，整条正则变成匹配字面量 `p{L}` 这四个字符——单独的 `p` 反而不匹配（我试了，`/\p{L}/.test('p')` 是 false）。它匹配不到中文，也不报错，安静地当一个永不触发的分支。改正则时最容易在这种地方漏一个字母。

修法就一行：

```js
.replace(/[^\p{L}\p{N}]+/gu, '-')
```

## 绿了以后先问：它拦得住什么

TDD 的核心那句是「没看着它红过，就不知道它测的是不是对的」。这句没法直接执行，我改成可数的：给实现留一个 `MUT` 开关，四种改坏各跑一遍 `node --test`，看哪条测试在哪种改坏下变红。

| 把实现改坏成 | T1 英文 | T2 中文 | T3 不含空格 | T4 碰撞 |
| --- | --- | --- | --- | --- |
| `return ''` | ✖ | ✖ | ✔ | ✔ |
| 不做小写 | ✖ | ✖ | ✔ | ✖ |
| 不去首尾连字符 | ✔ | ✖ | ✔ | ✔ |
| 退回 `[^a-z0-9]` | ✔ | ✖ | ✔ | ✔ |
| 能拦住几个 | 2 | 4 | 0 | 1 |

T3 是 0。它在四种改坏下全绿，因为断言是「不含空格」，而空串也不含空格——这条测试写得最快、看起来最像「我有测试了」，也最拦不住东西。T4 要打个折：它断言两个 slug 相等，`return ''` 的变异下同样成立（`'' === ''`），所以它记录的是当前行为，不是要求的行为。

这个矩阵不是覆盖率，是「这条测试对哪类错误有反应」。它也不需要工具，四行代码加一次循环就能跑。

## 回到留空的那一节

补 `## Review Focus` 时，我拿真实输入试了四组：

```text
$ node -e "import('./slug.mjs').then(({slugify})=>{for (const t of ['Hello World','hello world','C 入门','C++ 入门','C# 入门','!!!','İstanbul']) console.log(JSON.stringify(t).padEnd(18), '->', JSON.stringify(slugify(t)))})"
"Hello World"      -> "hello-world"
"hello world"      -> "hello-world"
"C 入门"            -> "c-入门"
"C++ 入门"          -> "c-入门"
"C# 入门"           -> "c-入门"
"!!!"              -> ""
"İstanbul"         -> "i-stanbul"
```

四条后果：

- 大小写不同的两个标题拿到同一个 slug，两条笔记抢一个 URL。
- 更糟的是 C 系列：三条不同标题全是 `c-入门`，`+`、`#` 被折成一个连字符后，区别就没了。
- 纯标点标题得到空串，`/notes/` 后面什么都没有。
- `İstanbul` 变成 `i-stanbul`：大写 İ 小写化后是 `i` 加一个组合点，组合符号不在 `\p{L}` 里，被折成连字符。要修得先 `normalize('NFKC')`，那是另一条 task。

这四条没有一条是流程替我发现的。计划的 `Interfaces` 只写了 `slugify(title: string) -> string`，唯一性被推给「调用方负责」——而调用方是谁、怎么负责，计划里没有 task。空着的 `## Review Focus` 就是我在计划里写下的「我查过了，这里没有问题」，链路上没有任何一步会去质疑这句话。

这是照做一遍最实在的收获：七步都在，每一环都在等一个产物，而产物可以是空的。流程能保证「该填的地方填了」，不保证「填的是一句真话」。

## 跑一遍

```bash
mkdir $env:TEMP\sp-tdd; cd $env:TEMP\sp-tdd
# slug.test.mjs 里放上面那四条测试，slug.mjs 里放修好的实现
node --test
```

```text
✔ T1 英文标题：空格折成连字符、全小写 (1.1512ms)
✔ T2 中文标题不能被清成空串 (0.46ms)
✔ T3 slug 里不出现空格 (0.1312ms)
✔ T4 大小写不同的两个标题会撞成同一个 slug (0.0715ms)
ℹ tests 4
ℹ pass 4
ℹ fail 0
ℹ duration_ms 138.8354
```

`duration_ms` 那行别当结论用，它只说明玩具函数跑得快。真正值得记住的数字是矩阵里那个 0。

## 动手

把 `## Review Focus` 补成三条：同名碰撞、纯标点空串、组合字符。每条都要落到某个 task 的测试里，顺序照旧——先加测试看它红，再动实现。

碰撞这条会改签名。`slugify` 是纯函数，它看不见已有笔记，唯一化只能由调用方把已有集合传进来，或者落库时加后缀。签名一动，Task 1 的 `Interfaces` 和后面每个 task 的 `Consumes` 都得跟着改——这正是上面那个 `clearLayers` / `clearFullLayers` 的例子要防的事。

`İstanbul` 那条先 `normalize('NFKC')` 再折字符。按同样的流程走，你会先看到一个 `i-stanbul` 的红，再看到它绿；这两步都自己跑过，才算真的照做过一遍。

## 检查一下

```quiz
{"id":"vibe-07-quiz","title":"红要红在哪一行","questions":[
 {"question":"第一次 `node --test` 报 ERR_MODULE_NOT_FOUND，测试函数一行没执行。这次红算不算 TDD 里的 RED？","options":["算，只要退出码非 0 就算红","算，因为测试文件已经写好了","不算，得先让被测符号存在，让失败落在断言里","不算，因为实现还没写"],"answer":2,"explain":"TDD 的检查项写的是 Test fails (not errors)，失败原因必须是功能缺失。文件级加载失败发生在断言之前，它只证明文件写错了。"},
 {"question":"中文标题被折成只剩 superpowers，第一处该看哪里？","options":["`.trim()` 是不是把中文裁掉了","`.toLowerCase()` 在中文上出错","输出编码是不是坏了","白名单用的字符类是不是只认 ASCII，`[^a-z0-9]` 和 `\\w` 都属于这类"],"answer":3,"explain":"`[^a-z0-9]` 把汉字当分隔符；`\\w` 是 `[A-Za-z0-9_]`，加 u flag 也不改这个集合。要 Unicode 字母得用 `\\p{L}` 配 u。"},
 {"question":"一条测试在「返回空串／不做小写／不去尾连字符／退回 ASCII 白名单」四种改坏下都绿，说明什么？","options":["它断言的东西与这四类错误无关，拦不住它们","这四种改坏都不影响功能","需要把覆盖率提上去","是测试框架的断言写错了"],"answer":0,"explain":"测试只能拦它断言到的那件事。「不含空格」对同样是空串的输出为真，所以它四种改坏全绿——这不是覆盖率问题，是断言选错了对象。"}
]}
```

## 还差的

- 没真装 superpowers 跑一遍。本机 `~/.dsh/skills` 下 23 个 skill 里没有它；七步中只有写计划、执行、TDD 三步我照 SKILL.md 的模板手动走过，brainstorming 的复述、worktree 基线、code review 的产物都是按流程自己造的。涉及插件自动触发、子智能体派发、PR 收尾的部分，属于「按公开说明」，不是实跑。
- 版本只核到日期，没核到提交：README 与两份 SKILL.md 取自 main 分支 2026-11-19；上一篇记的 v7.0.2 / `22f0b463` 是 2026-11-03 的值，我没有比对这个 tag 与 main 是不是同一个提交。
- 变异矩阵是手写的 4 条测试 × 4 种改坏，样本太小，不能当覆盖率读；也没有做「测试写得更好时矩阵会变成什么样」的对照。
- 碰撞的处置没实现：`slugify(title, taken)` 与后缀策略只写了思路，一行代码没有，所以它现在仍然是「计划里承认、代码里没管」的状态。
- 输出格式只在 Windows 11 + PowerShell 7 + Node v24.14.1 上核过。Linux 与 macOS 上 `node --test` 的 reporter 输出不同，照抄这段对照表可能对不上。

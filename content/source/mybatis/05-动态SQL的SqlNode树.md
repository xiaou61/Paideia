---
title: 动态 SQL：一棵 SqlNode 树，不是字符串拼接
category: mybatis-source
order: 5
minutes: 24
level: intermediate
status: published
---

> 版本：mybatis 3.5.19（revision `ee0d4f4`），源码核对日期：2026-11-19。

## 先分清：`#{}` 不在 `XMLScriptBuilder` 里变成 `?`

先给结论：`XMLScriptBuilder` 只负责把 XML 片段翻译成 `SqlNode` 树，并决定这棵树交给 `DynamicSqlSource` 还是 `RawSqlSource`。它看到 `#{}` 不会改字符串，只把文本分成两类：含 `${}` 的 `TextSqlNode`，不含 `${}` 的 `StaticTextSqlNode`。`#{id}` 原样留在文本里，直到 `SqlSourceBuilder.parse` 才变成 `?`。这是后面所有关卡的地基。

假设 `SqlSourceBuilder` 收到的 `parameterType` 是 `UserQuery`（例如在 `<select>` 上声明 `parameterType=UserQuery`），`getId()` 返回 `Long`，`getName()` 返回 `String`：

```xml
<select id="findUser" resultType="User">
  select id, name from user
  where id = #{id} and name = #{name}
</select>
```

按 3.5.19 的 `SqlSourceBuilder` 走一遍，得到：

```text
sql = select id, name from user where id = ? and name = ?
parameterMappings = [
  ParameterMapping(property = "id",   javaType = Long),
  ParameterMapping(property = "name", javaType = String)
]
```

为了读起来清楚，这里把空白压成了一行；`SqlSourceBuilder` 默认不会重排空白，除非 `shrinkWhitespacesInSql=true`。

`SqlSourceBuilder:44-55` 建 `GenericTokenParser("#{", "}", handler)`，`handleToken:89-92` 每命中一个 `#{}` 就 `parameterMappings.add(...)` 并返回 `?`。两个 `?` 的顺序就是两个 mapping 的顺序。注意这是「按源码走」的对照，不是某次日志打印；`javaType` 也只在这个参数类型下才是 `Long/String`，换成 `Map` 入参会走 `Object` 分支（`SqlSourceBuilder:98-113`）。

这段 SQL 没有 `${}`，也没有元素节点，所以 `XMLScriptBuilder:84-89` 把它放进 `StaticTextSqlNode`，`isDynamic` 仍是 false；`parseScriptNode:68-72` 选 `RawSqlSource`，`#{}` 的替换发生在构建期。带 `<if>` 时选 `DynamicSqlSource`，同一个 `SqlSourceBuilder` 改在每次 `getBoundSql` 时调用。转换位置没变，只是调用时机变了。

`${}` 走的是另一条路：`TextSqlNode` 用 OGNL 取值后直接拼进 SQL；值如果来自用户输入，就有注入风险。`#{}` 才会留下 `?` 和 `ParameterMapping`。

```source-walk
{
  "id": "dynamic-sql-nodes",
  "title": "动态 SQL 的建树与执行",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "label": "XMLScriptBuilder"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/IfSqlNode.java",
      "label": "IfSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "label": "ForEachSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "label": "TrimSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/WhereSqlNode.java",
      "label": "WhereSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/SetSqlNode.java",
      "label": "SetSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/ChooseSqlNode.java",
      "label": "ChooseSqlNode"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/MixedSqlNode.java",
      "label": "MixedSqlNode"
    }
  ],
  "steps": [
    {
      "title": "先决定 Dynamic 还是 Raw",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 65,
      "lines": 10,
      "explain": "`parseScriptNode` 先把整棵 XML 片段交给 `parseDynamicTags`，再看 `isDynamic`：true 就 new `DynamicSqlSource`，false 就 new `RawSqlSource`。`#{}` 不参与这个判断。",
      "state": {
        "vars": [
          [
            "isDynamic",
            "false（只有 #{} 时）"
          ],
          [
            "rootSqlNode",
            "MixedSqlNode"
          ]
        ]
      },
      "predict": {
        "question": "一条只有 #{}、没有任何动态标签和 ${} 的 SQL，用哪种 SqlSource？",
        "options": [
          "DynamicSqlSource",
          "RawSqlSource",
          "StaticSqlSource"
        ],
        "answer": 1,
        "reveal": "`isDynamic` 仍为 false，走 RawSqlSource；#{} 的替换在它构造时调用 SqlSourceBuilder。"
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "注册九种标签处理器",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 53,
      "lines": 11,
      "explain": "`initNodeHandlerMap` 注册 trim、where、set、foreach、if、choose、when、otherwise、bind。when 复用 IfHandler；otherwise 复用 OtherwiseHandler；bind 走 BindHandler。",
      "state": {
        "tables": [
          [
            "元素节点",
            "trim / where / set / foreach / if / choose / when / otherwise / bind"
          ],
          [
            "未知标签",
            "parseDynamicTags 里抛 BuilderException"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "文本分支与元素分支",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 76,
      "lines": 26,
      "explain": "文本/CDATA 先 new `TextSqlNode`，它的 `isDynamic()` 只在发现 `${}` 时为真；有 `${}` 就加 TextSqlNode 并置 `isDynamic=true`，没有就加 StaticTextSqlNode。元素节点查 `nodeHandlerMap`，查不到在 93-94 行抛「Unknown element」；查到就交给 handler，并在 97 行无条件置 `isDynamic=true`。",
      "state": {
        "tables": [
          [
            "文本含 ${}",
            "TextSqlNode + isDynamic=true"
          ],
          [
            "文本只有 #{}",
            "StaticTextSqlNode，isDynamic 不变"
          ],
          [
            "任意元素节点",
            "handler 建节点 + isDynamic=true"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "choose 收集 when / otherwise",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 211,
      "lines": 38,
      "explain": "`ChooseHandler` 把子节点分成 whenSqlNodes 与 otherwiseSqlNodes：前者必须是 IfHandler，后者必须是 OtherwiseHandler。`getDefaultSqlNode` 只接受一个 otherwise；多于一个在 244-245 行抛 BuilderException。",
      "state": {
        "vars": [
          [
            "whenSqlNodes",
            "按出现顺序"
          ],
          [
            "otherwiseSqlNodes",
            "最多 1 个"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "bind 生成 VarDeclSqlNode",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 107,
      "lines": 13,
      "explain": "`BindHandler` 读 `name` 与 `value`，new `VarDeclSqlNode` 后加进目标列表。`VarDeclSqlNode` 本身不在本快照，按同一 commit 的上游原文核对，不给快照行号。",
      "state": {
        "tables": [
          [
            "bind name",
            "变量名"
          ],
          [
            "bind value",
            "OGNL 表达式"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "If：test 为真就返回 true",
      "file": "org/apache/ibatis/scripting/xmltags/IfSqlNode.java",
      "line": 32,
      "lines": 7,
      "explain": "`evaluator.evaluateBoolean(test, bindings)` 为真时先 `contents.apply(context)`，然后返回 true；为假直接返回 false。它不检查子节点有没有拼出内容，也不看子节点的返回值。",
      "state": {
        "vars": [
          [
            "test",
            "name != null"
          ],
          [
            "name",
            "null"
          ],
          [
            "返回",
            "false"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Mixed：恒返回 true",
      "file": "org/apache/ibatis/scripting/xmltags/MixedSqlNode.java",
      "line": 31,
      "lines": 4,
      "explain": "`contents.forEach(node -> node.apply(context))` 逐个执行，忽略每个子节点的返回值，最后无条件返回 true。所以它不能告诉你「有没有拼出 SQL」。",
      "state": {
        "vars": [
          [
            "子节点数",
            "3"
          ],
          [
            "返回",
            "true"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "ForEach：空集合也返回 true",
      "file": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "line": 68,
      "lines": 40,
      "explain": "先求值 collection；null 或空迭代器在 72-73 行直接 return true，连 open/close 都不会加。非空时逐个 `FilteredDynamicContext` 改写 `#{item}` 为 `#{__frch_item_N}`，最后 106 行仍返回 true。",
      "state": {
        "tables": [
          [
            "ids=[]",
            "返回 true，缓冲区不变"
          ],
          [
            "ids=[10,20]",
            "生成 __frch_id_0 / __frch_id_1"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Trim：只透传子的返回值",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "lines": 6,
      "explain": "`apply` 先建 `FilteredDynamicContext`，把子节点的结果存进 `result`，再 `applyAll()` 裁剪，最后原样返回 `result`。它没有把「有没有内容」编码进这个 boolean。",
      "state": {
        "vars": [
          [
            "result",
            "contents.apply 的返回值"
          ],
          [
            "applyAll",
            "看缓冲区，不看 result"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "覆写词：大写，但不 trim",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 63,
      "lines": 10,
      "explain": "`StringTokenizer(overrides, 「|」, false)` 按竖线切分，然后对每个 token 做 `toUpperCase`。它没有 `trim()`，所以 `AND | OR` 得到的是 `AND ` 和 ` OR` 两个 token。",
      "state": {
        "tables": [
          [
            "AND|OR",
            "AND / OR"
          ],
          [
            "AND | OR",
            "AND （末尾空格）/  OR（开头空格）"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "前缀看不看内容，看缓冲区",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 89,
      "lines": 8,
      "explain": "`applyAll` 先 trim 缓冲区再转大写；只有长度大于 0 才 `applyPrefix` / `applySuffix`。`applyPrefix` 在 124-136 行用 `startsWith` 匹配，删除 `toRemove.trim().length()` 个字符，再插入 prefix。",
      "state": {
        "tables": [
          [
            "缓冲区空",
            "不加 prefix"
          ],
          [
            "缓冲区有内容",
            "先删覆写词，再加 prefix"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "where：固定八项覆写列表",
      "file": "org/apache/ibatis/scripting/xmltags/WhereSqlNode.java",
      "line": 28,
      "lines": 5,
      "explain": "`WhereSqlNode` 的 prefix 是 WHERE，prefixOverrides 是 8 项固定列表：AND/OR 后跟空格、换行、回车或制表符。它不删括号，也不删 `AND(`。",
      "state": {
        "tables": [
          [
            "prefix",
            "WHERE"
          ],
          [
            "prefixOverrides",
            "AND / OR + 空格、\\n、\\r、\\t"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "set：前后逗号都去",
      "file": "org/apache/ibatis/scripting/xmltags/SetSqlNode.java",
      "line": 28,
      "lines": 4,
      "explain": "`SetSqlNode` 同时把 COMMA 传给 prefixesToOverride 和 suffixesToOverride，prefix 是 SET，suffix 为 null。所以开头的逗号和结尾的逗号都会被去掉；中间的逗号不动。",
      "state": {
        "tables": [
          [
            "prefix",
            "SET"
          ],
          [
            "prefixesToOverride",
            "COMMA"
          ],
          [
            "suffixesToOverride",
            "COMMA"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Choose：唯一拿 boolean 做分支的节点",
      "file": "org/apache/ibatis/scripting/xmltags/ChooseSqlNode.java",
      "line": 34,
      "lines": 10,
      "explain": "按顺序调用每个 when；哪个 `apply` 返回 true 就立刻返回 true，后面的 when 不再执行。都不为真时走 defaultSqlNode；没有 default 才返回 false。",
      "state": {
        "tables": [
          [
            "第一个 when 返回 true",
            "选它，后面的跳过"
          ],
          [
            "所有 when 返回 false",
            "走 otherwise"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    }
  ],
  "map": {
    "title": "MyBatis 主干",
    "nodes": [
      {
        "id": "api",
        "label": "Mapper 接口 / SqlSession",
        "sub": "调用入口",
        "layer": 0,
        "order": 0
      },
      {
        "id": "config",
        "label": "Configuration",
        "sub": "配置与映射",
        "layer": 1,
        "order": 0
      },
      {
        "id": "proxy",
        "label": "MapperProxy",
        "sub": "动态代理",
        "layer": 2,
        "order": 0
      },
      {
        "id": "executor",
        "label": "Executor",
        "sub": "执行器",
        "layer": 3,
        "order": 0
      },
      {
        "id": "statement",
        "label": "StatementHandler",
        "sub": "JDBC 语句",
        "layer": 4,
        "order": 0
      },
      {
        "id": "result",
        "label": "ResultSetHandler",
        "sub": "结果映射",
        "layer": 5,
        "order": 0
      },
      {
        "id": "base",
        "label": "基础模块",
        "sub": "反射/类型/缓存/插件",
        "layer": 6,
        "order": 0
      }
    ],
    "edges": [
      {
        "from": "api",
        "to": "proxy",
        "label": "调用接口方法"
      },
      {
        "from": "config",
        "to": "proxy",
        "label": "注册 Mapper"
      },
      {
        "from": "proxy",
        "to": "executor",
        "label": "MapperMethod"
      },
      {
        "from": "config",
        "to": "executor",
        "label": "默认执行器"
      },
      {
        "from": "executor",
        "to": "statement",
        "label": "prepare"
      },
      {
        "from": "statement",
        "to": "result",
        "label": "ResultSet"
      },
      {
        "from": "executor",
        "to": "base",
        "label": "用到时才取"
      }
    ]
  }
}
```

这份快照没有 `TextSqlNode.java`、`DynamicContext.java`、`VarDeclSqlNode.java`，`StaticTextSqlNode.java` 也不在。正文提到它们时按同一 commit 的上游原文核对，不给快照行号，也不把它们放进 `source-walk` 的 `files`；否则 `check-source-walks` 会直接报「快照里没有」。

## 建树：文本分支与元素分支

`parseScriptNode:65-74` 只做一次分叉：先 `parseDynamicTags(context)` 得到 `MixedSqlNode`，再看 `isDynamic`。true 就是 `DynamicSqlSource`，false 就是 `RawSqlSource`。

`parseDynamicTags:76-101` 有两个分支：

- 文本或 CDATA：先 new 一个 `TextSqlNode(data)`，用 `isDynamic()` 检查有没有 `${}`。有就加入 `TextSqlNode` 并把 `isDynamic` 置 true；没有就加入 `StaticTextSqlNode`。`#{}` 不参与这个判断。
- 元素节点：从 `nodeHandlerMap` 取 handler。取不到在 93-94 行抛 `BuilderException("Unknown element <...> in SQL statement.")`；取到就 `handler.handleNode(child, contents)`，并在 97 行无条件把 `isDynamic` 置 true。注意是「任意元素」都置 true，哪怕 `<where>` 里只有静态文本。

注释等其它节点类型不进入这两个分支，循环直接跳过。

`initNodeHandlerMap:53-63` 注册的九个名字值得单独列一次；如果只记 `if/where/foreach`，会漏掉另外几个：

| 标签 | Handler | 产物 |
| --- | --- | --- |
| `trim` | `TrimHandler` | `TrimSqlNode` |
| `where` | `WhereHandler` | `WhereSqlNode` |
| `set` | `SetHandler` | `SetSqlNode` |
| `foreach` | `ForEachHandler` | `ForEachSqlNode` |
| `if`、`when` | `IfHandler` | `IfSqlNode` |
| `choose` | `ChooseHandler` | `ChooseSqlNode` |
| `otherwise` | `OtherwiseHandler` | `MixedSqlNode` |
| `bind` | `BindHandler` | `VarDeclSqlNode` |

`ChooseHandler:211-248` 不是简单地「把子节点都塞进去」：它把 `when` 收进 `whenSqlNodes`，把 `otherwise` 收进 `otherwiseSqlNodes`，最后用 `getDefaultSqlNode` 取唯一的 default。`otherwise` 多于一个时，244-245 行直接抛 `BuilderException("Too many default (otherwise) elements in choose statement.")`。

`bind` 由 `BindHandler:107-119` 处理：读 `name` 和 `value`，new `VarDeclSqlNode`。这个类不在快照里，但 handler 本身在 `XMLScriptBuilder` 里，行号是实的。

## `apply` 的 boolean 是「节点被应用」，不是「拼出了内容」

把几个实现放在一起看，语义就清楚了：

| 节点 | 什么时候返回 true | 源码 |
| --- | --- | --- |
| `IfSqlNode` | `test` 求值为真；不管 `contents` 拼出什么 | `IfSqlNode:32-38` |
| `ForEachSqlNode` | 集合为 null 或空也返回 true；非空遍历完也返回 true | `ForEachSqlNode:72-73`、`106` |
| `MixedSqlNode` | 恒 true | `MixedSqlNode:31-34` |
| `TrimSqlNode` | 原样透传 `contents.apply` 的结果 | `TrimSqlNode:56-61` |
| `ChooseSqlNode` | 某个 when 返回 true，或 default 被应用 | `ChooseSqlNode:34-43` |

所以不能拿它判断「有没有拼出非空 SQL」。两个反例：

```xml
<if test="true"></if>
```

`IfSqlNode` 的 test 为真，`contents.apply` 什么都没加，照样返回 true。

```xml
<foreach collection="emptyList" item="id" open="(" close=")">#{id}</foreach>
```

`ForEachSqlNode` 在 72-73 行看到空迭代器就 return true，连 `open` 和 `close` 都不会加。

`<trim>` 加不加前缀也不看这个 boolean。`TrimSqlNode:89-96` 先 `sqlBuffer.toString().trim()`，转大写，只有长度大于 0 才 `applyPrefix` / `applySuffix`。`applyPrefix:124-136` 再用 `startsWith` 决定删不删开头 token、插不插 prefix。就算子节点返回 true 但缓冲区是空的，也不会凭空多出一个 `WHERE`。

真正拿 boolean 做分支的是 `ChooseSqlNode:34-43`：按顺序调用 when，第一个返回 true 的赢，后面的不再执行；都不为真才走 default；没有 default 返回 false。于是下面这个 `<choose>` 会选中空分支，后面的 `<otherwise>` 不会执行：

```xml
<choose>
  <when test="true"></when>
  <otherwise>and 1 = 1</otherwise>
</choose>
```

`IfSqlNode` 只看 test，`ChooseSqlNode` 只看 `apply` 的返回值。两个语义拼在一起，就是「空 when 也能截胡」。

## `<where>` 与 `<trim>`：大小写不敏感，空格敏感

`TrimSqlNode.parseOverrides:63-72` 的写法是：

```java
StringTokenizer parser = new StringTokenizer(overrides, "|", false);
while (parser.hasMoreTokens()) {
  list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
}
```

`toUpperCase` 让匹配对大小写不敏感；`StringTokenizer` 不 trim，让 token 里的空格变得要命。`prefixOverrides="AND | OR"` 会得到两个 token：`"AND "` 和 `" OR"`。待匹配的 SQL 在 `applyAll:89-96` 已经 `trim()` 过，不可能以空格开头，所以 `" OR"` 永远匹配不到。要写 `AND|OR`，不要写 `AND | OR`。

`applyPrefix:124-136` 的匹配和删除也值得看清：

```java
prefixesToOverride.stream().filter(trimmedUppercaseSql::startsWith).findFirst()
    .ifPresent(toRemove -> sql.delete(0, toRemove.trim().length()));
if (prefix != null) {
  sql.insert(0, " ").insert(0, prefix);
}
```

它只删一个匹配到的开头 token，删除长度是 `toRemove.trim().length()`，所以 `"AND "` 实际删 3 个字符，留下的空格不会把 SQL 弄坏。prefix 非空时再插入 `"WHERE"` 和一个空格；如果删除覆写词后缓冲区还留着原来的分隔空格，就会出现两个空格（`WHERE  age > ...`），SQL 语义不受影响。

`<where>` 不走自定义字符串，它用 `WhereSqlNode:28-32` 的固定 8 项列表：

```java
Arrays.asList("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t");
```

也就是 `AND` / `OR` 后面跟空格、换行、回车或制表符。它不删括号，也不删 `AND(`。`<where>` 的 prefix 是 `WHERE`，所以下面这段：

```xml
<select id="search" resultType="User">
  select id, name from user
  <where>
    <if test="name != null">and name = #{name}</if>
    <if test="age != null">and age &gt; #{age}</if>
  </where>
</select>
```

当 `name=null`、`age=18` 时，缓冲区是 `and age > #{age}`；Trim 之后是 `WHERE  age > #{age}`；再交给 `SqlSourceBuilder` 才变成 `WHERE  age > ?`。当两个条件都不成立时，缓冲区为空，`applyAll` 连 `WHERE` 都不加，最终 SQL 里没有 WHERE。

`<set>` 是同一个机制的另一组参数。`SetSqlNode:28-31` 把 `COMMA` 同时传给 `prefixesToOverride` 和 `suffixesToOverride`：

```java
super(configuration, contents, "SET", COMMA, null, COMMA);
```

所以 `<set>` 会去掉开头的逗号，也会去掉结尾的逗号；中间的逗号不动。它只删第一个匹配到的前缀/后缀，不会帮你修 `a = 1,, b = 2` 这种中间重复。

## `<foreach>`：`__frch_` 是绑定名，不是装饰

`ForEachSqlNode:68-107` 的流程：

1. 求值 `collection`。null 或空迭代器在 72-73 行直接 return true。
2. `applyOpen` 追加 `open`，比如 `(`。
3. 每个元素用 `PrefixedContext` 加分隔符；`uniqueNumber` 由 `context.getUniqueNumber()` 发号。
4. 元素是 `Map.Entry` 时，key 当 index、value 当 item（87-95 行）；否则 index 就是循环序号。
5. `contents.apply(new FilteredDynamicContext(...))`；`FilteredDynamicContext:169-180` 用 `GenericTokenParser("#{", "}", ...)` 把 `#{id}` 改写成 `#{__frch_id_0}`。
6. 循环结束追加 `close`，移除 `item` / `index` 绑定，返回 true。

`itemizeItem:135-137` 的命名就是 `ITEM_PREFIX + item + "_" + i`，`ITEM_PREFIX` 是 `__frch_`。假设 `ids=[10,20]`、`item="id"`、`separator=","`，两个元素的占位符名会变成：

```text
#{id} -> #{__frch_id_0}
#{id} -> #{__frch_id_1}
```

再经过 `SqlSourceBuilder`，这两个 `#{__frch_*}` 各自变成一个 `?`。整条 SQL 的空格由 `DynamicContext` 的 `StringJoiner` 决定，这里不逐字复现。

这两个 `__frch_*` 不是给 XML 看的。`applyItem:116-120` 在循环里同时绑定了 `id` 和 `__frch_id_0`；循环结束只 remove `id` 和 `index`（104-105 行），`__frch_*` 留在 bindings 里。`DynamicSqlSource:44` 会把整个 `context.getBindings()` 复制进 `BoundSql.additionalParameters`，`DefaultParameterHandler` 取值时先查 additional，再查 parameterObject。这条链在 06 会展开。

`uniqueNumber` 的起始值是 `DynamicContext` 的事，这个类不在快照里；按同一 commit 的上游原文，它从 0 开始自增，所以第一个元素是 `__frch_id_0`。

## 动手：把建树和执行分开

下面四步是乱序的。先排对，再回答一个问题：为什么 `IfSqlNode.apply` 返回 true，缓冲区里却可能什么都没有？

```code-challenge
{
  "id": "ex-dynamicsql",
  "kind": "order",
  "title": "排出动态 SQL 的建树与执行",
  "lines": [
    "IfSqlNode.apply(context) 求值 test",
    "XMLScriptBuilder.parseDynamicTags(node)",
    "MixedSqlNode 依次 apply 子节点",
    "XMLScriptBuilder.parseScriptNode()"
  ],
  "answer": [
    3,
    1,
    2,
    0
  ],
  "explain": "parseScriptNode 先调 parseDynamicTags 建树；执行时 MixedSqlNode 按顺序调用子节点，轮到 IfSqlNode 才求值 test。boolean 只表示这个节点有没有被应用。"
}
```

## 检查一下

```quiz
{
  "id": "dynamic-sql-quiz",
  "title": "小测：动态 SQL",
  "questions": [
    {
      "question": "一条只有 #{}、没有 ${} 和动态标签的 SQL，isDynamic 是什么？",
      "options": [
        "true，因为 #{} 要绑定参数",
        "false，#{} 不参与 XMLScriptBuilder 的 dynamic 判断",
        "取决于参数是否为 null"
      ],
      "answer": 1,
      "explain": "`XMLScriptBuilder:84-89` 只把含 ${} 的文本标成 dynamic；#{} 原样留在文本里，等 SqlSourceBuilder 再换。"
    },
    {
      "question": "IfSqlNode.apply 返回 true 意味着什么？",
      "options": [
        "子节点拼出了非空字符串",
        "父节点可以据此决定加不加 prefix",
        "test 求值为真，这个节点被应用了"
      ],
      "answer": 2,
      "explain": "`IfSqlNode:32-38` 只看 test，不看子节点拼了什么；`<trim>` 的前缀看缓冲区（89-96），真正用 boolean 做分支的是 `ChooseSqlNode:34-43`。"
    },
    {
      "question": "自定义 `<trim prefixOverrides=「AND | OR」>` 为什么可能去不掉 OR？",
      "options": [
        "StringTokenizer 不 trim，第二个 token 是「 OR」，匹配不到去掉首尾空白的 SQL",
        "toUpperCase 只处理 AND，不处理 OR",
        "trim 只支持一个覆写词"
      ],
      "answer": 0,
      "explain": "`parseOverrides:63-72` 按竖线切分后只 toUpperCase，不 trim；`applyAll:89-96` 的 SQL 已经 trim 过，所以带前导空格的「 OR」永远不 startsWith。写 `AND|OR`；`<where>` 另走 `WhereSqlNode:28-32` 的 8 项固定列表。"
    }
  ]
}
```

## 还差的

- 没有在真实 MyBatis 查询里打印 `BoundSql` 逐字比对；上面的 SQL / mapping 是按 3.5.19 源码推的。还缺一个带 `UserQuery` 的 JUnit 探针，把 `getSql()` 和 `getParameterMappings()` 打出来。
- `TextSqlNode`、`DynamicContext`、`VarDeclSqlNode` 不在精选快照，`${}` 的 OGNL 取值和 `uniqueNumber` 起始值只按上游 commit 原文核对，没在仓库里做源码级高亮。
- 没覆盖注解 `@Select` 路径的 `LanguageDriver` 如何创建 SqlSource；本篇只走 XML 的 `XMLScriptBuilder`。
- `bind` 只讲到生成 `VarDeclSqlNode`，没展开它在后续 `DefaultParameterHandler` 取值时的完整路径。

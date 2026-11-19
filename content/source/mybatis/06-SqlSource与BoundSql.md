---
title: SqlSource 与 BoundSql：#{} 在哪一步变成 ?
category: mybatis-source
order: 6
minutes: 22
level: intermediate
status: published
---

> 版本：mybatis 3.5.19（revision `ee0d4f4`），源码核对日期：2026-11-19。

## 同一条 SQL，两个 `getBoundSql` 的重量不一样

先看一条没有 `<if>`、没有 `${}` 的语句：

```xml
<select id="findUser" resultType="User">
  select id, name from user where id = #{id}
</select>
```

`XMLScriptBuilder:84-89` 不会因为 `#{}` 把 `isDynamic` 置 true；`parseScriptNode:68-72` 选 `RawSqlSource`。把同样的 SQL 外面套一个 `<if>`，元素分支会在 `parseDynamicTags:96-97` 置 true，改选 `DynamicSqlSource`。两个实现的 `getBoundSql` 差别是结构性的：

| 实现 | 构建期做什么 | 每次 `getBoundSql` 做什么 |
| --- | --- | --- |
| `RawSqlSource` | `getSql(rootSqlNode)` 拼出文本，`SqlSourceBuilder.parse` 一次，存成 `StaticSqlSource`（39-47） | 直接委托 `StaticSqlSource.getBoundSql`，只 new 一个 `BoundSql`（56-58） |
| `DynamicSqlSource` | 只存 `SqlNode` 树（31-34） | new `DynamicContext`、apply 树、new `SqlSourceBuilder`、parse，再取 `BoundSql`，最后复制 bindings（37-46） |

所以 `#{}` → `?` 在 `RawSqlSource` 里是构建期一次，在 `DynamicSqlSource` 里是每次查询一次。`BoundSql` 对象本身每次都是新的；`RawSqlSource` 省掉的是 SQL 文本和 mapping 的重新计算，不是 `BoundSql` 对象的分配。

`getBoundSql` 也不负责 `IncompleteElementException`。`SqlSource:26` 没有 `throws`，三个实现（`DynamicSqlSource:37-46`、`RawSqlSource:56-58`、`StaticSqlSource:45-47`）也都没有声明异常。这个异常发生在构建期解析语句时：`XMLMapperBuilder:136-145` 捕获后把 `XMLStatementBuilder` 放进 `configuration.incompleteStatements`，`Configuration.parsePendingStatements:999-1015` 再重试或抛出。把 `resultMap` 写错，错误会在配置解析或取 `MappedStatement` 时露出来，不会等你调用 `getBoundSql`。

```source-walk
{
  "id": "sqlsource-boundSql",
  "title": "SqlSource → BoundSql",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/mapping/SqlSource.java",
      "label": "SqlSource"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/DynamicSqlSource.java",
      "label": "DynamicSqlSource"
    },
    {
      "path": "org/apache/ibatis/scripting/defaults/RawSqlSource.java",
      "label": "RawSqlSource"
    },
    {
      "path": "org/apache/ibatis/builder/StaticSqlSource.java",
      "label": "StaticSqlSource"
    },
    {
      "path": "org/apache/ibatis/builder/SqlSourceBuilder.java",
      "label": "SqlSourceBuilder"
    },
    {
      "path": "org/apache/ibatis/mapping/BoundSql.java",
      "label": "BoundSql"
    },
    {
      "path": "org/apache/ibatis/session/Configuration.java",
      "label": "Configuration"
    },
    {
      "path": "org/apache/ibatis/scripting/defaults/DefaultParameterHandler.java",
      "label": "DefaultParameterHandler"
    },
    {
      "path": "org/apache/ibatis/reflection/ParamNameResolver.java",
      "label": "ParamNameResolver"
    }
  ],
  "steps": [
    {
      "title": "接口只有一个方法，也没有异常声明",
      "file": "org/apache/ibatis/mapping/SqlSource.java",
      "line": 26,
      "lines": 1,
      "explain": "`BoundSql getBoundSql(Object parameterObject);`。没有 `throws IncompleteElementException`，也没有别的受检异常。",
      "state": {
        "tables": [
          [
            "实现类",
            "DynamicSqlSource / RawSqlSource / StaticSqlSource"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Dynamic：每次查询重新拼",
      "file": "org/apache/ibatis/scripting/xmltags/DynamicSqlSource.java",
      "line": 37,
      "lines": 10,
      "explain": "每次 `getBoundSql` 都 new `DynamicContext`、`rootSqlNode.apply(context)`，再用 `SqlSourceBuilder.parse(context.getSql(), ...)`。解析出的 `StaticSqlSource` 只在这一趟用，最后 44 行把 bindings 全量复制进 `BoundSql`。",
      "state": {
        "stack": [
          "DynamicSqlSource.getBoundSql",
          "rootSqlNode.apply",
          "SqlSourceBuilder.parse",
          "StaticSqlSource.getBoundSql"
        ],
        "vars": [
          [
            "解析次数",
            "每次查询 1 次"
          ]
        ]
      },
      "predict": {
        "question": "带 <if> 的 SQL 连续查两次，SqlSourceBuilder.parse 执行几次？",
        "options": [
          "构建期 1 次",
          "每次查询 1 次",
          "只在第一次查询时 1 次"
        ],
        "answer": 1,
        "reveal": "DynamicSqlSource 每次都 new DynamicContext、apply 树，再走 SqlSourceBuilder。"
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Raw：构建期拼一次，查询时只组装 BoundSql",
      "file": "org/apache/ibatis/scripting/defaults/RawSqlSource.java",
      "line": 39,
      "lines": 15,
      "explain": "构造函数先 `getSql(rootSqlNode)` 把静态树拼成字符串，再 `SqlSourceBuilder.parse` 一次，结果存进 `sqlSource`。`getBoundSql:56-58` 只是委托给这个 `StaticSqlSource`。",
      "state": {
        "stack": [
          "RawSqlSource.<init>",
          "getSql",
          "SqlSourceBuilder.parse"
        ],
        "vars": [
          [
            "解析次数",
            "构建期 1 次"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "Static：SQL 和 mapping 都是现成的",
      "file": "org/apache/ibatis/builder/StaticSqlSource.java",
      "line": 45,
      "lines": 3,
      "explain": "`StaticSqlSource.getBoundSql` 只做 `new BoundSql(configuration, sql, parameterMappings, parameterObject)`。SQL 文本和 parameterMappings 是构造时传进来的，不再解析。",
      "state": {
        "tables": [
          [
            "sql",
            "已带 ?"
          ],
          [
            "parameterMappings",
            "已按 ? 的顺序排好"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "#{} → ?：先建 TokenParser",
      "file": "org/apache/ibatis/builder/SqlSourceBuilder.java",
      "line": 44,
      "lines": 12,
      "explain": "`parse` 建 `ParameterMappingTokenHandler`，再用 `GenericTokenParser(「#{」, 「}」, handler)` 扫全文。若 `shrinkWhitespacesInSql` 为 true，先 `removeExtraWhitespaces` 再 parse；最后返回 `StaticSqlSource(configuration, sql, handler.getParameterMappings())`。",
      "state": {
        "vars": [
          [
            "originalSql",
            "where id = #{id}"
          ],
          [
            "返回",
            "StaticSqlSource"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "handleToken 只 add 和返回 ?",
      "file": "org/apache/ibatis/builder/SqlSourceBuilder.java",
      "line": 89,
      "lines": 4,
      "explain": "`parameterMappings.add(buildParameterMapping(content)); return 「?」;`。这里没有 javaType / jdbcType / typeHandler 的解析，只有加 mapping 和吐问号两个动作。",
      "state": {
        "tables": [
          [
            "输入",
            "#{id}"
          ],
          [
            "输出",
            "?"
          ],
          [
            "副作用",
            "parameterMappings 多一项"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "类型解析在 buildParameterMapping",
      "file": "org/apache/ibatis/builder/SqlSourceBuilder.java",
      "line": 94,
      "lines": 54,
      "explain": "98-113 行推导默认 propertyType：additionalParameters 有 getter 就用它的类型；parameterType 本身有 TypeHandler 就用 parameterType；jdbcType=CURSOR 用 ResultSet；property 为 null 或 parameterType 是 Map 用 Object；否则用 MetaClass 的 getter 类型。114 行建 Builder，120-122 行显式 javaType 覆盖，123-124 行 jdbcType，131-132 行记 typeHandler 别名，144-145 行才解析成实例。",
      "state": {
        "tables": [
          [
            "默认 javaType",
            "propertyType（98-113）"
          ],
          [
            "显式 javaType",
            "120-122 行覆盖"
          ],
          [
            "typeHandler",
            "131-132 行记别名，144-145 行解析"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "BoundSql 有五个字段",
      "file": "org/apache/ibatis/mapping/BoundSql.java",
      "line": 37,
      "lines": 5,
      "explain": "37-41 行依次是 sql、parameterMappings、parameterObject、additionalParameters、metaParameters。第五个不是另一份数据，它是包着 additionalParameters 的 MetaObject，负责嵌套路径读写。",
      "state": {
        "tables": [
          [
            "sql",
            "带 ? 的文本"
          ],
          [
            "parameterMappings",
            "按 ? 顺序排列"
          ],
          [
            "parameterObject",
            "调用者传入的参数对象"
          ],
          [
            "additionalParameters",
            "动态上下文绑定"
          ],
          [
            "metaParameters",
            "additionalParameters 的 MetaObject 视图"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "构造时把 additionalParameters 包起来",
      "file": "org/apache/ibatis/mapping/BoundSql.java",
      "line": 43,
      "lines": 8,
      "explain": "构造函数建一个新的 `HashMap`，再用 `configuration.newMetaObject(additionalParameters)` 建 `metaParameters`。所以每次 `BoundSql` 的 additional 容器都是独立的；`setAdditionalParameter` / `getAdditionalParameter` 都通过这个 MetaObject 读写。",
      "state": {
        "vars": [
          [
            "additionalParameters",
            "new HashMap<>()"
          ],
          [
            "metaParameters",
            "MetaObject 包装"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "DynamicSqlSource 复制全部 bindings",
      "file": "org/apache/ibatis/scripting/xmltags/DynamicSqlSource.java",
      "line": 44,
      "lines": 1,
      "explain": "`context.getBindings().forEach(boundSql::setAdditionalParameter)`。这里不是只复制 `__frch_*`，而是把 `_parameter`、`_databaseId`、foreach 变量、bind 变量、`${}` 留下的 value 全部塞进 additionalParameters。",
      "state": {
        "tables": [
          [
            "_parameter",
            "原始参数对象"
          ],
          [
            "_databaseId",
            "当前 databaseId"
          ],
          [
            "__frch_*",
            "foreach 变量"
          ],
          [
            "value",
            "${} 取值后的临时值"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "不完整语句在 Configuration 里重试",
      "file": "org/apache/ibatis/session/Configuration.java",
      "line": 999,
      "lines": 17,
      "explain": "`parsePendingStatements` 遍历 `incompleteStatements`，对每个 `XMLStatementBuilder` 调 `parseStatementNode()`；捕获 `IncompleteElementException` 后，`reportUnresolved` 为 true 才重抛。它处理的是构建期的语句解析队列，不是 `SqlSource.getBoundSql`。",
      "state": {
        "vars": [
          [
            "队列",
            "incompleteStatements"
          ],
          [
            "动作",
            "parseStatementNode 重试"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "取值发生在 DefaultParameterHandler",
      "file": "org/apache/ibatis/scripting/defaults/DefaultParameterHandler.java",
      "line": 62,
      "lines": 36,
      "explain": "按 parameterMappings 顺序遍历：先看 `hasAdditionalParameter(propertyName)`（72-73）；没有再看 parameterObject 是否为 null（74-75）、是否本身有 TypeHandler（76-77）；否则用 MetaObject 按属性名取值（78-83）。最后取 mapping 上的 TypeHandler 和 jdbcType，在 90 行 `setParameter(ps, i + 1, value, jdbcType)`。",
      "state": {
        "tables": [
          [
            "第一优先",
            "additionalParameters"
          ],
          [
            "第二优先",
            "parameterObject 本身"
          ],
          [
            "第三优先",
            "MetaObject 按属性名取"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "ParamNameResolver 管名字和入参形状",
      "file": "org/apache/ibatis/reflection/ParamNameResolver.java",
      "line": 63,
      "lines": 35,
      "explain": "构造器按 `@Param`、编译参数名、下标字符串的顺序确定名字；`getNamedParams:125-147` 负责组装入参，多参数时建 `ParamMap` 并补 `param1`、`param2`，单参数集合也会包一层。它不碰 `PreparedStatement`，也不决定某个 `?` 的值。",
      "state": {
        "tables": [
          [
            "单参数无 @Param",
            "标量直接返回；集合包 ParamMap"
          ],
          [
            "多参数",
            "ParamMap + paramN"
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

`TextSqlNode`、`DynamicContext` 不在精选快照；正文提到它们时按同一 commit 的上游原文核对，不给快照行号，也不把它们放进 `source-walk` 的 `files`。

## `RawSqlSource` 的树里没有 `${}`

常见的写法是「`getSql` 走一遍树，因为可能出现 `${}`」。这个因果关系是反的。

`XMLScriptBuilder:84-89` 在文本分支里一旦发现 `${}`，就把这段文本换成 `TextSqlNode` 并把 `isDynamic` 置 true；`parseScriptNode:68-72` 看到 true 就 new `DynamicSqlSource`，根本不会走 `RawSqlSource`。所以走 XML 路径时，传给 `RawSqlSource` 构造函数的树里没有 `${}` 节点，只有 `StaticTextSqlNode` 和 `MixedSqlNode`。`RawSqlSource:39-53` 的 `getSql` 只是 `DynamicContext` + `rootSqlNode.apply` 把静态文本拼起来，没有 OGNL 求值，也没有 `${}` 替换。

`RawSqlSource` 另有一个接收 `String sql` 的构造函数（43-47），直接调用时可以传任何字符串；但这不是 XML 路径的判定逻辑，不能拿它来证明「RawSqlSource 要处理 `${}`」。

## `handleToken` 只做两件事，类型解析在下一层

`SqlSourceBuilder:89-92` 的 `handleToken` 只有两行有效代码：

```java
parameterMappings.add(buildParameterMapping(content));
return "?";
```

它不解析 `javaType`，不解析 `jdbcType`，也不解析 `typeHandler`。这些都在它调用的 `buildParameterMapping:94-147` 里：

- 98-113 行先推导默认 `propertyType`。顺序是：additionalParameters 有 getter → 用它的 getter 类型；parameterType 本身有 TypeHandler → 用 parameterType；`jdbcType=CURSOR` → `ResultSet.class`；`property` 为 null 或 parameterType 是 `Map` → `Object.class`；否则用 `MetaClass` 的属性 getter 类型，找不到就 `Object.class`。
- 114 行用 `property` 和 `propertyType` 建 `ParameterMapping.Builder`，所以默认 `javaType` 就是这里的 `propertyType`。
- 120-122 行遇到显式 `javaType` 才覆盖；123-124 行处理显式 `jdbcType`。
- 131-132 行只把 `typeHandler` 的值记进 `typeHandlerAlias`；循环结束后 144-145 行才 `resolveTypeHandler(javaType, typeHandlerAlias)`。

举个最小的对照：`#{id}` 走默认推导，`#{id,javaType=long}` 走 120-122 行的覆盖分支。两者都只产生一个 `ParameterMapping`，`handleToken` 都只返回一个 `?`。

## `BoundSql` 是五个字段

`BoundSql:37-41` 一共五个字段：

| 字段 | 作用 |
| --- | --- |
| `sql` | 带 `?` 的 SQL 文本 |
| `parameterMappings` | 按 `?` 顺序排列的 `ParameterMapping` 列表 |
| `parameterObject` | 调用者传入的参数对象 |
| `additionalParameters` | 动态上下文绑定，如 `__frch_*`、`_parameter`、`_databaseId`、bind 变量 |
| `metaParameters` | 包着 `additionalParameters` 的 `MetaObject`，负责嵌套路径读写 |

`metaParameters` 不是第五份数据。`BoundSql` 构造函数在 48-49 行建了一个新的 `HashMap`，再用 `configuration.newMetaObject(additionalParameters)` 建 `metaParameters`。`setAdditionalParameter` 和 `getAdditionalParameter` 都通过这个 MetaObject 读写同一个 map。`hasAdditionalParameter:64-67` 则先用 `PropertyTokenizer` 取名字，再查 map 的 key。

`DynamicSqlSource:44` 把 `context.getBindings()` 全量复制进 `additionalParameters`。所以这里不只有 `<foreach>` 生成的 `__frch_*`：

```text
_parameter   -> 原始参数对象
_databaseId  -> 当前 databaseId
__frch_id_0  -> 10
__frch_id_1  -> 20
bindName     -> <bind> 定义的变量
```

这也是 `${}` 和 `#{}` 在运行期的另一个区别：`${}` 已经在 `TextSqlNode` 里变成字符串，不在 `parameterMappings` 里；`#{}` 留下 `?` 和 mapping，值要等 `DefaultParameterHandler` 去取。

`DefaultParameterHandler:62-97` 的取值顺序是：

1. `boundSql.hasAdditionalParameter(propertyName)` 为真，先从 `additionalParameters` 取（72-73）。
2. `parameterObject == null`，取 null（74-75）。
3. `parameterObject` 本身有 TypeHandler，直接用 `parameterObject`（76-77）。
4. 否则用 `MetaObject` 按属性名取（78-83）。

拿到值后，84-90 行取 `ParameterMapping` 上的 TypeHandler 和 jdbcType，最后 `typeHandler.setParameter(ps, i + 1, value, jdbcType)`。`ParamNameResolver:63-97` 管的是名字从哪里来，`getNamedParams:125-147` 负责组装入参（多参数时建 `ParamMap`，单参数集合也会包一层）；它不负责把值写进 `PreparedStatement`。

## 三层的分工

| 层 | 什么时候生成 | 内容 |
| --- | --- | --- |
| `SqlSource` | 构建期注册 | `RawSqlSource` 持有已解析的 `StaticSqlSource`；`DynamicSqlSource` 持有 SqlNode 树 |
| `BoundSql` | 每次 `getBoundSql` | 本次的 `?` SQL、parameterMappings、parameterObject、additionalParameters、metaParameters |
| `PreparedStatement` | 执行期 | `DefaultParameterHandler` 按 mapping 取值并 `setXxx` |

`getSql()` 里已经有 `?`，但没有值。`?` 的位置由 `parameterMappings` 的顺序解释；真正的值在 `DefaultParameterHandler.setParameters` 里才从 `parameterObject` 或 `additionalParameters` 取出来。看到日志里那条带 `?` 的 SQL，不要直接复制去数据库里跑。

## 动手：补 `#{}` 的归宿

```code-challenge
{
  "id": "ex-sqlsource",
  "kind": "fill",
  "title": "补 #{} 的归宿",
  "template": "`#{id}` 在 {{0}} 的 `handleToken` 里被替换成 {{1}}；`javaType` 的默认推导在 {{2}} 里完成。",
  "options": [
    [
      "XMLScriptBuilder.parseDynamicTags",
      "SqlSourceBuilder.parse"
    ],
    [
      "?",
      "#{id}"
    ],
    [
      "ResultSetHandler",
      "buildParameterMapping"
    ]
  ],
  "answers": [
    1,
    0,
    1
  ],
  "explain": "XMLScriptBuilder 只区分 TextSqlNode / StaticTextSqlNode；#{} 到 SqlSourceBuilder.parse 才变成 ?。handleToken 只 add mapping 并返回 ?，类型推导在 buildParameterMapping。"
}
```

## 检查一下

```quiz
{
  "id": "sqlsource-quiz",
  "title": "小测：SQL 的三种形态",
  "questions": [
    {
      "question": "同一条带 <if> 的 SQL，连续查两次，SqlSourceBuilder.parse 会执行几次？",
      "options": [
        "构建期 1 次",
        "每次查询 1 次",
        "只在第一次查询时 1 次"
      ],
      "answer": 1,
      "explain": "DynamicSqlSource.getBoundSql 每次都 new DynamicContext、apply 树，再走 SqlSourceBuilder。"
    },
    {
      "question": "SqlSourceBuilder.handleToken 在 #{id} 上做了什么？",
      "options": [
        "解析 javaType / jdbcType 并建 mapping",
        "把值绑到 PreparedStatement",
        "添加一个 ParameterMapping 并返回 ?"
      ],
      "answer": 2,
      "explain": "handleToken 只有 add 和 return ?；类型解析在 buildParameterMapping:94-147。"
    },
    {
      "question": "BoundSql 的 additionalParameters 里有什么？",
      "options": [
        "DynamicSqlSource 复制进来的全部 bindings，例如 _parameter、_databaseId、__frch_*",
        "只有 foreach 生成的 __frch_*",
        "已经绑好的 JDBC 参数值"
      ],
      "answer": 0,
      "explain": "DynamicSqlSource:44 全量复制 context.getBindings()；DefaultParameterHandler 取值时才区分 additional 和 parameterObject。"
    }
  ]
}
```

## 还差的

- 没有用真实 MyBatis 查询把 `BoundSql` 的五个字段逐项打印出来；上面的字段内容是按 3.5.19 源码推的，缺一个 JUnit 探针做逐字比对。
- `DynamicContext` 不在精选快照，`_parameter` / `_databaseId` 的具体绑定和 `uniqueNumber` 起始值只按上游 commit 原文核对，没在仓库里做源码级高亮。
- 没覆盖 `shrinkWhitespacesInSql=true` 时 `SqlSourceBuilder.removeExtraWhitespaces` 对 SQL 文本的实际影响；本篇只在 49-53 行提到这个分支。
- 没把 `IncompleteElementException` 的「resultMap 未解析」场景完整跑一遍，只核对了 `XMLMapperBuilder:136-145` 与 `Configuration:999-1015` 的调用关系。

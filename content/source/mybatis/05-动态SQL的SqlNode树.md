---
title: 动态 SQL：一棵 SqlNode 树，不是字符串拼接
category: mybatis-source
order: 5
minutes: 30
level: intermediate
status: published
---

## 这一关要读什么

`<if>、<choose>、<foreach>、<where>、<trim>` 这些标签不是"运行时文本替换"，而是**解析期建树、执行期遍历**。

这一关跟完 `XMLScriptBuilder` 的建树过程，再看三个节点的 `apply` 怎么写。

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
    { "path": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java", "label": "XMLScriptBuilder" },
    { "path": "org/apache/ibatis/scripting/xmltags/IfSqlNode.java", "label": "IfSqlNode" },
    { "path": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java", "label": "ForEachSqlNode" },
    { "path": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java", "label": "TrimSqlNode" }
  ],
  "controls": [
    {
      "id": "clause",
      "type": "select",
      "label": "条件该怎么收尾",
      "default": "where",
      "options": [
        { "value": "where", "label": "<where>" },
        { "value": "trim", "label": "<trim>" },
        { "value": "none", "label": "什么都不包" }
      ]
    }
  ],
  "steps": [
    {
      "title": "先判断动态还是静态",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 65,
      "lines": 10,
      "explain": "`parseScriptNode` 只做一个决定：树里有动态节点就是 `DynamicSqlSource`，否则 `RawSqlSource`。**没有 `<if>` 的 SQL 只解析一次**，这是性能上的关键分叉。",
      "state": {
        "vars": [["isDynamic", "true"], ["rootSqlNode", "MixedSqlNode"]]
      }
    },
    {
      "title": "把每个子节点翻译成 SqlNode",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 76,
      "lines": 27,
      "explain": "文本节点先看 `${}`：有就是 `TextSqlNode`（动态），没有就是 `StaticTextSqlNode`；元素节点查 `nodeHandlerMap`，找不到就抛「Unknown element」。**拼错标签名在这里就报错**，不会拖到运行期。",
      "state": {
        "tables": [["混合节点", "StaticTextSqlNode"], ["if", "IfSqlNode"], ["where", "WhereSqlNode"]]
      }
    },
    {
      "title": "<if>：求值，然后决定要不要拼",
      "file": "org/apache/ibatis/scripting/xmltags/IfSqlNode.java",
      "line": 32,
      "lines": 7,
      "explain": "`evaluator.evaluateBoolean(test, bindings)` 用 OGNL 求值；为真才 `contents.apply(context)`。注意它返回 boolean——父节点（比如 `<trim>`）据此判断有没有内容。",
      "state": {
        "vars": [["test", "name != null"], ["结果", "true"]]
      }
    },
    {
      "title": "<foreach>：遍历、加分隔符、生成 __frch_ 变量",
      "file": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "line": 68,
      "lines": 17,
      "explain": "集合为空直接返回；否则先追加 `open`（比如 `(`），再逐个元素用 `PrefixedContext` 加分隔符。每个元素有唯一编号，用来生成 `__frch_item_0` 这种绑定名。",
      "state": {
        "vars": [["collection", "ids"], ["separator", ","], ["uniqueNumber", "0、1、2…"]]
      }
    },
    {
      "title": "下标与元素：map 与 list 两种取法",
      "file": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "line": 86,
      "lines": 15,
      "explain": "元素是 `Map.Entry` 时，key 当下标、value 当元素；否则下标就是循环序号。最后 `contents.apply` 时用 `FilteredDynamicContext` 把 `#{item}` 换成 `#{__frch_item_0}`。",
      "state": {
        "tables": [["#{item}", "#{__frch_item_1}"], ["#{index}", "1"]]
      }
    },
    {
      "title": "<where> 的本质是一个 TrimSqlNode",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "lines": 6,
      "explain": "`apply` 先用 `FilteredDynamicContext` 收集内容，再 `applyAll()` 统一裁剪前后缀。`<where>` 就是「前缀 WHERE、去掉开头的 AND/OR」；`<set>` 是「前缀 SET、去掉结尾的逗号」。",
      "state": {
        "vars": [["prefix", "WHERE"], ["prefixOverrides", "AND | OR"]]
      }
    },
    {
      "title": "用 <where>：开头的 AND 会被吃掉",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "lines": 6,
      "explain": "第一个 `<if>` 不成立、第二个成立时，拼出来是 `WHERE AND age > 18`。`<where>` 会把开头的 `AND` 去掉——这就是它存在的全部意义。",
      "when": { "clause": "where" },
      "state": {
        "tables": [["拼出来", "WHERE AND age > 18"], ["最终", "WHERE age > 18"]]
      }
    },
    {
      "title": "用 <trim>：前后缀自己定",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "lines": 6,
      "explain": "`prefix` / `prefixOverrides` / `suffix` / `suffixOverrides` 四个属性，`<where>` 与 `<set>` 都是它的特例。自定义时最容易踩的是覆写词要**大写并用 | 分隔**。",
      "when": { "clause": "trim" },
      "state": {
        "tables": [["prefix", "WHERE"], ["prefixOverrides", "AND | OR"], ["suffix", "（空）"]]
      }
    },
    {
      "title": "什么都不包：语法错",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "lines": 6,
      "explain": "直接 `WHERE` 后面跟 `<if>`，第一个条件不成立就得到 `WHERE AND age > 18`，数据库报语法错。动态 SQL 的坑一半在这：**你看到的是模板，数据库看到的是拼完的字符串**。",
      "when": { "clause": "none" },
      "state": {
        "tables": [["拼出来", "WHERE AND age > 18"], ["数据库", "SQLSyntaxErrorException"]]
      }
    }
  ]
}
```

## 建树一次，执行多次

- 树在建 `SqlSessionFactory` 时就固定了；每次查询只是 `rootSqlNode.apply(context)` 走一遍。
- 动态判断用 OGNL，所以 `test="name != null and name != ''"` 是**表达式**，不是字符串比较。
- `${}` 是文本替换（有注入风险），`#{}` 是占位符 + 参数绑定。两者在这一层就分开了。

```quiz
{
  "id": "dynamic-sql-quiz",
  "title": "小测：动态 SQL",
  "questions": [
    {
      "question": "一条没有任何动态标签的 SQL，SqlSource 是哪种？",
      "options": ["DynamicSqlSource", "RawSqlSource", "StaticSqlSource"],
      "answer": 1,
      "explain": "没有动态节点时 `parseScriptNode` 选 RawSqlSource，在构建期就解析成 `?` 与 ParameterMapping。"
    },
    {
      "question": "<where> 解决了什么问题？",
      "options": ["给 SQL 加索引", "去掉拼接后开头的 AND/OR", "自动加 LIMIT"],
      "answer": 1,
      "explain": "它本质是 TrimSqlNode：prefix=WHERE，prefixOverrides=AND|OR。"
    },
    {
      "question": "#{} 与 ${} 在解析阶段就分开了吗？",
      "options": ["是，#{} 变成 ?，${} 变成文本", "不，运行时才分", "两者完全一样"],
      "answer": 0,
      "explain": "`SqlSourceBuilder` 只处理 `#{}`；`${}` 在 TextSqlNode 里按文本替换，所以有注入风险。"
    }
  ]
}
```

下一关把镜头拉近：`#{}` 到底在哪一步变成 `?`。

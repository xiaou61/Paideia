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

```code-flow
{
  "id": "dynamic-sql-nodes",
  "title": "动态 SQL 的建树与执行",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
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
      "sub": "反射 / 类型 / 缓存 / 插件",
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
  ],
  "steps": [
    {
      "title": "先判断动态还是静态",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 65,
      "code": "  public SqlSource parseScriptNode() {\n    MixedSqlNode rootSqlNode = parseDynamicTags(context);\n    SqlSource sqlSource;\n    if (isDynamic) {\n      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);\n    } else {\n      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);\n    }\n    return sqlSource;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "`parseScriptNode` 只做一个决定：树里有动态节点就是 `DynamicSqlSource`，否则 `RawSqlSource`。**没有 `<if>` 的 SQL 只解析一次**，这是性能上的关键分叉。",
      "state": {
        "vars": [
          {
            "name": "isDynamic",
            "value": "true"
          },
          {
            "name": "rootSqlNode",
            "value": "MixedSqlNode"
          }
        ]
      },
      "predict": {
        "question": "一条没有任何动态标签的 SQL 用哪种 SqlSource？",
        "options": [
          "DynamicSqlSource",
          "RawSqlSource",
          "StaticSqlSource"
        ],
        "answer": 1,
        "reveal": "没有动态节点走 RawSqlSource，构建期解析一次。"
      }
    },
    {
      "title": "把每个子节点翻译成 SqlNode",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 76,
      "code": "  protected MixedSqlNode parseDynamicTags(XNode node) {\n    List<SqlNode> contents = new ArrayList<>();\n    NodeList children = node.getNode().getChildNodes();\n    for (int i = 0; i < children.getLength(); i++) {\n      XNode child = node.newXNode(children.item(i));\n      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {\n        String data = child.getStringBody(\"\");\n        TextSqlNode textSqlNode = new TextSqlNode(data);\n        if (textSqlNode.isDynamic()) {\n          contents.add(textSqlNode);\n          isDynamic = true;\n        } else {\n          contents.add(new StaticTextSqlNode(data));\n        }\n      } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628\n        String nodeName = child.getNode().getNodeName();\n        NodeHandler handler = nodeHandlerMap.get(nodeName);\n        if (handler == null) {\n          throw new BuilderException(\"Unknown element <\" + nodeName + \"> in SQL statement.\");\n        }\n        handler.handleNode(child, contents);\n        isDynamic = true;\n      }\n    }\n    return new MixedSqlNode(contents);\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        20
      ],
      "nodes": [
        "base"
      ],
      "edges": [],
      "explain": "文本节点先看 `${}`：有就是 `TextSqlNode`（动态），没有就是 `StaticTextSqlNode`；元素节点查 `nodeHandlerMap`，找不到就抛「Unknown element」。**拼错标签名在这里就报错**，不会拖到运行期。",
      "log": [
        {
          "k": "混合节点",
          "v": "StaticTextSqlNode"
        },
        {
          "k": "if",
          "v": "IfSqlNode"
        },
        {
          "k": "where",
          "v": "WhereSqlNode"
        }
      ]
    },
    {
      "title": "<if>：求值，然后决定要不要拼",
      "file": "org/apache/ibatis/scripting/xmltags/IfSqlNode.java",
      "line": 32,
      "code": "  public boolean apply(DynamicContext context) {\n    if (evaluator.evaluateBoolean(test, context.getBindings())) {\n      contents.apply(context);\n      return true;\n    }\n    return false;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`evaluator.evaluateBoolean(test, bindings)` 用 OGNL 求值；为真才 `contents.apply(context)`。注意它返回 boolean——父节点（比如 `<trim>`）据此判断有没有内容。",
      "state": {
        "vars": [
          {
            "name": "test",
            "value": "name != null"
          },
          {
            "name": "结果",
            "value": "true"
          }
        ]
      }
    },
    {
      "title": "<foreach>：遍历、加分隔符、生成 __frch_ 变量",
      "file": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "line": 68,
      "code": "  public boolean apply(DynamicContext context) {\n    Map<String, Object> bindings = context.getBindings();\n    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings,\n        Optional.ofNullable(nullable).orElseGet(configuration::isNullableOnForEach));\n    if (iterable == null || !iterable.iterator().hasNext()) {\n      return true;\n    }\n    boolean first = true;\n    applyOpen(context);\n    int i = 0;\n    for (Object o : iterable) {\n      DynamicContext oldContext = context;\n      if (first || separator == null) {\n        context = new PrefixedContext(context, \"\");\n      } else {\n        context = new PrefixedContext(context, separator);\n      }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "集合为空直接返回；否则先追加 `open`（比如 `(`），再逐个元素用 `PrefixedContext` 加分隔符。每个元素有唯一编号，用来生成 `__frch_item_0` 这种绑定名。",
      "state": {
        "vars": [
          {
            "name": "collection",
            "value": "ids"
          },
          {
            "name": "separator",
            "value": ","
          },
          {
            "name": "uniqueNumber",
            "value": "0、1、2…"
          }
        ]
      }
    },
    {
      "title": "下标与元素：map 与 list 两种取法",
      "file": "org/apache/ibatis/scripting/xmltags/ForEachSqlNode.java",
      "line": 86,
      "code": "      // Issue #709\n      if (o instanceof Map.Entry) {\n        @SuppressWarnings(\"unchecked\")\n        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;\n        applyIndex(context, mapEntry.getKey(), uniqueNumber);\n        applyItem(context, mapEntry.getValue(), uniqueNumber);\n      } else {\n        applyIndex(context, i, uniqueNumber);\n        applyItem(context, o, uniqueNumber);\n      }\n      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));\n      if (first) {\n        first = !((PrefixedContext) context).isPrefixApplied();\n      }\n      context = oldContext;",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "元素是 `Map.Entry` 时，key 当下标、value 当元素；否则下标就是循环序号。最后 `contents.apply` 时用 `FilteredDynamicContext` 把 `#{item}` 换成 `#{__frch_item_0}`。",
      "log": [
        {
          "k": "#{item}",
          "v": "#{__frch_item_1}"
        },
        {
          "k": "#{index}",
          "v": "1"
        }
      ]
    },
    {
      "title": "<where> 的本质是一个 TrimSqlNode",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "code": "  public boolean apply(DynamicContext context) {\n    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);\n    boolean result = contents.apply(filteredDynamicContext);\n    filteredDynamicContext.applyAll();\n    return result;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`apply` 先用 `FilteredDynamicContext` 收集内容，再 `applyAll()` 统一裁剪前后缀。`<where>` 就是「前缀 WHERE、去掉开头的 AND/OR」；`<set>` 是「前缀 SET、去掉结尾的逗号」。",
      "state": {
        "vars": [
          {
            "name": "prefix",
            "value": "WHERE"
          },
          {
            "name": "prefixOverrides",
            "value": "AND | OR"
          }
        ]
      },
      "predict": {
        "question": "<where> 去掉的是什么？",
        "options": [
          "结尾的逗号",
          "开头的 AND/OR",
          "所有括号"
        ],
        "answer": 1,
        "reveal": "prefix=WHERE，prefixOverrides=AND|OR。"
      }
    },
    {
      "title": "用 <where>：开头的 AND 会被吃掉",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "code": "  public boolean apply(DynamicContext context) {\n    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);\n    boolean result = contents.apply(filteredDynamicContext);\n    filteredDynamicContext.applyAll();\n    return result;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "第一个 `<if>` 不成立、第二个成立时，拼出来是 `WHERE AND age > 18`。`<where>` 会把开头的 `AND` 去掉——这就是它存在的全部意义。",
      "log": [
        {
          "k": "拼出来",
          "v": "WHERE AND age > 18"
        },
        {
          "k": "最终",
          "v": "WHERE age > 18"
        }
      ]
    },
    {
      "title": "用 <trim>：前后缀自己定",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "code": "  public boolean apply(DynamicContext context) {\n    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);\n    boolean result = contents.apply(filteredDynamicContext);\n    filteredDynamicContext.applyAll();\n    return result;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "`prefix` / `prefixOverrides` / `suffix` / `suffixOverrides` 四个属性，`<where>` 与 `<set>` 都是它的特例。自定义时最容易踩的是覆写词要**大写并用 | 分隔**。",
      "log": [
        {
          "k": "prefix",
          "v": "WHERE"
        },
        {
          "k": "prefixOverrides",
          "v": "AND | OR"
        },
        {
          "k": "suffix",
          "v": "（空）"
        }
      ]
    },
    {
      "title": "什么都不包：语法错",
      "file": "org/apache/ibatis/scripting/xmltags/TrimSqlNode.java",
      "line": 56,
      "code": "  public boolean apply(DynamicContext context) {\n    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);\n    boolean result = contents.apply(filteredDynamicContext);\n    filteredDynamicContext.applyAll();\n    return result;\n  }",
      "language": "java",
      "focus": [
        1,
        2,
        3,
        4,
        5,
        6
      ],
      "nodes": [
        "config"
      ],
      "edges": [],
      "explain": "直接 `WHERE` 后面跟 `<if>`，第一个条件不成立就得到 `WHERE AND age > 18`，数据库报语法错。动态 SQL 的坑一半在这：**你看到的是模板，数据库看到的是拼完的字符串**。",
      "log": [
        {
          "k": "拼出来",
          "v": "WHERE AND age > 18"
        },
        {
          "k": "数据库",
          "v": "SQLSyntaxErrorException"
        }
      ]
    }
  ]
}
```

## 建树一次，执行多次

- 树在建 `SqlSessionFactory` 时就固定了；每次查询只是 `rootSqlNode.apply(context)` 走一遍。
- 动态判断用 OGNL，所以 `test="name != null and name != ''"` 是**表达式**，不是字符串比较。
- `${}` 是文本替换（有注入风险），`#{}` 是占位符 + 参数绑定。两者在这一层就分开了。

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
    0,
    2
  ],
  "explain": "先建树（parseScriptNode → parseDynamicTags），执行时再逐个 apply。"
}
```

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

---
title: ResultSetHandler：列怎么变成属性
category: mybatis-source
order: 16
minutes: 30
level: intermediate
status: published
---

## 这一关要读什么

SQL 回来了，`ResultSet` 里是一列一列的值，你要的是一个 `User` 对象。中间是
`DefaultResultSetHandler` 的活儿：先按 `resultMap` 显式映射，再做自动映射。

这一关看**自动映射到底怎么匹配**——`user_name` 为什么有时能对上 `userName`，有时不能。

```code-flow
{
  "id": "result-set-handler",
  "title": "handleResultSets 与自动映射",
  "repo": "mybatis/mybatis-3",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
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
      "title": "入口：每个结果集交给一个 resultMap",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 189,
      "code": "  public List<Object> handleResultSets(Statement stmt) throws SQLException {\n    ErrorContext.instance().activity(\"handling results\").object(mappedStatement.getId());\n\n    final List<Object> multipleResults = new ArrayList<>();\n\n    int resultSetCount = 0;\n    ResultSetWrapper rsw = getFirstResultSet(stmt);\n\n    List<ResultMap> resultMaps = mappedStatement.getResultMaps();\n    int resultMapCount = resultMaps.size();\n    validateResultMapsCount(rsw, resultMapCount);\n    while (rsw != null && resultMapCount > resultSetCount) {\n      ResultMap resultMap = resultMaps.get(resultSetCount);\n      handleResultSet(rsw, resultMap, multipleResults, null);\n      rsw = getNextResultSet(stmt);\n      cleanUpAfterHandlingResultSet();\n      resultSetCount++;\n    }\n\n    String[] resultSets = mappedStatement.getResultSets();\n    if (resultSets != null) {\n      while (rsw != null && resultSetCount < resultSets.length) {\n        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);\n        if (parentMapping != null) {\n          String nestedResultMapId = parentMapping.getNestedResultMapId();\n          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);\n          handleResultSet(rsw, resultMap, null, parentMapping);\n        }\n        rsw = getNextResultSet(stmt);\n        cleanUpAfterHandlingResultSet();\n        resultSetCount++;\n      }\n    }\n\n    return collapseSingleResultList(multipleResults);\n  }\n\n  @Override\n  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {\n    ErrorContext.instance().activity(\"handling cursor results\").object(mappedStatement.getId());",
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
        "executor"
      ],
      "edges": [],
      "explain": "`handleResultSets` 按语句顺序处理多个 ResultSet，每个都调 `handleResultSet` → `handleRowValues` → `getRowValue`。一个语句返回两个结果集（存储过程）时，这里会走多轮。",
      "state": {
        "vars": [
          {
            "name": "resultSets",
            "value": "1"
          },
          {
            "name": "resultMap",
            "value": "com.example.UserMapper.userMap"
          }
        ]
      }
    },
    {
      "title": "先建对象，再填属性",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 429,
      "code": "  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix,\n      Object partialObject) throws SQLException {\n    final String resultMapId = resultMap.getId();\n    Object rowValue = partialObject;\n    if (rowValue != null) {\n      final MetaObject metaObject = configuration.newMetaObject(rowValue);\n      putAncestor(rowValue, resultMapId);\n      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);\n      ancestorObjects.remove(resultMapId);\n    } else {\n      final ResultLoaderMap lazyLoader = new ResultLoaderMap();\n      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);\n      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {\n        final MetaObject metaObject = configuration.newMetaObject(rowValue);\n        boolean foundValues = this.useConstructorMappings;\n        if (shouldApplyAutomaticMappings(resultMap, true)) {\n          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;\n        }\n        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;\n        putAncestor(rowValue, resultMapId);\n        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true)\n            || foundValues;\n        ancestorObjects.remove(resultMapId);\n        foundValues = lazyLoader.size() > 0 || foundValues;\n        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;\n      }\n      if (combinedKey != CacheKey.NULL_CACHE_KEY) {\n        nestedResultObjects.put(combinedKey, rowValue);\n      }\n    }\n    return rowValue;\n  }\n\n  private void putAncestor(Object resultObject, String resultMapId) {\n    ancestorObjects.put(resultMapId, resultObject);\n  }\n\n  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {\n    if (resultMap.getAutoMapping() != null) {\n      return resultMap.getAutoMapping();\n    }\n    if (isNested) {\n      return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();\n    } else {\n      return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();\n    }\n  }\n\n  //\n  // PROPERTY MAPPINGS\n  //",
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
        "executor"
      ],
      "edges": [],
      "explain": "`getRowValue` 先 `createResultObject`（构造器/工厂），然后依次：`applyPropertyMappings`（显式 resultMap）→ `applyAutomaticMappings`（自动映射）→ 嵌套结果/关联对象 → 延迟加载登记。",
      "log": [
        {
          "k": "显式映射",
          "v": "resultMap 里写了的列"
        },
        {
          "k": "自动映射",
          "v": "没写但同名的列"
        }
      ]
    },
    {
      "title": "显式映射：resultMap 说了算",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 481,
      "code": "  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,\n      ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {\n    final Set<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);\n    boolean foundValues = false;\n    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();\n    for (ResultMapping propertyMapping : propertyMappings) {\n      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);\n      if (propertyMapping.getNestedResultMapId() != null) {\n        // the user added a column attribute to a nested result map, ignore it\n        column = null;\n      }\n      if (propertyMapping.isCompositeResult()\n          || column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))\n          || propertyMapping.getResultSet() != null) {\n        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader,\n            columnPrefix);\n        // issue #541 make property optional\n        final String property = propertyMapping.getProperty();\n        if (property == null) {\n          continue;\n        }\n        if (value == DEFERRED) {\n          foundValues = true;\n          continue;\n        }\n        if (value != null) {\n          foundValues = true;\n        }\n        if (value != null\n            || configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive()) {\n          // gcode issue #377, call setter on nulls (value is not 'found')\n          metaObject.setValue(property, value);\n        }\n      }\n    }\n    return foundValues;\n  }\n\n  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,\n      ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {\n    if (propertyMapping.getNestedQueryId() != null) {\n      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);\n    }\n    if (propertyMapping.getResultSet() != null) {\n      addPendingChildRelation(rs, metaResultObject, propertyMapping); // TODO is that OK?\n      return DEFERRED;\n    } else {\n      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();\n      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);\n      return typeHandler.getResult(rs, column);\n    }\n  }",
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
        "executor"
      ],
      "edges": [],
      "explain": "遍历 `resultMappings`，按 `column` 取列、按 `property` 写属性，类型转换交给 `typeHandler`。**显式映射优先**，同名属性不会被自动映射覆盖。",
      "log": [
        {
          "k": "<id column=\"id\" property",
          "v": "已处理"
        },
        {
          "k": "<result column=\"user_nam",
          "v": "已处理"
        }
      ],
      "predict": {
        "question": "显式 resultMap 与自动映射谁先执行？",
        "options": [
          "显式映射",
          "自动映射"
        ],
        "answer": 0,
        "reveal": "先 applyPropertyMappings，自动映射只处理剩下的列。"
      }
    },
    {
      "title": "自动映射：只处理「没配过」的列",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 534,
      "code": "  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,\n      MetaObject metaObject, String columnPrefix) throws SQLException {\n    final String mapKey = resultMap.getId() + \":\" + columnPrefix;\n    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);\n    if (autoMapping == null) {\n      autoMapping = new ArrayList<>();\n      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);\n      // Remove the entry to release the memory\n      List<String> mappedInConstructorAutoMapping = constructorAutoMappingColumns.remove(mapKey);\n      if (mappedInConstructorAutoMapping != null) {\n        unmappedColumnNames.removeAll(mappedInConstructorAutoMapping);\n      }\n      for (String columnName : unmappedColumnNames) {\n        String propertyName = columnName;\n        if (columnPrefix != null && !columnPrefix.isEmpty()) {\n          // When columnPrefix is specified,\n          // ignore columns without the prefix.\n          if (!columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {\n            continue;\n          }\n          propertyName = columnName.substring(columnPrefix.length());\n        }\n        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());\n        if (property != null && metaObject.hasSetter(property)) {\n          if (resultMap.getMappedProperties().contains(property)) {\n            continue;\n          }\n          final Class<?> propertyType = metaObject.getSetterType(property);\n          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {\n            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);\n            autoMapping\n                .add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));\n          } else {\n            configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName, property,\n                propertyType);\n          }\n        } else {\n          configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName,\n              property != null ? property : propertyName, null);\n        }\n      }\n      autoMappingsCache.put(mapKey, autoMapping);\n    }\n    return autoMapping;\n  }",
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
        "executor"
      ],
      "edges": [],
      "explain": "`createAutomaticMappings` 把 ResultSet 里**没被 resultMap 用过**的列拿出来，按列名找属性。`autoMappingBehavior=PARTIAL`（默认）时，有嵌套结果映射就不做自动映射。",
      "log": [
        {
          "k": "未映射列",
          "v": "age, email"
        },
        {
          "k": "候选属性",
          "v": "age, email"
        }
      ],
      "predict": {
        "question": "PARTIAL 模式下什么时候不做自动映射？",
        "options": [
          "没有嵌套映射",
          "有嵌套结果映射"
        ],
        "answer": 1,
        "reveal": "有 association/collection 时跳过自动映射。"
      }
    },
    {
      "title": "user_name 能不能对上 userName，全看这个开关",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 580,
      "code": "  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,\n      String columnPrefix) throws SQLException {\n    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);\n    boolean foundValues = false;\n    if (!autoMapping.isEmpty()) {\n      for (UnMappedColumnAutoMapping mapping : autoMapping) {\n        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);\n        if (value != null) {\n          foundValues = true;\n        }\n        if (value != null || configuration.isCallSettersOnNulls() && !mapping.primitive) {\n          // gcode issue #377, call setter on nulls (value is not 'found')\n          metaObject.setValue(mapping.property, value);\n        }\n      }\n    }\n    return foundValues;\n  }\n\n  // MULTIPLE RESULT SETS\n\n  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {\n    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),\n        parentMapping.getForeignColumn());\n    List<PendingRelation> parents = pendingRelations.get(parentKey);\n    if (parents != null) {\n      for (PendingRelation parent : parents) {\n        if (parent != null && rowValue != null) {\n          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);\n        }\n      }\n    }\n  }\n\n  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)\n      throws SQLException {\n    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),\n        parentMapping.getColumn());\n    PendingRelation deferLoad = new PendingRelation();\n    deferLoad.metaObject = metaResultObject;\n    deferLoad.propertyMapping = parentMapping;\n    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());\n    // issue #255\n    relations.add(deferLoad);\n    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());",
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
        "executor"
      ],
      "edges": [],
      "explain": "`applyAutomaticMappings` 里对列名做 `configuration.isMapUnderscoreToCamelCase()` 判断：开着就把 `user_name` 转成 `userName` 再找；关着就按原样找，找不到就跳过（`autoMappingUnknownColumnBehavior` 默认 NONE，不报错）。",
      "log": [
        {
          "k": "列 user_name",
          "v": "→ userName（命中）"
        },
        {
          "k": "未映射列",
          "v": "0"
        }
      ]
    },
    {
      "title": "关掉驼峰：user_name 就没人接",
      "file": "org/apache/ibatis/executor/resultset/DefaultResultSetHandler.java",
      "line": 580,
      "code": "  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,\n      String columnPrefix) throws SQLException {\n    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);\n    boolean foundValues = false;\n    if (!autoMapping.isEmpty()) {\n      for (UnMappedColumnAutoMapping mapping : autoMapping) {\n        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);\n        if (value != null) {\n          foundValues = true;\n        }\n        if (value != null || configuration.isCallSettersOnNulls() && !mapping.primitive) {\n          // gcode issue #377, call setter on nulls (value is not 'found')\n          metaObject.setValue(mapping.property, value);\n        }\n      }\n    }\n    return foundValues;\n  }\n\n  // MULTIPLE RESULT SETS\n\n  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {\n    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),\n        parentMapping.getForeignColumn());\n    List<PendingRelation> parents = pendingRelations.get(parentKey);\n    if (parents != null) {\n      for (PendingRelation parent : parents) {\n        if (parent != null && rowValue != null) {\n          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);\n        }\n      }\n    }\n  }\n\n  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)\n      throws SQLException {\n    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),\n        parentMapping.getColumn());\n    PendingRelation deferLoad = new PendingRelation();\n    deferLoad.metaObject = metaResultObject;\n    deferLoad.propertyMapping = parentMapping;\n    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());\n    // issue #255\n    relations.add(deferLoad);\n    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());",
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
        "executor"
      ],
      "edges": [],
      "explain": "默认是关的。列 `user_name` 找不到属性 `user_name`，于是被静默跳过——对象上 `userName` 还是 null。**这就是「为什么我的字段是 null」最常见的原因**。",
      "log": [
        {
          "k": "列 user_name",
          "v": "找不到属性（跳过）"
        },
        {
          "k": "userName",
          "v": "null"
        }
      ]
    }
  ]
}
```

## 嵌套结果与延迟加载

- 显式 `<association>` / `<collection>` 的列会先被消费，自动映射不会再碰。
- `fetchType="lazy"` 时，此行不查关联对象，而是登记一个 `ResultLoader`，访问属性时才查。
- `autoMappingBehavior` 改成 `FULL` 时，即使有嵌套结果映射也做自动映射——方便，也更容易撞上意料之外的字段。

```code-challenge
{
  "id": "ex-resultset",
  "kind": "fill",
  "title": "补列名与属性名的桥",
  "template": "列 `user_name` 要映射到 `userName`，需要开启 `{{0}}`；显式 resultMap 的映射{{1}}自动映射。",
  "options": [
    [
      "mapUnderscoreToCamelCase",
      "useActualParamName"
    ],
    [
      "优先于",
      "晚于"
    ]
  ],
  "answers": [
    0,
    0
  ],
  "explain": "驼峰开关默认关；显式映射先跑，自动映射只处理剩下的列。"
}
```

```quiz
{
  "id": "resultset-quiz",
  "title": "小测：结果映射",
  "questions": [
    {
      "question": "列 user_name 对属性 userName，默认能映射上吗？",
      "options": ["能", "不能，mapUnderscoreToCamelCase 默认关", "只有 resultMap 能"],
      "answer": 1,
      "explain": "默认 false；要么开这个开关，要么在 resultMap 里显式写。"
    },
    {
      "question": "显式 resultMap 与自动映射谁优先？",
      "options": ["自动映射", "显式映射", "后执行的覆盖"],
      "answer": 1,
      "explain": "先 applyPropertyMappings，自动映射只处理剩下的列。"
    },
    {
      "question": "autoMappingUnknownColumnBehavior 默认值是什么？",
      "options": ["NONE（不报错）", "WARNING", "FAILING"],
      "answer": 0,
      "explain": "默认 NONE：多出来的列静默忽略。"
    }
  ]
}
```

下一关：值到底怎么从 `String` 变成 `LocalDateTime`——`TypeHandler`。

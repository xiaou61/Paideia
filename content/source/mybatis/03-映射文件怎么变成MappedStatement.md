---
title: 一个 select 标签怎么变成 MappedStatement
category: mybatis-source
order: 3
minutes: 24
level: intermediate
status: published
---

版本：MyBatis 3.5.19，git revision ee0d4f4；行号核对日期 2026-11-19。下面所有 `文件:行号` 都指这个版本。

## 断点停在第 224 行，id 变成了全限定名

建一个最小的 `UserMapper.xml`，不连数据库，只让 `XMLMapperBuilder` 解析：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.UserMapper">
  <select id="selectById" resultType="map">
    select id, name from user where id = #{id}
  </select>
</mapper>
```

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

public class Main {
  public static void main(String[] args) throws Exception {
    Configuration c = new Configuration();
    try (InputStream in = Files.newInputStream(Path.of("UserMapper.xml"))) {
      new XMLMapperBuilder(in, c, "mapper/UserMapper.xml", c.getSqlFragments()).parse();
    }
    System.out.println(c.getMappedStatement("com.example.UserMapper.selectById").getId());
  }
}
```

Windows 上用 `javac -encoding UTF-8 -cp mybatis-3.5.19.jar Main.java` 编译，再跑 `java -cp "mybatis-3.5.19.jar;." Main`；Linux/macOS 把 classpath 分隔符换成 `:`。JDK 25 + `mybatis-3.5.19.jar` 的实测输出只有一行：

```text
com.example.UserMapper.selectById
```

XML 里写的是 `id="selectById"`。在 `MapperBuilderAssistant.addMappedStatement` 的 224 行下断点，`statement.getId()` 已经是 `com.example.UserMapper.selectById`；再往上，209 行的 `applyCurrentNamespace(id, false)` 就是加前缀的那一步。

把 namespace 改成 `com.example.UserMappper`（多一个 p），再用同一个程序查正确 id：

```text
IllegalArgumentException: Mapped Statements collection does not contain value for com.example.UserMapper.selectById
```

这不是概率问题。`Configuration.addMappedStatement:833-835` 按 `ms.getId()` 存 key；`StrictMap.get:1181-1185` 在 key 不存在时直接抛。同一份探针的 `getMappedStatementNames()` 里能看到 `com.example.UserMappper.selectById`，你查的是 `com.example.UserMapper.selectById`，两个字符串只差一个 p，运行期就是找不到。解析阶段不会因为 namespace 不是接口而失败：`bindMapperForNamespace:383-392` 把 `ClassNotFoundException` ignore 掉；报错发生在按 id 取语句时。

这条 id 的拼装牵出三层 Builder：

```text
XMLMapperBuilder.parse:96
  configurationElement:111
    buildStatementFromContext:136
      XMLStatementBuilder.parseStatementNode:58
        MapperBuilderAssistant.addMappedStatement:209 / 224
```

## 三级 Builder 各管一段

`XMLMapperBuilder` 管一个 mapper 文件：namespace、cache、parameterMap、resultMap、`<sql>` 片段、所有语句。`XMLStatementBuilder` 只管一个 `<select|insert|update|delete>` 节点，但它要处理 databaseId、include、selectKey、主键策略、SqlSource 和结果映射。`MapperBuilderAssistant` 最后把 id 补全、用 `MappedStatement.Builder` 组装、注册进 `Configuration`。

下面这条走读线按源码顺序走一遍。控件默认是 `useGeneratedKeys=false`，也就是 MyBatis 的框架默认值；把它打开后，主键那两步会换成另一条分支。

```source-walk
{
  "id": "mapper-xml-to-statement",
  "title": "从 <select> 到 MappedStatement",
  "repo": "mybatis/mybatis-3",
  "ref": "mybatis-3.5.19",
  "commit": "ee0d4f4831ffdd311b0183c202f4ad6492a3f404",
  "license": "Apache-2.0",
  "base": "source/mybatis-3.5.19",
  "files": [
    {
      "path": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "label": "XMLMapperBuilder"
    },
    {
      "path": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "label": "XMLStatementBuilder"
    },
    {
      "path": "org/apache/ibatis/builder/MapperBuilderAssistant.java",
      "label": "MapperBuilderAssistant"
    },
    {
      "path": "org/apache/ibatis/executor/keygen/Jdbc3KeyGenerator.java",
      "label": "Jdbc3KeyGenerator"
    },
    {
      "path": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "label": "XMLScriptBuilder"
    }
  ],
  "controls": [
    {
      "id": "generatedKeys",
      "type": "toggle",
      "label": "useGeneratedKeys",
      "default": false
    }
  ],
  "steps": [
    {
      "title": "一个 Mapper 文件只解析一次",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 96,
      "lines": 10,
      "explain": "97 行先看 `isResourceLoaded`，已加载就跳过 98-100 行；没加载才 `configurationElement`、记账、绑定 namespace。102-104 行是延迟重试：ResultMap、cache-ref、语句各自扫一遍 pending 队列。",
      "state": {
        "vars": [
          [
            "resource",
            "mapper/UserMapper.xml"
          ],
          [
            "namespace",
            "com.example.UserMapper"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "namespace 先落进 Assistant，再按依赖顺序解析",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 111,
      "lines": 17,
      "explain": "113-117 行取 namespace、拒绝空值、`builderAssistant.setCurrentNamespace`。118-123 行的顺序是 cache-ref、cache、parameterMap、resultMap、sql、最后才是语句；语句可能引用前五者，所以必须最后解析。",
      "state": {
        "tables": [
          [
            "currentNamespace",
            "com.example.UserMapper"
          ],
          [
            "parseOrder",
            "cache-ref → cache → parameterMap → resultMap → sql → statements"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "每条语句交给 XMLStatementBuilder",
      "file": "org/apache/ibatis/builder/xml/XMLMapperBuilder.java",
      "line": 136,
      "lines": 10,
      "explain": "137-141 行逐个建 `XMLStatementBuilder` 并 `parseStatementNode`；142-144 行 catch `IncompleteElementException` 后把 parser 放进 `incompleteStatements`。这不是报错，是留到 102-104 行的 pending 队列里重试。",
      "state": {
        "stack": [
          "XMLMapperBuilder.parse()",
          "buildStatementFromContext(list)",
          "XMLStatementBuilder.parseStatementNode()"
        ],
        "vars": [
          [
            "statements",
            "4 条"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "先按 databaseId 过滤",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 58,
      "lines": 7,
      "explain": "59-60 行取 id 和 databaseId；62-63 行 `databaseIdMatchesCurrent` 不匹配就直接 return。`XMLMapperBuilder` 配置了 databaseId 时会先用它解析一遍，再用 null 解析一遍，185-199 行负责防止 null 版本覆盖专用版本。",
      "state": {
        "vars": [
          [
            "id",
            "selectById"
          ],
          [
            "databaseId",
            "null（没配）"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "标签名决定 SqlCommandType，默认值按类型给",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 66,
      "lines": 6,
      "explain": "67 行 `<select>` → SELECT；68 行 `isSelect`；69 行 `flushCache` 默认 `!isSelect`；70 行 `useCache` 默认 `isSelect`。所以 select 默认不清缓存、写语句默认清缓存。`flushCache` 会同时作用于一级和二级，`useCache` 只在 `CachingExecutor.query:99` 管二级。",
      "predict": {
        "question": "一条 <update> 默认会清缓存吗？",
        "options": [
          "会，flushCache 默认 true",
          "不会，要手动写 flushCache",
          "取决于数据库"
        ],
        "answer": 0,
        "reveal": "69 行 `!isSelect` 给写语句 `flushCache=true`；`BaseExecutor.update:116` 无条件清一级，有二级缓存时 `CachingExecutor.update:75` 按 `flushCache` 清二级。"
      },
      "state": {
        "tables": [
          [
            "<select>",
            "flushCache=false，useCache=true"
          ],
          [
            "<update>",
            "flushCache=true，useCache=false"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "include 必须在解析 SQL 之前展开",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 74,
      "lines": 2,
      "explain": "74-75 行 `applyIncludes` 把 `<sql>` 片段拼进当前节点；98 行的 `createSqlSource` 看到的才是完整 SQL。反过来做，动态 SQL 解析器会看到还没展开的 `<include>`。",
      "state": {
        "tables": [
          [
            "sqlFragments",
            "Base_Column_List → id, name, age"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "selectKey 先处理，它不归 useGeneratedKeys 管",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 84,
      "lines": 1,
      "explain": "84 行 `processSelectKeyNodes` 先把 `<selectKey>` 解析成独立的 MappedStatement（id 加 `!selectKey`），176 行注册 `SelectKeyGenerator`，131 行再把节点从原语句里删掉。后面 88-91 行先查这个 keyGenerator；查到就用它，不再看 `useGeneratedKeys`。",
      "state": {
        "tables": [
          [
            "<selectKey>",
            "独立 MappedStatement + SelectKeyGenerator"
          ],
          [
            "优先级",
            "先于 useGeneratedKeys"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "没有 selectKey 时，才看 useGeneratedKeys",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 87,
      "lines": 10,
      "explain": "88-91 行先拼 `id + !selectKey` 并查 `configuration.hasKeyGenerator`；没有注册过，才走 93-95 行的三元表达式。默认值表达式是 `configuration.isUseGeneratedKeys() && INSERT`，但语句上显式写 true 时，`getBooleanAttribute` 直接返回 true，不看命令类型。",
      "state": {
        "tables": [
          [
            "有 selectKey",
            "SelectKeyGenerator"
          ],
          [
            "没有 selectKey",
            "看 93-95 行"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "没有 selectKey、显式 true：Jdbc3KeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 93,
      "lines": 3,
      "explain": "确认没有注册 selectKey 后，`useGeneratedKeys` 显式为 true 时选 `Jdbc3KeyGenerator`。默认值里的 INSERT 只影响「没写属性」的情况；UPDATE 上显式写 true 也会拿到 Jdbc3KeyGenerator。回填还要 `keyProperty`，见下一步。",
      "when": {
        "generatedKeys": true
      },
      "state": {
        "tables": [
          [
            "keyGenerator",
            "Jdbc3KeyGenerator"
          ],
          [
            "keyProperty",
            "115 行读取，120-122 行传入"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "没有 selectKey、默认 false：NoKeyGenerator",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 93,
      "lines": 3,
      "explain": "确认没有注册 selectKey 后，不写 `useGeneratedKeys` 时，`XMLConfigBuilder.settingsElement:271` 的默认值是 false；`Configuration.java:111` 的字段也没有初始值。插入照常执行，但 keyGenerator 是 `NoKeyGenerator`，不会回填 id。",
      "when": {
        "generatedKeys": false
      },
      "predict": {
        "question": "insert 不写 useGeneratedKeys，插入后对象上的 id 会回填吗？",
        "options": [
          "会，框架默认开",
          "不会，默认是 false",
          "看数据库是否自增"
        ],
        "answer": 1,
        "reveal": "`XMLConfigBuilder.settingsElement:271` 的默认值是 false；`XMLStatementBuilder:93-95` 选 `NoKeyGenerator`。"
      },
      "state": {
        "tables": [
          [
            "keyGenerator",
            "NoKeyGenerator"
          ],
          [
            "回填",
            "无"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "keyProperty 为空，Jdbc3 也不回填",
      "file": "org/apache/ibatis/executor/keygen/Jdbc3KeyGenerator.java",
      "line": 74,
      "lines": 5,
      "explain": "74-78 行先看 `ms.getKeyProperties()`；null 或空数组直接 return，连 `getGeneratedKeys()` 都不调用。`keyProperty` 在 `XMLStatementBuilder:115` 读取、120-122 行传入，`MappedStatement.Builder.keyProperty:155-157` 拆成数组；空串在 340-345 行变成 null。",
      "when": {
        "generatedKeys": true
      },
      "state": {
        "tables": [
          [
            "useGeneratedKeys=true，无 keyProperty",
            "keyProps=null → return"
          ],
          [
            "useGeneratedKeys=true，keyProperty=id",
            "keyProps=[id] → assignKeys"
          ]
        ]
      },
      "nodes": [
        "executor"
      ]
    },
    {
      "title": "createSqlSource 与结果映射引用",
      "file": "org/apache/ibatis/builder/xml/XMLStatementBuilder.java",
      "line": 97,
      "lines": 13,
      "explain": "98 行 `langDriver.createSqlSource` 决定 SqlSource；99-100 行取 statementType；104-108 行读 resultType/resultMap，两个都没写时才用 `MapperAnnotationBuilder.getMethodReturnType:627-642` 猜接口方法返回类型。",
      "state": {
        "vars": [
          [
            "sqlSource",
            "RawSqlSource"
          ],
          [
            "statementType",
            "PREPARED"
          ],
          [
            "resultType",
            "map"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "没有动态节点，就是 RawSqlSource",
      "file": "org/apache/ibatis/scripting/xmltags/XMLScriptBuilder.java",
      "line": 65,
      "lines": 9,
      "explain": "65-73 行：`isDynamic=false` 走 `RawSqlSource`，true 才走 `DynamicSqlSource`。76-95 行里，文本含 `${}` 或遇到 `<if>`、`<where>`、`<foreach>` 等元素节点都会把 isDynamic 置 true；只有 `#{}` 的静态 select 不算动态。",
      "state": {
        "tables": [
          [
            "无 if 且无 ${}",
            "RawSqlSource"
          ],
          [
            "有 if 或 ${}",
            "DynamicSqlSource"
          ]
        ]
      },
      "nodes": [
        "config"
      ]
    },
    {
      "title": "最后一步：拼 id、组装、入库",
      "file": "org/apache/ibatis/builder/MapperBuilderAssistant.java",
      "line": 209,
      "lines": 17,
      "explain": "209 行 `applyCurrentNamespace(id, false)` 补上 namespace；211-216 行用 Builder 设字段，215 行把结果映射交给 `getStatementResultMaps`；223 行 build，224 行 `configuration.addMappedStatement`。断点打在 224 行，看到的就是拼好的 id。",
      "predict": {
        "question": "addMappedStatement 会把 id 补成什么？",
        "options": [
          "selectById",
          "com.example.UserMapper.selectById",
          "namespace:selectById"
        ],
        "answer": 1,
        "reveal": "`applyCurrentNamespace:86-105` 在 209 行拼 `currentNamespace + . + base`；`Configuration.addMappedStatement:833-835` 用这个 id 入库。"
      },
      "state": {
        "stack": [
          "MapperBuilderAssistant.addMappedStatement(...)"
        ],
        "tables": [
          [
            "mappedStatements",
            "com.example.UserMapper.selectById"
          ],
          [
            "statement.parameterMap",
            "自动生成"
          ]
        ]
      },
      "nodes": [
        "base"
      ]
    },
    {
      "title": "resultMap 非空时，resultType 被静默忽略",
      "file": "org/apache/ibatis/builder/MapperBuilderAssistant.java",
      "line": 314,
      "lines": 21,
      "explain": "315 行先给 resultMap 补 namespace；318-327 行只要 resultMap 非空就逐个取 ResultMap；328-331 行的 resultType 分支只在 resultMap 为 null 时执行。两个都写不报错，resultMap 赢。resultMap 找不到才会在 321-325 行抛 IncompleteElementException。",
      "state": {
        "tables": [
          [
            "resultMap + resultType",
            "只用 resultMap"
          ],
          [
            "resultMap 找不到",
            "IncompleteElementException，稍后重试"
          ]
        ]
      },
      "nodes": [
        "result"
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

走读线到 `addMappedStatement:224` 结束；`SqlSource` 怎么生成 `BoundSql`、参数怎么绑，留到 06 关。

## id 是拼出来的，namespace 写错就查不到

`MapperBuilderAssistant.applyCurrentNamespace:86-105` 的规则很短：

- `base == null` 直接返回 null（87-89）。
- `isReference=true` 时，已经带点的引用原样返回（90-94）；`resultMap`、`parameterMap`、`cache-ref` 走这条。
- `isReference=false` 时，如果已经以当前 namespace 开头就返回（97-99）；如果带点但不是当前 namespace，抛 `Dots are not allowed in element names`（100-102）；否则拼 `currentNamespace + "." + base`（104）。

语句 id 在 209 行走的是 `isReference=false`。所以 XML 里的 `id` 写 `selectById` 就够了；写成 `com.example.UserMapper.selectById` 且 namespace 一致也能过，写成别的 namespace 会在解析期被 100-102 挡下。注册之后，`getMappedStatement` 用拼好的全名查；namespace 拼错，`StrictMap.get` 就抛上面那条 `Mapped Statements collection does not contain value for ...`。

`StrictMap.put:1160-1166` 还会给带点的 key 存一个短名副本，所以 `getMappedStatementNames()` 里会同时看到 `com.example.UserMapper.selectById` 和 `selectById`。短名能用，但别把它当契约：接口调用和 `session.selectOne` 的常规写法都用全名。

## 默认值按语句类型给，useGeneratedKeys 默认是 false

`XMLStatementBuilder.parseStatementNode:66-71` 先拿标签名：

```java
String nodeName = context.getNode().getNodeName();                       // 66
SqlCommandType sqlCommandType = SqlCommandType.valueOf(...);             // 67
boolean isSelect = sqlCommandType == SqlCommandType.SELECT;              // 68
boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect); // 69
boolean useCache = context.getBooleanAttribute("useCache", isSelect);      // 70
```

`getBooleanAttribute` 的第二个参数是默认值，所以 `<select>` 默认 `flushCache=false`、`useCache=true`；`<insert>/<update>/<delete>` 默认反过来。探针打印 `selectById` 是 `flushCache=false useCache=true`，`insertOk` 是 `flushCache=true useCache=false`。

| 属性 | 一级缓存 `BaseExecutor.localCache` | 二级缓存 `CachingExecutor` |
| --- | --- | --- |
| `flushCache` | 查询前 148-149 行清；`update:116` 总是清 | `update:75` / `query:98` → `flushCacheIfRequired:168-172` |
| `useCache` | 不参与 | `query:99` 决定是否读写 |

所以准确说法是：`flushCache` 影响一级与二级，`useCache` 只影响二级。把 `useCache` 算成一级缓存的一半，会把「查二级缓存」误当成「查 SqlSession 的一级缓存」。

`useGeneratedKeys` 的默认值在 `XMLConfigBuilder.settingsElement:271`：读 settings 时第二个参数是 `false`。`Configuration.java:111` 的字段也没有初始值，Java 默认就是 false。我原来以为它是 true，探针里 `insertDefault` 打出 `NoKeyGenerator` 才发现反了。

```java
keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
    configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
        ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
```

这是 `XMLStatementBuilder:93-95` 的原文。`INSERT` 只出现在默认值里：语句上显式写 `useGeneratedKeys="true"` 时，`getBooleanAttribute` 直接返回 true，不看命令类型。所以 UPDATE 上显式写 true 也会拿到 `Jdbc3KeyGenerator`。同一个探针不执行 SQL，解析后读 `getKeyGenerator()` 和 `getKeyProperties()`，实测值如下：

| 语句 | keyGen | keyProps |
| --- | --- | --- |
| `insertDefault` | `NoKeyGenerator` | `null` |
| `insertNoKeyProperty` | `Jdbc3KeyGenerator` | `null` |
| `insertOk` | `Jdbc3KeyGenerator` | `[id]` |
| `updateWithKeys` | `Jdbc3KeyGenerator` | `[id]` |
| `insertSelectKey` | `SelectKeyGenerator` | `null` |

## 主键回填要 useGeneratedKeys 和 keyProperty 同时成立

`useGeneratedKeys` 只决定选哪个 KeyGenerator，回填还要 `keyProperty`。`XMLStatementBuilder` 在 115 行读 `keyProperty`，120-122 行传给 Assistant；Assistant 在 213 行 `keyProperty(keyProperty)`，`MappedStatement.Builder.keyProperty:155-157` 把它拆成数组，空字符串在 340-345 行返回 null。

真正回填在 `Jdbc3KeyGenerator.processBatch:74-78`：

```java
final String[] keyProperties = ms.getKeyProperties();
if (keyProperties == null || keyProperties.length == 0) {
  return;
}
```

`keyProperties` 为空就直接返回，连 `getGeneratedKeys()` 都不调用。探针里 `insertNoKeyProperty` 的 `keyGen` 是 `Jdbc3KeyGenerator`，但 `keyProps=null`，所以插入后 id 不会变。正确写法是：

```xml
<insert id="insertOk" useGeneratedKeys="true" keyProperty="id">
  insert into user(name) values(#{name})
</insert>
```

这时 `keyProps=[id]`，`processAfter:70-72` 才会走到 `processBatch`，85 行把生成键赋回对象。

`<selectKey>` 是另一条路，而且在 `useGeneratedKeys` 之前处理：`XMLStatementBuilder:84` 调 `processSelectKeyNodes`，它在 125-183 行把 `<selectKey>` 解析成独立的 MappedStatement（id 加 `!selectKey`），176 行注册 `SelectKeyGenerator`，再把节点从原语句里删掉（131 行）。后面的 88-91 行先查这个 keyGenerator；查到就用它，不再看 `useGeneratedKeys`。探针里 `insertSelectKey` 的 `keyGen=SelectKeyGenerator` 就是这个分支。

顺序上要记清楚：84 行处理 `<selectKey>`，87-96 行才决定 `useGeneratedKeys` 的默认分支。把 84 行漏掉，就会以为 `<selectKey>` 是在 87-96 里处理的。

## 静态 select 是 RawSqlSource，不是 DynamicSqlSource

`XMLStatementBuilder:98` 把节点交给 `langDriver.createSqlSource`。默认语言驱动最后走到 `XMLScriptBuilder.parseScriptNode:65-73`（`XMLLanguageDriver` 不在这份快照里，下面引它最终调用的脚本构建器）：

```java
MixedSqlNode rootSqlNode = parseDynamicTags(context);
if (isDynamic) {
  sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
} else {
  sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
}
```

`isDynamic` 在 `parseDynamicTags:76-95` 里置位：文本里有 `${}`（`TextSqlNode.isDynamic`）或者遇到 `<if>`、`<where>`、`<foreach>` 这类节点，都会走动态分支。只有 `#{}` 的静态 SQL 不算动态。探针里：

| 语句 | SqlSource |
| --- | --- |
| `selectById` | `RawSqlSource` |
| `selectDynamic` | `DynamicSqlSource` |

`selectById` 的 XML 没有 `<if>`、没有 `${}`，所以它是 `RawSqlSource`。原例子里的 `resultType=User` 也不影响这个判断：SqlSource 只看 SQL 文本和动态节点，`resultType` 是结果映射的事。把静态 select 标成 `DynamicSqlSource` 是错的；如果例子里确实有 `<if>`，那才应该是 `DynamicSqlSource`。

`RawSqlSource` 构造时就把 SQL 解析好（39-47 行），`getBoundSql` 直接委托（56-58 行）。`SqlSource` 和 `BoundSql` 的细节在 06 关展开，这里只认类型。

## resultMap 与 resultType 同时写，resultMap 赢

`XMLStatementBuilder:104-108` 读两个属性：`resultType`、`resultMap`；只有两个都为 null 时才调 `MapperAnnotationBuilder.getMethodReturnType:627-642` 猜接口方法返回类型。它不检查「两个都写了」。

`MapperBuilderAssistant.getStatementResultMaps:314-334` 的顺序是：

1. 315 行先给 `resultMap` 补 namespace；
2. 318-327 行只要 `resultMap != null`，就按逗号拆开、逐个从 Configuration 取 ResultMap；
3. 328-331 行才轮到 `resultType`，条件是 `resultMap == null`。

所以同时写 `resultMap` 和 `resultType` 时，`resultType` 被静默忽略，不报错。探针里 `both` 的 `resultMaps=[com.example.UserMapper.userMap]`，没有 `resultType` 的内联 map。约定是二选一，实际是 resultMap 优先。

如果 `resultMap` 写了但找不到，321-325 行会抛 `IncompleteElementException`，不是静默忽略；这个异常会被 `XMLMapperBuilder` 的 136-145 行接住，留到后面重试。

## 顺序、延迟重试与 databaseId

`configurationElement:111-127` 的解析顺序是固定的：

```text
118 cacheRefElement
119 cacheElement
120 parameterMapElement
121 resultMapElements
122 sqlElement
123 buildStatementFromContext
```

语句放最后，因为它可能引用前面的 cache、parameterMap、resultMap 和 `<sql>` 片段。练习里容易漏掉的是 `cache-ref` 和 `parameterMap`，它们在这条顺序里各有位置。

`include` 还要更早展开：`XMLStatementBuilder:74-75` 在 `createSqlSource:98` 之前调用 `applyIncludes`，把 `<sql>` 片段拼进当前节点。反过来的话，`XMLScriptBuilder.parseDynamicTags:93-94` 会把还没展开的 `<include>` 当未知元素抛 `Unknown element`。

`IncompleteElementException` 不是配置写错。`XMLMapperBuilder.parse:96-105` 在解析完当前文件后，102-104 行分别重试 pending 的 ResultMap、cache-ref 和语句；`buildStatementFromContext:136-145` 捕获异常后把 `XMLStatementBuilder` 放进 `incompleteStatements`。典型场景是 A mapper 的语句引用了 B mapper 后面才定义的 resultMap：第一次解析找不到，先记下，等所有资源加载完再重试。

`databaseId` 在 `parseStatementNode:58-64` 先过滤：`databaseId` 不匹配当前 `requiredDatabaseId` 就直接 return（62-63）。`XMLMapperBuilder.buildStatementFromContext:129-134` 配置了 databaseId 时会先用它解析一遍，再用 null 解析一遍；`databaseIdMatchesCurrent:185-199` 防止 null 版本覆盖已经注册的 databaseId 专用版本。

下一篇 04 是「注解方式与 XML」；`binding` 包的 `MapperRegistry` / `MapperProxyFactory` 在 08 关。

## 动手

```code-challenge
{
  "id": "ex-statement",
  "kind": "order",
  "title": "排出 select 的装配顺序",
  "lines": [
    "MapperBuilderAssistant.addMappedStatement(...)",
    "XMLMapperBuilder.parse()",
    "XMLStatementBuilder.parseStatementNode()",
    "configurationElement() 依次解析 cache-ref/cache/parameterMap/resultMap/sql",
    "buildStatementFromContext() 遍历 select/insert/update/delete"
  ],
  "answer": [
    1,
    3,
    4,
    2,
    0
  ],
  "explain": "XMLMapperBuilder.parse:96 先进 configurationElement:111；118-122 依次是 cache-ref、cache、parameterMap、resultMap、sql，123 才 buildStatementFromContext。每条语句进 XMLStatementBuilder.parseStatementNode:58，最后 MapperBuilderAssistant.addMappedStatement:209 拼 id 并注册。"
}
```

## 检查一下

```quiz
{
  "id": "statement-quiz",
  "title": "小测：装配线与默认值",
  "questions": [
    {
      "question": "MappedStatement 的 id 最终长什么样？",
      "options": [
        "selectById",
        "com.example.UserMapper.selectById",
        "UserMapper#selectById"
      ],
      "answer": 1,
      "explain": "MapperBuilderAssistant.applyCurrentNamespace:86-105 在 209 行给语句 id 拼上 namespace；XML 里的 id 只写方法名。"
    },
    {
      "question": "useGeneratedKeys=true 但没写 keyProperty，插入后 id 会回填吗？",
      "options": [
        "会，Jdbc3KeyGenerator 会自动找 id",
        "只在 MySQL 会",
        "不会，keyProperties 为空时 processBatch 直接 return"
      ],
      "answer": 2,
      "explain": "Jdbc3KeyGenerator.processBatch:74-78 先看 ms.getKeyProperties()；为空就 return。useGeneratedKeys 只选 KeyGenerator，keyProperty 才决定回填哪个字段。"
    },
    {
      "question": "一个只有 #{}、没有 if 和 ${} 的 select，SqlSource 是哪一种？",
      "options": [
        "RawSqlSource",
        "DynamicSqlSource",
        "SqlSourceBuilder"
      ],
      "answer": 0,
      "explain": "XMLScriptBuilder.parseScriptNode:65-73 在 isDynamic=false 时 new RawSqlSource；只有动态标签或 ${} 才会走 DynamicSqlSource。"
    }
  ]
}
```

## 还差的

- 探针只解析 mapper，没有连数据库执行 insert；`keyProperty` 为空时 `processBatch` 直接 return 是源码结论，没有在真实 JDBC 驱动上验证 `getGeneratedKeys()` 的返回。
- 没有覆盖 `<include>` 与 `${}` 同时出现的动态 SQL；`SqlSource`/`BoundSql` 的展开留到 06 关。
- 没有测 `databaseId` 在多数据库厂商下的实际选择，只按 `XMLStatementBuilder:58-64`、`185-199` 和 `XMLMapperBuilder:129-134` 的源码路径判断。
- 没有测 Spring 的 mapper 扫描顺序、多个 `XMLMapperBuilder` 并发解析时 `incompleteStatements` 的重试时序；本文只讲裸 MyBatis 的单线程解析。

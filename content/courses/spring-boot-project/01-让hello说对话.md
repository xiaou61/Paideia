---
title: 01 · 让 /hello 说出正确的话
order: 1
chapter: 起步
level: beginner
category: spring-boot-project
minutes: 15
status: published
---

## 这一关要做什么

骨架里已经有一个能启动的 Spring Boot 应用：`HelloController` 把 `/hello/{name}` 交给 `Greeter`。
现在 `Greeter` 的输出不对——它说 `hi zoe`，而且没处理名字里的空白与大小写。

让它满足两件事：

1. `GET /hello/zoe` 返回 `hello zoe`（不是 `hi zoe`）；
2. `greet("  ZOE  ")` 返回 `hello zoe`（**去掉首尾空白、转小写**）。

只改 `src/main/java/demo/Greeter.java` 一个文件。

## 怎么交

改完点运行：容器会编译整个项目、起一次 Spring 上下文、打一次真实的 HTTP 请求。
两条测试全绿就算过。失败时输出里会点名是哪一条没过。

```code-challenge
{
  "id": "sp-project-01",
  "kind": "run",
  "title": "改 Greeter：让测试全绿",
  "language": "spring",
  "editablePath": "main/java/demo/Greeter.java",
  "starter": "package demo;\n\npublic class Greeter {\n\n    /**\n     * 让 /hello/{name} 返回 \"hello <name>\"：去掉首尾空白、转小写。\n     */\n    public String greet(String name) {\n        // TODO: 现在是 \"hi \",而且没处理空白与大小写\n        return \"hi \" + name;\n    }\n}\n",
  "files": [
    {
      "path": "test/java/demo/AcceptanceTest.java",
      "content": "package demo;\n\nimport static org.assertj.core.api.Assertions.assertThat;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\nimport org.junit.jupiter.api.Test;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.boot.test.context.SpringBootTest;\nimport org.springframework.boot.test.web.client.TestRestTemplate;\nimport org.springframework.boot.test.web.server.LocalServerPort;\n\n/**\n * 判分用的测试。**不要改这个文件**：它描述的是这一关的要求。\n */\n@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)\nclass AcceptanceTest {\n\n    @LocalServerPort\n    int port;\n\n    @Autowired\n    TestRestTemplate rest;\n\n    @Test\n    void servesHello() {\n        assertThat(rest.getForObject(\"http://127.0.0.1:\" + port + \"/hello/zoe\", String.class))\n                .isEqualTo(\"hello zoe\");\n    }\n\n    @Test\n    void trimsAndLowercases() {\n        assertEquals(\"hello zoe\", new Greeter().greet(\"  ZOE  \"));\n    }\n}\n"
    }
  ],
  "cases": [
    {
      "name": "两条测试全绿"
    }
  ],
  "explain": "判分看 mvn test 的退出码：有失败用例时非 0。先把去空白与转小写做对，再看 HTTP 那一条。"
}
```

## 提示

- `String.trim()` 去首尾空白；`toLowerCase()` 转小写——组合起来是链式调用；
- 别在 `HelloController` 里改：那一层只负责把路径变量传下来，问题在 `Greeter`。

---
title: 02 · 让接口返回 JSON
order: 2
chapter: 起步
level: intermediate
category: spring-boot-project
minutes: 20
status: published
---

## 这一关要做什么

上一关返回的是一段字符串。真实接口基本都返回 JSON——前端要拿到**字段**，而不是自己切字符串。

把 `/api/greeting` 改成返回 JSON：

```text
GET /api/greeting?name=%20Zoe%20
→ 200
{"message":"hello zoe","letters":9}
```

三个点都要对：

1. `message` 是 `hello zoe`（去首尾空白、转小写——starter 里已经做了，别删）；
2. `letters` 是 `message` 的字符数（`hello zoe` 是 9：5 个字母 + 1 个空格 + 3 个字母）；
3. 响应的 `Content-Type` 是 `application/json`（返回一个对象，Spring 会自动做这件事）。

只改 `src/main/java/demo/GreetingApi.java` 一个文件。

## 怎么交

改完点运行：容器会编译整个项目、起一次 Spring 上下文、**真的发一次 HTTP 请求并解析 JSON**。
两条测试全绿就算过。

```sketch
{
  "title": "返回对象，Spring 负责变成 JSON",
  "rows": [
    [
      { "id": "ctrl", "title": "控制器返回对象", "sub": "Greeting 记录", "tone": "blue" },
      { "id": "jackson", "title": "Jackson 序列化", "sub": "字段名 = 组件名", "tone": "gray" },
      { "id": "json", "title": "响应体是 JSON", "sub": "message / letters", "tone": "green" }
    ],
    [
      { "id": "string", "title": "返回 String", "sub": "Content-Type 是 text/plain", "tone": "gray" }
    ]
  ],
  "arrows": [
    { "from": "ctrl", "to": "jackson" },
    { "from": "jackson", "to": "json" }
  ]
}
```

```code-challenge
{
  "id": "sp-project-02",
  "kind": "run",
  "title": "把 /api/greeting 改成返回 JSON",
  "language": "spring",
  "editablePath": "main/java/demo/GreetingApi.java",
  "starter": "package demo;\n\nimport org.springframework.web.bind.annotation.GetMapping;\nimport org.springframework.web.bind.annotation.RequestParam;\nimport org.springframework.web.bind.annotation.RestController;\n\n@RestController\npublic class GreetingApi {\n\n    private final Greeter greeter = new Greeter();\n\n    /**\n     * 现在是纯文本：Content-Type 是 text/plain，也没有 letters 字段。\n     * 改成返回一个带 message 与 letters 的对象。\n     */\n    @GetMapping(\"/api/greeting\")\n    public String greeting(@RequestParam String name) {\n        return greeter.greet(name.trim().toLowerCase());\n    }\n}\n",
  "files": [
    {
      "path": "test/java/demo/AcceptanceTest.java",
      "content": "package demo;\n\nimport java.net.URI;\nimport static org.assertj.core.api.Assertions.assertThat;\n\nimport java.util.Map;\nimport org.junit.jupiter.api.Test;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.boot.test.context.SpringBootTest;\nimport org.springframework.boot.test.web.client.TestRestTemplate;\nimport org.springframework.boot.test.web.server.LocalServerPort;\nimport org.springframework.http.HttpStatus;\nimport org.springframework.http.MediaType;\nimport org.springframework.http.ResponseEntity;\n\n/**\n * 判分用的测试。**不要改这个文件**：它描述的是这一关的要求。\n */\n@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)\nclass AcceptanceTest {\n\n    @LocalServerPort\n    int port;\n\n    @Autowired\n    TestRestTemplate rest;\n\n    String url(String query) {\n        return \"http://127.0.0.1:\" + port + \"/api/greeting\" + query;\n    }\n\n    @Test\n    void returnsJsonObject() {\n        ResponseEntity<Map> response = rest.getForEntity(URI.create(url(\"?name=%20Zoe%20\")), Map.class);\n\n        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);\n        assertThat(response.getHeaders().getContentType()).isNotNull();\n        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();\n        assertThat(response.getBody()).isNotNull();\n        assertThat(response.getBody().get(\"message\")).isEqualTo(\"hello zoe\");\n        assertThat(response.getBody().get(\"letters\")).isEqualTo(9);\n    }\n\n    @Test\n    void countsLettersOfMessage() {\n        ResponseEntity<Map> response = rest.getForEntity(URI.create(url(\"?name=X\")), Map.class);\n\n        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);\n        assertThat(response.getBody().get(\"message\")).isEqualTo(\"hello x\");\n        assertThat(response.getBody().get(\"letters\")).isEqualTo(7);\n    }\n}\n"
    }
  ],
  "cases": [
    {
      "name": "两条测试全绿",
      "expected": "两条测试全绿：输出里能看到「Tests run: 2, Failures: 0」"
    }
  ],
  "explain": "返回 String 时 Content-Type 是 text/plain，测试读出来的 Map 是空的；换成对象（record 就行）Spring 会用 Jackson 序列化成 JSON，字段名就是 record 的组件名。"
}
```

## 提示

- `record Greeting(String message, int letters) {}` 写在控制器里就行——它只是这个接口的返回值，不需要单独一个文件；
- `letters` 是**消息**的字符数，不是名字的：`"hello zoe".length()`；
- 别动 `Greeter`：这一关考的是"接口的返回值形状"，不是字符串处理。

<details>
<summary>参考实现（先自己写，再对答案）</summary>

```java
package demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingApi {

    private final Greeter greeter = new Greeter();

    /** record 的组件名就是 JSON 的字段名。 */
    public record Greeting(String message, int letters) {
    }

    @GetMapping("/api/greeting")
    public Greeting greeting(@RequestParam String name) {
        String message = greeter.greet(name.trim().toLowerCase());
        return new Greeting(message, message.length());
    }
}
```

</details>

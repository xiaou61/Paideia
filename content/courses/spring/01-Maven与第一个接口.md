---
title: 01 · Maven、目录结构与第一个接口
order: 1
chapter: 起步
level: beginner
category: spring-from-zero
minutes: 28
status: published
---

# 01 · Maven、目录结构与第一个接口

## 这一课你要会

- 说清一个 Spring Boot 项目的目录里每个位置"该放什么"；
- 读懂 `pom.xml` 里最少要写哪三样东西；
- 知道"接口返回 200"在网络上到底是什么，以及框架帮你省掉了哪一段；
- 从空目录起一个能被 `curl` 访问到的服务。

## 为什么

教程通常从"打开 start.spring.io，下载，运行"开始。这能跑，但跳过了最容易出问题的一段：**你并不知道那些自动生成的文件各自在干什么**。

结果就是第一次遇到"启动就退出""404 但不是没写接口""依赖加上了却编译不过"时，只能回去重下压缩包。

这一课只做一件事：把"空目录 → 能响应请求"这条路拆成看得见的几步。你会先用手写代码打印出一段真实的 HTTP 响应，再让框架替你做同一件事。

## 讲解

### Maven 只需要三样东西

一个 Maven 项目的身份由三个坐标决定：`groupId`（谁的组织）、`artifactId`（这个项目叫什么）、`version`（第几版）。Spring Boot 项目在此基础上多一个父工程，它统一管理所有依赖的版本：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.5</version>
</parent>

<groupId>com.example</groupId>
<artifactId>demo</artifactId>
<version>0.0.1-SNAPSHOT</version>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
</dependencies>
```

三点要注意：

- **父工程负责版本**，所以依赖里不写 `<version>`。你写了个版本反而容易和其它依赖打架。
- **Spring Boot 4 把这个 starter 改了名**：`spring-boot-starter-web` → `spring-boot-starter-webmvc`。用 Boot 4 而照旧写 `web`，会得到一个"依赖解析不到"的错误。
- `-SNAPSHOT` 表示"还在开发中"。自己的项目无所谓，给别人用就别带。

### 目录结构是按约定来的

```
demo/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/demo/
    │   │   ├── DemoApplication.java     ← 启动类，只放一个 main
    │   │   └── web/HelloController.java ← 接口
    │   └── resources/
    │       └── application.yml          ← 配置
    └── test/java/com/example/demo/      ← 测试，与 main 同包结构
```

Maven 不认别的布局：`src/main/java` 下的 `.java` 会被编译，`src/main/resources` 下的文件会被打进 classpath，`src/test/java` 只在测试时编译。放错位置的表现是"代码明明写了，编译时找不到"。

**包名要与目录一致**。`package com.example.demo.web;` 的文件必须放在 `com/example/demo/web/` 下，否则编译能过但 Spring 扫描不到——这是新手最常见的"接口 404 但代码没错"。

### 一个 200 到底是什么

去掉框架，一次 HTTP 请求在网络上就是**一段文本**。服务端读到请求行（`GET /hello HTTP/1.1`），拼一段响应文本写回去：

```text
HTTP/1.1 200 OK
Content-Length: 5

hello
```

`200` 是状态码，`OK` 是给人看的短语，真正决定客户端行为的是状态码。响应体是 `hello`，长度写在 `Content-Length` 里。

下面这段代码就是一个"服务器"的全部逻辑——它不监听端口，但处理请求的部分与真服务器一模一样：

<!-- run -->
```java
public class Main {

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        String reason() {
            switch (code) {
                case 200: return "OK";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                default: return "Unknown";
            }
        }
    }

    static Response handle(String method, String path) {
        if (path.equals("/hello")) {
            if (!method.equals("GET")) {
                return new Response(405, "method not allowed");
            }
            return new Response(200, "hello");
        }
        return new Response(404, "not found");
    }

    static void call(String method, String path) {
        Response response = handle(method, path);
        System.out.println(method + " " + path + " -> " + response.code + " " + response.reason()
                + " | body=" + response.body + " | length=" + response.body.length());
    }

    public static void main(String[] args) {
        call("GET", "/hello");
        call("POST", "/hello");
        call("GET", "/nope");
    }
}
```
```text
GET /hello -> 200 OK | body=hello | length=5
POST /hello -> 405 Method Not Allowed | body=method not allowed | length=18
GET /nope -> 404 Not Found | body=not found | length=9
```

三个分支对应三种结果：路径对且方法对 → 200；路径对但方法不对 → 405（不是 404，这个区分很重要）；路径不对 → 404。

### 框架替你做的，就是这三件事

在 Spring Boot 里，同样的三段逻辑写成：

```java
@RestController
@RequestMapping("/hello")
public class HelloController {

    @GetMapping
    public String hello() {
        return "hello";
    }
}
```

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

对照一下就能看出框架省掉了什么：

| 手写版 | Spring 版 | 框架做了什么 |
| --- | --- | --- |
| `handle()` 里的 `path.equals` 判断 | `@RequestMapping("/hello")` | 启动时把路径与方法的对应关系建成一张表 |
| `method.equals("GET")` | `@GetMapping` | 同上，表里多存一个"方法" |
| `return "hello"` 后拼响应 | `return "hello"` | 有 `@RestController` 就自动拼响应、算长度、写状态码 |
| 404 / 405 判断 | 不用写 | 表里没匹配上，框架自己给 |

**没有任何魔法**。它做的事和你刚才写的那 30 行是同一件，只是那张表是它替你建的，而且建得比 `switch` 更全（路径变量、通配、优先级）。

### `@SpringBootApplication` 是三个注解合起来

```java
@SpringBootApplication   // 等价于下面三个
// @SpringBootConfiguration   —— 这个类是配置类
// @EnableAutoConfiguration   —— 打开自动配置（第 9 课细讲）
// @ComponentScan             —— 从当前包往下扫描所有 @Component
```

第三个别名最关键：**扫描是从启动类所在的包往下扫**。所以启动类必须在最外层包里（`com.example.demo`），业务代码放它的子包（`com.example.demo.web`）。启动类放在 `com.example.demo.web` 里，父包下的东西就全都扫不到。

## 动手

把"处理一个请求"的步骤排成正确的顺序：

```code-challenge
{
  "id": "spring-01-order",
  "kind": "order",
  "title": "一次请求在服务端走过的六步",
  "lines": [
    "从 socket 读到请求行：GET /hello HTTP/1.1",
    "解析出 method = GET、path = /hello",
    "在路由表里查 /hello 对应的处理方法",
    "调用处理方法的代码，拿到返回值 hello",
    "按调用的方法类型与返回值类型决定用哪个转换器（这里是纯文本）",
    "写回状态行与响应体：HTTP/1.1 200 OK + hello"
  ],
  "answer": [0, 1, 2, 3, 4, 5],
  "explain": "容易排错的是第 5 步：返回值的类型（String / 对象 / ResponseEntity）决定用哪个 MessageConverter，这也是\"为什么我的接口返回的不是 JSON\"的答案。"
}
```

**提示**：这一步之所以经常被排错，是因为它在代码里看不见——框架替你做了。

## 检查一下

```quiz
{
  "id": "spring-01-quiz",
  "title": "项目结构与第一个接口三问",
  "questions": [
    {
      "question": "启动类放在 com.example.demo.web 包下，会发生什么？",
      "options": ["没有影响", "父包 com.example.demo 下的组件扫描不到", "编译失败"],
      "answer": 1,
      "explain": "@ComponentScan 默认从启动类所在包往下扫。放在子包里，兄弟包与父包的东西都扫不到。"
    },
    {
      "question": "路径存在但用了 POST 请求一个只写了 @GetMapping 的接口，正确状态码是？",
      "options": ["200", "404", "405"],
      "answer": 2,
      "explain": "405 Method Not Allowed 的含义是\"这个路径上有接口，但它不接受这个方法\"。404 是路径根本不存在，两者要分清。"
    },
    {
      "question": "依赖里不写 <version> 还能解析到，是因为？",
      "options": ["Maven 会自动挑最新版", "父工程 spring-boot-starter-parent 统一管理了版本", "本地仓库里只有一个版本"],
      "answer": 1,
      "explain": "父工程有一份 dependencyManagement，所有由它管理的依赖都不用自己写版本。自己写版本反而容易与其它依赖冲突。"
    }
  ]
}
```

## 常见误区

### 误区：包名写对了就行，目录随便放

**你以为**：`package com.example.demo.web;` 写了，文件放在哪都行。
**其实**：Maven 的编译单元是由**目录**决定的——`src/main/java` 下所有 `.java` 都编译，但包声明与目录不一致时 javac 会在某些构建工具下报错，即使过了编译，Spring 的扫描也是**按包路径**找的。
**怎么验证**：把 `HelloController.java` 挪到 `src/main/java/other/` 但保留原 `package` 声明，编译能过，运行时接口 404。

### 误区：404 一定是路由写错了

**你以为**：接口 404，就是 `@GetMapping` 的路径写错了。
**其实**：404 有三种常见来源——路径确实不对、启动类扫描不到你的包、请求被前置的过滤器拦掉（比如安全框架把未登录请求转成 404 而不是 401）。先看启动日志里有没有你的 Controller 被注册，再看服务的上下文路径（`server.servlet.context-path`）有没有被改过。
**怎么验证**：启动时加 `--debug`，或直接把 `server.servlet.context-path` 临时去掉，看 404 是否变成 200。

### 误区：先下载模板最省事

**你以为**：模板项目一定比手写全。
**其实**：模板生成的 `mvnw`、`.gitignore`、`HELP.md` 你多数时候不需要，而真正决定行为的三样东西（父工程版本、starter 名字、启动类包位置）它并不会提醒你。手写一次之后，这三样你会记得比看十遍模板都牢。

## 小结

Maven 用三个坐标加一个父工程定身份，目录布局是约定，包名必须与目录一致。一个 HTTP 响应就是一段文本，框架替你做的事是"建路由表 + 拼响应"。启动类必须在最外层包，因为扫描是从它往下走的。

下一课：把"路由表 → 处理逻辑 → 数据访问"拆成三层，并说清为什么不能都塞在 Controller 里。

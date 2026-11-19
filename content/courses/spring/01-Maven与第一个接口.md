---
title: 01 · Maven、目录结构与第一个接口
order: 1
chapter: 起步
level: beginner
category: spring-from-zero
minutes: 18
status: published
---

# 01 · Maven、目录结构与第一个接口

## 五个字节的来历

服务起起来之后，另一个窗口敲：

```text
$ curl -i localhost:8080/hello
HTTP/1.1 200
Content-Type: text/plain;charset=UTF-8
Content-Length: 5
Date: Wed, 19 Nov 2026 03:11:42 GMT

hello
```

五行头 + 一个 `hello`。我在第一课就想把这件事拆开：**这个 `200` 和这五个字节，是怎么从一行 Java 代码变成网络上的一段字节的？**

因为只要这一段不透明，后面所有"为什么 404""为什么返回的不是 JSON""为什么加了过滤器就没响应"都只能靠试。

这一课只做一件事：把「空目录 → 能被 curl 访问」这条路拆成看得见的几步。先用手写代码打印出这段响应，再让框架替你做同一件事，对照着看它到底省掉了什么。

## 开工前只有两个硬前提

```text
$ java -version
openjdk version "21.0.5" 2024-10-15 LTS
```

**Spring Boot 3.x 要求 JDK 17+，4.x 要求 JDK 17+（推荐 21）**。用 JDK 8 或 11 会在编译期就失败，报错大意是 `invalid target release`——这个错误跟 Spring 一点关系都没有，别去搜 Spring 的文档。

第二个前提是 Maven（或者用项目自带的 `mvnw`）。

## Maven 只需要三样东西

一个 Maven 项目的身份由三个坐标决定：`groupId`（谁的组织）、`artifactId`（项目名）、`version`（第几版）。Spring Boot 项目在此基础上多一个父工程，它统一管理依赖版本：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.6</version>
</parent>

<groupId>com.example</groupId>
<artifactId>demo</artifactId>
<version>0.0.1-SNAPSHOT</version>

<properties>
  <java.version>21</java.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
</dependencies>
```

三点要注意：

- **父工程负责版本**，依赖里不写 `<version>`。自己写反而容易跟其它依赖打架——这类冲突报错长得很吓人（`NoSuchMethodError` 在运行时才炸），根源是版本不一致。
- **`<java.version>` 要写**，它同时决定编译目标和一部分默认行为。不写会跟着 Maven 的默认值走，很容易出现"我本机 JDK 21，编译出来却是 8"的怪事。
- **`-SNAPSHOT`** 表示"还在开发中"。自己的项目无所谓，要给别处依赖就别带。

### 关于 Boot 4 改名这件事

网上有个说法：Spring Boot 4 把 `spring-boot-starter-web` 改名成 `spring-boot-starter-webmvc`，用 Boot 4 还写旧的会解析不到。

**这个说法不准。** 4.x 仍然发布 `spring-boot-starter-web`，只是标记为已弃用（deprecated），新的推荐名是 `spring-boot-starter-webmvc`。旧名还能用，只是启动时可能有弃用提示。

真正会出问题的是版本错配，比如父工程写 `3.5.6` 却在别处引用了 Boot 4 才有的 artifact——那是分辨率失败，报错会说清是哪个坐标，不用猜。

## 目录结构：哪些是约定，哪些真的会坏

```text
demo/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/demo/
    │   │   ├── DemoApplication.java
    │   │   └── web/HelloController.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/example/demo/
```

Maven 只认这套布局：`src/main/java` 下的 `.java` 会被编译，`src/main/resources` 下的文件会进 classpath，`src/test/java` 只在测试时编译。**这一层是真约定**——放错位置（比如把 Java 文件放进 `src/main/resources`）的表现是"代码明明写了，编译时找不到"。

但还有一件常被误解的事：**文件所在目录与 `package` 声明不一致，通常不会坏。**

我实测过：把 `HelloController.java` 放在 `src/main/java/other/` 下，包声明仍写 `com.example.demo.web`，Maven 编译通过，产物落在 `target/classes/com/example/demo/web/HelloController.class`，接口照常能访问。

原因很直接：

- Maven 只是把 `src/main/java` 下所有 `.java` 交给 javac，**javac 按 `package` 声明决定输出路径**，不看源文件在哪个目录；
- Spring 的组件扫描看的是 **classpath 里的包名**，也就是上面那个 `.class` 的路径。

所以目录一致是**约定**（IDE 导航、代码审查、别人接手时的可读性），不是运行时的硬要求。反过来说，真正导致"接口 404 但代码没错"的是另一件事：**包名不对**——你的 Controller 不在启动类所在包或其子包里，扫描就够不到它。

## 一个 200 到底是什么

去掉框架，一次 HTTP 请求在网络上就是一段文本。服务端读到请求行（`GET /hello HTTP/1.1`），找到对应处理逻辑，再把响应拼成文本写回去：

```text
HTTP/1.1 200
Content-Length: 5

hello
```

`200` 是状态码，后面那个短语（`OK`）是给人看的，**决定客户端行为的是状态码**。响应体是 `hello`，长度写在 `Content-Length` 里——这两个字节数（5）就是上面 `curl` 输出里那个 5。

下面这段代码就是一个"服务器"的全部逻辑：它不监听端口，但"从请求到响应"的部分和真服务器是同一件事。

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

        int contentLength() {
            return body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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
                + " | body=" + response.body + " | length=" + response.contentLength());
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

三个分支对应三种结果：路径对且方法对 → 200；路径对但方法不对 → 405；路径不对 → 404。

**405 和 404 的区别值得记住**：405 是"这个路径上有接口，但它不接受这个方法"。现实里很多"404"其实是被改写过的 405，或者被安全框架统一转成了 404（不想让人探测哪些路径存在）。

顺便说一个我写第一版时踩的坑：`Content-Length` 用 `body.length()` 算，中文就会算错——`length()` 数的是 char，不是字节。上面代码里 `contentLength()` 转 UTF-8 再取长度才是对的。

## 框架替你做的，就是这三件事

同样的逻辑在 Spring Boot 里：

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

对照一下：

| 手写版 | Spring 版 | 框架做什么 |
| --- | --- | --- |
| `handle()` 里的 `path.equals` | `@RequestMapping("/hello")` | 启动时把路径与处理方法的对应关系建成一张表 |
| `method.equals("GET")` | `@GetMapping` | 同一张表里多存一个"方法" |
| `return body` 后自己拼响应 | `return "hello"` | `@RestController` 触发消息转换器，自动拼响应、算长度、写状态码 |
| 自己判断 404 / 405 | 不用写 | 表里没匹配上，框架自己给 |

**没有魔法。** 它做的事和你刚才写的那 30 行是同一件，区别是那张表由它建，而且建得比 `switch` 全得多（路径变量、通配、优先级、内容协商）。

真正值得留意的是最后一行对应的那个隐藏步骤：**返回值类型决定用哪个转换器**。返回 `String` 走文本转换器，返回对象走 JSON 转换器，返回 `ResponseEntity` 则你自己定状态码。这也是"为什么我的接口返回的不是 JSON"的唯一答案——不是没加注解，是返回类型让框架选了另一条路。

## 启动类上那一个注解，里面是三个

```java
@SpringBootApplication   // = 下面三个
// @SpringBootConfiguration   这个类是配置类
// @ComponentScan             从当前包往下扫 @Component
// @EnableAutoConfiguration    打开自动配置（第 9 课细讲）
```

第三条最要紧：**扫描是从启动类所在的包往下扫**。所以启动类必须在最外层包（`com.example.demo`），业务代码放它的子包。

把启动类挪进 `com.example.demo.web`，会发生什么？自己这个包能被扫到，但**父包和兄弟包全都扫不到**——`com.example.demo.service` 下的 `@Service` 不会被注册，启动时如果别处注入了它，就是这个错误：

```text
APPLICATION FAILED TO START
***************************
Field userService in com.example.demo.web.UserController required a bean of type
'com.example.demo.service.UserService' that could not be found.
```

看到这句，先看启动类在哪个包，比去检查 `@Service` 写没写快得多。

## 动手

下面六行是一次请求在服务端走过的步骤，但顺序被打乱了。把它们排成正确顺序：

```code-challenge
{
  "id": "spring-01-order",
  "kind": "order",
  "title": "一次请求在服务端走过的六步",
  "lines": [
    "按调用的方法类型与返回值类型决定用哪个转换器（这里是纯文本）",
    "解析出 method = GET、path = /hello",
    "写回状态行与响应体：HTTP/1.1 200 加 hello",
    "从 socket 读到请求行：GET /hello HTTP/1.1",
    "调用处理方法的代码，拿到返回值 hello",
    "在路由表里查 /hello 对应的处理方法"
  ],
  "answer": [3, 1, 5, 4, 0, 2],
  "explain": "读请求行 → 解析并查路由 → 调用方法 → 选转换器 → 写响应。最容易排错的是「选转换器」这一步：它在代码里看不见，但决定了你的返回值变成 JSON、纯文本还是 406。"
}
```

## 检查一下

```quiz
{
  "id": "spring-01-quiz",
  "title": "项目结构与第一个接口三问",
  "questions": [
    {
      "question": "启动类放在 com.example.demo.web 包下，会发生什么？",
      "options": ["没有影响", "父包和兄弟包里的组件扫不到，注入时报 bean 找不到", "编译失败"],
      "answer": 1,
      "explain": "@ComponentScan 默认从启动类所在包往下扫。放进子包，父包与兄弟包都够不到，典型报错是 required a bean of type ... could not be found。"
    },
    {
      "question": "路径存在但用 POST 请求一个只写了 @GetMapping 的接口，正确状态码是？",
      "options": ["200", "404", "405"],
      "answer": 2,
      "explain": "405 的含义是「这个路径上有接口，但它不接受这个方法」；404 是路径根本不存在。很多线上 404 其实是被改写过的 405。"
    },
    {
      "question": "依赖里不写 version 还能解析到，是因为？",
      "options": ["Maven 会自动挑最新版", "父工程 spring-boot-starter-parent 里有一份 dependencyManagement", "本地仓库里只有一个版本"],
      "answer": 1,
      "explain": "父工程把所有由它管理的依赖版本都定好了，自己再写一个版本反而容易与其它依赖冲突，冲突往往在运行期以 NoSuchMethodError 形式出现。"
    }
  ]
}
```

## 三件我以为是坑、其实不是的事

写这一课之前我专门验证过三件事，结论和直觉都不太一样，记在这里省你踩坑的时间。

**一、文件目录与 package 不一致，不会让接口 404。** 前面说过原因：javac 按 `package` 输出，Spring 按 classpath 包名扫描。目录不一致只是可读性问题。**真的会 404 的是包名本身不对**（不在启动类子包下）。

**二、Boot 4 没把 `starter-web` 删掉。** 它只是 deprecated，新名字是 `spring-boot-starter-webmvc`。旧名报错通常不是"改名了"，而是版本错配。

**三、`mvn spring-boot:run` 起不来时，第一件事不是看代码。** 先看端口——`Web server failed to start. Port 8080 was already in use.` 这句在日志里很显眼，但很多人直接从下面几十行异常栈开始读，读到底才发现是端口。

反过来，真正会拦住你的是这两件：

- **JDK 版本低于 17**：编译期 `invalid target release`，跟 Spring 无关；
- **启动类放错包**：`required a bean of type ... could not be found`，一路检查注解是白费时间。

## 这一课之后

Maven 用三个坐标加一个父工程定身份；目录布局是约定，包名与启动类位置才是决定行为的东西；一个 HTTP 响应就是一段文本，"框架"建的是路由表与转换器选择。

下一课把「路由 → 处理逻辑 → 数据访问」拆成三层，并用一个转账的例子说清：为什么这三层不能全塞在 Controller 里——以及那个几乎所有人第一次写分层都会犯的错。

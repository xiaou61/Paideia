---
title: 多阶段 Dockerfile
category: docker-deploy
order: 1
minutes: 30
level: intermediate
status: published
---

## 场景

一个 Java 应用，目录里有 `app/target/app.jar`。你要写一个 `Dockerfile`，满足：

- **构建阶段**用 Maven 镜像编译打包，阶段命名为 `build`；
- **运行阶段**用 JRE 镜像，只复制构建产物，不带源码与 Maven；
- 进程以**非 root** 用户运行；
- 声明 `EXPOSE 8080`，有 `HEALTHCHECK` 与 `ENTRYPOINT`。

打个比方：多阶段像盖楼用的脚手架，楼盖好就把脚手架拆走，交付的楼里不留钢管和水泥袋。真实机制是：Docker 每个 `FROM` 是一个独立阶段，最终镜像只保留最后一个阶段的文件系统。

```sketch
{
  "title": "多阶段：工具留在构建阶段",
  "rows": [
    [
      { "id": "maven", "title": "构建阶段", "sub": "maven + JDK", "tone": "violet" },
      { "id": "jar", "title": "app.jar", "sub": "编译产物", "tone": "violet" }
    ],
    [
      { "id": "jre", "title": "运行阶段", "sub": "JRE 镜像", "tone": "blue" },
      { "id": "copy", "title": "COPY --from", "sub": "只带产物", "tone": "blue" }
    ],
    [
      { "id": "user", "title": "USER 1000", "sub": "非 root 运行", "tone": "green" },
      { "id": "expose", "title": "EXPOSE 8080", "sub": "声明端口", "tone": "green" },
      { "id": "health", "title": "HEALTHCHECK", "sub": "判断真健康", "tone": "green" },
      { "id": "entry", "title": "ENTRYPOINT", "sub": "启动进程", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "maven", "to": "jar", "label": "打包" },
    { "from": "jar", "to": "copy", "label": "跨阶段复制" },
    { "from": "jre", "to": "copy", "label": "装进运行镜像" },
    { "from": "copy", "to": "user", "label": "再降权" },
    { "from": "user", "to": "entry", "label": "启动" }
  ]
}
```

## 先想清楚

多阶段的收益只有一个：**运行镜像里没有构建工具**。JDK + Maven 镜像通常 500MB 起，JRE 镜像一百多 MB。少的还不只是体积，攻击面也一起小了：编译器、shell 工具、包管理器都不在运行镜像里。

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER 1000
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

两个细节：

- `COPY --from=build` 是关键——没有它，第二阶段拿不到产物；
- `USER 1000` 用数字而不是用户名，避免基础镜像里没有对应用户。非 root 的代价是：监听 1024 以下端口、写某些目录会受限，所以应用要选高位端口、日志写标准输出。

## 动手

下面的模板里有 `TODO`。补全后运行，判定会检查结构（本环境不真的构建镜像）。

```code-challenge
{
 "id": "docker-1-shell",
 "kind": "shell",
 "title": "写一个多阶段 Dockerfile",
 "seed": [
  {
   "path": "pom.xml",
   "content": "<project><modelVersion>4.0.0</modelVersion></project>\n"
  },
  {
   "path": "src/main/java/App.java",
   "content": "public class App { public static void main(String[] a) {} }\n"
  },
  {
   "path": "target/app.jar",
   "content": "placeholder jar\n"
  }
 ],
 "starter": "cat > Dockerfile <<'EOF'\n# 构建阶段：maven 镜像，阶段命名 build\nFROM <maven-image> AS build\nWORKDIR /src\nCOPY . .\nRUN mvn -q -DskipTests package\n\n# 运行阶段：JRE 镜像，只带产物\nFROM <jre-image>\nWORKDIR /app\nCOPY <从 build 复制产物> app.jar\nUSER <uid>\nEXPOSE 8080\nHEALTHCHECK <check-command>\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\nEOF\n",
 "judge": "set -e\n[ -f Dockerfile ] || { echo \"Dockerfile 没有生成\"; exit 1; }\nfroms=$(grep -c '^FROM ' Dockerfile || true)\n[ \"$froms\" -ge 2 ] || { echo \"FROM 只有 $froms 个：需要构建阶段 + 运行阶段\"; exit 1; }\ngrep -qiE '^FROM .+ AS +[A-Za-z0-9_-]+' Dockerfile || { echo \"构建阶段需要命名，例如 FROM maven:... AS build\"; exit 1; }\ngrep -qiE '^FROM .*jre' Dockerfile || { echo \"运行阶段请用带 jre 的基础镜像（不要带完整 JDK）\"; exit 1; }\ngrep -qiE '^COPY --from=[A-Za-z0-9_-]+' Dockerfile || { echo \"运行阶段需要 COPY --from=build ... 来拿产物\"; exit 1; }\ngrep -qiE '^USER +[0-9]+' Dockerfile || { echo \"缺少非 root 的 USER（建议数字 UID，如 1000）\"; exit 1; }\nif grep -qiE '^USER +root *$' Dockerfile; then echo \"USER 不能是 root\"; exit 1; fi\ngrep -qiE '^EXPOSE +8080' Dockerfile || { echo \"需要 EXPOSE 8080\"; exit 1; }\ngrep -qiE '^HEALTHCHECK' Dockerfile || { echo \"缺少 HEALTHCHECK（服务进程活着不等于健康）\"; exit 1; }\ngrep -qiE '^ENTRYPOINT' Dockerfile || { echo \"缺少 ENTRYPOINT\"; exit 1; }",
 "explain": "构建阶段用 maven 镜像并命名（AS build），运行阶段用 jre 镜像；`COPY --from=build /src/target/app.jar app.jar` 把产物带过去；`USER 1000` 让进程不是 root；HEALTHCHECK 让编排系统能判断应用层健康。"
}
```

## 常见误区

### 误区：一个阶段就够，最后删掉 Maven 目录

**你以为**：`rm -rf ~/.m2` 能瘦身。
**其实**：删除只发生在**这一层**，前面的层里仍然有；镜像总大小不会变小。多阶段是唯一能把构建工具彻底留在构建阶段的办法。
**怎么验证**：单阶段镜像里 `docker history` 看层大小；多阶段运行镜像里 `ls /root/.m2` 不存在。

### 误区：`USER` 写了就行

**你以为**：加一行 `USER app` 就安全了。
**其实**：基础镜像里必须真的存在这个用户；否则容器起不来。用数字 UID（如 1000）最稳，或者先用 `RUN useradd` 创建。
**怎么验证**：`docker run --rm 镜像 id` 看实际用户；起不来看 `docker logs`。

### 误区：`HEALTHCHECK` 可有可无

**你以为**：进程活着就是健康。
**其实**：Java 服务可以在"正在启动"或"线程卡死"时保持进程存在。健康检查决定编排系统要不要重启/摘流量。
**怎么验证**：`docker ps` 的 STATUS 里有没有 `(healthy)`。

## 带走一句

**构建阶段装工具、运行阶段只放产物**——多阶段的边界，就是镜像的最小攻击面。

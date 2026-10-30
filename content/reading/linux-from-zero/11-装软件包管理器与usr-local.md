---
title: 装软件：包管理器与 /usr/local
category: linux-from-zero
order: 11
minutes: 20
level: beginner
status: published
---

## 这一关在解决什么

Linux 上装东西有五条路：包管理器、官方安装脚本、下载压缩包、自己编译、用容器。新手最常见的两种事故是：**同一个软件装了两份，跑起来的是旧的那份**；**`sudo pip install` 把系统 Python 搞坏了**。

```sketch
{
  "title": "装了两份，跑起来的往往是旧的那份",
  "rows": [
    [
      { "id": "pkg", "title": "包管理器装的", "sub": "/usr/bin，系统管", "tone": "blue" },
      { "id": "local", "title": "/usr/local 或自编译", "sub": "PATH 顺序不同", "tone": "violet" },
      { "id": "which", "title": "which -a 看全部", "sub": "排在前面的生效", "tone": "green" }
    ]
  ],
  "arrows": [
    { "from": "pkg", "to": "local" },
    { "from": "local", "to": "which" }
  ]
}
```

这一关讲清楚五条路各自的边界，以及"装完了为什么版本没变"该怎么查。

## 为什么要有包管理器

因为一个软件不是"一个文件"。装 Nginx 要装二进制、配置、systemd 单元、依赖库、日志轮转规则，还要登记进系统的数据库，以后才能干净地升级和卸载。

包管理器解决的就是**记录、依赖、卸载**这三件事。它装的东西知道自己是"系统包"，能被 `dpkg -l` / `dnf list` 查到，能升级，能完整删除。

代价是**版本受发行版控制**：Ubuntu 24.04 的仓库里是什么版本，你就装到那个版本，除非换成第三方源或别的安装方式。

## 讲解

### 两条主线：`apt` 与 `dnf`

Debian/Ubuntu 系用 `apt`（底层 `dpkg`），RHEL/CentOS/Fedora 系用 `dnf`（底层 `rpm`）。命令几乎一一对应：

| 要做的事 | Debian/Ubuntu | RHEL/Fedora |
| --- | --- | --- |
| 刷新仓库索引 | `apt update` | `dnf check-update` |
| 装 | `apt install nginx` | `dnf install nginx` |
| 卸（保留配置） | `apt remove nginx` | `dnf remove nginx` |
| 卸（连配置一起） | `apt purge nginx` | `dnf remove nginx && rm -rf /etc/nginx` |
| 搜索 | `apt search 关键词` | `dnf search 关键词` |
| 看包信息 | `apt show nginx` | `dnf info nginx` |
| 看哪个包提供这个文件 | `dpkg -S /usr/sbin/nginx` | `rpm -qf /usr/sbin/nginx` |
| 列出已装 | `apt list --installed` / `dpkg -l` | `dnf list installed` |
| 清理无用依赖 | `apt autoremove` | `dnf autoremove` |
| 查可用的版本 | `apt policy nginx` | `dnf --showduplicates list nginx` |

**`apt update` 和 `apt upgrade` 是两件完全不同的事**，这是新手第一大混淆：

- `apt update`：只刷新"仓库里有哪些包、什么版本"的索引（相当于更新购物目录），**不装任何东西**。
- `apt upgrade`：真正升级已装的包。

装之前跑一次 `update` 是必须的，否则你按旧索引装，可能直接报"版本找不到"。而 `upgrade` 在服务器上要谨慎——它会连内核一起升，可能要求重启。

### 第三方源：它能装新版本，也能搞坏系统

发行版仓库的版本旧，所以常见软件都提供自己的源（Docker、Nginx 官方、NodeSource 等）。加源的过程永远是三步：

```text
$ curl -fsSL https://example.com/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/example.gpg
$ echo "deb [signed-by=/usr/share/keyrings/example.gpg] https://example.com/apt stable main" \
    | sudo tee /etc/apt/sources.list.d/example.list
$ sudo apt update && sudo apt install example
```

三步各管一件事：

- **密钥**：让 apt 能验证"这个包确实来自官方"，防止镜像站或链路被篡改。
- **源地址**：写在 `/etc/apt/sources.list.d/` 下的独立文件里，**不要直接改 `/etc/apt/sources.list`**——独立文件好删好排查。
- **刷新索引**：加完必须 `update`，否则新源还没进索引。

风险也在这里：第三方源和官方源装着同一个软件时会**互相覆盖**，而且第三方源的包常常不跟随系统升级。装之前想一下：这个软件是不是真需要比系统更新的版本。

### 那些不走包管理器的安装方式

| 方式 | 装到哪 | 升级靠什么 | 什么时候用 |
| --- | --- | --- | --- |
| 包管理器 | `/usr/bin`、`/etc`、`/lib/systemd/system` | `apt upgrade` | **默认选这个** |
| 官方二进制包（tar.gz） | 你自己选，通常 `/usr/local/xxx` 或 `/opt/xxx` | 手动重下 | 仓库版本太旧，但又不想编译 |
| 自己编译 | `./configure --prefix=/usr/local/xxx` → `make install` | 手动 | 需要改编译参数（比如换 OpenSSL 版本） |
| 官方安装脚本（`curl … \| sh`） | 各家自定 | 各家自定 | 只在信任来源时用 |
| 容器 | 不进宿主 | 换镜像 | **最干净的隔离**（Docker 线整条讲这个） |

**装到 `/opt` 和 `/usr/local` 的区别是个约定**，不是内核规则：

- `/opt/<产品名>/`：一整套独立产品放一个目录（`/opt/firecrawl`、`/opt/paideia`）。删掉整个目录就等于卸载。
- `/usr/local/`：手工装的"本地版"系统软件（`/usr/local/bin`、`/usr/local/lib`）。它天然在 PATH 里靠前的位置（第 10 关），所以会**优先于** `/usr/bin` 里的同名命令。

### "我装了新版，为什么还是旧版"

这大概是装软件环节最常见的求助。原因只有一个：**跑的是另一个可执行文件**。三步定位：

```text
$ type -a java          # 所有同名命令，按 PATH 顺序
java is /usr/bin/java
java is /usr/local/bin/java
$ readlink -f $(command -v java)   # 现在这个到底是哪个文件
$ /usr/local/bin/java -version     # 直接跑你想用的那个，确认版本对不对
```

如果新版本在 PATH 里但排在后面，要么调整 PATH 顺序，要么用 `update-alternatives` 正式接管多版本：

```text
$ sudo update-alternatives --install /usr/bin/java java /opt/jdk21/bin/java 2100
$ sudo update-alternatives --config java
```

`--config` 会给你一个菜单选默认版本，这是 Debian 系处理"多版本共存"的标准做法。

### 卸载要卸干净

卸载一个软件，除了 `apt remove`，还要想三件事：

```text
$ sudo apt remove nginx           # 1. 卸包（配置留着）
$ sudo apt purge nginx            # 2. 连配置一起删
$ sudo apt autoremove             # 3. 清掉不再需要的依赖
$ ls /etc/nginx /var/log/nginx    # 4. 确认残留目录
```

`purge` 之后 `/etc/nginx`、`/var/log/nginx` 有时还在——包管理器只删它记录过的文件，**运行时产生的数据（日志、数据库文件、用户上传）它不碰**，这是有意的：卸载软件不该删你的数据。要清就自己 `rm -rf`，删前先确认里面没有要留的东西。

### 不要在系统 Python 上 `pip install`

`pip install`（不带虚拟环境）会把包装进系统 Python 的 `site-packages`，和包管理器装的东西混在一起。后果：

- 一个 `pip install` 升级了某个库，另一个依赖旧版本的系统工具就崩了；
- 无法用 `apt` 干净卸载，也说不清哪个文件是谁装的；
- 有些发行版（Ubuntu 23.04+）会直接报 `externally-managed-environment` 拒绝安装。

正确做法是**每个项目一个虚拟环境**：

```text
$ python3 -m venv .venv
$ source .venv/bin/activate
$ pip install -r requirements.txt
```

对服务来说，用容器（Docker 线）或至少把程序装进 `/opt/<服务>/.venv`，别碰系统 Python。

### 别用 `curl | sh` 装生产环境

```text
$ curl -fsSL https://example.com/install.sh | sudo sh
```

这条命令做了三件你没法审的事：下载、以 root 执行、执行的内容每次都不同（没有版本固定）。它**可能**没问题，但它违反了"可复现、可审计"这两条运维底线。

如果你确实要用，至少做两步：先 `curl -O` 下载、`less` 看一遍、再 `sh ./install.sh`。生产环境首选包管理器或官方二进制包（带校验和的那种：`sha256sum -c`）。

## 动手

```code-challenge
{"id":"linux-11-apt","kind":"fill","title":"装一个包的正确起手式","language":"bash",
 "template":"# 1. 先刷新仓库索引\nsudo apt {{0}}\n# 2. 再安装\nsudo apt {{1}} nginx\n# 3. 确认版本与安装状态\ndpkg -l | grep nginx",
 "options":[["update","upgrade","install"],["install","add","get"]],"answers":[0,0],
 "explain":"`apt update` 只刷新索引、不装东西；`apt upgrade` 是升级已装的全部包，服务器上要谨慎。装用 `install`。"}
```

## 检查一下

```quiz
{"id":"linux-11-quiz","title":"装软件三问","questions":[
 {"question":"`apt update` 做了什么？","options":["刷新仓库索引，不安装任何包","升级所有已装包","更新系统内核"],"answer":0,"explain":"update 只同步「仓库里有哪些包、什么版本」的目录；真正升级是 upgrade，安装是 install。"},
 {"question":"装了两份 Java，`java -version` 显示旧的那份，第一步该做什么？","options":["`type -a java` 看按 PATH 顺序执行的是哪个","直接删掉旧版本","重装系统包"],"answer":0,"explain":"先确认实际执行的文件路径，再决定调整 PATH 顺序还是用 update-alternatives 接管。"},
 {"question":"为什么不建议 `sudo pip install`？","options":["会污染系统 Python，和包管理器互相覆盖且无法干净卸载","pip 不能装到系统目录","会让系统变慢"],"answer":0,"explain":"系统 Python 里混进 pip 装的包后，依赖关系失控，卸载也说不清。用 venv 或容器隔离。"}
]}
```

## 常见误区

### 误区：手动装的 `/usr/local` 版本会自动生效

**你以为**：装到 `/usr/local` 了，系统"自然"会用新的。
**其实**：只有 PATH 里 `/usr/local/bin` 排在 `/usr/bin` 前面时才生效——多数发行版确实如此，但 systemd 服务里 PATH 可能不同（第 10 关），`sudo` 下也会换成 `secure_path`。所以服务里跑的还是旧的。
**怎么验证**：`type -a 命令名` 看顺序；`sudo sh -c 'echo $PATH'` 看 root 的 PATH；服务则要看 unit 文件里有没有写 `Environment=`。

### 误区：`apt remove` 之后软件就彻底没了

**你以为**：卸载等于清空。
**其实**：`remove` 保留配置文件；`purge` 才删配置。而**运行时数据（日志、数据库、上传文件、系统用户）从来不由包管理器删**。所以"重装一遍就干净了"通常不成立，重装后读到的还是旧配置。
**怎么验证**：`apt remove nginx` 后 `ls /etc/nginx`——还在；`dpkg -l | grep nginx` 显示状态是 `rc`（已删但配置残留）。

### 误区：加第三方源没有副作用

**你以为**：多一个源就是"多一个下载地址"。
**其实**：源会参与**同名包**的解析，同一软件在官方源和第三方源都有时，版本高的那个胜出，可能把你系统里的一批依赖一起换掉（比如 OpenSSL）。这也是容器流行起来的一个原因：装依赖的破坏半径被隔离在镜像里。
**怎么验证**：`apt policy 包名` 会列出所有提供该包的源和各自版本，加源前后对比一次就明白了。

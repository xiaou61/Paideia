---
title: 装软件：包管理器与 /usr/local
category: linux-from-zero
order: 11
minutes: 18
level: beginner
status: published
---

## 敲 `python`，出来的是 3.9.13

下面这段是我在这台 Windows 机器上刚跑的，**不是 Linux**：Git for Windows 自带的 MSYS bash（同机的 `git --version` 是 `2.45.1.windows.1`，下面还会用到它）。

```text
$ python --version
Python 3.9.13
$ type -a python
python is /c/Users/Lenovo/AppData/Local/Programs/Python/Python39/python
python is /c/Users/Lenovo/AppData/Local/Programs/Python/Python311/python
python is /c/Users/Lenovo/AppData/Local/Microsoft/WindowsApps/python
$ /c/Users/Lenovo/AppData/Local/Programs/Python/Python311/python --version
Python 3.11.5
```

三份都在 PATH 里，敲 `python` 拿到的是排第一的那份。同一台机器换 PowerShell 问同一件事，答案就变了：

```text
PS> Get-Command python -All | Select-Object -First 3 Source
C:\Users\Lenovo\PycharmProjects\pythonProject\venv\Scripts\python.bat
C:\Users\Lenovo\AppData\Local\Programs\Python\Python39\python.exe
C:\Users\Lenovo\AppData\Local\Programs\Python\Python311\python.exe
```

因为 PowerShell 的 PATH 里有一个 Git Bash 没有的 venv 目录，一个 `python.bat` 就把它顶到了最前面。

`git` 更能说明问题：Git Bash 里有两份，PowerShell 里只看得见一份。

```text
$ type -a git
git is /mingw64/bin/git
git is /cmd/git
$ ls -l /mingw64/bin/git.exe /cmd/git.exe
-rwxr-xr-x 2 Lenovo 197121   45032 May  2  2024 /cmd/git.exe
-rwxr-xr-x 4 Lenovo 197121 4003816 May  2  2024 /mingw64/bin/git.exe
$ command -v git
/mingw64/bin/git
```

两份版本号一模一样（都是 `2.45.1.windows.1`），一份 `45032` 字节，一份 `4003816` 字节——差 88 倍，一个是转发的壳，一个是真身。所以「装了两份」第一层意思不是「一新一旧」，而是**同一个名字对应两个文件，选哪个由启动它的那个环境决定**。`command -v` 只回答「按当前 PATH 我会执行哪个」，不回答「机器上有几个」——要后者，用 `type -a`（第 10 关讲过它比 `which` 多认别名）。

Linux 上这套机制一样，只是多出一层：装下去的东西**有没有被记账**。

## Linux 上多的一层：谁给这份文件记了账

包管理器和「解压一个 tar.gz 扔进 /usr/local/bin」的差别，不在装到哪个目录，在装完之后系统里留下什么。

- **包管理器装的**：文件被登记进 dpkg（Debian 系）或 rpm（RHEL 系）的数据库。谁装的、装了哪些文件、依赖谁、哪个是 conffile，都查得到。所以能 `dpkg -S` 反查、能 `apt purge` 干净删、能 `apt upgrade` 批量升级。
- **tar.gz 解压放的**：系统里只有那个文件本身。没有记录，就没有反查、没有卸载清单、没有升级。

仓库里 `deploy/ops/install-ops.sh` 的下载函数把这件事写在代码里了（第 165-175 行）：

```sh
# deploy/ops/install-ops.sh:165-175（节选）
install_release() {
    local repo=$1 version=$2 name=$3 tar=$4
    local target="/usr/local/bin/$name"
    [ -x "$target" ] && { echo "已有 $target"; return; }
    local url="https://github.com/$repo/releases/download/v${version}/${tar}.tar.gz"
    curl -fsSL --retry 3 --retry-all-errors --retry-delay 2 "$url" -o "/tmp/$tar.tgz"
    tar -xzf "/tmp/$tar.tgz" -C /tmp
    install -m 0755 "/tmp/$tar/$name" "$target"
    rm -rf "/tmp/$tar" "/tmp/$tar.tgz"
}
```

函数体把 `target` 固定成 `/usr/local/bin/$name`（不是 `/usr/bin`），而「装过了没」靠 `[ -x "$target" ]`——**看文件在不在**。这是没有数据库可问之后的唯一办法，它也顺带说明了这种装法的边界：文件在就跳过，版本对不对它不管。

| 命令 | 输出告诉你什么 |
| --- | --- |
| `dpkg -S /usr/bin/nginx` | 这个路径属于哪个包。报 `dpkg-query: no path found matching pattern /usr/local/bin/prometheus` 就是没有包管它 |
| `dpkg -l nginx` | 状态两字母：`ii` 已装、`rc` 删了但配置还在；`purge` 完成后条目从状态库里消失，再查就是 `no packages found` |
| `apt policy nginx` | 候选版本，以及每个源给的版本与 pin 优先级 |
| `type -a prometheus` | 按 PATH 顺序的全部同名可执行文件 |
| `systemctl list-unit-files prometheus` | 单元名在不在系统里；包自带的在 `/lib/systemd/system/`，自己写的在 `/etc/systemd/system/` |

（上表是标准形态，未实跑——本机是 Windows，没有 dpkg。）

## 装好了，命令却不在 PATH 里

同一个脚本第 62 行，它在问两遍「装了没」：

```sh
# deploy/ops/install-ops.sh:62
if ! command -v grafana-server >/dev/null 2>&1 && [ ! -x /usr/share/grafana/bin/grafana ]; then
    # 加官方源、apt 装 grafana
fi
```

先问 PATH 里的 `grafana-server`，再问绝对路径 `/usr/share/grafana/bin/grafana`。要问两遍，就说明这个 apt 包的可执行文件不一定在 PATH 里——它落在 `/usr/share/grafana/bin/`，而 `/etc/environment` 那份 PATH 里没有这个目录。所以 Grafana 自己的单元文件也是拿绝对路径启动的：

```ini
# deploy/ops/grafana.service（节选）
ExecStart=/usr/share/grafana/bin/grafana server \
    --homepath=/usr/share/grafana \
    --config=/etc/grafana/grafana.ini
```

（标准形态，未实跑。）

三件事就此分开，别再混着说：

1. **文件在磁盘上**（`ls -l` 有它）——装好了。
2. **命令能敲**（`command -v` 有输出）——它所在目录在 PATH 里。
3. **服务能跑**（`systemctl start` 成功）——unit 里写的是能找到它的路径。

`/usr/local/bin` 排在 `/usr/bin` 前面这件事，在 Debian/Ubuntu 上成立，但它是**发行版给的默认值**，不是规则：`/etc/environment` 里那行是 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`，`sudo` 的 `secure_path` 是同一顺序（`sudo -l` 能看到）。unit 里写了 `Environment=PATH=` 就以写的为准，写死绝对路径的 unit 根本不受影响。想确认某个服务实际拿到什么 PATH，别去猜配置文件，直接读进程自己：

```text
$ systemctl show -p MainPID paideia.service
MainPID=2143
$ sudo tr '\0' '\n' < /proc/2143/environ | grep '^PATH='
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
```

（标准形态，未实跑。这行的具体值随 systemd 版本与发行版不同，只是示意——你要的是「读到的那个值」，不是我这个值。）

## 两份都装上了，重启的却不是真正在跑的那个

脚本第 214-221 行的注释，是这件事最难看见的一层：

```sh
# **包自带的 grafana-server.service 必须停用**（2026-10-27 实测踩过）：apt 装的 grafana 会
# enable 它自己的单元，而本脚本用的是自写的 grafana.service。两个单元抢 127.0.0.1:3000，
# 后启动的那个 bind 失败并进入 `Restart=on-failure` 重启循环（半小时内 66 次），
# 而先启动的那个只是「看着正常」——`systemctl restart grafana-server` 更是完全改不了真正在跑的那个。
if systemctl list-unit-files grafana-server.service >/dev/null 2>&1; then
    systemctl disable --now grafana-server.service 2>/dev/null || true
    systemctl reset-failed grafana-server.service 2>/dev/null || true
fi
```

（66 次、2026-10-27，是脚本注释里记的这台机器上的数，不是我这次跑的。）

关键在**两个单元名字不同**：`grafana-server.service` 和 `grafana.service`。systemd 的覆盖规则只对**同名**单元生效——`/etc/systemd/system/grafana.service` 会遮住 `/lib/systemd/system/grafana.service`；名字不一样就是两个互不相干的单元，两个都能 `enable`、都能启动、都去 bind `127.0.0.1:3000`。先起来的占住端口；后起来的 bind 失败后按自己单元的 `Restart` 策略反复重启（注释里那半小时 66 次就是这个循环留下的）。这时 `systemctl status` 看到的是 active，`systemctl restart` 动的只是你点的那个单元，而真正在服务请求的是另一个进程。

| 症状 | 先看哪 |
| --- | --- |
| `systemctl status grafana` 是 active，但 3000 端口时好时坏 | `ss -ltnp \| grep :3000` 拿 pid，再 `systemctl status <pid>` 看它属于哪个单元 |
| `systemctl restart grafana-server` 之后行为一点没变 | `systemctl cat grafana-server grafana` 看两个单元的 `ExecStart` 是不是同一个二进制、`Environment` 是不是同一份 |
| `systemctl --failed` 里有东西在反复重启 | `journalctl -u <单元> -n 30`，找 `bind: address already in use` |
| 某个路径的文件，不知道谁放的 | `dpkg -S <路径>`；报 `no path found matching pattern` 就是手工放的，去翻装它的脚本 |

（标准形态，未实跑。）

脚本选择停用包自带的那份、保留自己写的，理由是记账归属：包自带的 unit 在 `/lib/systemd/system/` 下，`apt upgrade` 会照包里的版本覆盖它；自己写的在 `/etc/systemd/system/` 下，包管理器不碰。

## 版本：仓库里是什么就装什么

脚本开头把每个组件的版本号写死（`deploy/ops/install-ops.sh:24-31`），注释说得直白：

```sh
# 版本固定：latest 会在某天悄悄变行为，而这里没有任何自动升级机制
PROMETHEUS_VERSION=2.54.1
NODE_EXPORTER_VERSION=1.8.2
MYSQLD_EXPORTER_VERSION=0.15.1
```

包管理器这条路相反：装到什么版本，由**发行版的仓库**决定。`apt policy nginx` 看候选版本和它来自哪个源，`dnf --showduplicates list nginx` 看所有可用版本。Ubuntu 24.04 的仓库里是哪个版本，你就装到那个版本。

要更新的版本，就得加第三方源。脚本里加 Grafana 官方源那四行（第 63-70 行）：

```sh
install -d -m 0755 /etc/apt/keyrings
curl -fsSL --retry 3 --retry-all-errors \
    https://apt.grafana.com/gpg.key -o /etc/apt/keyrings/grafana.asc
echo 'deb [signed-by=/etc/apt/keyrings/grafana.asc] https://apt.grafana.com stable main' \
    > /etc/apt/sources.list.d/grafana.list
apt-get update -qq
```

注意这里**没有 `apt-key add`**。`apt-key` 从 apt 2.4 起标了 deprecated（man page 自己写的），现行写法是把密钥存成文件、源里用 `signed-by=` 指名。`apt-key adv --keyserver` 那种旧写法会把密钥塞进全局 trusted 库、对所有源生效，没法按源收窄——所以这个仓库没有用它。目录约定也有一层：`/usr/share/keyrings/` 是包管理器装的全局密钥环（如 `ubuntu-archive-keyring.gpg`），管理员自己加的放 `/etc/apt/keyrings/`——脚本用的是后者。

`apt update` 和 `apt upgrade` 的区别要说得再准一点，因为流传的版本有一半是错的：

- `apt update` 只刷新索引，不装任何东西。索引过期时 `apt install` 会报 `Unable to locate package` 或下载时 `404 Not Found`。
- `apt upgrade` 升级**已装且有新版本**的包，不装新包、不删包。
- 新内核在 Debian/Ubuntu 上是**一个新包名**（`linux-image-6.x.y-z-generic`），新包 → `apt upgrade` 不动它，输出里那句 `The following packages have been kept back:` 就是它。要装，得 `apt full-upgrade`。
- RHEL 系的 `dnf upgrade` 会装新内核，因为 kernel 是 installonly 包，多版本共存。

「upgrade 会连内核一起升」在 Debian 系和 RHEL 系各是另一半真相，别当通用结论用。想先看它打算干什么，用空跑：`apt -s full-upgrade`，`-s` 不动系统。

（以上回显标准形态，未实跑。）

同一个包名出现在两个源里时，apt 的候选是「pin 优先级最高的那个，平手再比版本号」——`apt policy` 会把两者的优先级和版本一起列出来。所以装第三方源那一下，可能顺手把一批共享依赖换掉，比如 OpenSSL。加源前后各跑一次 `apt policy 包名` 对比，是成本最低的确认方式。

## 卸的时候，包管理器只删它记过账的东西

```text
$ sudo apt remove nginx
$ dpkg -l nginx | tail -1
rc  nginx  1.24.0-2ubuntu7  all  small, powerful, scalable web/proxy server
$ sudo apt purge nginx
$ dpkg -l nginx
dpkg-query: no packages found matching nginx
$ sudo apt autoremove
```

（标准形态，未实跑。）

`rc` 里那个 `c` 就是 conffile 残留：程序和库删了，`/etc/nginx/` 下的配置留着。`purge` 删的是**登记在 conffile 清单里的**那些文件，删完这条记录就没了——所以第三步查不到包，不等于「它没装过」，而是「账已经销了」。运行时才产生的东西不在任何清单里：日志、数据库文件、上传、包创建的系统用户，包管理器一概不碰。这是有意的（卸载不该删你的数据），也意味着「重装一遍就干净了」不成立：重装后它读到的还是你改过的配置。

RHEL 侧的差别别照搬：`dnf remove` 遇到改过的 `%config` 文件不删，改名成 `xxx.rpmsave` 留着；Debian 侧是「留着、状态变 rc」。两边都不帮你删业务数据。

`/opt` 那条路最简单：一个目录一整套产品，`rm -rf /opt/<产品>` 就等于卸载——顺带删掉了它的配置和数据，所以删之前先 `ls` 一眼里面有没有你要留的东西。`/usr/local/bin` 是多个产品混在一起的手工安装，没有目录边界，只能一个文件一个文件数，这也是那个 `[ -x "$target" ]` 存在的原因。

## 跑一遍

上面所有 Linux 回显都是标准形态，我没在 Linux 上跑过。能在这台机器上真跑的是「一个名字两份文件」这条机制：

```text
$ type -a git
git is /mingw64/bin/git
git is /cmd/git
$ command -v git
/mingw64/bin/git
$ readlink -f "$(command -v git)"
/mingw64/bin/git
$ /mingw64/bin/git --version
git version 2.45.1.windows.1
$ /cmd/git --version
git version 2.45.1.windows.1
```

换成 PowerShell 问同一个问题：

```text
PS> (Get-Command git -All).Source
C:\Program Files\Git\cmd\git.exe
```

Git Bash（MSYS）看到两份、PowerShell 看到一份，差别只在 PATH 内容。这跟 Linux 上 `type -a` 与 `command -v` 的分工是同一套：前者回答「有几个」，后者回答「这次用哪个」。你没有 Linux 机器时，这三条命令在任何装了多个同名可执行文件的机器上都能复现。

## 动手

```code-challenge
{"id":"linux-11-owner","kind":"fill","title":"先分清这一份是谁装的、跑的是哪个","language":"bash",
 "template":"# 1. 这个文件归哪个包管？报 no path found 就说明是手工放的\nsudo dpkg -S /usr/local/bin/prometheus\n# 2. 同名命令有几个，按 PATH 顺序哪个先被找到\ntype -a {{0}}\n# 3. 直接跑绝对路径，问二进制自己\n/usr/local/bin/prometheus {{1}}\n# 4. 再看端口上真正在跑的进程是哪个\nsudo ss -ltnp | grep :9090",
 "options":[["prometheus","which prometheus","dpkg -l prometheus"],["-v","--version","version"]],"answers":[0,1],
 "explain":"type -a 按 PATH 顺序列出全部同名可执行文件（第 10 关：它比 which 多认别名）；问版本用双横线的 --version，单横线短选项各家自定、别猜，而且走绝对路径才能绕过 PATH 直接问到那个文件。第 1 行的 dpkg -S 会告诉你 /usr/local/bin 下的文件不归任何包管。"}
```

## 检查一下

```quiz
{"id":"linux-11-quiz","title":"装软件三问","questions":[
  {"question":"`apt install nginx` 之前跑 `apt update`，它做了什么？","options":["把已装的包全部升到最新","同步仓库索引，不安装任何包","安装 nginx 所需的依赖"],"answer":1,"explain":"update 只同步「仓库里有哪些包、什么版本」。升级已装包是 upgrade，装依赖是 install 顺带解析的。"},
  {"question":"`dpkg -S /usr/local/bin/prometheus` 报 `no path found matching pattern`，说明什么？","options":["这个文件被删掉了","这个文件的权限不对","没有任何已安装的包登记了这个路径"],"answer":2,"explain":"dpkg 的数据库里没有这条路径，意味着它不是任何包装的，是手工放进去的（tar.gz 解压或 install 复制）。想删它就得回到装它的脚本，purge 管不到。"},
  {"question":"`systemctl restart grafana-server` 之后 3000 端口的行为没变，第一步看什么？","options":["`ss -ltnp` 找监听 3000 的 pid，再 `systemctl status <pid>` 看它归哪个单元","把 grafana 包重装一遍","再跑一次 `apt update`"],"answer":0,"explain":"名字不同的两个单元会各自启动、各自 bind，先确认端口上那个进程属于谁，再决定停哪个。"}
]}
```

## 还差的

1. **没实跑。** 本机是 Windows，`dpkg`、`apt`、`systemctl` 的输出全按标准形态写，没有一条在 Linux 上验过。唯一真跑的是 Git Bash / PowerShell 那两段。你要落地，先在虚拟机上把 `apt -s full-upgrade` 和 `dpkg -l` 各跑一次对一遍。
2. **发行版差异只覆盖了两侧各一半。** apt 侧的例子来自 Debian/Ubuntu，`.rpmsave`、kernel installonly 这些是按 rpm/dnf 文档写的，没在 RHEL 机器上验。`/usr/local` 靠前那条也只在 Debian/Ubuntu 的默认配置下成立，别的发行版（含容器里的精简镜像）要自己看 `/etc/environment`。
3. **幂等不等于正确。** 脚本用 `[ -x "$target" ]` 判断装过没装过，只能证明文件在，证明不了版本对不对，也没有校验和比对。真要让手工安装可复现，得自己记 `sha256sum`，这一步仓库里没做。
4. **密钥轮换没写。** `signed-by` 解决了「这个源用什么密钥」，没解决「密钥换了怎么办」「过期了怎么告警」。仓库里那套源全靠人工重跑脚本，我还没搞清它什么时候会静默失败。
5. **两个单元抢端口那节，我只解释了症状怎么定位。** 更彻底的解法（同名单元覆盖、`Conflicts=`、socket 激活）属于第 12 关的范围，这篇没展开。

---
title: 文本处理：grep、sed、awk
category: linux-from-zero
order: 7
minutes: 28
level: beginner
status: published
---

## 这一关在解决什么

Linux 上几乎所有东西都是文本：配置、日志、CSV、`/proc` 里的状态。所以"从一堆文本里捞出你要的那几行、改掉某个字段、按列汇总"是最高频的操作，而这三个命令就是干这个的：

- **`grep` 挑行**：哪些行留下来；
- **`sed` 改行**：留下来的行要换成什么；
- **`awk` 切列**：按列看、按列算。

配合第 06 关的管道，你就能一条命令回答"昨天哪个 IP 打我们最多""有多少个 5xx"这类问题。

## 为什么是这三个而不是 Python

因为**这几件事不需要写程序**。一行 `grep -c ERROR app.log` 是 0.02 秒的事，写个 Python 脚本要建文件、读文件、正则、打印，还要维护。判据是：**一次性的、几十 MB 以内的文本查询用三个命令；反复用的、需要逻辑分支的写成脚本。**

## 讲解

### `grep`：按模式挑行

```text
$ grep ERROR app.log              # 含 ERROR 的行
$ grep -i error app.log           # 忽略大小写
$ grep -v DEBUG app.log           # 反选：不含 DEBUG 的行
$ grep -c " 500 " access.log      # 只数行数
$ grep -n ERROR app.log           # 带行号
$ grep -r "datasource" /etc/myapp # 递归搜目录
$ grep -l "TODO" *.java           # 只列出文件名，不列内容
$ grep -A3 -B1 "Exception" app.log  # 命中行后 3 行、前 1 行（-C3 是前后各 3 行）
```

`-A/-B/-C` 是看异常堆栈的关键：报错那一行往往没信息，要看它后面的调用栈。

**基础正则 vs 扩展正则**：`grep` 默认用 BRE（basic），`+ ? | ( )` 这些得转义才能当元字符；`grep -E`（等价 `egrep`）用 ERE，写起来像你熟悉的那些正则：

```text
$ grep -E "ERROR|WARN" app.log        # 或
$ grep -E "^[0-9]{4}-[0-9]{2}-[0-9]{2}" app.log
```

日志里最常用的几个模式：

| 模式 | 含义 |
| --- | --- |
| `^` / `$` | 行首 / 行尾 |
| `.` | 任意一个字符 |
| `*` | 前一项出现 0 次或多次 |
| `+` | 前一项 1 次或多次（要 `-E`） |
| `[0-9]` / `[^0-9]` | 数字 / 非数字 |
| `\b` | 词边界，避免 `err` 匹配到 `error` 的中间 |

**递归搜代码时永远加 `--include`**：

```text
$ grep -rn "TODO" --include="*.java" src/
```

不加的话会把 `.class`、`.jar` 里的二进制内容也扫一遍，输出一堆乱码，还慢。

### `sed`：按规则改行

`sed` 的基本形态是 `sed '地址 命令' 文件`。日常 80% 的用法只有替换：

```text
$ sed 's/old/new/' app.conf          # 每行只换第一个
$ sed 's/old/new/g' app.conf         # 每行全换（g = global）
$ sed -i 's/8080/9090/g' app.yml     # 直接改文件
$ sed -i.bak 's/8080/9090/g' app.yml # 改文件并留一个 .bak 备份
```

**`-i` 没有撤销键**。改配置一律写 `-i.bak`，跑完 `diff app.yml.bak app.yml` 看一眼改了什么，再删备份。

按行号或模式挑行：

```text
$ sed -n '10,20p' app.log        # 只打印 10–20 行（-n 关掉默认输出，p 打印）
$ sed '1,5d' app.log             # 删掉前 5 行
$ sed '/^#/d' app.conf           # 删掉所有注释行
$ sed '/^$/d' app.conf           # 删掉空行
$ sed -n '/ERROR/,/END/p' app.log  # 打印两个标记之间的内容
```

一条实用组合——**去掉配置文件里的注释和空行**，方便看清到底配了什么：

```text
$ sed '/^[[:space:]]*#/d;/^[[:space:]]*$/d' /etc/nginx/nginx.conf
```

替换时的三个细节：

- 分隔符不一定是 `/`，路径里有斜杠时换一个更好读：`sed 's|/old/path|/new/path|g'`。
- `&` 代表"匹配到的整体"：`sed 's/[0-9]*/[&]/'` 给数字加方括号。
- 分组用 `\(\)`（BRE）或 `-E` 下的 `()`，反向引用是 `\1`：`sed -E 's/([a-z]+)@([a-z.]+)/\2:\1/'`。

### `awk`：按列看、按列算

`awk` 天生按"字段"工作：默认以空白分隔，`$1` 是第一列，`$0` 是整行，`NF` 是列数，`NR` 是行号。

```text
$ awk '{print $1}' access.log              # 第一列
$ awk '{print $1, $7}' access.log          # 多列，逗号会输出一个空格
$ awk -F: '{print $1}' /etc/passwd         # 指定分隔符为冒号
$ awk -F, '{print $2}' data.csv            # CSV 用逗号
$ awk '$9 == 500' access.log               # 第 9 列等于 500 的行（整行打印）
$ awk 'NR > 1' data.csv                    # 跳过表头
$ awk 'NF != 5' data.csv                   # 列数不是 5 的行（找坏数据）
```

`awk` 真正的威力在"计数与求和"，用 `BEGIN`/`END` 两个块：

```text
$ awk '{sum += $5} END {print sum}' access.log           # 第 5 列求和
$ awk '{c[$9]++} END {for (k in c) print c[k], k}' access.log  # 按状态码计数
$ awk 'length($0) > 200 {n++} END {print n}' app.log     # 超长行有几条
```

第三行那种"数组 + 计数"是日志统计的通用套路：**用某一列当 key 累加**。

### 一条命令回答一个真问题

假设你有 Nginx 的 access log，格式是标准的 `$remote_addr - - [$time] "METHOD path HTTP/1.1" status bytes`（共 9 列，`$1` 是 IP，`$9` 是状态码）。几个真实需求：

```text
# 1. 请求量前 10 的 IP
awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10

# 2. 各状态码各有多少
awk '{print $9}' access.log | sort | uniq -c | sort -rn

# 3. 5xx 一共有多少条
awk '$9 >= 500' access.log | wc -l

# 4. 总流量（第 10 列是字节数）
awk '{sum += $10} END {printf "%.1f MB\n", sum/1024/1024}' access.log

# 5. 每小时的请求数
awk -F'[][:/ ]+' '{print $3":"$4}' access.log | sort | uniq -c
```

前两条里都有一个容易忘的地方：**`uniq` 只合并相邻的重复行**，所以必须先 `sort`。写成 `awk ... | uniq -c` 会得到一堆断断续续的重复项，看起来像统计错了——这大概是这三个命令里最常见的坑。

第 5 条的 `-F'[][:/ ]+'` 是把 `[`、`]`、`:`、`/`、空格都当分隔符（正则字符类），这样时间戳会被切成独立的字段。

### 配角：`sort`、`uniq`、`cut`、`head`、`tail`、`tr`、`wc`

| 命令 | 用途 | 常用参数 |
| --- | --- | --- |
| `sort` | 排序 | `-n` 数值序、`-r` 倒序、`-k2` 按第 2 列、`-u` 去重 |
| `uniq` | 相邻去重/计数 | `-c` 计数、`-d` 只留重复的 |
| `cut` | 按列切 | `-d,` 分隔符、`-f2-4` 第 2 到 4 个字段 |
| `head` / `tail` | 头尾 | `-n 20`、`tail -f` 持续跟随 |
| `tr` | 字符替换 | `tr 'a-z' 'A-Z'`、`tr -d '\r'`（去掉 Windows 换行里的 CR） |
| `wc` | 计数 | `-l` 行、`-w` 词、`-c` 字节 |

`tr -d '\r'` 值得单独记：从 Windows 传过来的脚本，行尾是 `\r\n`，bash 会把 `\r` 当成命令的一部分，报 `$'\r': command not found` 或 `bad interpreter`。修法就是 `tr -d '\r' < a.sh > a.sh.new`。

## 动手

```code-challenge
{"id":"linux-07-top-ip","kind":"order","title":"把「请求量前 10 的 IP」这条流水线排好","lines":[
 "awk '{print $1}' access.log",
 "sort",
 "uniq -c",
 "sort -rn",
 "head -10"
],"answer":[0,1,2,3,4],
 "explain":"先取 IP 列，再排序让相同 IP 相邻（uniq 只合并相邻行），计数后按数字倒序排，最后取前 10。少了第一个 sort，uniq 会把同一个 IP 拆成好几段。"}
```

这一道用 awk 按列算一个真结果。

```code-challenge
{
 "id": "linux-07-shell-awk-sum",
 "kind": "shell",
 "title": "用 awk 统计北区销售额",
 "seed": [
  {
   "path": "sales/orders.csv",
   "content": "id,region,amount\n1,north,120\n2,south,80\n3,north,150\n4,east,50\n"
  }
 ],
 "starter": "# 把 north 区域 amount 之和写进 sales/north-total.txt（只要数字）\n",
 "judge": "set -e\ntest \"$(tr -d ' \n' < sales/north-total.txt)\" = \"270\"",
 "explain": "`awk -F, '$2==\"north\"{s+=$3} END{print s}' sales/orders.csv > sales/north-total.txt`。-F, 指定逗号分隔，END 块在读完所有行后输出累计值。判定会去掉换行与空格再比，所以写成 270 或 270 换行都算对。"
}
```

## 检查一下

```quiz
{"id":"linux-07-quiz","title":"文本处理三问","questions":[
 {"question":"`awk '{print $1}' access.log | uniq -c` 统计不准，为什么？","options":["uniq 只合并相邻的重复行，缺 sort","awk 默认按逗号分隔","uniq -c 只处理前 10 行"],"answer":0,"explain":"uniq 的语义是「相邻去重」，所以统计前必须让相同行挨在一起——这就是 uniq 前面总要跟一个 sort 的原因。"},
 {"question":"`grep -E \"ERROR|WARN\"` 与 `grep \"ERROR|WARN\"` 的区别？","options":["前者按扩展正则解释 |，后者把 | 当普通字符","没有区别","后者更快"],"answer":0,"explain":"默认的 BRE 里 | 不是元字符，所以要 -E 打开 ERE，或者写成 `grep -e ERROR -e WARN`。"},
 {"question":"想给匹配到的数字整体加方括号，`sed` 里用哪个符号代表匹配内容？","options":["&","$1","\\0"],"answer":0,"explain":"sed 的替换串里 `&` 表示整个匹配；分组反向引用才是 `\\1`。awk 里取字段是 `$1`，别混。"}
]}
```

## 常见误区

### 误区：`sed -i` 可以直接在生产配置上改

**你以为**：先 `sed -i` 改，改错了再说。
**其实**：`-i` 是**原地写**，没有备份、没有 diff、没有撤销。改错一个正则可能把整份配置写成一行，服务重启才发现。正确顺序是 `sed -i.bak`、`diff` 确认、再重启服务。
**怎么验证**：随便改一个配置文件，用 `-i.bak`，然后 `diff app.conf.bak app.conf` 看差异；对比文件时间戳 `stat` 也会变。

### 误区：`grep -r` 在整个仓库里搜就够了

**你以为**：`grep -r 关键词 .` 最简单。
**其实**：它会把 `node_modules`、`target`、`.git`、二进制文件全扫一遍，慢几十倍，还会因为"Binary file matches"刷屏。加 `--include` 限定扩展名，或者用 `grep -rn --exclude-dir={node_modules,target,.git}`。
**怎么验证**：在有大依赖的项目里分别跑一次带与不带 `--include` 的版本，用 `time` 对比。

### 误区：`awk` 的 `$1` 和 shell 的 `$1` 是一回事

**你以为**：`awk '{print $1}'` 里的 `$1` 是脚本的第一个参数。
**其实**：在**单引号**里，`$1` 是 `awk` 自己的第一列，shell 不会展开。这也是为什么 awk 脚本外面几乎总是单引号——换成双引号，`$1` 会先被 shell 换成脚本参数，然后 awk 拿到一个空字段。真要在 awk 里用 shell 变量，用 `-v` 传：`awk -v name="$NAME" '{print name, $1}'`。
**怎么验证**：`echo "a b" | awk "{print \$1}"` 与 `echo "a b" | awk '{print $1}'` 对比，前者容易因为引号问题出错。

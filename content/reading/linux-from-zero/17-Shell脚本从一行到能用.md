---
title: Shell 脚本：从一行到能用的脚本
category: linux-from-zero
order: 17
minutes: 20
level: beginner
status: published
---

## `ERROR 行数:` 后面是空的，退出码是 0

这是我第一版 `sweep.sh`。它做两件事：数一下 `app.log` 里有多少行 ERROR，再把 7 天前的 `.log` 压成 `.gz`。

```bash
#!/usr/bin/env bash
set -euo pipefail

count_errors() {
  local n=$(grep -c ERROR "$1/app.log")
  printf 'ERROR 行数: %s\n' "$n"
}

count_errors "$1"
find "$1" -maxdepth 1 -name '*.log' -mtime +7 -print0 | xargs -0 -r gzip --
echo "sweep done"
```

`logs/` 里只有一个 `b.log`，没有 `app.log`。跑一次：

```text
$ bash sweep.sh logs; echo "exit=$?"
grep: logs/app.log: No such file or directory
ERROR 行数: 
sweep done
exit=0
```

`set -euo pipefail` 就在第一行，我原以为它是一根安全带。实际发生的是：`grep` 的错误只留在 stderr，`ERROR 行数:` 后面什么都没有，脚本照常打完 `sweep done`，退出码 0。

拆开看是三件事叠在一起：

- `grep -c` **打不开文件时 stdout 是空的，不是 `0`**（GNU grep，退出码 2）。我原以为它会数出 0，然后我把它当数字用。
- `local n=$(...)` 整条赋值语句的退出码是 `local` 自己的 0，`grep` 的 2 被丢掉，`set -e` 根本没看见失败。
- 于是 `$n` 是空字符串。后面任何拿它做比较的地方都会「通过」，那句报错只在 stderr 里闪一下，被下一条输出冲掉。

要让它失败得像失败，得把退出码显式分流：

```bash
if errors=$(grep -c ERROR "$SRC_DIR/app.log"); then
  printf 'ERROR 行数: %s\n' "$errors"
else
  printf '读不到 %s/app.log（grep 退出码 %s）\n' "$SRC_DIR" "$?" >&2
  exit 1
fi
```

```text
$ bash sweep.sh logs; echo "exit=$?"
grep: logs/app.log: No such file or directory
读不到 logs/app.log（grep 退出码 2）
exit=1
```

`$?` 在 `else` 分支里仍然是 grep 的退出码。但别写成 `if ! errors=$(...); then`——那时 `$?` 是取反后的 0。我第一版就是那么写的，打出来是「grep 退出码 0」。

以下输出都来自 Git Bash 5.2.26（MINGW64_NT-10.0-22000，Windows 11），dash 用的是它自带的 `/usr/bin/dash`。换系统换版本，红字会变，机制不变。

## `set -e` 挡住的与挡不住的

bash 默认对失败的命令只记一个非零退出码，接着执行下一行。所以第一步不是写业务逻辑，是把默认行为关掉：

```bash
#!/usr/bin/env bash
set -euo pipefail
```

| 写法 | 作用 | 它挡不住什么 |
| --- | --- | --- |
| `set -e` | 命令返回非 0 就退出脚本 | 条件位置、`&&` 左侧、`local` 包裹的赋值、用 `true` 兜底的写法 |
| `set -u` | 引用未定义变量直接报错 | 「参数存在但是空串」 |
| `set -o pipefail` | 管道里任何一段失败，整条管道算失败 | 命令替换不在管道里，它的失败由赋值语句自己判 |
| `IFS=$'\n\t'` | 让不加引号的展开只按行和 tab 分词 | 它不替代引号，带空格的文件名照样要引号 |

`set -e` 的例外是要真的记的。同一份脚本加不加 `-e`，`cd` 失败后的走向完全不同：

```text
$ bash k1.sh          # 没有 set -e
k1.sh: line 2: cd: /no/such/dir: No such file or directory
还在 /tmp/l17
无 -e exit=0

$ bash k2.sh          # 有 set -e
k2.sh: line 3: cd: /no/such/dir: No such file or directory
有 -e exit=1
```

上面那条 `还在 /tmp/l17` 就是「错了还往下跑」：目录没切过去，后面的路径全落在旧目录里。所以遇到 `cd`，`cd "$DIR" && ...` 或者 `set -e` 至少得有一个。

还有一个不吭声的坑，算术自增：

```bash
#!/usr/bin/env bash
set -euo pipefail
i=0
((i++))
echo "走得到这里"
```

```text
$ bash arith.sh
（没有任何输出，exit=1）
```

`(( ))` 的退出码是「表达式的值是否非 0」。`i=0` 时 `i++` 返回旧值 0，所以 `((i++))` 返回 1，`set -e` 认为它失败了，脚本死在这一行。要计数就写 `i=$((i + 1))`，或者 `((i++)) || true`。

碰到脚本行为不对，先按症状定位：

| 症状 | 先看哪 |
| --- | --- |
| 退出码 0，但该做的事没做 | 那一步是不是被 `local`/`declare`/`export` 包着，或者落在 `if`、`&&` 这类条件位置 |
| 报错闪一下就没了，脚本继续 | 失败的那段在管道里，且最后一段成功了：开 `pipefail`，再用 `PIPESTATUS` 看是哪一段 |
| 参数为空时动到了别处的文件 | `$1` 换成 `${1:?}`；`set -u` 只挡「没传」，挡不住「传了空串」 |
| 带空格的文件名被拆成两半 | 展开处有没有引号；骨架里有没有 `IFS=$'\n\t'` 把空格从分隔符里拿掉 |
| 中途失败留下临时文件 | `trap ... EXIT` 有没有；进程是不是被 `kill -9` 或容器 OOM 杀掉 |
| 脚本一声不响地退出 | 是不是 `(( ))` 的表达式值为 0，返回了 1 |

## 管道：退出码只看最后一段

`find ... | xargs gzip` 这类写法里，`$?` 是 `xargs` 的退出码，`find` 的死活看不见：

```text
$ false | true; echo "rc=$?"
rc=0

$ set -o pipefail; false | true; echo "rc=$?  PIPESTATUS=${PIPESTATUS[*]}"
rc=1  PIPESTATUS=1 0
```

`$?` 只有一个数字，`PIPESTATUS` 是一段一个。三段管道一样：`false | true | false` 的 `PIPESTATUS` 是 `1 0 1`，加了 `pipefail` 之后 `$?` 才变成 1。

把 `sweep.sh` 里的 find 换成一个不存在的目录，两种配置的差别就是脚本能不能活下来：

```text
$ bash p1.sh          # 只有 set -eu
find: '/nonexistent-logdir': No such file or directory
after find|gzip
exit=0

$ bash p2.sh          # set -euo pipefail
find: '/nonexistent-logdir': No such file or directory
exit=1
```

所以 `set -euo pipefail` 里的 `pipefail` 不是装饰：清理脚本真正怕的就是「find 没找到东西，xargs 安安稳稳退出 0，脚本报告清理成功」。

`xargs -r` 是 GNU findutils 的选项（空输入时不执行命令）。BSD 和 macOS 那份 `xargs` 没有它，空输入时仍会执行一次命令（`man xargs` 的说法），这条我没在 macOS 上跑过，是照文档写的。

## 参数为空、参数没传，是两件事

这是删库类脚本的第一道闸，而 `set -u` 只挡一半：

```text
$ bash b.sh              # 不传
参数个数=0
b.sh: line 4: $1: unbound variable
exit=1

$ bash b.sh ""           # 传空串
参数个数=1
D=[]
exit=0
```

「传了空串」比「没传」更容易漏过检查：上游变量没取到值，展开成空，然后拼进 `rm` 的路径，而 `set -u` 一声不吭。要拦它，靠花括号加大问号：

| 写法 | 传空串 | 完全不传 |
| --- | --- | --- |
| `$1`（配 `set -u`） | 通过，值是空 | 报 unbound variable，退出 1 |
| `${1:?用法}` | 报错退出 1 | 报错退出 1 |
| `${1?用法}` | 通过，值是空 | 报错退出 1 |
| `${1:-默认}` | 取默认值 | 取默认值 |

所以清理脚本的开头要写：

```bash
readonly SRC_DIR="${1:?用法: $0 <日志目录>}"
```

冒号决定它管不管空串：`${1:?}` 里那个冒号的意思是「空的也算没给」。`${1?}` 少了冒号，空串照样放行——它就挡不住上游传 `""` 的情况。

## 引号：带空格的目录名，和骨架里那行 `IFS`

日志目录叫 `logs 2026` 的时候，不加引号的展开会把它劈成两个词：

```text
$ for f in logs 2026/*.log; do echo "[$f]"; done
[logs]
[2026/*.log]

$ for f in "logs 2026"/*.log; do echo "[$f]"; done
[logs 2026/one two.log]
```

第一组的两行都是错的：目录被拆开，通配符成了 `2026/*.log` 字面量。

骨架里那行 `IFS=$'\n\t'` 是在这个基础上再加一层：把空格从分隔符里拿掉。它只影响**不加引号**的展开：

```text
$ files="b.log c.log app.log"
$ for f in $files; do echo "[$f]"; done
[b.log]
[c.log]
[app.log]

$ IFS=$'\n\t'
$ for f in $files; do echo "[$f]"; done
[b.log c.log app.log]
```

同一个 `for f in $files`，设 IFS 之前拆成 3 个词，设之后只有 1 个词。所以「`IFS=$'\n\t'` 让文件名里有空格时更安全」和「`for f in $files` 会按空格循环 3 次」不能同时成立——只能留后面那句的一半：**引号永远要加，IFS 只是让不加引号时的伤害小一点。** 这条也是我改上一版稿子时发现的矛盾：正文举的例子被自己骨架里的 IFS 推翻了。

`"$@"` 是同一件事的参数版本：带引号时一个参数就是一个词，不带引号时会被重新分词。脚本入口写 `main "$@"`，不要写 `main $@`。

## `trap`：哪些出口会清理

临时目录要在所有出口都清掉，写 `trap`：

```bash
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
```

注意是单引号。双引号会在**定义 trap 那一刻**就把 `$tmp` 展开定死：之后 `tmp` 改了，trap 里用的还是旧值，变量那时还没赋值的话就固定成空。单引号留到**执行那一刻**才展开，所以清除的是最终那个目录。

实测它覆盖哪些出口：

```text
$ bash t.sh                          # 脚本在 false 处被 set -e 掐断
清理 /tmp/tmp.qrlWLBFoYr
exit=1

$ bash t.sh & sleep 1; kill -TERM $!; wait $!; echo "exit=$?"
清理 /tmp/tmp.e3EpfRW0Px
exit=143

$ bash g.sh & sleep 1; kill -9 $!; wait $!; echo "exit=$?"
T=/tmp/tmp.MPDYikJxk4
exit=137
（「清理跑了」没有出现）
```

正常结束、`set -e` 中途退出、SIGTERM 都会跑；`kill -9` 不会，容器 OOM 被杀同理。要扛这种场景，靠的不是 trap，而是让临时文件下次启动时能被识别和重建。

## 同一份脚本丢进 `/bin/sh`

这台机器上 `/bin/sh` 是 bash，但在 Debian/Ubuntu 上它是 dash。判断脚本能不能用 `#!/bin/sh`，直接用 dash 跑一遍最快：

```text
$ dash posix.sh
sh 跑起来了
posix.sh: 4: [[: not found
exit=0
```

注意退出码是 0。`[[` 在 `if` 条件里，dash 找不到这个命令，条件为假，直接走 `else` 分支，`set -e` 不管条件位置——**脚本没崩，是静默走了错的分支**。这比报错难查得多。

```text
$ dash posix2.sh
posix2.sh: 2: set: Illegal option -o pipefail
exit=2

$ dash j.sh                # 脚本里写 s=$'a\nb'，然后 echo ${#s}
长度=5                     # bash 下是 3
```

`$'...'` 在 dash 里不是 ANSI-C 引用：同一个 `$'a\nb'` 量出来是 5 个字符（bash 下是 3），而且不报错。`(( ))` 也一样，报 `i++: not found` 然后接着往下跑（`i` 还是 0）；换成 `$((i + 1))` 就能用。

一条实用判据：脚本里只要出现 `[[`、`$'...'`、`(( ))`、`set -o pipefail` 中的任何一个，shebang 就必须是 `#!/usr/bin/env bash`，别写 `#!/bin/sh`。

## 跑一遍

把上面这些拼起来，就是能用的版本。文件里不再有未加引号的路径、不再有被 `local` 吞掉的退出码：

```bash
#!/usr/bin/env bash
set -euo pipefail

readonly SRC_DIR="${1:?用法: $0 <日志目录>}"
readonly KEEP_DAYS="${KEEP_DAYS:-7}"

[[ -d "$SRC_DIR" ]] || { printf '不是目录: %s\n' "$SRC_DIR" >&2; exit 1; }

if errors=$(grep -c ERROR "$SRC_DIR/app.log"); then
  printf 'ERROR 行数: %s\n' "$errors"
else
  printf '读不到 %s/app.log（grep 退出码 %s）\n' "$SRC_DIR" "$?" >&2
  exit 1
fi

find "$SRC_DIR" -maxdepth 1 -name '*.log' -mtime +"$KEEP_DAYS" -print0 |
  xargs -0 -r gzip --
printf '已压缩 %s 天前的 .log\n' "$KEEP_DAYS"
```

上一节的 `trap` 没拼进来：这版直接原地 `gzip`，没有中间产物要清。等脚本要先生成临时文件（先解压再压、先拷贝再原子替换）时，再按那一节加。

准备一个目录：`app.log` 里有两行 ERROR，另外两个日志的修改时间是 10 月 20 和 10 月 21（今天 11 月 19）。

```text
$ ls -l --time-style=+%F logs
total 1
-rw-r--r-- 1 Lenovo 197121 36 2026-11-19 app.log
-rw-r--r-- 1 Lenovo 197121  0 2026-10-20 b.log
-rw-r--r-- 1 Lenovo 197121  0 2026-10-21 c.log

$ bash sweep.sh logs; echo "exit=$?"
ERROR 行数: 2
已压缩 7 天前的 .log
exit=0

$ ls logs
app.log  b.log.gz  c.log.gz
```

出错的三条路径，各自在不同的地方停下：

```text
$ bash sweep.sh ""; echo "exit=$?"
sweep.sh: line 4: 1: 用法: sweep.sh <日志目录>
exit=1

$ bash sweep.sh logs/nologs; echo "exit=$?"
不是目录: logs/nologs
exit=1

$ rm logs/app.log && bash sweep.sh logs; echo "exit=$?"
grep: logs/app.log: No such file or directory
读不到 logs/app.log（grep 退出码 2）
exit=1
```

第一行报错里的 `1:` 是 bash 自己加的（它报的是参数名），不好看但真实：`${1:?}` 触发时报错的就是这个形状。

## 动手

下面这题问错在哪一行。注意它已经写了 `set -u`，所以「完全不传」不是它的漏洞——漏洞在别处：

```code-challenge
{"id":"linux-17-unquoted","kind":"bug","title":"参数为空时，这个清理脚本会去碰根目录，错在哪一行？","language":"bash",
 "code":"#!/usr/bin/env bash\nset -euo pipefail\n\nLOG_DIR=\"${1:-}\"\nrm -rf $LOG_DIR/*.gz","bugLine":5,
 "explain":"第 4 行的 ${1:-} 让「没传参数」也变成空串，第 5 行再不带引号展开，命令就成为 rm -rf /*.gz：根目录真有 .gz 时它们会被删掉；没有匹配时 bash 把字面量 /*.gz 原样交给 rm，-f 让它静默返回 0（实测 rm -rf 一个不存在的路径退出码是 0，去掉 -f 才是 1）——两种都不该发生。要写成 rm -rf \"${LOG_DIR:?}\"/*.gz：冒号让空串也报错退出，引号防止路径里的空格把参数拆开。"}
```

第二道要在整份脚本上做：留着引号还不够，判定会先传空串、再传真实目录各跑一次。

```code-challenge
{
 "id": "linux-17-shell-quote-fix",
 "kind": "shell",
 "title": "修掉那个会删错东西的清理脚本",
 "seed": [
  {
   "path": "app/cleanup.sh",
   "content": "#!/bin/bash\n# 删掉指定目录下的 *.tmp\nDIR=$1\nrm -rf $DIR/*.tmp\n"
  },
  {
   "path": "app/keep.tmp",
   "content": "only deleted when DIR=app\n"
  },
  {
   "path": "app/keep.txt",
   "content": "never delete me\n"
  }
 ],
 "starter": "# 重写 app/cleanup.sh（用 heredoc 写回去），要求：\n# - 参数为空（空串或不传）时直接报错退出，一个文件都不许删\n# - 正常传入目录时删掉里面的 *.tmp，别动其它文件\n# 提示：只加引号不够，要对空串也生效\n",
 "judge": "set -e\n[ -f app/keep.tmp ] || { echo \"app/keep.tmp 一开始就没了——脚本在正常调用之前就删了东西\"; exit 1; }\nif bash app/cleanup.sh '' > /dev/null 2>&1; then echo \"参数为空时脚本仍然成功：要用 \\${1:?} 让它报错退出\"; exit 1; fi\n[ -f app/keep.tmp ] || { echo \"参数为空时把 app/keep.tmp 删了\"; exit 1; }\nbash app/cleanup.sh app || { echo \"正常调用（传 app）时脚本失败了\"; exit 1; }\n[ ! -f app/keep.tmp ] || { echo \"传 app 之后 app/keep.tmp 还在：没删掉 .tmp\"; exit 1; }\n[ -f app/keep.txt ] || { echo \"app/keep.txt 被误删了：只该删 *.tmp\"; exit 1; }",
 "explain": "关键是 ${DIR:?} 与引号：rm -rf \"${DIR:?}\"/*.tmp 在参数是空串时直接报错退出，不会变成 rm -rf /*.tmp。判定顺序是：先确认 seed 完好、再传空串要求它失败、再确认没删东西、最后传 app 确认真能删 —— 我按这个顺序在本机跑过：原始 seed 被判不过，只加引号不拦空串的答案也被判不过，${DIR:?} 那版通过。注意 judge 里的 ${1:?} 写成 \\${1:?}，否则报错信息自己先出错。"
}
```

## 检查一下

```quiz
{"id":"linux-17-quiz","title":"脚本三问","questions":[
 {"question":"`local n=$(grep -c ...)` 里 grep 失败时，为什么 `set -e` 没让脚本停下？","options":["因为命令替换里的退出码永远不会传出来","因为 `local` 自己的退出码是 0，把整条赋值语句的状态覆盖了","因为 `set -e` 只对最后一条命令生效"],"answer":1,"explain":"命令替换的退出码本来会作为赋值语句的状态被 set -e 检查；是 local 这个内建执行成功，把状态换成 0。所以 `n=$(...)` 会停，`local n=$(...)` 不会——实测前者退出 2，后者退出 0 且 n 为空。"},
 {"question":"开了 `set -o pipefail` 之后，`find ... | xargs -r gzip` 里 find 失败、xargs 成功，脚本的退出码是什么？","options":["0，最后一段成功了","`$?` 不会变，还是上一条命令留下的值","1，也就是管道里那个非 0 的退出码"],"answer":2,"explain":"pipefail 让管道的状态取最后一个失败命令的退出码。实测 `set -o pipefail; false | true` 的 $? 是 1、PIPESTATUS 是 1 0；不开 pipefail 时同样一条管道 $? 是 0。"},
 {"question":"脚本里写了 `set -u`，然后用 `\"\"` 当参数调用 `sweep.sh`，`D=$1` 会怎样？","options":["不会怎样，D 是空字符串；要拦空串得写成 ${1:?}","报 unbound variable，脚本退出","D 保持赋值前的旧值"],"answer":0,"explain":"`set -u` 拦的是「这个参数根本不存在」。实测传空串时 $# 是 1、D=[]、退出码 0；换成 ${1:?} 才会在空串时退出 1，换 ${1?} 则仍然放行。"}
]}
```

## 还差的

- **`kill -9` 和 OOM 下的清理，这版绕开了。** 它没有临时文件，所以不需要清理；可一旦你按上一节加了中间目录，被 `kill -9` 时 trap 不跑（实测退出码 137，没有「清理跑了」那行）。真要让残留能被收拾，得给临时目录固定命名、启动时按名字扫一遍并重建，而不是只靠 `trap EXIT`。
- **压缩数量拿不到。** 用 `find | xargs gzip` 能看见 find 的失败（靠 `pipefail`），但数不出到底压了几个文件。要计数得用 `while IFS= read -r -d ''` 循环——而那个写法下 `< <(find ...)` 里 find 的失败又不进 `set -e`。这一版我选了「看得见失败」，放弃了计数。
- **`shellcheck` 没跑。** 本机 `command -v shellcheck` 是空的，这篇里所有结论都来自手写用例和 bash 实测。它专门挑「引号没加」「`[ ]` 里写了 `==`」「`$@` 没加引号」这类问题，交脚本进仓库前应该跑一遍。
- **没有防并发。** 两个定时任务同时跑同一份 `sweep.sh`，第二个会拿到已经变成 `.gz` 的文件，重复压缩或直接失败；也没做幂等判断。要补得加 `flock`，那需要另开一段讲。

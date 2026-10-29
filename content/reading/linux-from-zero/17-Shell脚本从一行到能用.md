---
title: Shell 脚本：从一行到能用的脚本
category: linux-from-zero
order: 17
minutes: 30
level: beginner
status: published
---

## 这一关在解决什么

命令行里一步一步敲能做的事，为什么要写成脚本？因为**要重复、要定时、要别人也能跑**。而脚本最容易出事的地方不是"语法写错"，是**出错之后还继续往下跑**——比如 `cd` 失败后仍在这个目录里执行 `rm -rf`。

这一关给你一套可以当模板用的骨架（`set -euo pipefail`、引号规则、`trap` 清理），再加两件工具：`bash -x` 和 `shellcheck`。

## 为什么 shell 脚本"看起来简单、写起来危险"

因为 bash 的默认行为是**尽量继续执行**：命令失败不会停、未定义变量当成空字符串、变量不加引号会被空格拆成多个参数。这些设计在交互式使用时是便利（一条命令错了接着敲下一条），在脚本里全是坑。

所以写脚本的第一件事不是写业务逻辑，而是**把这三条默认行为关掉**：

```bash
#!/usr/bin/env bash
set -euo pipefail
```

## 讲解

### 骨架

```bash
#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

readonly APP_DIR="/opt/paideia"
readonly BACKUP_DIR="/data/backup"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

main() {
  log "start"
  # 业务逻辑
}

main "$@"
```

逐项解释：

| 写法 | 作用 |
| --- | --- |
| `#!/usr/bin/env bash` | 用环境里的 bash 执行。比 `#!/bin/bash` 更可移植（有些系统 bash 在 `/usr/local/bin`） |
| `set -e` | **命令失败就退出**。不然一步失败后面全在错误状态下继续跑 |
| `set -u` | **引用未定义变量就报错**。避免 `rm -rf "$DIR"/*` 里 `$DIR` 为空变成 `rm -rf /*` |
| `set -o pipefail` | 管道里**任何一段失败都算失败**。否则 `a | b` 的退出码只看 `b` |
| `IFS=$'\n\t'` | 按行和 tab 分词，不按空格——文件名里有空格时更安全 |
| `readonly` | 常量，误改会报错 |
| `main "$@"` | 把参数透传给函数；`"$@"` 带引号才能保证"一个参数就是一个参数" |

`set -e` 有它自己的例外，值得知道：**在 `if`、`&&`、`||`、`!` 的条件位置里的命令失败不会退出**——这正是我们要的（`if 命令; then` 就是拿它做判断）。

### 引号：唯一必须背下来的规则

```bash
name="paideia"
echo $name            # 可以
echo "$name"          # 永远更安全
files="a b  c"
for f in $files; do   # 循环 3 次：a、b、c（空格被当成分隔符）
for f in "$files"; do # 循环 1 次："a b  c"
```

| 写法 | 行为 |
| --- | --- |
| `"$VAR"` | 原样展开，**保留空格与换行**。默认就用这个 |
| `$VAR` | 展开后**按空白拆词**，还会做通配符展开。只有明确想要这个行为时用 |
| `'$VAR'` | 单引号：完全不展开，原样字面量 |
| `"${VAR}s"` | 用花括号界定变量名边界（`$VARs` 会被当成变量 `VARs`） |

三个常用的变量语法：

```bash
: "${1:?用法: $0 <目录>}"         # 参数缺失时打印提示并退出
LOG_LEVEL="${LOG_LEVEL:-info}"     # 未定义时用默认值
FILE="${CONF_DIR}/app.conf"        # 拼接时用花括号
```

`${VAR:?}` 是**防删库的核心写法**：`rm -rf "${DIR:?}"/*` 在 `DIR` 为空时会直接报错退出，不会删到根目录。

### 判断与分支

```bash
if [[ -f "$conf" && -r "$conf" ]]; then
  echo "配置文件可读"
elif [[ -d "$conf" ]]; then
  echo "这是个目录，不是文件"
else
  echo "文件不存在" >&2
  exit 1
fi

# 数字比较用 (( )) 最清楚
if (( count > 10 )); then echo "too many"; fi

# 字符串比较
[[ "$env" == "prod" ]] && echo "生产环境"
```

`[[ ]]` 和 `[ ]` 的差别：`[[ ]]` 是 bash 的内建语法，支持 `&&`、`||`、正则 `=~`，且**变量为空也不会报错**；`[ ]` 是普通命令（等价 `test`），`[ $x = 1 ]` 在 `x` 为空时会变成 `[ = 1 ]`，报 `unary operator expected`。**写 bash 就用 `[[ ]]`。**

常用测试符：

| 测试 | 真当 |
| --- | --- |
| `-f` / `-d` / `-L` | 是普通文件 / 目录 / 符号链接 |
| `-r` / `-w` / `-x` | 有读 / 写 / 执行权限 |
| `-z` / `-n` | 字符串为空 / 非空 |
| `-eq -ne -gt -lt` | 数字比较 |
| `==` `!=` | 字符串比较（`[ ]` 里要写 `=`） |

### 循环

```bash
for f in /var/log/*.log; do
  gzip -f "$f"
done

for i in $(seq 1 5); do echo "$i"; done
for ((i = 0; i < 5; i++)); do echo "$i"; done     # 更像 C

# 按行读文件：while read 是标准写法
while IFS= read -r line; do
  echo "行: $line"
done < hosts.txt
```

**`while read` 那三件套别省**：`IFS=` 保留行首尾空白，`-r` 不让反斜杠被吞。少了它们，读配置文件时某些行会莫名变形。

`find ... | while read` 是个常见陷阱：管道里的 `while` 在**子 shell** 里执行，循环内修改的变量在外面看不到。解法是用进程替换：`while read -r f; do ...; done < <(find ...)`。

### 函数与参数

```bash
push_file() {
  local src="$1" dest="$2"      # local：变量不泄露到全局
  scp "$src" "prod:$dest" || return 1
}

push_file a.txt /tmp/
```

脚本自己的参数：

| 变量 | 含义 |
| --- | --- |
| `$0` | 脚本名 |
| `$1` … `$9` | 位置参数，超过 9 个用 `${10}` |
| `$#` | 参数个数 |
| `"$@"` | 所有参数，**每个参数保持一个独立的词**（正确写法） |
| `$*` | 所有参数拼成一个字符串（多数情况是错的） |

### 退出码与 `trap`：清理要在所有出口都执行

```bash
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# 无论正常结束、return 1 还是 Ctrl+C，都会执行 EXIT 陷阱
```

`trap '...' EXIT` 是脚本的"finally"。没有它，中途失败会留下一堆临时文件——这也是为什么很多服务器上的 `/tmp` 一年后有几万个残留目录。

想看上一个命令的退出码：

```bash
grep -q ERROR app.log
echo $?          # 0 = 找到，1 = 没找到
```

### 调试：两条命令解决 90% 的"它为什么不按我想的跑"

```bash
$ bash -x ./deploy.sh             # 打印每一条展开后的命令
$ bash -n ./deploy.sh             # 只做语法检查，不执行
```

`-x` 的输出形如 `+ cp a.txt /tmp/`，你能看到**变量展开后**的真实命令。把 `set -x` 写在脚本某个位置，就只跟踪那一段。

再往上一步是 `shellcheck`：

```text
$ shellcheck deploy.sh
```

它专门挑"引号没加""`[` 里用了 `==`""`$@` 没加引号""用 `ls` 遍历文件"这类问题，是写 shell 脚本最有性价比的工具。

### 一个完整的例子

```bash
#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

readonly SRC_DIR="${1:?用法: $0 <源目录>}"
readonly BACKUP_ROOT="/data/backup"
readonly KEEP=7

[[ -d "$SRC_DIR" ]] || { echo "不是目录: $SRC_DIR" >&2; exit 1; }

stamp="$(date '+%Y%m%d-%H%M%S')"
dest="$BACKUP_ROOT/$(basename "$SRC_DIR")-$stamp"
mkdir -p "$BACKUP_ROOT"

tar -czf "$dest.tar.gz" -C "$(dirname "$SRC_DIR")" "$(basename "$SRC_DIR")"
echo "已备份到 $dest.tar.gz"

# 只留最近 7 份
ls -1t "$BACKUP_ROOT"/*.tar.gz | tail -n +$((KEEP + 1)) | xargs -r rm -f
```

这份脚本里每一行都在用本关讲的东西：`${1:?}` 校验参数、`[[ -d ]]` 判断、`||` 短路退出、`"$@"` 式的引号习惯、`basename/dirname` 处理路径。把它当模板改，比从空文件开始稳。

## 动手

```code-challenge
{"id":"linux-17-unquoted","kind":"bug","title":"这个清理脚本会在变量为空时删掉整个根目录，错在哪一行？","language":"bash",
 "code":"#!/usr/bin/env bash\nset -euo pipefail\n\nLOG_DIR=$1\nrm -rf $LOG_DIR/*.gz","bugLine":5,
 "explain":"`$1` 没传时 `LOG_DIR` 为空，第 5 行展开成 `rm -rf /*.gz`——危险且不可逆。要写成 `rm -rf \"${LOG_DIR:?}\"/*.gz`：变量为空时直接报错退出，引号也防止路径里含空格被拆开。"}
```

这一道是「改写一个会删库的脚本」，判定会真的拿空参数调它一次。

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
 "starter": "# 重写 app/cleanup.sh（用 heredoc 写回去），要求：\n# - 参数为空时直接报错退出，一个文件都不许删\n# - 正常传入目录时删掉里面的 *.tmp，别动其它文件\n",
 "judge": "set -e\n[ -f app/keep.tmp ] || { echo \"app/keep.tmp 一开始就没了——脚本在正常调用之前就删了东西\"; exit 1; }\nif bash app/cleanup.sh \"\" > /dev/null 2>&1; then echo \"参数为空时脚本仍然成功：要用 ${1:?} 让它报错退出\"; exit 1; fi\n[ -f app/keep.tmp ] || { echo \"参数为空时把 app/keep.tmp 删了\"; exit 1; }\nbash app/cleanup.sh app || { echo \"正常调用（传 app）时脚本失败了\"; exit 1; }\n[ ! -f app/keep.tmp ] || { echo \"传 app 之后 app/keep.tmp 还在：没删掉 .tmp\"; exit 1; }\n[ -f app/keep.txt ] || { echo \"app/keep.txt 被误删了：只该删 *.tmp\"; exit 1; }",
 "explain": "核心是 `${DIR:?}` 与引号：`rm -rf \"${DIR:?}\"/*.tmp` 在参数为空时直接报错退出，不会变成 `rm -rf /*.tmp`。判定先传空参数确认它拒绝，再传 app 确认真能删。"
}
```

## 检查一下

```quiz
{"id":"linux-17-quiz","title":"脚本三问","questions":[
 {"question":"`set -euo pipefail` 里的 `-u` 做什么？","options":["引用未定义变量时报错退出","只输出错误日志","禁止使用 sudo"],"answer":0,"explain":"-u（nounset）让未定义变量直接报错，而不是当空字符串——这是防止「变量空导致 rm -rf /」的第一道闸。"},
 {"question":"`for f in $files` 与 `for f in \"$files\"` 的区别？","options":["前者按空格拆成多个词，后者保持一个整体","前者更快","两者等价"],"answer":0,"explain":"不带引号的展开会做分词和通配符展开；带引号保留原始字符串。默认永远带引号。"},
 {"question":"`set -e` 会不会让 `if grep -q x f; then` 出错时退出？","options":["不会，条件位置的命令失败不算致命错误","会，脚本立刻退出","只在函数内不会"],"answer":0,"explain":"set -e 忽略 if/while/&&/||/! 条件位置的失败，这正是用它做判断的原因。"}
]}
```

## 常见误区

### 误区：`set -e` 之后就不会有"半途失败还继续跑"

**你以为**：加了 `set -e` 就安全了。
**其实**：`set -e` 不管**管道**里前几段的失败（要靠 `pipefail`），不检查**函数返回值的忽略**（`cmd || true` 之后就是成功），也不会让 `cd` 失败后自动切换目录——`cd "$DIR" && rm -rf ./*` 里的 `&&` 才是必需的。而且命令在 `$(...)` 子 shell 里失败时的行为还依赖 bash 版本。
**怎么验证**：写个 `false; echo still here` 的脚本对比加不加 `-e`；再用 `false | true` 看加不加 `pipefail` 的差别。

### 误区：脚本里可以用相对路径

**你以为**：`./build.sh` 在项目目录里跑，写 `cd build` 没问题。
**其实**：脚本会被从别处调用（cron 的工作目录是 `/`，systemd 的工作目录由 `WorkingDirectory` 决定，第 10 关）。**脚本开头就 `cd` 到自己所在目录**：

```bash
cd "$(dirname "$(readlink -f "$0")")" || exit 1
```

这一行让脚本"永远以自己所在目录为工作目录"，是所有注意相对路径的脚本的第一行。
**怎么验证**：在 `/tmp` 下执行 `/opt/app/build.sh`，不加这一行时相对路径全错。

### 误区：`$(ls *.log)` 是遍历文件的标准写法

**你以为**：用 `ls` 的输出做循环很自然。
**其实**：`ls` 的输出是给**人**看的，文件名里有空格或换行时会被拆成多个词，导致"循环里出现了半个文件名"。直接用通配符（`for f in *.log`），或用 `find -print0` 配合 `while IFS= read -r -d '' f`。
**怎么验证**：建一个名为 `a b.log` 的文件，跑 `for f in $(ls *.log); do echo "[$f]"; done`，会看到两个方括号。

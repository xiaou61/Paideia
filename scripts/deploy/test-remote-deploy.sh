#!/usr/bin/env bash
# 远端发布状态机的本地隔离验证（WORK-092 TASK-008）。
#
# 不碰服务器、不碰真实 systemctl/curl/jar：用临时目录模拟 RemoteDir，用假的
# systemctl/curl/sleep 顶替，真实跑一遍 scripts/deploy/remote-deploy.sh 渲染后的脚本，
# 断言三种情况下文件系统状态与输出正确：
#   1. 健康通过 -> DEPLOY_OK，jar/web/清单全部推进，快照与锁清理；
#   2. 健康失败 -> DEPLOY_FAILED，jar/web/清单整体恢复到发布前，不残留半套版本；
#   3. 已有其它发布持锁 -> 直接 DEPLOY_FAILED，且不触碰任何文件。
set -u

ROOT=$(cd "$(dirname "$0")" && pwd)
TEMPLATE="$ROOT/remote-deploy.sh"
if [ ! -f "$TEMPLATE" ]; then echo "找不到模板 $TEMPLATE" >&2; exit 2; fi

PASS=0
FAIL=0
ok()   { PASS=$(( PASS + 1 )); echo "  ok   - $1"; }
bad()  { FAIL=$(( FAIL + 1 )); echo "  FAIL - $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

# 把 __XXX__ 占位符替换成真实值（值里有 /、& 时先转义，用 | 作分隔符）
render() {
  local remote="$1" repack="$2" prom="$3" prom_b64="$4"
  local s
  s=$(cat "$TEMPLATE")
  esc() { printf '%s' "$1" | sed -e 's/[&|]/\\&/g'; }
  s=${s//__REMOTE_DIR__/$(esc "$remote")}
  s=${s//__PORT__/18081}
  s=${s//__REMOVE_CMD__/true}
  s=${s//__WEB_REMOVE_CMD__/true}
  s=${s//__REPACK_CMD__/$(esc "$repack")}
  s=${s//__PROM_B64__/$prom_b64}
  s=${s//__PROM_PATH__/$(esc "$prom")}
  printf '%s\n' "$s" > "$remote/deploy-in/deploy.sh"
}

setup() {
  # 造一个「已经发布过一次」的远端目录：jar、web、两份清单、exploded 基点齐全
  local remote="$1"
  mkdir -p "$remote/exploded" "$remote/web/admin" "$remote/deploy-in" "$remote/fake"
  printf 'OLDJAR' > "$remote/app.jar"
  printf 'm-old' > "$remote/exploded/MANIFEST.MF"
  printf 'old'   > "$remote/exploded/keep"
  printf 'old'   > "$remote/web/index.html"
  printf 'old'   > "$remote/web/admin/index.html"
  printf '#v2\noldhash  keep\n' > "$remote/exploded.manifest"
  printf '#v2\noldwebhash  index.html\n' > "$remote/web.manifest"

  # 部署包内容：一个新增 jar 文件、一个新增 web 文件、两份「新」清单
  local tmp="$remote/.tmp"; mkdir -p "$tmp/patch" "$tmp/webpatch"
  printf 'NEWFILE' > "$tmp/patch/newfile"
  tar -czf "$remote/deploy-in/release-patch.tar.gz" -C "$tmp/patch" .
  printf 'NEWWEB' > "$tmp/webpatch/neww.js"
  tar -czf "$remote/deploy-in/web-patch.tar.gz" -C "$tmp/webpatch" .
  printf 'm-new' > "$remote/deploy-in/MANIFEST.MF"
  printf '#v2\nnewhash  newfile\n' > "$remote/deploy-in/exploded.manifest"
  printf '#v2\nnewwebhash  neww.js\n' > "$remote/deploy-in/web.manifest"

  # 假的 repack：新 jar = 旧 jar + 标记
  printf '#!/usr/bin/env bash\ncp -f app.jar app.jar.new && printf "NEW" >> app.jar.new\n' > "$remote/repack.sh"
  chmod +x "$remote/repack.sh"

  # 假的外部命令：systemctl 记录调用、curl 按 HEALTH_OK 返回、sleep 不真睡
  printf '#!/usr/bin/env bash\necho "$@" >> "${SYSTEMCTL_LOG:-/dev/null}"\nexit 0\n' > "$remote/fake/systemctl"
  printf '#!/usr/bin/env bash\nif [ "${HEALTH_OK:-0}" = "1" ]; then printf %s "{\\"status\\":\\"UP\\"}"; exit 0; else exit 7; fi\n' > "$remote/fake/curl"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$remote/fake/sleep"
  chmod +x "$remote/fake/systemctl" "$remote/fake/curl" "$remote/fake/sleep"
}

run_script() {
  local remote="$1" health="$2"
  ( cd "$remote" && PATH="$remote/fake:$PATH" HEALTH_OK="$health" SYSTEMCTL_LOG="$remote/systemctl.log" \
      bash deploy-in/deploy.sh 2>&1 )
}

PROM_B64=$(printf 'paideia_deploy=1 1\n' | base64 | tr -d '\n')

# ── 场景 1：健康通过 ──────────────────────────────────────────────────────────
echo "[场景 1] 健康检查通过"
R1=$(mktemp -d); setup "$R1"; render "$R1" "$R1/repack.sh" "$R1/deploy.prom" "$PROM_B64"
OUT1=$(run_script "$R1" 1); RC1=$?
check "退出码 0" "[ $RC1 -eq 0 ]"
check "输出含 DEPLOY_OK" "grep -q DEPLOY_OK <<<\"\$OUT1\""
check "jar 已换成新版本" "grep -q NEW '$R1/app.jar'"
check "web 已换成新版本" "[ -f '$R1/web/neww.js' ]"
check "exploded.manifest 已推进" "grep -q newfile '$R1/exploded.manifest'"
check "web.manifest 已推进" "grep -q neww.js '$R1/web.manifest'"
check "发版标记已写" "grep -q paideia_deploy '$R1/deploy.prom'"
check "回滚快照已清理" "[ ! -d '$R1/.rollback' ]"
check "发布锁已释放" "[ ! -d '$R1/.deploy.lock.d' ]"
rm -rf "$R1"

# ── 场景 2：健康失败，必须整体回滚 ─────────────────────────────────────────────
echo "[场景 2] 健康检查失败"
R2=$(mktemp -d); setup "$R2"; render "$R2" "$R2/repack.sh" "$R2/deploy.prom" "$PROM_B64"
OUT2=$(run_script "$R2" 0); RC2=$?
check "退出码 0（失败以 DEPLOY_FAILED 表达）" "[ $RC2 -eq 0 ]"
check "输出含 DEPLOY_FAILED" "grep -q DEPLOY_FAILED <<<\"\$OUT2\""
check "jar 已恢复到旧版本" "[ \"\$(cat '$R2/app.jar')\" = 'OLDJAR' ]"
check "web 已恢复到旧版本（新文件消失）" "[ ! -f '$R2/web/neww.js' ] && [ -f '$R2/web/index.html' ]"
check "exploded.manifest 未推进" "grep -q oldhash '$R2/exploded.manifest'"
check "web.manifest 未推进" "grep -q oldwebhash '$R2/web.manifest'"
check "不残留回滚快照" "[ ! -d '$R2/.rollback' ]"
check "发布锁已释放" "[ ! -d '$R2/.deploy.lock.d' ]"
rm -rf "$R2"

# ── 场景 3：已有发布持锁，第二个发布不得触碰文件 ───────────────────────────────
echo "[场景 3] 并发发布互斥"
R3=$(mktemp -d); setup "$R3"; render "$R3" "$R3/repack.sh" "$R3/deploy.prom" "$PROM_B64"
mkdir -p "$R3/.deploy.lock.d"
sleep 30 & HOLDER=$!
echo "$HOLDER" > "$R3/.deploy.lock.d/pid"
OUT3=$(run_script "$R3" 1); RC3=$?
check "退出码 0" "[ $RC3 -eq 0 ]"
check "输出含 DEPLOY_FAILED / 另一个发布" "grep -q '另一个发布' <<<\"\$OUT3\""
check "jar 未被改动" "[ \"\$(cat '$R3/app.jar')\" = 'OLDJAR' ]"
check "web 未被改动" "[ ! -f '$R3/web/neww.js' ]"
kill "$HOLDER" 2>/dev/null || true
rm -rf "$R3"

echo
echo "通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" -eq 0 ]

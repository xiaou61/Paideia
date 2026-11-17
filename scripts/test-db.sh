#!/usr/bin/env bash
# 给一次测试运行分配一个**独占的 MySQL 库**，避免多个会话共用一个 paideia_test 时互相踩数据。
#
# 为什么需要：共用库时，"我的测试"和"别人的测试"写同一个 schema。2026-10-18 追到过一次：
# KnowledgeRead / KnowledgeSelfAssessment 在全量跑时读不到种子条目 id=1——那条是被另一个会话的
# 测试真删掉的（单类跑永远绿，全员跑才红）。一人一库就没有这个竞争。
#
# 代价可以忽略：空库跑完整 21 个迁移实测 **1.3 秒**，库本身约 1MB。
#
# 用法：
#   scripts/test-db.sh                                  # 在 backend/ 跑 mvn -B test
#   cd frontend && ../scripts/test-db.sh pnpm --filter @paideia/app test:e2e
#   scripts/test-db.sh --keep mvn -B test               # 跑完保留库（排查失败时用）
#   scripts/test-db.sh --drop paideia_run_1018194500_65184
#
# 前置：凭据在被忽略的 backend/config/remote-db.local.env 里（默认指服务器上的 paideia_e2e）。
# 本机库会派生独占库；远程库不会（账号只被授权一个库），因此**两轮测试不能同时跑**。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/backend/config/remote-db.local.env"

# 读配置，但**不覆盖调用者显式传进来的 URL**——否则"指向远程就拒绝"这条检查永远测不到，
# 也会让临时改用别的库跑变得不可能。
load_env() {
  local preset_url="${PAIDEIA_TEST_DB_URL:-}"
  [[ -f "$ENV_FILE" ]] || { echo "缺少 $ENV_FILE（被 .gitignore 忽略，需自己配）" >&2; exit 2; }
  source "$ENV_FILE"
  if [[ -n "$preset_url" ]]; then PAIDEIA_TEST_DB_URL="$preset_url"; fi
  export PAIDEIA_TEST_DB_URL
}

# 清理模式：DROP DATABASE <name>。用同一条 JDBC 驱动，不依赖本机有没有 mysql 客户端。
if [[ "${1:-}" == "--drop" ]]; then
  [[ -n "${2:-}" ]] || { echo "用法：$0 --drop <库名>" >&2; exit 2; }
  load_env
  driver="$(ls "$HOME/.m2/repository/com/mysql/mysql-connector-j"/*/mysql-connector-j-*.jar | tail -1)"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  cat >"$tmp/DropDb.java" <<'JAVA'
import java.sql.*;
public class DropDb {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("PAIDEIA_TEST_DB_URL");
        String base = url.substring(0, url.indexOf('/', url.indexOf("//") + 2));
        try (Connection c = DriverManager.getConnection(base + "/information_schema",
                System.getenv("PAIDEIA_TEST_DB_USER"), System.getenv("PAIDEIA_TEST_DB_PASSWORD"));
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + args[0] + "`");
            System.out.println("已删除 " + args[0]);
        }
    }
}
JAVA
  PAIDEIA_TEST_DB_URL="$PAIDEIA_TEST_DB_URL" java -cp "$(cygpath -w "$driver")" "$(cygpath -w "$tmp/DropDb.java")" "$2"
  exit 0
fi

KEEP=0
if [[ "${1:-}" == "--keep" ]]; then KEEP=1; shift; fi

load_env
: "${PAIDEIA_TEST_DB_URL:?还没配置 PAIDEIA_TEST_DB_URL（source backend/config/remote-db.local.env）}"

# 本机库才派生独占库名；远程测试库（2026-11-11 起的默认）账号只有 paideia_e2e 一个库的
# 权限，建不了新库，所以直接共用库上跑，并把"必须串行"这件事说清楚。
RUN_DB=""
case "$PAIDEIA_TEST_DB_URL" in
  *//127.0.0.1:*|*//localhost:*)
    RUN_DB="paideia_run_$(date +%m%d%H%M%S)_$$"
    URL_NO_QUERY="${PAIDEIA_TEST_DB_URL%%\?*}"
    QUERY="${PAIDEIA_TEST_DB_URL#*\?}"
    [[ "$QUERY" == "$PAIDEIA_TEST_DB_URL" ]] && QUERY=""   # 原 URL 没有查询串
    PREFIX="${URL_NO_QUERY%/*}"
    export PAIDEIA_TEST_DB_URL="${PREFIX}/${RUN_DB}?${QUERY:+$QUERY&}createDatabaseIfNotExist=true"
    echo "[test-db] 本次独占库：$RUN_DB"
    ;;
  *)
    echo "[test-db] 远程库 $(echo "$PAIDEIA_TEST_DB_URL" | sed -E 's#(//[^/]+/)[^?]+#\1<db>#')：不派生独占库，直接在共用库上跑。"
    echo "[test-db] 同一时刻只跑一轮：共用库会被彼此的测试删数据，单类全绿、全量跑红那种红不算证据。"
    ;;
esac

if [[ $# -eq 0 ]]; then
  cd "$ROOT/backend"
  set -- mvn -B test
fi

status=0
"$@" || status=$?

if [[ -n "$RUN_DB" ]]; then
  if [[ $status -eq 0 && $KEEP -eq 0 ]]; then
    "$0" --drop "$RUN_DB" || true
  else
    echo "[test-db] 库保留，排查用：$0 --drop $RUN_DB"
  fi
fi
exit $status

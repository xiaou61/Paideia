#!/usr/bin/env bash
# 把「systemd 单元 / Docker 容器 / 备份状态 / 定时任务结果」写成 Prometheus textfile（WORK-028）。
#
# 为什么不用 cAdvisor：它要常驻一个吃数百 MB 的守护进程，而这里只要回答
# 「unit 活没活、占多少内存、容器在不在、上次备份成不成」。20 行 shell 每分钟跑一次就够，
# 而且巡检范围就是下面的白名单常量——**不接受任何外部输入**，所以没有注入面。
#
# **原子写**：先写 .tmp 再 mv。Prometheus 恰好在写入中途抓取时会读到半行，
# 表现为随机的解析错误（一次两次会被忽略，多了就是整段指标消失）。
set -uo pipefail
# 刻意不用 set -e：某一个采集项失败（比如 docker 不在）不该让整份文件写不出来。

OUTPUT_DIR="${OUTPUT_DIR:-/var/lib/node_exporter/textfile}"
OUTPUT_FILE="$OUTPUT_DIR/ops.prom"
BACKUP_STATUS="${BACKUP_STATUS:-/opt/paideia-backup/last-status.json}"
UNITS=(paideia laya rustfs mysql redis-server loki alloy)
CONTAINER_PREFIX="firecrawl-"

mkdir -p "$OUTPUT_DIR"
temporary="$OUTPUT_FILE.tmp"
: > "$temporary"

emit() { printf '%s\n' "$*" >> "$temporary"; }

# ── systemd 单元 ────────────────────────────────────────────────────────────
emit '# HELP paideia_unit_active 1 = active, 0 = 其它状态'
emit '# TYPE paideia_unit_active gauge'
emit '# HELP paideia_unit_memory_bytes 单元当前内存占用'
emit '# TYPE paideia_unit_memory_bytes gauge'
emit '# HELP paideia_unit_memory_max_bytes 单元内存上限（0 = 未设上限）'
emit '# TYPE paideia_unit_memory_max_bytes gauge'

for unit in "${UNITS[@]}"; do
    # 一个单元查询失败不影响其它单元；用 || true 让它继续
    values=$(systemctl show "$unit" \
        --property=ActiveState,MemoryCurrent,MemoryMax 2>/dev/null) || values=""
    [ -n "$values" ] || continue
    state=$(printf '%s\n' "$values" | sed -n 's/^ActiveState=//p')
    memory=$(printf '%s\n' "$values" | sed -n 's/^MemoryCurrent=//p')
    maximum=$(printf '%s\n' "$values" | sed -n 's/^MemoryMax=//p')

    # "inactive" 时 MemoryCurrent 可能是空或 [not set]/infinity，统一归零而不是输出非法值
    case "$memory" in ''|*[!0-9]*) memory=0 ;; esac
    case "$maximum" in ''|*[!0-9]*) maximum=0 ;; esac
    active=0
    [ "$state" = "active" ] && active=1

    emit "paideia_unit_active{unit=\"$unit\"} $active"
    emit "paideia_unit_memory_bytes{unit=\"$unit\"} $memory"
    emit "paideia_unit_memory_max_bytes{unit=\"$unit\"} $maximum"
done

# ── Docker 容器 ────────────────────────────────────────────────────────────
emit '# HELP paideia_container_running 1 = Up, 0 = 其它'
emit '# TYPE paideia_container_running gauge'
emit '# HELP paideia_container_restarts 容器累计重启次数'
emit '# TYPE paideia_container_restarts gauge'

if command -v docker >/dev/null 2>&1; then
    # 只统计白名单前缀；`docker ps -a` 才看得到已退出的容器（那正是要发现的情形）
    while IFS=$'\t' read -r name running; do
        [ -n "$name" ] || continue
        case "$name" in
            "$CONTAINER_PREFIX"*) ;;
            *) continue ;;
        esac
        emit "paideia_container_running{name=\"$name\"} $running"
        restarts=$(docker inspect --format '{{.RestartCount}}' "$name" 2>/dev/null) || restarts=0
        case "$restarts" in ''|*[!0-9]*) restarts=0 ;; esac
        emit "paideia_container_restarts{name=\"$name\"} $restarts"
    done < <(docker ps -a --format '{{.Names}}\t{{.State}}' 2>/dev/null \
        | awk -F'\t' '{ print $1 "\t" ($2 == "running" ? 1 : 0) }')
fi

# ── 备份状态 ────────────────────────────────────────────────────────────────
emit '# HELP paideia_backup_success 1 = 最近一次备份成功'
emit '# TYPE paideia_backup_success gauge'
emit '# HELP paideia_backup_age_seconds 距最近一次成功备份的秒数'
emit '# TYPE paideia_backup_age_seconds gauge'
emit '# HELP paideia_backup_last_bytes 最近一次备份产物大小'
emit '# TYPE paideia_backup_last_bytes gauge'

if [ -r "$BACKUP_STATUS" ]; then
    # 只用 grep 取值：服务器上没有 jq，为读两个字段装它是本末倒置
    success=$(grep -o '"success":[a-z]*' "$BACKUP_STATUS" | cut -d: -f2)
    at=$(grep -o '"at":"[^"]*"' "$BACKUP_STATUS" | cut -d'"' -f4)
    bytes=$(grep -o '"bytes":[0-9]*' "$BACKUP_STATUS" | cut -d: -f2)
    case "$success" in true) success=1 ;; *) success=0 ;; esac
    case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
    emit "paideia_backup_success $success"
    emit "paideia_backup_last_bytes $bytes"
    # 只有成功过才有"距上次成功"可言；失败时这一项不输出，摘要页据此显示"未知"
    if [ "$success" = "1" ] && [ -n "$at" ]; then
        epoch=$(date -u -d "$at" +%s 2>/dev/null) || epoch=""
        if [ -n "$epoch" ]; then
            emit "paideia_backup_age_seconds $(( $(date +%s) - epoch ))"
        fi
    fi
fi

# ── 备份文件是否真的存在（防止 last-status 说成功但文件被手删） ──────────────
emit '# HELP paideia_backup_files 备份目录里的产物数量'
emit '# TYPE paideia_backup_files gauge'
count=$(find /opt/paideia-backup -maxdepth 1 -name 'paideia-*.sql.gz' -type f 2>/dev/null | wc -l) || count=0
emit "paideia_backup_files $count"

mv "$temporary" "$OUTPUT_FILE"

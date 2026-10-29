#!/usr/bin/env bash
# 构建 Linux 判题练习场的沙箱镜像（WORK-056）。
#
# 用法：
#   bash backend/paideia-sandbox/images/shell/build.sh          # 构建并打印 digest
#
# 构建完成后把输出里的 digest 填进：
#   - `backend/paideia-app/src/main/resources/application.yml` 的 `paideia.sandbox.shell-image`（默认留空）
#   - 部署机 `/opt/paideia-app/application.yml` 的同一项（线上是这里生效）
set -euo pipefail
TAG="${TAG:-paideia-sandbox-shell:bookworm}"

cd "$(dirname "$0")"
docker build -t "$TAG" .

echo
echo "已构建 $TAG"
echo -n "digest: "
docker inspect --format='{{index .RepoDigests 0}}' "$TAG" 2>/dev/null \
  || docker inspect --format='sha256:{{.Id}}' "$TAG"
echo
echo "自检（用真实隔离参数跑一次，确认 GNU 工具与快照都在）："
docker run --rm --network=none --read-only --tmpfs /tmp:rw,size=64m,exec \
  --user 65534:65534 --cap-drop=ALL --security-opt no-new-privileges \
  --entrypoint bash "$TAG" -c '
    set -e
    mkdir -p /tmp/work && cd /tmp/work
    printf "TOKEN=demo\n" > secrets.env
    chmod 600 secrets.env
    find . -printf "%M %s %p\n" | sort
    awk "BEGIN{print 1+1}"; grep --version | head -1; systemd-analyze --version | head -1'

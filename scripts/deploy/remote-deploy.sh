#!/usr/bin/env bash
# 远端发布状态机（WORK-092 TASK-008）。
#
# 由 scripts/release.ps1 读取本模板、替换 __XXX__ 占位符后作为部署包里的 deploy.sh 上传执行。
# 之所以外置成独立文件：发布失败的回滚路径必须有可测试的实现，内嵌在 PowerShell here-string 里
# 无法在本地隔离目录反复验证。
#
# 设计要点：
#   1. 停服后才切换文件；切换前把「当前已验证版本」（app.jar + web/ + 两份清单）快照到 .rollback/。
#   2. 健康检查未通过时把三者一起还原，输出 DEPLOY_FAILED，绝不留下「新前端 + 旧后端」。
#   3. 用 mkdir 原子目录做发布锁，第二个并发发布直接失败退出、不触碰任何文件。
#   4. 清单只在健康通过后才推进（.new -> 正式），失败发布不会让下一次误判「已最新」。
#   5. 代码可以回滚，数据库 schema 只前向；跨迁移边界不能自动回滚（见 docs/architecture/03-deployment.md）。
set -eu
cd __REMOTE_DIR__

# ── 发布锁：串行化并发发布 ─────────────────────────────────────────────────────
LOCK=__REMOTE_DIR__/.deploy.lock.d
if ! mkdir "$LOCK" 2>/dev/null; then
  holder=$(cat "$LOCK/pid" 2>/dev/null || true)
  if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
    echo "DEPLOY_FAILED 另一个发布正在进行（发布锁被 pid=$holder 持有）"
    exit 0
  fi
  # 持有者已不存在（进程被杀留下的死锁）：清掉重新抢
  rm -rf "$LOCK"
  if ! mkdir "$LOCK" 2>/dev/null; then
    echo "DEPLOY_FAILED 无法获取发布锁"
    exit 0
  fi
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

# unpack（含本脚本自己的解包）已在命令行里做过——脚本是从 deploy-in/deploy.sh 跑起来的。
# 只报 0 秒，保持 PHASE 序列完整。
echo "PHASE unpack 0"

# 跳过前端构建的前提是「服务器上那份还在且完整」：缺了就在这里拦下，别安静地发出一份没有前端的版本。
if [ ! -d deploy-in/web ] && { [ ! -f web/index.html ] || [ ! -f web/admin/index.html ]; }; then
  echo "DEPLOY_FAILED 服务器上缺 web 产物，请用 -WithWeb 重建"
  exit 0
fi

t1=$(date +%s)
if [ -f deploy-in/app-full.jar ]; then
  cp -f deploy-in/app-full.jar app.jar.new
  rm -rf exploded && mkdir exploded
  ( cd exploded && unzip -q ../app.jar.new )
  mv -f exploded/META-INF/MANIFEST.MF MANIFEST.MF
else
  # 增量路径要有 exploded/ 这个基点：缺了就只能整包，别在这里用 tar 撞一个看不懂的错
  if [ ! -d exploded ]; then
    echo "DEPLOY_FAILED 服务器上没有 exploded/ 增量基点，请在服务器上删掉 exploded.manifest 后重跑（会走整包）"
    exit 0
  fi
  if [ -f deploy-in/release-patch.tar.gz ]; then
    tar xzf deploy-in/release-patch.tar.gz -C exploded
    rm -f deploy-in/release-patch.tar.gz
  fi
  __REMOVE_CMD__
  cp -f deploy-in/MANIFEST.MF MANIFEST.MF
  __REPACK_CMD__
fi
mv -f deploy-in/exploded.manifest exploded.manifest.new
if [ -f deploy-in/web.manifest ]; then mv -f deploy-in/web.manifest web.manifest.new; fi
echo "PHASE repack $(( $(date +%s) - t1 ))"

# ── 快照「当前已验证版本」：物理停服前先拍好，失败时整体还原 ─────────────────────
t2=$(date +%s)
rm -rf .rollback && mkdir -p .rollback
cp -f app.jar .rollback/app.jar
if [ -d web ]; then cp -a web .rollback/web; fi
if [ -f exploded.manifest ]; then cp -f exploded.manifest .rollback/exploded.manifest; fi
if [ -f web.manifest ]; then cp -f web.manifest .rollback/web.manifest; fi

# 先停服务再换文件：应用在跑时换掉 app.jar 会让懒加载类找不到（见 release.ps1 文件头约束 4）
systemctl stop paideia
cp -f app.jar app.jar.prev
mv -f app.jar.new app.jar
if [ -d deploy-in/web ]; then
  rm -rf web && mv -f deploy-in/web web
elif [ -f deploy-in/web-patch.tar.gz ]; then
  # 前端增量：只解变化的文件，再按 diff 删掉服务器上多出来的（清单推进在健康检查之后）
  tar xzf deploy-in/web-patch.tar.gz -C web
  __WEB_REMOVE_CMD__
  rm -f deploy-in/web-patch.tar.gz
fi
rm -rf deploy-in
echo "PHASE swap $(( $(date +%s) - t2 ))"

t3=$(date +%s)
systemctl start paideia
echo "PHASE start $(( $(date +%s) - t3 ))"

t4=$(date +%s)
ok=no
i=0
while [ $i -lt 90 ]; do
  if curl -fsS -m 2 http://127.0.0.1:__PORT__/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then ok=yes; break; fi
  sleep 1
  i=$(( i + 1 ))
done
echo "PHASE health $(( $(date +%s) - t4 ))"
if [ "$ok" != yes ]; then
  # 整体回滚：jar、web、两份清单都恢复到发布前同一已验证版本
  cp -f .rollback/app.jar app.jar
  rm -rf web
  if [ -d .rollback/web ]; then cp -a .rollback/web web; fi
  if [ -f .rollback/exploded.manifest ]; then cp -f .rollback/exploded.manifest exploded.manifest; fi
  if [ -f .rollback/web.manifest ]; then cp -f .rollback/web.manifest web.manifest; fi
  rm -rf .rollback
  systemctl start paideia
  echo "DEPLOY_FAILED 健康检查 90 秒未就绪；已整体恢复上一版本（jar+web+清单）"
  exit 0
fi

# 走到这里才算成功：推进基线清单 + 写发版标记 + 清掉回滚快照
t5=$(date +%s)
mv -f exploded.manifest.new exploded.manifest
if [ -f web.manifest.new ]; then mv -f web.manifest.new web.manifest; fi
printf '%s' '__PROM_B64__' | base64 -d > __PROM_PATH__.tmp && mv -f __PROM_PATH__.tmp __PROM_PATH__
rm -rf .rollback
echo "PHASE marker $(( $(date +%s) - t5 ))"
echo "DEPLOY_OK"

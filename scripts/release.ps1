<#
  一条命令发版。目标：一分钟以内（每步耗时都会打出来）。

  流程：本地增量打包 -> 只打一个 tar -> 传到服务器 -> 解包 -> 重启 -> 健康检查。

  三条设计约束：
  1. **脚本里不写任何凭据与服务器地址**：连服务器走 quick-server 的别名，凭据从 Windows 凭据管理器取。
  2. **发版不碰服务器上的 application.yml**：那份配置（数据源口令、JWT 密钥、SMTP 授权码、端口）
     只存在于服务器上，用它自己的值。用本地副本覆盖它会把端口与库名改回开发值。
  3. **不依赖增量编译的正确性**：打包前先删掉 `target/classes/db`，强制资源目录重新拷贝。
     不这么做的话，删掉的迁移文件会留在 target 里继续被打进包（2026-10-10 真踩过：
     两个临时探针迁移被带上了部署）。

  用法：
    powershell -File scripts/release.ps1                 # 后端 + 前端（前端没改则自动跳过构建）
    powershell -File scripts/release.ps1 -SkipWeb        # 强制只发后端
    powershell -File scripts/release.ps1 -WithWeb        # 强制重建前端（明明没改也要重打）
    powershell -File scripts/release.ps1 -WithTests      # 发版前先跑全量后端测试
    powershell -File scripts/release.ps1 -Server <别名>  # 默认 codenest-online
#>
param(
    [switch]$SkipWeb,
    [switch]$WithWeb,
    [switch]$WithTests,
    [string]$Server = 'codenest-online',
    [int]$Port = 8081,
    [string]$RemoteDir = '/opt/paideia-app',
    [string]$QuickServerScript = (Join-Path $HOME '.codex\skills\quick-server\scripts\connect.ps1')
)

$ErrorActionPreference = 'Stop'
$total = [System.Diagnostics.Stopwatch]::StartNew()
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$staging = Join-Path $repo 'backend\paideia-app\target\release-staging'
$archive = Join-Path $repo 'backend\paideia-app\target\release.tar.gz'
$jar = Join-Path $repo 'backend\paideia-app\target\paideia-app-0.0.1-SNAPSHOT.jar'
$explodedLocal = Join-Path $repo 'backend\paideia-app\target\release-exploded'
$manifestLocal = Join-Path $staging 'MANIFEST.MF'
$manifestList = Join-Path $staging 'exploded.manifest'
$patch = Join-Path $staging 'release-patch.tar.gz'
$bsdtar = Join-Path $env:SystemRoot 'System32\tar.exe'
# 上一次成功发版时的 git 提交。用来判断"前端到底改没改"——它放在 target 里，不进仓库。
$lastReleaseMarker = Join-Path $repo 'backend\paideia-app\target\.last-release-commit'

if (-not (Test-Path $QuickServerScript)) { throw "找不到 quick-server 脚本：$QuickServerScript" }

function Step([string]$name, [scriptblock]$action) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $action
    $sw.Stop()
    Write-Host ("  {0,-28} {1,6:N1}s" -f $name, ($sw.Elapsed.TotalMilliseconds / 1000))
}

function Remote([string]$command) {
    & powershell -ExecutionPolicy Bypass -File $QuickServerScript -Server $Server -Command $command | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "远端命令失败（退出码 $LASTEXITCODE）：$command" }
}

# 远端命令并取回输出（Remote 会把输出吞掉，增量清单需要返回值）。
function RemoteOutput([string]$command) {
    $out = & powershell -ExecutionPolicy Bypass -File $QuickServerScript -Server $Server -Command $command
    if ($LASTEXITCODE -ne 0) { throw "远端命令失败（退出码 $LASTEXITCODE）：$command" }
    return $out
}

# 清单格式 v2：自家模块与 classes 用内容哈希，第三方依赖用「文件名 + 字节数」。
# 依赖升级会换版本号（文件名变），同坐标的内容 Maven 不会原地改写；这样本地清单
# 从 16 秒降到 1 秒以内，代价是依赖同名同大小却被手工改写时会漏判。
function Get-FileManifest([string]$dir) {
    $map = @{}
    Get-ChildItem -LiteralPath $dir -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($dir.Length + 1).Replace('\', '/')
        if ($rel.StartsWith('BOOT-INF/classes/') -or $rel.StartsWith('BOOT-INF/lib/paideia-')) {
            $map[$rel] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        } else {
            $map[$rel] = 'size:' + $_.Length
        }
    }
    return $map
}

function Write-ManifestFile($map, [string]$path) {
    @('#v2') + @($map.Keys | Sort-Object | ForEach-Object { "$($map[$_])  $_" }) |
        Set-Content -LiteralPath $path -Encoding utf8
}

function Upload([string]$local, [string]$remote) {
    $env:MSYS_NO_PATHCONV = '1'
    try {
        & powershell -ExecutionPolicy Bypass -File $QuickServerScript -Server $Server -Upload $local -RemotePath $remote | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "上传失败（退出码 $LASTEXITCODE）：$local" }
    } finally { Remove-Item Env:\MSYS_NO_PATHCONV -ErrorAction SilentlyContinue }
}

<#
  前端自上次发版以来有没有改过？

  **只看 frontend/ 目录**（含 packages 里的共享包）：后端改动不该让前端白重建 80 秒。
  判据两条，任一成立就算改过：提交之间动过，或工作区有未提交改动。

  判断不了的时候一律**当作改过**（返回 true）：多花 80 秒可以接受，少发一个改过的前端不可以。
  这也是 marker 不存在（第一次用这个脚本）时的行为。
#>
function Test-FrontendChanged {
    if (-not (Test-Path $lastReleaseMarker)) { return $true }
    $last = (Get-Content $lastReleaseMarker -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($last)) { return $true }

    $diff = & git -C $repo diff --name-only "$last..HEAD" -- frontend 2>$null
    if ($LASTEXITCODE -ne 0) { return $true }   # 提交不在历史里（rebase/换分支）：当作改过
    if ($diff) { return $true }

    $dirty = & git -C $repo status --porcelain -- frontend 2>$null
    if ($dirty) { return $true }
    return $false
}

Write-Host "发版到 $Server（端口 $Port，目录 $RemoteDir）"

if ($WithTests) {
    Step '后端全量测试' {
        Push-Location (Join-Path $repo 'backend')
        try { & mvn -B verify | Out-Null } finally { Pop-Location }
        # $ErrorActionPreference 管不住原生命令：mvn 失败不会抛异常，必须自己看退出码
        if ($LASTEXITCODE -ne 0) { throw "后端测试失败（退出码 $LASTEXITCODE）" }
    }
}

Step '后端打包（增量）' {
    Push-Location (Join-Path $repo 'backend')
    try {
        Remove-Item (Join-Path $repo 'backend\paideia-app\target\classes\db') -Recurse -Force -ErrorAction SilentlyContinue
        # **输出不吞**：原来 `| Out-Null` 让失败时只剩一句"退出码 1"，得再手跑一遍 mvn 才知道
        # 是 jar 被占用还是编译错。现在只过滤到 ERROR 行，正常构建照样安静。
        $build = & mvn -B package -DskipTests 2>&1
        $code = $LASTEXITCODE
        if ($code -ne 0) {
            ($build | Select-String -Pattern 'ERROR' | Select-Object -First 20) | ForEach-Object { Write-Host "    $_" }
        }
    } finally { Pop-Location }
    # 不检查的话：打包失败时 target 里仍是上一次的旧 JAR，Copy-Item 照样成功，
    # 最后把旧产物当新版本发出去——"发版成功但线上没生效"
    if ($code -ne 0) {
        # jar 被本地在跑的后端占着是这台机器上最常见的原因，直接点名怎么处理
        throw "后端打包失败（退出码 $code）。若提示 Unable to rename *.jar：先关掉本机在跑的旧后端（netstat -ano | findstr :8080）"
    }
}

# 前端只在真的改过时才重建。实测重建一次要 80 秒（其中约 49 秒是 vite-plugin-pwa
# 生成 Service Worker 清单），而一次纯后端发版总共才 70 秒上下——不跳过的话它比整个发版还贵。
$buildWeb = if ($SkipWeb) { $false } elseif ($WithWeb) { $true } else { Test-FrontendChanged }

if ($buildWeb) {
    Step '前端构建（学习者端 + 管理端）' {
        Push-Location (Join-Path $repo 'frontend')
        try {
            & pnpm --filter '@paideia/app' build | Out-Null
            # 同 mvn：pnpm 也是原生命令，构建失败时旧 dist 仍会被打进包并发出去
            if ($LASTEXITCODE -ne 0) { throw "学习者端构建失败（退出码 $LASTEXITCODE）" }
            & pnpm --filter '@paideia/admin' build | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "管理端构建失败（退出码 $LASTEXITCODE）" }
        } finally { Pop-Location }
    }
} else {
    if ($SkipWeb) {
        Write-Host '  （跳过前端构建：-SkipWeb）'
    } else {
        Write-Host '  （跳过前端构建：frontend/ 自上次发版以来没有改动；要强制重建用 -WithWeb）'
    }
    if (-not (Test-Path (Join-Path $repo 'frontend\apps\app\dist\index.html'))) {
        throw '前端没有可用的 dist，却又被跳过了构建：第一次发版请去掉 -SkipWeb，或用 -WithWeb'
    }
}

Step '打包 staging 并压缩 web' {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    # 只有真要发前端时才把 dist 拷进 staging 并压包。纯后端发版完全跳过这一步，
    # 省掉"拷 325 个文件 + 压 102MB"以及一次多余的上传。
    if ($buildWeb) {
        New-Item -ItemType Directory -Path (Join-Path $staging 'web\admin') -Force | Out-Null
        Copy-Item (Join-Path $repo 'frontend\apps\app\dist\*') (Join-Path $staging 'web') -Recurse -Force
        Copy-Item (Join-Path $repo 'frontend\apps\admin\dist\*') (Join-Path $staging 'web\admin') -Recurse -Force
        Remove-Item $archive -Force -ErrorAction SilentlyContinue
        # 必须用 Windows 自带的 bsdtar：从 Git Bash 继承 PATH 时会拿到 GNU tar，
        # 它把 `F:\...` 当成"远程主机:路径"并报 Cannot connect to F: resolve failed。
$bsdtar = Join-Path $env:SystemRoot 'System32\tar.exe'
        if (-not (Test-Path $bsdtar)) { throw "找不到 $bsdtar" }
        # web 那 24 MB（325 个文本文件）压完只剩 4.9 MB，几乎不花时间。
        & $bsdtar --options "gzip:compression-level=1" -czf $archive -C $staging web
        if ($LASTEXITCODE -ne 0) { throw '压缩 web 失败' }
        if (-not (Test-Path $archive)) { throw '压缩产物不存在' }
    }
}

# **增量上传**（2026-10-22 实测）：82 MB 的 fat jar 里自家模块 + classes 只有 1.4 MB，
# 其余 84 MB 是第三方依赖。链路约 3.2 MB/s，整包传要 32 秒；按内容哈希只传变化的文件，
# 再在服务器上用 /opt/jdk25/bin/jar 重新打包，通常只需 1–2 MB。
# 首次（服务器还没有基线）仍传整包，并在远端解包建立基点。
Step '上传 jar' {
    Remove-Item $explodedLocal -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $explodedLocal -Force | Out-Null
    New-Item -ItemType Directory -Path $staging -Force | Out-Null

    & $bsdtar -xf $jar -C $explodedLocal
    if ($LASTEXITCODE -ne 0) { throw '解包 jar 失败' }
    $mf = Join-Path $explodedLocal 'META-INF\MANIFEST.MF'
    if (-not (Test-Path $mf)) { throw 'jar 里没有 MANIFEST.MF' }
    Move-Item $mf $manifestLocal -Force

    $local = Get-FileManifest $explodedLocal
    Write-ManifestFile $local $manifestList

    $remoteLines = @(RemoteOutput "cat $RemoteDir/exploded.manifest 2>/dev/null || true")
    $remote = @{}
    if ($remoteLines.Count -gt 0 -and $remoteLines[0].Trim() -eq '#v2') {
        foreach ($line in $remoteLines) {
            if ($line -match '^(size:\d+|[0-9a-f]{64})\s+(.+)$') { $remote[$matches[2]] = $matches[1] }
        }
    }
    if ($remote.Count -eq 0) {
        Write-Host '  （首次或清单格式升级：整包上传，并在服务器建立增量基线）'
        Upload $jar "$RemoteDir/app.jar.new"
        Remote "cd $RemoteDir && rm -rf exploded && mkdir exploded && cd exploded && unzip -q ../app.jar.new && mv META-INF/MANIFEST.MF ../MANIFEST.MF && echo seeded"
        Upload $manifestList "$RemoteDir/exploded.manifest"
        Upload $manifestLocal "$RemoteDir/MANIFEST.MF"
        return
    }

    $changed = @($local.Keys | Where-Object { $remote[$_] -ne $local[$_] })
    $removed = @($remote.Keys | Where-Object { -not $local.ContainsKey($_) })
    Write-Host ("  变化 {0} 个文件 / 删除 {1} 个（共 {2}）" -f $changed.Count, $removed.Count, $local.Count)

    if ($changed.Count -gt 0) {
        $listFile = Join-Path $staging 'changed-files.txt'
        $changed | Set-Content -LiteralPath $listFile -Encoding utf8
        Remove-Item $patch -Force -ErrorAction SilentlyContinue
        & $bsdtar --options "gzip:compression-level=1" -czf $patch -C $explodedLocal -T $listFile
        if ($LASTEXITCODE -ne 0) { throw '打包增量失败' }
        Upload $patch "$RemoteDir/release-patch.tar.gz"
        Upload $manifestList "$RemoteDir/exploded.manifest.new"
    }
    Upload $manifestLocal "$RemoteDir/MANIFEST.MF"

    $removeCmd = if ($removed.Count -gt 0) {
        "rm -f -- " + (($removed | ForEach-Object { "'$_'" }) -join ' ')
    } else { 'true' }
    $extract = if ($changed.Count -gt 0) { 'tar xzf release-patch.tar.gz -C exploded && rm -f release-patch.tar.gz' } else { 'true' }
    $promote = if ($changed.Count -gt 0) { 'mv -f exploded.manifest.new exploded.manifest' } else { 'true' }
    # 没有任何变化时不必重打包：当前 app.jar 就是基线，直接复制成待切换文件
    $buildCmd = if ($changed.Count -eq 0 -and $removed.Count -eq 0) {
        'cp -f app.jar app.jar.new'
    } else {
        '/opt/jdk25/bin/jar c0fm app.jar.new MANIFEST.MF -C exploded .'
    }
    Remote "cd $RemoteDir && $extract && (cd exploded && $removeCmd) && $promote && $buildCmd && echo repacked"
}

if ($buildWeb) {
    Step '上传 web 包' { Upload $archive "$RemoteDir/release.tar.gz" }
}

Step '远端就位' {
    # 所有上传都成功了才动线上文件。web 目录先清空再解：旧版本删掉的文件（改名的 chunk）
    # 不该留在服务器上——那是"部署成功但界面还在跑旧代码"的经典来源。
    if ($buildWeb) {
        Remote "cd $RemoteDir && mv -f app.jar.new app.jar && rm -rf web && tar xzf release.tar.gz && rm -f release.tar.gz && echo swapped"
    } else {
        # 跳过 web 的前提是"服务器上那份还在且完整"。少了这个检查，一旦 web 被清掉过，
        # 跳过策略就会安静地发出一份**没有前端**的版本——线上只剩 API，界面全 404。
        Remote "cd $RemoteDir && { test -f web/index.html && test -f web/admin/index.html; } || { echo 'ERR: 服务器上缺 web 产物，请用 -WithWeb 重建'; exit 3; }; mv -f app.jar.new app.jar && echo swapped"
    }
}

Step '重启服务' { Remote 'systemctl restart paideia' }

Step '等待健康检查' {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $out = & powershell -ExecutionPolicy Bypass -File $QuickServerScript -Server $Server `
            -Command "curl -fsS -m 4 http://127.0.0.1:$Port/actuator/health" 2>$null
        if ($out -match '"status":"UP"') { return }
    }
    throw '健康检查超时：服务未在 60 秒内就绪'
}

# 发版标记（WORK-042）：写进 node_exporter 的 textfile 目录，供 Prometheus 与 AI 诊断读取
# "最近一次成功发版是什么时候、哪个版本"。**只在健康检查通过后写**；写失败只告警，
# 不让一次标记失败把已经成功的发版判成失败。
$head = (& git -C $repo rev-parse HEAD 2>$null)
$version = if ($LASTEXITCODE -eq 0 -and $head) { $head.Trim().Substring(0, 7) } else { 'unknown' }
$epoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$deployProm = @"
# HELP paideia_deploy_epoch_seconds 最近一次成功发版的 Unix 时间
# TYPE paideia_deploy_epoch_seconds gauge
paideia_deploy_epoch_seconds $epoch
# HELP paideia_build_info 当前运行的版本
# TYPE paideia_build_info gauge
paideia_build_info{version="$version"} 1
"@
$deployB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($deployProm))
try {
    Step '写发版标记' {
        Remote "echo $deployB64 | base64 -d > /var/lib/node_exporter/textfile/deploy.prom.tmp && mv -f /var/lib/node_exporter/textfile/deploy.prom.tmp /var/lib/node_exporter/textfile/deploy.prom"
    }
} catch {
    Write-Warning "发版标记写入失败（不影响本次部署）：$($_.Exception.Message)"
}

$total.Stop()

# 只有走到这里（解包 + 重启 + 健康检查全过了）才更新标记：失败的发布不能让下一次
# 误以为"前端已经是最新的"。即使这次跳过了前端构建也照样写——因为跳过的前提正是
# "frontend/ 自上次发版起没动过"，所以线上那份 dist 与 HEAD 的前端内容是一致的。
# git rev-parse 失败时留空，下次 Test-FrontendChanged 会当作"改过"而重建（安全方向）。
# 上一次成功发版的提交同上（$head 在上面的发版标记里已经取过）。
if ($LASTEXITCODE -eq 0 -and $head) {
    Set-Content -Path $lastReleaseMarker -Value $head.Trim() -NoNewline
}

Write-Host ("完成：{0:N1} 秒" -f $total.Elapsed.TotalSeconds)

<#
  一条命令发版。目标：一分钟以内（每步耗时都会打出来）。

  流程：本地增量打包 -> 一个部署包（web + 增量补丁 + 清单）-> 一次上传 ->
        远端一条脚本跑完（换文件 -> 重启 -> 等健康 -> 写发版标记）。

  四条设计约束：
  1. **脚本里不写任何凭据与服务器地址**：连服务器走 quick-server 的别名，凭据从 Windows 凭据管理器取。
  2. **发版不碰服务器上的 application.yml**：那份配置（数据源口令、JWT 密钥、SMTP 授权码、端口）
     只存在于服务器上，用它自己的值。用本地副本覆盖它会把端口与库名改回开发值。
  3. **不依赖增量编译的正确性**：打包前先删掉 `target/classes/db`，强制资源目录重新拷贝。
     不这么做的话，删掉的迁移文件会留在 target 里继续被打进包（2026-10-10 真踩过：
     两个临时探针迁移被带上了部署）。
  4. **远端只开两次 SSH 连接，且先停服务再换文件**（2026-11-02 改，原因见下）。
     Windows 上没法做连接复用（ControlMaster 传不了 socket fd），每次连接 5.7 秒起步；
     而"边跑边换 jar"会让关机路径上的懒加载类抛 NoClassDefFoundError。

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
$jar = Join-Path $repo 'backend\paideia-app\target\paideia-app-0.0.1-SNAPSHOT.jar'
$explodedLocal = Join-Path $repo 'backend\paideia-app\target\release-exploded'
$manifestLocal = Join-Path $staging 'MANIFEST.MF'
$manifestList = Join-Path $staging 'exploded.manifest'
$patch = Join-Path $staging 'release-patch.tar.gz'
$webPatch = Join-Path $staging 'web-patch.tar.gz'
$webPatchDir = Join-Path $staging 'web-patch'
$webManifestLocal = Join-Path $staging 'web.manifest'
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

# 同上，但**不因退出码抛异常**：把退出码与输出一起交回调用方。用在"可读的错误信息
# 在远端脚本里"的场景——否则一次失败会把整段 base64 命令糊进报错，等于没有错误信息。
function RemoteScript([string]$command) {
    $out = & powershell -ExecutionPolicy Bypass -File $QuickServerScript -Server $Server -Command $command
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Lines = @($out) }
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
    # 固定 LF + 无 BOM：这份清单两端都要解析（远端脚本，以及下一次发版的本地解析）。
    # Set-Content 在 Windows 上写 CRLF，行尾的 \r 会混进"文件路径"这个键里——两端都带
    # 还能撞对，只一端带就是"468 个文件全变了"（等于整包重传 + 全量重打包），所以写死 LF。
    $lines = @('#v2') + @($map.Keys | Sort-Object | ForEach-Object { "$($map[$_])  $_" })
    [System.IO.File]::WriteAllText($path, ($lines -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
}

# 内容 SHA256（.NET 流式）。Get-FileHash 每次调用的 cmdlet 开销在近千个小文件上累积很明显。
function Get-ContentHash([string]$path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fs = [System.IO.File]::OpenRead($path)
        try { return [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '').ToLowerInvariant() }
        finally { $fs.Dispose() }
    } finally { $sha.Dispose() }
}

<#
  web 清单（2026-11-02）：把两份 dist 映射到服务器上 web/ 的相对路径——学习者端在根、
  管理端在 admin/。哈希用内容 SHA256，远端靠它做增量 diff：改一次前端只传变化的文件，
  不再把 920 个文件（32 MB）整个重传、整个 rm -rf 替换。

  为什么不照 jar 清单那样分"自家/第三方"两档：web 产物全是自家内容，没有"依赖不改"
  这个前提可用；全量算下来不到一秒，没必要分层。
#>
function Get-WebManifest {
    $map = @{}
    foreach ($pair in @(
            @{ Dir = (Join-Path $repo 'frontend\apps\app\dist'); Prefix = '' },
            @{ Dir = (Join-Path $repo 'frontend\apps\admin\dist'); Prefix = 'admin/' })) {
        if (-not (Test-Path $pair.Dir)) { throw "找不到前端产物目录：$($pair.Dir)（先构建，或别用 -SkipWeb）" }
        foreach ($file in Get-ChildItem -LiteralPath $pair.Dir -Recurse -File) {
            $rel = $pair.Prefix + $file.FullName.Substring($pair.Dir.Length + 1).Replace('\', '/')
            $map[$rel] = Get-ContentHash $file.FullName
        }
    }
    return $map
}

# web 相对路径 → 本地 dist 源文件（打增量补丁用）
function Resolve-WebSource([string]$rel) {
    if ($rel.StartsWith('admin/')) {
        return Join-Path (Join-Path $repo 'frontend\apps\admin\dist') ($rel.Substring(6).Replace('/', '\'))
    }
    return Join-Path (Join-Path $repo 'frontend\apps\app\dist') ($rel.Replace('/', '\'))
}

# #v2 清单文本 → map（jar 清单与 web 清单同一格式，解析共用）
function Parse-ManifestLines($lines) {
    $map = @{}
    if ($lines.Count -gt 0 -and $lines[0].Trim() -eq '#v2') {
        foreach ($line in $lines) {
            # TrimEnd：服务器上那份可能是别的行尾（CRLF），别让 \r 混进路径键
            if ($line.TrimEnd() -match '^(size:\d+|[0-9a-f]{64})\s+(.+)$') { $map[$matches[2]] = $matches[1] }
        }
    }
    return $map
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

<#
  后端自上次发版以来有没有改过？与 Test-FrontendChanged 同一条纪律：**判断不了一律当作改过**。
  但"没改过"只是跳过打包的必要条件之一——还要 target 里的 jar 与上次发出去的那份指纹一致
  （别的会话跑过 mvn 就会覆盖 target，光看 git 看不出来）。见打包步骤前的 skipPackaging。
#>
function Test-BackendChanged {
    if (-not (Test-Path $lastReleaseMarker)) { return $true }
    $last = (Get-Content $lastReleaseMarker -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($last)) { return $true }

    $diff = & git -C $repo diff --name-only "$last..HEAD" -- backend 2>$null
    if ($LASTEXITCODE -ne 0) { return $true }   # 提交不在历史里（rebase/换分支）：当作改过
    if ($diff) { return $true }

    $dirty = & git -C $repo status --porcelain -- backend 2>$null
    if ($dirty) { return $true }
    return $false
}

# 用 .NET 流式 SHA256：Get-FileHash 在 PS 5.1 上算 90 MB 要好几秒，这个不到一秒
function Get-JarFingerprint([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fs = [System.IO.File]::OpenRead($path)
        try { return [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '') }
        finally { $fs.Dispose() }
    } finally { $sha.Dispose() }
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

$jarMarker = Join-Path (Split-Path $jar) '.last-release-jar'
# 跳过打包的前提是"target 里的 jar 与上次发出去的那份逐字节一致"（指纹比对）：
# 别的构建/会话覆盖过 target 时指纹对不上，会老老实实重打——不会把旧产物当新的发出去。
# -WithTests 时 verify 必然重建 jar，直接照常打包。
$skipPackaging = $false
if (-not $WithTests -and -not (Test-BackendChanged)) {
    $recorded = if (Test-Path $jarMarker) { (Get-Content $jarMarker -Raw).Trim() } else { '' }
    $currentJar = Get-JarFingerprint $jar
    if ($recorded -ne '' -and $currentJar -ne '' -and $currentJar -eq $recorded) {
        $skipPackaging = $true
    }
}

if ($skipPackaging) {
    Write-Host '  （跳过后端打包：backend/ 自上次发版以来没有改动，且 target 里的 jar 与上次发出去的一致）'
} else {
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

$bundle = Join-Path $staging 'deploy-bundle.tar.gz'

# 必须用 Windows 自带的 bsdtar：从 Git Bash 继承 PATH 时会拿到 GNU tar，
# 它把 `F:\...` 当成"远程主机:路径"并报 Cannot connect to F: resolve failed。
$bsdtar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $bsdtar)) { throw "找不到 $bsdtar" }

Step '解包 jar 并算清单' {
    Remove-Item $explodedLocal -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $explodedLocal -Force | Out-Null
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    Remove-Item (Join-Path $staging 'web') -Recurse -Force -ErrorAction SilentlyContinue

    & $bsdtar -xf $jar -C $explodedLocal
    if ($LASTEXITCODE -ne 0) { throw '解包 jar 失败' }
    $mf = Join-Path $explodedLocal 'META-INF\MANIFEST.MF'
    if (-not (Test-Path $mf)) { throw 'jar 里没有 MANIFEST.MF' }
    Move-Item $mf $manifestLocal -Force

    # 注意用 $script: 前缀：Step 的脚本块是**子作用域**，直接赋值出去下一个步骤读不到
    $script:local = Get-FileManifest $explodedLocal
    Write-ManifestFile $script:local $manifestList
}

# 远端基线清单（#v2：自家内容用 SHA256，第三方依赖用"文件名 + 字节数"）。本地清单与它
# 比出"变过 / 删掉"的文件，只把这些打进部署包——82 MB 的 fat jar 里自家内容只有 1.4 MB。
$script:firstTime = $false
$script:changed = @()
$script:removed = @()
$script:remoteWeb = @{}
$script:webBaseline = $false
$script:webLocal = @{}
$script:webChanged = @()
$script:webRemoved = @()
Step '取远端基线' {
    # 两份清单一次取回（中间用哨兵行分隔，省掉一次 5.7 秒的 SSH 握手）：
    # exploded.manifest 管 jar 增量，web.manifest 管前端产物增量
    $remoteLines = @(RemoteOutput "cat $RemoteDir/exploded.manifest 2>/dev/null || true; echo __WEB__; cat $RemoteDir/web.manifest 2>/dev/null || true")
    $split = -1
    for ($i = 0; $i -lt $remoteLines.Count; $i++) {
        if ($remoteLines[$i].Trim() -eq '__WEB__') { $split = $i; break }
    }
    $jarLines = if ($split -lt 0) { $remoteLines } elseif ($split -eq 0) { @() } else { @($remoteLines[0..($split - 1)]) }
    $webLines = if ($split -ge 0 -and $split + 1 -lt $remoteLines.Count) { @($remoteLines[($split + 1)..($remoteLines.Count - 1)]) } else { @() }

    $remote = Parse-ManifestLines $jarLines
    # 别用 @() 包 hashtable——那会变成"含一个 hashtable 的单元素数组"，Count 与索引全错
    $script:remoteWeb = Parse-ManifestLines $webLines
    $script:webBaseline = $script:remoteWeb.Count -gt 0

    if ($remote.Count -eq 0) {
        $script:firstTime = $true
        Write-Host '  （首次或清单格式升级：整包上传，并在服务器建立增量基线）'
        return
    }
    $script:changed = @($script:local.Keys | Where-Object { $remote[$_] -ne $script:local[$_] })
    $script:removed = @($remote.Keys | Where-Object { -not $script:local.ContainsKey($_) })
    Write-Host ("  变化 {0} 个文件 / 删除 {1} 个（共 {2}）" -f $script:changed.Count, $script:removed.Count, $script:local.Count)
}

Step '打部署包' {
    if ($script:firstTime) {
        # 首次没有基线，只能整包（91 MB）。这一趟慢是应该的，第二趟起走增量。
        Copy-Item $jar (Join-Path $staging 'app-full.jar') -Force
    } elseif ($script:changed.Count -gt 0) {
        $listFile = Join-Path $staging 'changed-files.txt'
        $script:changed | Set-Content -LiteralPath $listFile -Encoding utf8
        Remove-Item $patch -Force -ErrorAction SilentlyContinue
        & $bsdtar --options "gzip:compression-level=1" -czf $patch -C $explodedLocal -T $listFile
        if ($LASTEXITCODE -ne 0) { throw '打包增量失败' }
    }

    # 一个包、一次上传。原来 web 单独压一份 tar.gz 再单独传一次 = 多压一遍 + 多一次 SSH。
    $members = @()
    # 前端只在真重建过时才进包；没重建时由远端自己检查"服务器上那份还在不在且完整"。
    # 重建时也不再全量：拿服务器上的 web.manifest 做 diff，只传变化的文件（2026-11-02）。
    if ($buildWeb) {
        $script:webLocal = Get-WebManifest
        $script:webChanged = @($script:webLocal.Keys | Where-Object { $script:remoteWeb[$_] -ne $script:webLocal[$_] })
        $script:webRemoved = @($script:remoteWeb.Keys | Where-Object { -not $script:webLocal.ContainsKey($_) })

        if (-not $script:webBaseline) {
            # 服务器上还没有 web 清单（首次/刚换上新逻辑）：只能全量替换，并在这次建立基线
            New-Item -ItemType Directory -Path (Join-Path $staging 'web\admin') -Force | Out-Null
            Copy-Item (Join-Path $repo 'frontend\apps\app\dist\*') (Join-Path $staging 'web') -Recurse -Force
            Copy-Item (Join-Path $repo 'frontend\apps\admin\dist\*') (Join-Path $staging 'web\admin') -Recurse -Force
            $members += 'web'
            $members += 'web.manifest'
            Write-Host ("  web 首次：全量 {0} 个文件（服务器上还没有 web 基线清单）" -f $script:webLocal.Count)
        } elseif ($script:webChanged.Count -gt 0 -or $script:webRemoved.Count -gt 0) {
            Remove-Item $webPatchDir -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Path $webPatchDir -Force | Out-Null
            foreach ($rel in $script:webChanged) {
                $dst = Join-Path $webPatchDir ($rel.Replace('/', '\'))
                New-Item -ItemType Directory -Path (Split-Path $dst) -Force | Out-Null
                Copy-Item (Resolve-WebSource $rel) $dst -Force
            }
            Remove-Item $webPatch -Force -ErrorAction SilentlyContinue
            & $bsdtar --options "gzip:compression-level=1" -czf $webPatch -C $webPatchDir .
            if ($LASTEXITCODE -ne 0) { throw '打包前端增量失败' }
            $members += 'web-patch.tar.gz'
            $members += 'web.manifest'
            Write-Host ("  web 变化 {0} 个文件 / 删除 {1} 个（共 {2}）" -f $script:webChanged.Count, $script:webRemoved.Count, $script:webLocal.Count)
        } else {
            Write-Host ("  （前端没有变化：{0} 个文件与服务器一致，不传 web）" -f $script:webLocal.Count)
        }
        if ($members -contains 'web.manifest') {
            Write-ManifestFile $script:webLocal $webManifestLocal
        }
    }
    if ($script:firstTime) { $members += 'app-full.jar' }
    elseif ($script:changed.Count -gt 0) { $members += 'release-patch.tar.gz' }
    $members += 'exploded.manifest'
    $members += 'MANIFEST.MF'

    # 压缩交给 7-Zip（**-mmt 必须写数字**，`-mmt=on` 对 gzip 编码器不并行）。选它不是因为
    # gzip 本身慢——同一份 32 MB 前端产物，单线程 bsdtar 干净时 2.4s、受 Defender/负载干扰时
    # 实测拖到 31~114s（同类命令两轮差 4 倍）；多线程 7z 同一时刻 3~5s 且包小 8%（7.8 vs 8.5 MB）。
    # 产物仍是标准 gzip，服务器端解包不动；本机没有 7z 时回退 bsdtar。
    Remove-Item $bundle -Force -ErrorAction SilentlyContinue
    $sevenZip = (Get-Command 7z -ErrorAction SilentlyContinue).Source
    if ($sevenZip) {
        $bundleTar = Join-Path $staging 'deploy-bundle.tar'
        Remove-Item $bundleTar -Force -ErrorAction SilentlyContinue
        & $bsdtar -cf $bundleTar -C $staging @members
        if ($LASTEXITCODE -ne 0) { throw '打部署包（tar）失败' }
        & $sevenZip a -tgzip -mx1 -mmt=16 -bsp0 -y $bundle $bundleTar | Out-Null
        $zipExit = $LASTEXITCODE
        Remove-Item $bundleTar -Force -ErrorAction SilentlyContinue
        if ($zipExit -ne 0) { throw "打部署包（7z）失败：退出码 $zipExit" }
    } else {
        & $bsdtar --options "gzip:compression-level=1" -czf $bundle -C $staging @members
        if ($LASTEXITCODE -ne 0) { throw '打部署包失败' }
    }
    if (-not (Test-Path $bundle)) { throw '部署包不存在' }
    Write-Host ("  包内：{0}（{1:N1} MB）" -f ($members -join ' + '), ((Get-Item $bundle).Length / 1MB))
}

# ── 上传并应用：两次 SSH 搞定整轮远端动作（2026-11-02 改）────────────────────────
# 原实现把"取基线 / 传补丁 / 传两个清单 / 传 web / 就位 / 重启 / 健康轮询 / 写标记"
# 拆成 16 处独立 SSH 调用。Windows 上做不了连接复用（ControlMaster 传不了 socket fd），
# 实测每次连接 5.7 秒起步——TCP 到 22 端口只要 0.03 秒、服务器 usedns 已是 no、切 Clash
# 直连也没改善，所以那 16 次握手就是 90 秒起步的纯空等。现在远端只剩两次连接。
#
# 顺序也改了：原来先 `mv app.jar.new app.jar`、隔着一次 SSH 才 restart，应用在那段窗口里
# 用着被换掉的 jar 跑。2026-11-02 实测那次停机 31 秒，日志里
# `ClassNotFoundException: org.apache.catalina.Lifecycle$SingleUse` 与
# `NoClassDefFoundError ... PoolingHttpClientConnectionManager$2` 都是关机路径上才懒加载
# 的类——干净停机（没换过 jar）实测 0 秒。现在**先停服务、再换文件、再启动**，窗口消失。
#
# 健康检查也搬到远端自己循环：原来每轮都要从本机开一个 PowerShell + 一次 SSH。
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
# Prometheus 的 textfile 只接受 LF：PowerShell 的 here-string 是 CRLF，直接写会让整份文件
# 以 "unknown metric type \"gauge\r\"" 被拒绝（2026-10-26 实测）。末尾也必须有换行。
$deployProm = ($deployProm -replace "`r`n", "`n").TrimEnd("`n") + "`n"
$deployB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($deployProm))

$removeCmd = if ($script:removed.Count -gt 0) {
    "( cd exploded && rm -f -- " + (($script:removed | ForEach-Object { "'$_'" }) -join ' ') + " )"
} else { 'true' }
# 前端增量的删除清单（web 相对路径，如 admin/assets/old.js）
$webRemoveCmd = if ($script:webRemoved.Count -gt 0) {
    "( cd web && rm -f -- " + (($script:webRemoved | ForEach-Object { "'$_'" }) -join ' ') + " )"
} else { 'true' }
# 没有任何变化时不必重打包：当前 app.jar 就是基线，直接复制成待切换文件
$repackCmd = if ($script:changed.Count -eq 0 -and $script:removed.Count -eq 0) {
    'cp -f app.jar app.jar.new'
} else {
    '/opt/jdk25/bin/jar c0fm app.jar.new MANIFEST.MF -C exploded .'
}

# 远端脚本以 base64 传过去执行：省掉引号与换行在 SSH 命令行里的层层转义。
# 清单只在健康检查通过后才推进（.new -> 正式），失败的部署不会让下一次误判"已经是最新的"。
$remoteScript = @'
set -eu
cd __REMOTE_DIR__
t0=$(date +%s)
rm -rf deploy-in && mkdir deploy-in
tar xzf deploy-bundle.tar.gz -C deploy-in
rm -f deploy-bundle.tar.gz
echo "PHASE unpack $(( $(date +%s) - t0 ))"

# 跳过前端构建的前提是"服务器上那份还在且完整"：缺了就在这里拦下，
# 别安静地发出一份没有前端的版本。
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
# web 清单同样先放 .new：健康检查过了才推进，失败的部署不会让下一次误判"服务器已是最新"
if [ -f deploy-in/web.manifest ]; then mv -f deploy-in/web.manifest web.manifest.new; fi
echo "PHASE repack $(( $(date +%s) - t1 ))"

# **先停服务再换文件**（见文件头约束 4）：应用在跑时换掉 app.jar 会让懒加载类找不到
t2=$(date +%s)
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
  echo "DEPLOY_FAILED 健康检查 90 秒未就绪；回滚：cd __REMOTE_DIR__ && mv -f app.jar.prev app.jar && systemctl start paideia"
  exit 0
fi

# 走到这里才算成功：推进基线清单 + 写发版标记
t5=$(date +%s)
mv -f exploded.manifest.new exploded.manifest
if [ -f web.manifest.new ]; then mv -f web.manifest.new web.manifest; fi
printf '%s' '__PROM_B64__' | base64 -d > /var/lib/node_exporter/textfile/deploy.prom.tmp && mv -f /var/lib/node_exporter/textfile/deploy.prom.tmp /var/lib/node_exporter/textfile/deploy.prom
echo "PHASE marker $(( $(date +%s) - t5 ))"
echo "DEPLOY_OK"
'@
$remoteScript = $remoteScript.Replace('__REMOTE_DIR__', $RemoteDir).Replace('__PORT__', "$Port").Replace('__REMOVE_CMD__', $removeCmd).Replace('__WEB_REMOVE_CMD__', $webRemoveCmd).Replace('__REPACK_CMD__', $repackCmd).Replace('__PROM_B64__', $deployB64)
# 工作区是 CRLF（git autocrlf），bash 会把行尾的 \r 当成命令的一部分。2026-11-02 第一次
# 跑就是这么炸的：`cd: $'/opt/paideia-app\r': No such file or directory` + `set: -: invalid option`。
$remoteScript = $remoteScript.Replace("`r`n", "`n")
$scriptB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))

$script:deployError = ''
Step '上传并应用（远端一条脚本：换文件、重启、等健康、写标记）' {
    Upload $bundle "$RemoteDir/deploy-bundle.tar.gz"
    $r = RemoteScript "echo $scriptB64 | base64 -d > /tmp/paideia-deploy.sh && bash /tmp/paideia-deploy.sh"
    foreach ($line in $r.Lines) {
        if ($line -match '^\s*PHASE\s+(\S+)\s+(\d+)\s*$') {
            Write-Host ("    {0,-8} {1,4}s" -f $Matches[1], $Matches[2])
        } elseif ($line -match 'DEPLOY_FAILED\s+(.+?)\s*$') {
            $script:deployError = $Matches[1]
        }
    }
    if ($r.ExitCode -ne 0) {
        $tail = ($r.Lines | Where-Object { $_ -match '\S' } | Select-Object -Last 3) -join ' / '
        throw "远端脚本退出码 $($r.ExitCode)：$tail"
    }
    if (-not ($r.Lines -match 'DEPLOY_OK')) {
        throw "远端部署未成功。$script:deployError"
    }
}



$total.Stop()

# 走到这里才算成功：远端那条脚本只有在"换文件 + 重启 + 健康检查"全过之后才会回 DEPLOY_OK
# （写不成功会 throw 在上面那一步）。即使这次跳过了前端构建也照样写——跳过的前提正是
# "frontend/ 自上次发版起没动过"，所以线上那份 dist 与 HEAD 的前端内容一致。
# git rev-parse 失败时留空，下次 Test-FrontendChanged 会当作"改过"而重建（安全方向）。
if ($head) {
    Set-Content -Path $lastReleaseMarker -Value $head.Trim() -NoNewline
}
# jar 指纹：下次 backend/ 没改时靠它决定能否跳过打包（与 skippedPackaging 判定同一份算法）
$finalJar = Get-JarFingerprint $jar
if ($finalJar -ne '') {
    Set-Content -Path $jarMarker -Value $finalJar -NoNewline
}

Write-Host ("完成：{0:N1} 秒" -f $total.Elapsed.TotalSeconds)

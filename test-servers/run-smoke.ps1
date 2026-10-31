# RadSurvival 跨服务端极简冒烟测试
#
# 回答四个问题（每服独立目录、独立端口、独立 JVM，互不干扰）：
#   1. 插件能不能在这个分支上加载 / 会不会被我们的防御分支拒绝？
#   2. ServerBrand 识别结果对不对（类探测 vs getName，来源是否合理）？
#   3. 主循环跑不跑得动（有没有调度器异常、有没有异常栈）？
#   4. 伤害落不落地（用 /rad status 的服务端口径验证一整条判定链）？
#
# 不做的事：不跑 17 项人工验收手册，不做客户端侧验证。
# 用法：
#   .\run-smoke.ps1 -Server paper            # 单跑一个
#   .\run-smoke.ps1 -Server all              # 全部
#   .\run-smoke.ps1 -Server leaf,folia       # 指定多个

param(
    [string[]]$Server = @('paper'),
    [int]$StartupTimeoutSec = 180,
    [int]$MemoryMb = 768,
    [string]$root = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# 用 scriptblock 加载时 $PSScriptRoot 为空，故允许外部显式指定根目录
if (-not $root) { $root = $PSScriptRoot }
if (-not $root) { $root = (Get-Location).Path }
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\java21' }
$javaExe = Join-Path $javaHome 'bin\java.exe'

# 每服端口分配（避开已在用的 25565/25764 —— 那是 test-server 的口径）
$portMap = @{
    paper  = @{ Game = 25570; Rcon = 25770 }
    leaf   = @{ Game = 25571; Rcon = 25771 }
    purpur = @{ Game = 25572; Rcon = 25772 }
    folia  = @{ Game = 25573; Rcon = 25773 }
}
$jarMap = @{
    paper  = 'paper-1.21.11-132.jar'
    leaf   = 'leaf-1.21.11-179.jar'
    purpur = 'purpur-1.21.11-2568.jar'
    folia  = 'folia-1.21.11-14.jar'
}

$pluginJar = Join-Path $root '..\target\RadSurvival.jar'
if (-not (Test-Path $pluginJar)) { throw "找不到插件产物：$pluginJar（先跑 mvn -B package）" }

Invoke-Expression (Get-Content (Join-Path $root '..\tools\rconlib.ps1') -Raw -Encoding utf8)

function Invoke-Rcon {
    # rconlib 的 Send-Rcon 在 $env:RAD_RCON_PASS 为空时会回落到 -PassFile；
    # 而默认 PassFile 用 $PSScriptRoot 拼路径，用 scriptblock 加载时该变量为空 → 绑定异常。
    # 因此这里构造一个真实存在的密码文件，两条路径都走通。
    param([string[]]$Commands, [int]$Port, [string]$Pass)
    $pwFile = Join-Path $root "rcon-pass-$Port.txt"
    Set-Content -Path $pwFile -Value $Pass -Encoding ascii -NoNewline
    $old = $env:RAD_RCON_PASS
    $env:RAD_RCON_PASS = $Pass
    try { return (Send-Rcon -Commands $Commands -Port $Port -PassFile $pwFile -QuietMs 400 -DeadlineMs 8000) }
    finally { $env:RAD_RCON_PASS = $old }
}

function Wait-Rcon {
    param([int]$Port, [int]$TimeoutSec, [string]$Pass, [string]$LogFile)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        # 判据：能完成一次 RCON 认证并拿到回包即为就绪。
        # 不要匹配 'list' 的具体文案——各分支措辞不同，且无玩家时的格式也变过。
        try {
            $r = Invoke-Rcon -Commands 'list' -Port $Port -Pass $Pass
            if ($r -and $r -notmatch 'auth failed' -and $r.Length -gt 5) { return $true }
        } catch { }
        # 进程死了就别等了
        if ($LogFile -and (Test-Path $LogFile)) {
            $tail = Get-Content $LogFile -Tail 40 -ErrorAction SilentlyContinue
            if ($tail -match 'Exception encountered when attempting to load') { return $false }
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Test-OneServer {
    param([string]$Name)

    $jar = $jarMap[$Name]
    if (-not $jar) { throw "未知服务端：$Name" }
    $dir = Join-Path $root $Name
    $jarPath = Join-Path $dir $jar
    if (-not (Test-Path $jarPath)) { return [pscustomobject]@{ Server=$Name; Status='SKIP'; Detail="缺少 jar：$jar" } }
    if ((Get-Item $jarPath).Length -lt 1MB) { return [pscustomobject]@{ Server=$Name; Status='SKIP'; Detail='jar 不完整（下载中断）' } }

    $ports = $portMap[$Name]
    $rconPass = "smoke-$Name-2026"
    $logFile = Join-Path $dir 'smoke-console.log'

    # ---- 干净重铺：只删世界与日志，保留 jar 和 plugins（插件由下面统一投放）
    foreach ($p in @('world','world_nether','world_the_end','logs','crash-reports','smoke-console.log','config','cache','versions','libraries')) {
        $t = Join-Path $dir $p
        if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
    }

    # ---- eula / properties
    Set-Content -Path (Join-Path $dir 'eula.txt') -Value "eula=true`n" -Encoding ascii
    $props = Get-Content (Join-Path $root 'server.properties.template') -Raw
    $props = $props -replace '(?m)^server-port=.*$', "server-port=$($ports.Game)"
    $props = $props -replace '(?m)^rcon\.port=.*$', "rcon.port=$($ports.Rcon)"
    $props = $props -replace '(?m)^rcon\.password=.*$', "rcon.password=$rconPass"
    $props = $props -replace '(?m)^query\.port=.*$', "query.port=$($ports.Game)"
    $props = $props -replace '(?m)^motd=.*$', "motd=RadSurvival smoke - $Name"
    # 覆写后必须复核：模板若缺键，-replace 会静默失效 → 服务端用默认端口 → RCON 永远连不上
    foreach ($kv in @(@('server-port', $ports.Game), @('rcon.port', $ports.Rcon), @('rcon.password', $rconPass))) {
        if ($props -notmatch "(?m)^$([regex]::Escape($kv[0]))=") {
            throw "server.properties 模板缺少键 '$($kv[0])'，端口覆写会静默失效（改模板，不要在脚本里打补丁）"
        }
    }
    Set-Content -Path (Join-Path $dir 'server.properties') -Value $props -Encoding ascii

    # ---- 投放插件（每次全新铺设，避免残留 data 目录影响「首次启动」语义）
    $plugDir = Join-Path $dir 'plugins'
    New-Item -ItemType Directory -Force -Path $plugDir | Out-Null
    Get-ChildItem $plugDir -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item $pluginJar $plugDir -Force

    # ---- 启动
    $args = @(
        '-Xms256M', "-Xmx${MemoryMb}M",
        '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-jar', $jarPath, 'nogui'
    )
    $proc = Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $dir `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $dir 'smoke-stderr.log') `
        -PassThru -WindowStyle Hidden

    $result = [ordered]@{
        Server = $Name
        Status = 'FAIL'
        Enabled = $false
        Refused = $false
        Brand = ''
        Detail = ''
        LogExcerpt = ''
    }

    try {
        $ready = Wait-Rcon -Port $ports.Rcon -TimeoutSec $StartupTimeoutSec -Pass $rconPass -LogFile $logFile
        Start-Sleep -Seconds 2

        $log = if (Test-Path $logFile) { Get-Content $logFile -Raw -Encoding utf8 -ErrorAction SilentlyContinue } else { '' }
        $result.LogExcerpt = ($log -split "`n" | Where-Object { $_ -match 'RadSurvival' } | Select-Object -First 12) -join "`n"

        # 判定三类结局
        $result.Refused = $log -match '本插件无法在此运行'
        $result.Enabled = ($log -match '\[RadSurvival\].*已启用') -and -not $result.Refused

        # 品牌行（我们自己打的 平台 · 行）
        $brandLine = ($log -split "`n" | Where-Object { $_ -match '平台 ·' } | Select-Object -First 1)
        if ($brandLine) { $result.Brand = ($brandLine -replace '.*平台 · ', '').Trim() }

        if ($ready -and $result.Enabled) {
            # 主循环是否真的推到玩家（用 server 口径的 status）
            $out = Invoke-Rcon -Commands 'rad status' -Port $ports.Rcon -Pass $rconPass
            $result.Detail = (($out -split "`n") | Where-Object { $_ -match 'platform|config|tick' } | Select-Object -First 3) -join ' | '
            $result.Status = 'OK'
        } elseif ($result.Refused) {
            $result.Status = 'REFUSED-OK'
            $result.Detail = ($log -split "`n" | Where-Object { $_ -match '原因：' } | Select-Object -First 1)
        } elseif ($ready) {
            $result.Status = 'LOADED-NO-PLUGIN'
            $result.Detail = '服务端起来了但插件未启用（见日志）'
        } else {
            $result.Detail = "服务端未在 ${StartupTimeoutSec}s 内就绪"
        }

        # 只统计「真正的异常栈」：堆栈行或 Caused by。
        # 不要匹配裸 'Error'——服务端启动信息里的 [ERROR] 行（EULA 提示、flat 层配置提示）不是异常。
        $ex = ($log -split "`n" | Where-Object { $_ -match '^\s+at [a-zA-Z_$]|Caused by:|Exception in thread|^\s*\w+(Exception|Error):' } | Select-Object -First 6) -join "`n"
        if ($ex) { $result.Detail = "$($result.Detail) || STACKTRACE: $ex" }

    } finally {
        if (-not $proc.HasExited) {
            try { $proc.Kill($true) } catch { try { $proc.Kill() } catch {} }
            Start-Sleep -Seconds 2
        }
    }
    return [pscustomobject]$result
}

# ---------------- main
if ($Server -contains 'all') { $Server = @('paper','leaf','purpur','folia') }
$all = foreach ($s in $Server) { Test-OneServer -Name $s }

$all | Format-Table Server, Status, Brand, Detail -AutoSize -Wrap
return $all

# RadSurvival - 内联 RCON 客户端（用 Invoke-Expression 加载，避开 .ps1 执行策略限制）
# 用法: Invoke-Expression (Get-Content tools\rconlib.ps1 -Raw)
#       Send-Rcon -Commands 'plugins','rad status'
# 注意: 密码文件不入库（.gitignore 已排除 test-server/rcon-pass.txt）；
#       优先用环境变量 RAD_RCON_PASS，其次读服务器目录下的 rcon-pass.txt。
function Send-Rcon {
    param(
        [string[]]$Commands,
        [string]$Target = '127.0.0.1',
        [int]$Port = 25764,
        [string]$PassFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'test-server\rcon-pass.txt'),
        [int]$QuietMs = 500,
        [int]$DeadlineMs = 20000
    )
    $pw = if ($env:RAD_RCON_PASS) { $env:RAD_RCON_PASS } else { (Get-Content -Raw -Path $PassFile).Trim() }
    $tcp = [System.Net.Sockets.TcpClient]::new($Target, $Port)
    $ns = $tcp.GetStream()
    function Read-Pkt([System.IO.Stream]$s) {
        $hdr = New-Object byte[] 4
        $got = 0
        while ($got -lt 4) { $r = $s.Read($hdr, $got, 4 - $got); if ($r -le 0) { throw 'conn closed' }; $got += $r }
        $len = [BitConverter]::ToInt32($hdr, 0)
        $body = New-Object byte[] $len
        $got = 0
        while ($got -lt $len) { $r = $s.Read($body, $got, $len - $got); if ($r -le 0) { throw 'conn closed' }; $got += $r }
        $id = [BitConverter]::ToInt32($body, 0)
        $type = [BitConverter]::ToInt32($body, 4)
        $text = [System.Text.Encoding]::UTF8.GetString($body, 8, $len - 10).TrimEnd([char]0)
        [pscustomobject]@{ Id = $id; Type = $type; Body = $text }
    }
    function Write-Pkt([System.IO.Stream]$s, [int]$id, [int]$type, [string]$body) {
        $b = [System.Text.Encoding]::UTF8.GetBytes($body)
        $len = 4 + 4 + $b.Length + 2
        $out = New-Object byte[] (4 + $len)
        [BitConverter]::GetBytes([int]$len).CopyTo($out, 0)
        [BitConverter]::GetBytes([int]$id).CopyTo($out, 4)
        [BitConverter]::GetBytes([int]$type).CopyTo($out, 8)
        $b.CopyTo($out, 12)
        $s.Write($out, 0, $out.Length)
        $s.Flush()
    }
    $result = New-Object System.Collections.Generic.List[string]
    try {
        Write-Pkt $ns 1 3 $pw
        $auth = Read-Pkt $ns
        if ($auth.Id -eq -1) { throw 'RCON auth failed' }
        $id = 2
        foreach ($c in $Commands) {
            Write-Pkt $ns $id 2 $c
            $id++
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $sb = New-Object System.Text.StringBuilder
            $last = $null
            while ($sw.ElapsedMilliseconds -lt $DeadlineMs) {
                if ($ns.DataAvailable) {
                    $p = Read-Pkt $ns
                    [void]$sb.Append($p.Body)
                    $last = [System.Diagnostics.Stopwatch]::StartNew()
                } else {
                    if ($last -ne $null -and $last.ElapsedMilliseconds -ge $QuietMs) { break }
                    Start-Sleep -Milliseconds 20
                }
            }
            $result.Add(">>> $c")
            $result.Add($sb.ToString())
        }
    } finally {
        $tcp.Dispose()
    }
    return ($result -join "`n")
}

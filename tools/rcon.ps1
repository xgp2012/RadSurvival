# RadSurvival M4 -- Source RCON client (no deps, .NET TcpClient, hand-rolled protocol).
# Packet: [int32 len][int32 id][int32 type][payload utf8][0x00][0x00] little-endian.
# Client types: 3=AUTH, 2=EXECCOMMAND. Reads until quiet window. ASCII-only file (PS 5.1 default ANSI decode).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File rcon.ps1 -Command 'plugins'
#        multi-command same connection: -Command 'a','b' -BetweenDelayMs 150
param(
    [Parameter(Mandatory = $true)][string[]]$Command,
    [string]$RconHost = '127.0.0.1',
    [int]$Port = 25764,
    [string]$PassFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'test-server\rcon-pass.txt'),
    [int]$QuietMs = 400,
    [int]$DeadlineMs = 30000,
    [int]$BetweenDelayMs = 0
)
$ErrorActionPreference = 'Stop'
$pw = if ($env:RAD_RCON_PASS) { $env:RAD_RCON_PASS } else { (Get-Content -Raw -Path $PassFile).Trim() }

function New-Packet([int]$id, [int]$type, [string]$body) {
    $b = [System.Text.Encoding]::UTF8.GetBytes($body)
    $len = 4 + 4 + $b.Length + 2
    $out = New-Object byte[] (4 + $len)
    [BitConverter]::GetBytes([int]$len).CopyTo($out, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($out, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($out, 8)
    $b.CopyTo($out, 12)
    $out[12 + $b.Length] = 0
    $out[13 + $b.Length] = 0
    return ,$out
}

function Read-Exact([System.IO.Stream]$s, [int]$n) {
    $buf = New-Object byte[] $n
    $got = 0
    while ($got -lt $n) {
        $r = $s.Read($buf, $got, $n - $got)
        if ($r -le 0) { throw "connection closed while reading (got $got/$n)" }
        $got += $r
    }
    return ,$buf
}

function Read-PacketIfAvailable([System.IO.Stream]$s) {
    if (-not $s.DataAvailable) { return $null }
    $hdr = Read-Exact $s 4
    $len = [BitConverter]::ToInt32($hdr, 0)
    if ($len -lt 10 -or $len -gt 8 * 1024 * 1024) { throw "bad packet length: $len" }
    $body = Read-Exact $s $len
    $id = [BitConverter]::ToInt32($body, 0)
    $type = [BitConverter]::ToInt32($body, 4)
    $text = [System.Text.Encoding]::UTF8.GetString($body, 8, $len - 10)
    $text = $text.TrimEnd([char]0)
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $text }
}

$tcp = New-Object System.Net.Sockets.TcpClient
$tcp.Connect($RconHost, $Port)
$net = $tcp.GetStream()
try {
    # 1) AUTH (id=1; echo id=1 on success, -1 on failure)
    $auth = New-Packet 1 3 $pw
    $net.Write($auth, 0, $auth.Length); $net.Flush()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $authPkt = $null
    while ($sw.ElapsedMilliseconds -lt $DeadlineMs) {
        $p = Read-PacketIfAvailable $net
        if ($p -ne $null) { $authPkt = $p; break }
        Start-Sleep -Milliseconds 25
    }
    if ($authPkt -eq $null) { throw 'auth: no response (timeout)' }
    if ($authPkt.Id -eq -1) { throw 'auth FAILED (id=-1, wrong password?)' }

    # 2) EXECCOMMAND per entry (incrementing id), collect until quiet window/deadline
    $nextId = 2
    $all = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $Command.Count; $i++) {
        if ($i -gt 0 -and $BetweenDelayMs -gt 0) { Start-Sleep -Milliseconds $BetweenDelayMs }
        $id = $nextId; $nextId++; $text = $Command[$i]
        $cmd = New-Packet $id 2 $text
        $net.Write($cmd, 0, $cmd.Length); $net.Flush()
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $lastPacketAt = $null
        $bodies = New-Object System.Collections.Generic.List[string]
        while ($sw.ElapsedMilliseconds -lt $DeadlineMs) {
            $p = Read-PacketIfAvailable $net
            if ($p -ne $null) {
                $bodies.Add($p.Body)
                $lastPacketAt = [System.Diagnostics.Stopwatch]::StartNew()
            } else {
                if ($bodies.Count -gt 0 -and $lastPacketAt.ElapsedMilliseconds -ge $QuietMs) { break }
                Start-Sleep -Milliseconds 20
            }
        }
        if ($Command.Count -gt 1) { $all.Add(">>> " + $text) }
        if ($bodies.Count -eq 0) { $all.Add('(no response within deadline)') }
        else { $all.Add(($bodies -join "`n")) }
    }
    $outText = ($all -join "`n")
    # write raw UTF-8 bytes to stdout (harness decodes UTF-8; avoids child console codepage mangling)
    $stdout = [Console]::OpenStandardOutput()
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($outText + "`n")
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
} finally {
    $tcp.Dispose()
}

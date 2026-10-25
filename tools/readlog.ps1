param([string]$LogPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'test-server\logs\latest.log'))
$fs = [System.IO.FileStream]::new($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
try {
    $sr = [System.IO.StreamReader]::new($fs)
    $text = $sr.ReadToEnd()
    Write-Output $text
} finally {
    $fs.Dispose()
}

<#
.SYNOPSIS
  Block until a message lands in this agent's relay inbox, print its path, exit 0.

.DESCRIPTION
  The Claude <-> Gemini relay (see .relay/PROTOCOL.md). Each side runs this
  and waits. It does NOT read, move or delete the message: the reader archives
  it after reading (archive-message.ps1), so a crash mid-task never loses one.

  Only finished messages count. send-message.ps1 writes "<name>.md.tmp" and
  renames it to "<name>.md" when complete, and this script only matches *.md.
  On top of that, it waits until the file size holds steady, in case a message
  was dropped in by hand.

  Exit codes:
    0  a message is waiting. Its full path is the LAST line of output.
    2  timed out with nothing waiting. Just run it again.
    1  bad arguments or a missing relay folder.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\wait-for-message.ps1 -For claude
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\wait-for-message.ps1 -For gemini -TimeoutMinutes 25
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claude', 'gemini')]
    [string]$For,

    # 0 = wait forever. Use a timeout when your shell tool kills long commands.
    [int]$TimeoutMinutes = 0,

    [int]$PollSeconds = 3
)

$ErrorActionPreference = 'Stop'
$relay = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.relay'
$inbox = Join-Path $relay "to-$For"
if (-not (Test-Path $inbox)) {
    Write-Output "relay inbox missing: $inbox"
    exit 1
}

$deadline = if ($TimeoutMinutes -gt 0) { (Get-Date).AddMinutes($TimeoutMinutes) } else { $null }
Write-Output ("[{0}] waiting for a message in {1}" -f (Get-Date -Format 'HH:mm:ss'), $inbox)

while ($true) {
    # Names start with a sortable timestamp, so the oldest message goes first.
    $next = Get-ChildItem -Path $inbox -Filter '*.md' -File -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -First 1

    if ($next) {
        $size = -1
        while ($size -ne $next.Length) {
            $size = $next.Length
            Start-Sleep -Seconds 1
            $next.Refresh()
        }
        $waiting = (Get-ChildItem -Path $inbox -Filter '*.md' -File).Count
        Write-Output ("[{0}] message arrived ({1} waiting in total)" -f (Get-Date -Format 'HH:mm:ss'), $waiting)
        Write-Output $next.FullName
        exit 0
    }

    if ($deadline -and (Get-Date) -ge $deadline) {
        Write-Output ("[{0}] no message after {1} min; run again to keep waiting" -f (Get-Date -Format 'HH:mm:ss'), $TimeoutMinutes)
        exit 2
    }
    Start-Sleep -Seconds $PollSeconds
}

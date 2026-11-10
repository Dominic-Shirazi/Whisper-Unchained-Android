<#
.SYNOPSIS
  Check this agent's relay inbox WITHOUT waiting. For checkpoints between work steps.

.DESCRIPTION
  The non-blocking twin of wait-for-message.ps1. Both agents in this repo are
  building at the same time, so neither can sit in a blocking wait all day:
  run this at every checkpoint (see PROTOCOL.md, "When to check").

  Exit codes:
    0  one or more messages waiting. Every path is printed, oldest first;
       the oldest is the LAST line (same convention as wait-for-message).
    3  inbox empty. Carry on.
    1  bad arguments or a missing relay folder.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\check-inbox.ps1 -For gemini
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claude', 'gemini')]
    [string]$For
)

$ErrorActionPreference = 'Stop'
$relay = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.relay'
$inbox = Join-Path $relay "to-$For"
if (-not (Test-Path $inbox)) {
    Write-Output "relay inbox missing: $inbox"
    exit 1
}

$msgs = @(Get-ChildItem -Path $inbox -Filter '*.md' -File -ErrorAction SilentlyContinue | Sort-Object Name)
if ($msgs.Count -eq 0) {
    Write-Output "inbox empty: $inbox"
    exit 3
}

Write-Output ("{0} message(s) waiting; oldest is the last line" -f $msgs.Count)
# Newest first, so the oldest (the one to read first) ends up on the last line.
[array]::Reverse($msgs)
$msgs | ForEach-Object { Write-Output $_.FullName }
exit 0

<#
.SYNOPSIS
  Move a message you have finished reading out of your inbox into .relay/archive.

.DESCRIPTION
  Run this right after reading a message and before starting its work, so
  the next wait-for-message.ps1 does not hand you the same file again. Refuses
  any path outside .relay/to-claude or .relay/to-gemini.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\archive-message.ps1 -Path .relay\to-gemini\20261003-101500-000-from-claude-c28-r3.md
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = 'Stop'
$relay = (Resolve-Path (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.relay')).Path
$full = (Resolve-Path $Path).Path
$parent = Split-Path $full -Parent
$allowed = @((Join-Path $relay 'to-claude'), (Join-Path $relay 'to-gemini'))
if ($allowed -notcontains $parent) {
    Write-Output "refusing: not a relay inbox message: $full"
    exit 1
}

$archive = Join-Path $relay 'archive'
New-Item -ItemType Directory -Force -Path $archive | Out-Null
$dest = Join-Path $archive (Split-Path $full -Leaf)
Move-Item -Path $full -Destination $dest
Write-Output $dest
exit 0

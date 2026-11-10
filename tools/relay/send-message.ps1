<#
.SYNOPSIS
  Deliver a message to the other agent's relay inbox, atomically.

.DESCRIPTION
  Writes "<timestamp>-from-<sender>-<slug>.md.tmp" and then renames it to
  ".md", so the other side's wait-for-message.ps1 can never pick up a half-
  written file. Pass the body either as a file (-BodyPath, the normal case:
  draft the message in a scratch file first) or as a string (-Body).

  Prints the delivered path. Exit 0 on success, 1 on failure.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\send-message.ps1 -To claude -From gemini -Slug c28-r3-report -BodyPath .relay\drafts\report.md
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('claude', 'gemini')]
    [string]$To,

    [Parameter(Mandatory = $true)]
    [ValidateSet('claude', 'gemini')]
    [string]$From,

    # Short kebab-case label, e.g. "c28-r3-report". Letters, digits and dashes.
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9-]{1,60}$')]
    [string]$Slug,

    [string]$BodyPath,
    [string]$Body
)

$ErrorActionPreference = 'Stop'
if ($To -eq $From) { Write-Output 'To and From must differ'; exit 1 }
if (-not $BodyPath -and -not $Body) { Write-Output 'pass -BodyPath or -Body'; exit 1 }

$relay = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.relay'
$inbox = Join-Path $relay "to-$To"
if (-not (Test-Path $inbox)) { Write-Output "relay inbox missing: $inbox"; exit 1 }

$text = if ($BodyPath) { [IO.File]::ReadAllText((Resolve-Path $BodyPath)) } else { $Body }
$name = '{0}-from-{1}-{2}.md' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), $From, $Slug
$final = Join-Path $inbox $name
$tmp = "$final.tmp"

# UTF-8 without a BOM; Windows PowerShell 5.1's Set-Content would add one.
[IO.File]::WriteAllText($tmp, $text, (New-Object System.Text.UTF8Encoding($false)))
Rename-Item -Path $tmp -NewName $name
Write-Output $final
exit 0

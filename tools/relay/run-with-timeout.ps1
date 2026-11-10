# Runs a command with a hard time limit, so a stalled Gradle/test run can never
# block the relay for hours (2026-10-05: a stalled test run sat for 10+ hours).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\relay\run-with-timeout.ps1 `
#       -TimeoutMinutes 15 -Command ".\gradlew.bat :app:testDebugUnitTest --rerun-tasks"
#
# Output goes to the console AND to .relay\drafts\run-<time>-<pid>.log (one file per run).
# Exit code: the command's own exit code, or 124 on timeout. On timeout the whole
# process tree is killed and `gradlew --stop` is run, then the last 40 lines are
# printed so you can see where it stalled.
#
# Why the command is started through WMI and not Start-Process/Process.Start: a
# normally started child inherits this script's stdout handle, and the Gradle
# daemon (which outlives the build) keeps it open, so a caller that waits for its
# output pipe to close (an agent's terminal tool) hangs forever after
# "BUILD SUCCESSFUL". A WMI-created process inherits no handles.
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [int]$TimeoutMinutes = 15
)

$cwd = (Get-Location).Path
$stamp = "{0:yyyyMMdd-HHmmss}-{1}" -f (Get-Date), $PID
$log = Join-Path $cwd ".relay\drafts\run-$stamp.log"
$wrapper = Join-Path $cwd ".relay\drafts\run-$stamp.cmd"
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null

# The wrapper writes the command's output and, last, its exit code into the log.
# `call` matters: without it a .bat command (gradlew.bat) never returns to the wrapper.
Set-Content -Encoding ascii -Path $wrapper -Value @(
    "@echo off",
    "call $Command > `"$log`" 2>&1",
    "echo __EXIT__%ERRORLEVEL%>> `"$log`""
)

$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = "cmd.exe /c `"$wrapper`""
    CurrentDirectory = $cwd
}
if ($r.ReturnValue -ne 0) { Write-Host "[run-with-timeout] could not start (WMI $($r.ReturnValue))"; exit 1 }
$procId = $r.ProcessId

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$lastSize = 0
$lastGrowth = Get-Date
while (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
    Start-Sleep -Seconds 15
    if (Test-Path $log) {
        $size = (Get-Item $log).Length
        if ($size -ne $lastSize) { $lastSize = $size; $lastGrowth = Get-Date }
    }
    $quiet = [int]((Get-Date) - $lastGrowth).TotalMinutes
    Write-Host ("[run-with-timeout] {0:HH:mm:ss} running; log {1} bytes; quiet {2} min" -f (Get-Date), $lastSize, $quiet)
    if ((Get-Date) -gt $deadline) {
        Write-Host "[run-with-timeout] TIMEOUT after $TimeoutMinutes min - killing the process tree"
        & taskkill /T /F /PID $procId | Out-Null
        if (Test-Path (Join-Path $cwd "gradlew.bat")) {
            Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
                CommandLine = "cmd.exe /c gradlew.bat --stop"; CurrentDirectory = $cwd } | Out-Null
        }
        Write-Host "----- last 40 lines -----"
        if (Test-Path $log) { Get-Content $log -Tail 40 }
        Remove-Item $wrapper -ErrorAction SilentlyContinue
        exit 124
    }
}
Remove-Item $wrapper -ErrorAction SilentlyContinue
$code = 1
if (Test-Path $log) {
    $exitLine = Select-String -Path $log -Pattern '^__EXIT__(-?\d+)' | Select-Object -Last 1
    if ($exitLine) { $code = [int]$exitLine.Matches[0].Groups[1].Value }
    Write-Host "----- last 40 lines -----"
    Get-Content $log -Tail 40 | Where-Object { $_ -notmatch '^__EXIT__' }
}
exit $code

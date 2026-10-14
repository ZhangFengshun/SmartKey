[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AdbArguments = @('devices', '-l')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adbCandidates = @(
    (Join-Path $projectRoot '.toolchain\android-sdk\platform-tools\adb.exe')
)
if ($env:SMARTKEY_ADB) { $adbCandidates = @($env:SMARTKEY_ADB) + $adbCandidates }
if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
$adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $adb) { $adbCommand = Get-Command adb -ErrorAction SilentlyContinue; if ($adbCommand) { $adb = $adbCommand.Source } }
if (-not $adb) { throw 'ADB is missing. Run tools\setup.ps1 or set SMARTKEY_ADB.' }
& $adb @AdbArguments
if ($LASTEXITCODE -ne 0) { throw "ADB failed with exit code $LASTEXITCODE" }

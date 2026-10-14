[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @(':app:assembleDebug')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$buildEnvironment = Initialize-SmartKeyEnvironment
$gradle = Join-Path $buildEnvironment.ToolchainRoot 'gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    $gradle = Join-Path $buildEnvironment.ProjectRoot 'gradlew.bat'
}
if (-not (Test-Path -LiteralPath (Join-Path $buildEnvironment.SdkRoot 'platforms\android-35\android.jar'))) {
    throw 'Android SDK 35 is missing. Run tools\setup.ps1 first.'
}
Push-Location $buildEnvironment.ProjectRoot
try {
    & $gradle @GradleArguments --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

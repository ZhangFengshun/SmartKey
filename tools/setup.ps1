[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$buildEnvironment = Initialize-SmartKeyEnvironment
$downloads = Join-Path $buildEnvironment.ToolchainRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloads, $buildEnvironment.SdkRoot | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-VerifiedArchive {
    param([string]$Url, [string]$Path, [string]$Sha256)
    if ((Test-Path -LiteralPath $Path) -and ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256)) { return }
    $curlCommand = Get-Command curl.exe -ErrorAction Stop
    & $curlCommand.Source --fail --location --retry 3 --connect-timeout 20 --output $Path $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) { throw "SHA256 mismatch: $Path" }
}

$sdkManager = Join-Path $buildEnvironment.SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $sdkArchive = Join-Path $downloads 'commandlinetools-win-15859902_latest.zip'
    Get-VerifiedArchive -Url 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' -Path $sdkArchive -Sha256 '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $staging = Join-Path $buildEnvironment.ToolchainRoot ('sdk-staging-' + [guid]::NewGuid().ToString('N'))
    [System.IO.Compression.ZipFile]::ExtractToDirectory($sdkArchive, $staging)
    $source = [System.IO.Path]::GetFullPath((Join-Path $staging 'cmdline-tools'))
    $destination = [System.IO.Path]::GetFullPath((Join-Path $buildEnvironment.SdkRoot 'cmdline-tools\latest'))
    if (-not $source.StartsWith($buildEnvironment.ToolchainRoot + '\') -or -not $destination.StartsWith($buildEnvironment.SdkRoot + '\')) { throw 'Unexpected SDK staging path' }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
}

$gradleExecutable = Join-Path $buildEnvironment.ToolchainRoot 'gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradleExecutable)) {
    $gradleArchive = Join-Path $downloads 'gradle-8.11.1-bin.zip'
    Get-VerifiedArchive -Url 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip' -Path $gradleArchive -Sha256 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
    [System.IO.Compression.ZipFile]::ExtractToDirectory($gradleArchive, $buildEnvironment.ToolchainRoot)
}

$sdkLog = Join-Path $buildEnvironment.ToolchainRoot 'sdk-install.log'
$previousPreference = $ErrorActionPreference
try {
    # sdkmanager emits a harmless deprecation notice on stderr. PowerShell 5
    # must not turn that notice into a terminating error while capturing logs.
    $ErrorActionPreference = 'Continue'
    1..40 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$($buildEnvironment.SdkRoot)" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.0.12077973' 'cmake;3.22.1' *> $sdkLog
    $sdkExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($sdkExitCode -ne 0) { throw "SDK installation failed. See $sdkLog" }
Set-Content -LiteralPath (Join-Path $buildEnvironment.ProjectRoot 'local.properties') -Value ('sdk.dir=' + $buildEnvironment.SdkRoot.Replace('\', '/')) -Encoding ASCII
Write-Output "JDK: $($buildEnvironment.JavaHome)"
Write-Output "Android SDK: $($buildEnvironment.SdkRoot)"
& $gradleExecutable --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed' }

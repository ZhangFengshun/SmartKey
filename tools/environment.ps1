Set-StrictMode -Version Latest

function Initialize-SmartKeyEnvironment {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $toolchainRoot = Join-Path $projectRoot '.toolchain'
    $jdkRoot = $env:JAVA_HOME
    if (-not $jdkRoot -or -not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\javac.exe'))) {
        $javaCommand = Get-Command java.exe -ErrorAction Stop
        # Windows PowerShell 5 treats native stderr as errors when captured.
        # java prints its normal version/settings information on stderr.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $javaSettings = & $javaCommand.Source -XshowSettings:properties -version 2>&1
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        $javaHomeLine = $javaSettings | ForEach-Object { "$_" } | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if ($javaHomeLine) { $jdkRoot = ($javaHomeLine -split '=', 2)[1].Trim() }
    }
    if (-not $jdkRoot -or -not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\javac.exe'))) {
        throw 'A JDK 17 or newer is required. Set JAVA_HOME to a full JDK installation.'
    }

    $sdkRoot = Join-Path $toolchainRoot 'android-sdk'
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) {
            $configuredSdk = ($sdkLine -split '=', 2)[1].Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $configuredSdk) { $sdkRoot = $configuredSdk }
        }
    }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platforms\android-35\android.jar'))) {
            $sdkRoot = $candidate
            break
        }
    }
    $sdkRoot = [System.IO.Path]::GetFullPath($sdkRoot).TrimEnd('\', '/')
    $env:JAVA_HOME = $jdkRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:GRADLE_USER_HOME = Join-Path $toolchainRoot 'gradle-home'
    # Use a full path rather than Windows short-name temporary paths.
    # Oracle JDK 21.0.2 cannot open Gradle's NIO pipe through that short path.
    $temporaryRoot = Join-Path $toolchainRoot 'tmp'
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $env:TEMP = $temporaryRoot
    $env:TMP = $temporaryRoot
    $env:Path = (Join-Path $jdkRoot 'bin') + ';' + (Join-Path $sdkRoot 'platform-tools') + ';' + $env:Path
    [pscustomobject]@{
        ProjectRoot = $projectRoot
        ToolchainRoot = $toolchainRoot
        JavaHome = $jdkRoot
        SdkRoot = $sdkRoot
        GradleHome = $env:GRADLE_USER_HOME
        TemporaryRoot = $temporaryRoot
    }
}

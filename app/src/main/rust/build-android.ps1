$ErrorActionPreference = "Stop"

$sdkPath = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdkPath)) {
    $sdkPath = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($sdkPath)) {
    throw "Set ANDROID_SDK_ROOT or ANDROID_HOME to build the translation decoder."
}

$ndkPath = Join-Path $sdkPath "ndk\28.0.13004108"
if (-not (Test-Path -LiteralPath $ndkPath -PathType Container)) {
    throw "Android NDK 28.0.13004108 was not found at $ndkPath."
}
$env:ANDROID_NDK_HOME = $ndkPath
$cargoBin = Join-Path $env:USERPROFILE ".cargo\bin"
if (Test-Path -LiteralPath $cargoBin -PathType Container) {
    $env:PATH = "$cargoBin;$env:PATH"
}

Push-Location $PSScriptRoot
try {
    cargo ndk -t arm64-v8a --platform 26 -o ..\jniLibs build --release
    if ($LASTEXITCODE -ne 0) {
        throw "cargo ndk failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

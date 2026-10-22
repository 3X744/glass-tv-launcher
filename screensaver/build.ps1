# Builds the koi pond (screensaver/nagomi) into Glass's APK assets, where
# ScreensaverActivity loads it as assets/nagomi/index.html?tv=1. Run after changing
# anything under nagomi/; the output is committed so Gradle builds don't need Node.
# Native tools write warnings to stderr, which Windows PowerShell would turn into errors; exit codes are checked instead
$ErrorActionPreference = 'Continue'
$nagomi = Join-Path $PSScriptRoot 'nagomi'
$out = Join-Path $PSScriptRoot '..\glass-launcher\launcher\app\src\main\assets\nagomi'

Push-Location $nagomi
try {
    if (-not (Test-Path node_modules)) { npm ci --no-audit --no-fund }
    npx tsc
    if ($LASTEXITCODE) { throw 'type check failed' }
    npx vite build --base ./ --target es2019 --outDir $out --emptyOutDir
    if ($LASTEXITCODE) { throw 'vite build failed' }
} finally {
    Pop-Location
}

# The screensaver is silent and offline: drop the web app's river sound and social-card image
Remove-Item -Recurse -Force (Join-Path $out 'audio'), (Join-Path $out 'nagomi-social-v1.png') -ErrorAction SilentlyContinue
Write-Output "built into $out"

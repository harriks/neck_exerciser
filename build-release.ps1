# Build a signed release APK directly in the project directory.
# The project path is ASCII-only now, so no temp-dir sync is needed.
# Usage: powershell -File build-release.ps1
$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path

# Locate Gradle (wrapper dist cache first, then PATH)
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.2-bin" -Recurse -Filter gradle.bat |
    Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { $gradle = 'gradle.bat' }

Write-Host "Building signed release APK with $gradle (project: $proj) ..."
Push-Location $proj
& $gradle ':app:assembleRelease' --console=plain
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { exit $code }

# The APK is produced inside the app module's standard build output dir,
# because the project path is ASCII-only now (no build-dir redirect).
$outApk = Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'
$back = Join-Path $proj 'app-release.apk'
Copy-Item $outApk $back -Force
Write-Host "APK copied to $back"
Write-Host "Done: release APK at $outApk"

# Run unit tests from an ASCII-only path (Gradle test worker fails to load
# classes when the project path contains non-ASCII characters, e.g. Chinese).
# Usage: powershell -File run-tests.ps1 [-Keep]
param([switch]$Keep)

$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$dest = Join-Path $env:TEMP 'SpineExerciseTimer-tests'

Write-Host "Syncing sources to $dest ..."
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item (Join-Path $proj 'settings.gradle.kts') $dest
Copy-Item (Join-Path $proj 'build.gradle.kts') $dest
Copy-Item (Join-Path $proj 'gradle.properties') $dest
Copy-Item (Join-Path $proj 'local.properties') $dest
Copy-Item (Join-Path $proj 'gradle') $dest -Recurse
Copy-Item (Join-Path $proj 'app') $dest -Recurse
Remove-Item (Join-Path $dest 'app\build') -Recurse -Force -ErrorAction SilentlyContinue

# Locate Gradle (wrapper dist cache first, then PATH)
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.2-bin" -Recurse -Filter gradle.bat |
    Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { $gradle = 'gradle.bat' }

Write-Host "Running tests with $gradle ..."
& $gradle -p $dest ':app:testDebugUnitTest' --console=plain
$code = $LASTEXITCODE

if (-not $Keep) { Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue }
exit $code


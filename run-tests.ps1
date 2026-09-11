# Run unit tests directly in the project directory.
# The project path is ASCII-only now, so no temp-dir sync is needed.
# (Symmetric to build-release.ps1.)
# Usage: powershell -File run-tests.ps1
$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path

# Locate Gradle (wrapper dist cache first, then PATH)
$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.2-bin" -Recurse -Filter gradle.bat |
    Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { $gradle = 'gradle.bat' }

Write-Host "Running tests with $gradle (project: $proj) ..."
Push-Location $proj
& $gradle ':app:testDebugUnitTest' --console=plain
$code = $LASTEXITCODE
Pop-Location
exit $code
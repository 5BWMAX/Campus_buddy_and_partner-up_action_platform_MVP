param([switch]$SkipTests)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
 . "$PSScriptRoot/env.ps1"
 & npm.cmd --prefix web ci --no-audit --no-fund
 if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
 & npm.cmd --prefix web run build
 if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
 if(-not $SkipTests){ & "$PSScriptRoot/test.ps1" }
 & .\mvnw.cmd -B '-Dmaven.repo.local=.local/m2' '-DskipTests' package
 if($LASTEXITCODE -ne 0){throw 'Packaging failed'}
} finally {Pop-Location}

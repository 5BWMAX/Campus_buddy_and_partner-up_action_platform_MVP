$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$config=Get-Content -Raw -LiteralPath "$projectRoot\.local\settings.json" | ConvertFrom-Json
foreach($p in $config.PSObject.Properties){[Environment]::SetEnvironmentVariable($p.Name,[string]$p.Value,'Process')}
$env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)
$env:MAVEN_USER_HOME="$projectRoot\.local\maven"
$env:TLS_KEYSTORE='file:'+(Join-Path $projectRoot '.local\localhost.p12').Replace('\','/')

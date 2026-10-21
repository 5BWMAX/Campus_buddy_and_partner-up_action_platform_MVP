$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$config=Get-Content -Raw -LiteralPath "$projectRoot\.local\settings.json" | ConvertFrom-Json
foreach($p in $config.PSObject.Properties){[Environment]::SetEnvironmentVariable($p.Name,[string]$p.Value,'Process')}
$javaHome=$env:JAVA_HOME
if(-not $javaHome -or -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe'))){
  $java=Get-Command java -ErrorAction SilentlyContinue
  if(-not $java){throw 'java not found; install JDK 21+ and put it on PATH or set JAVA_HOME'}
  $javaHome=Split-Path -Parent (Split-Path -Parent $java.Source)
}
$env:JAVA_HOME=$javaHome
$versionOutput=(& (Join-Path $javaHome 'bin/java.exe') -version 2>&1 | Out-String)
if($versionOutput -notmatch 'version "(\d+)'){throw "Unable to detect Java version at $javaHome"}
if([int]$Matches[1] -lt 21){throw "JDK 21+ is required; current JAVA_HOME is $javaHome (version $($Matches[1]))"}
$env:MAVEN_USER_HOME="$projectRoot\.local\maven"
$env:TLS_KEYSTORE='file:'+(Join-Path $projectRoot '.local\localhost.p12').Replace('\','/')

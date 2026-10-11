param([switch]$NoProxy)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/env.ps1"
$jar=Join-Path $projectRoot 'server/target/campus-buddy-server-2.0.0.jar'
if(-not (Test-Path -LiteralPath $jar)){throw 'Run scripts/build.ps1 first'}
$pidFile=Join-Path $projectRoot '.local/app.pid'
if(Test-Path -LiteralPath $pidFile){$existing=Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue;if($existing){throw 'Recorded app process is running; use scripts/stop.ps1 before restart'}}
$runDir=Join-Path $projectRoot '.local/run'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$runJar=Join-Path $runDir 'campus-buddy-server-2.0.0.jar'
Copy-Item -LiteralPath $jar -Destination $runJar -Force
$jar=$runJar
$p=Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$projectRoot/.local/app.log" -RedirectStandardError "$projectRoot/.local/app-error.log" -PassThru
$p.Id | Set-Content -LiteralPath $pidFile
$healthy=$false
for($i=0;$i -lt 45;$i++){
 if($p.HasExited){throw 'Application exited; inspect .local/app.log and .local/app-error.log'}
 try{$health=Invoke-RestMethod 'https://localhost:8443/actuator/health/readiness' -SkipCertificateCheck -TimeoutSec 2;if($health.status -eq 'UP'){$healthy=$true;break}}catch{}
 Start-Sleep -Milliseconds 1000
}
if(-not $healthy){throw 'Application readiness timed out'}
if(-not $NoProxy){
 $nginxRoot=Join-Path $projectRoot '.local/nginx-1.30.5'
 $prefix=$nginxRoot.Replace('\','/')+'/'
 & "$nginxRoot/nginx.exe" -p $prefix -c "$projectRoot/deploy/nginx.conf" -t
 if($LASTEXITCODE -ne 0){throw 'Nginx configuration failed'}
 Start-Process -FilePath "$nginxRoot/nginx.exe" -ArgumentList @('-p',$prefix,'-c',"$projectRoot/deploy/nginx.conf") -WorkingDirectory $nginxRoot -WindowStyle Hidden
 Write-Output 'Application ready: https://localhost:8444 (Nginx HTTPS entry).'
}else{Write-Output 'Application ready: https://localhost:8443 (direct HTTPS).'}

param([switch]$NoProxy)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/env.ps1"
$jar=Join-Path $projectRoot 'server/target/campus-buddy-server-2.0.0.jar'
if(-not (Test-Path -LiteralPath $jar)){throw 'Run scripts/build.ps1 first'}
$pidFile=Join-Path $projectRoot '.local/app.pid'

function Test-TcpPort([int]$Port){
  try{
    $client=[System.Net.Sockets.TcpClient]::new()
    try{$client.Connect('127.0.0.1',$Port);return $client.Connected}finally{$client.Dispose()}
  }catch{return $false}
}

function Test-AppHealth{
  # Prefer Node's OpenSSL client. On this machine Windows PowerShell/.NET TLS can fail
  # against the local self-signed endpoint even though browsers and Node work fine.
  $node=Get-Command node -ErrorAction SilentlyContinue
  if($node){
    $code="const https=require('https');const req=https.get({host:'127.0.0.1',port:8443,path:'/actuator/health/readiness',rejectUnauthorized:false,timeout:2000},res=>{let d='';res.on('data',c=>d+=c);res.on('end',()=>{try{process.exit(res.statusCode===200&&JSON.parse(d).status==='UP'?0:1)}catch{process.exit(1)}})});req.on('timeout',()=>{req.destroy();process.exit(1)});req.on('error',()=>process.exit(1));"
    & $node.Source -e $code *> $null
    if($LASTEXITCODE -eq 0){return $true}
  }
  try{
    $health=Invoke-RestMethod 'https://localhost:8443/actuator/health/readiness' -SkipCertificateCheck -TimeoutSec 2
    return $health.status -eq 'UP'
  }catch{return $false}
}

function Start-Proxy{
  if($NoProxy){return}
  if(Test-TcpPort 8444){return}
  $nginxRoot=Join-Path $projectRoot '.local/nginx-1.30.5'
  $prefix=$nginxRoot.Replace('\','/')+'/'
  & "$nginxRoot/nginx.exe" -p $prefix -c "$projectRoot/deploy/nginx.conf" -t
  if($LASTEXITCODE -ne 0){throw 'Nginx configuration failed'}
  Start-Process -FilePath "$nginxRoot/nginx.exe" -ArgumentList @('-p',$prefix,'-c',"$projectRoot/deploy/nginx.conf") -WorkingDirectory $nginxRoot -WindowStyle Hidden
  for($i=0;$i -lt 20;$i++){
    if(Test-TcpPort 8444){break}
    Start-Sleep -Milliseconds 250
  }
  if(-not (Test-TcpPort 8444)){throw 'Nginx did not start listening on 8444; inspect .local/nginx-1.30.5/logs/error.log'}
}

if(Test-Path -LiteralPath $pidFile){
  $recorded=[int](Get-Content -LiteralPath $pidFile | Select-Object -First 1)
  $existing=Get-Process -Id $recorded -ErrorAction SilentlyContinue
  if($existing){
    for($i=0;$i -lt 10;$i++){
      if(Test-AppHealth){
        Start-Proxy
        if($NoProxy){Write-Output "Application already running (pid=$recorded): https://localhost:8443 (direct HTTPS)."}
        else{Write-Output "Application already running (pid=$recorded): https://localhost:8444 (Nginx HTTPS entry)."}
        Write-Output 'Use scripts/stop.ps1 before restarting.'
        return
      }
      Start-Sleep -Seconds 1
    }
    throw "Recorded process $recorded is running but readiness is not UP. Inspect .local/app.log and .local/app-error.log."
  }
  Remove-Item -LiteralPath $pidFile -Force
}

if(Test-TcpPort 8443){
  if(Test-AppHealth){
    Start-Proxy
    Write-Output 'Another application instance is already listening on 8443; no local pid file was found.'
    if($NoProxy){Write-Output 'Open https://localhost:8443 (direct HTTPS).'}
    else{Write-Output 'Open https://localhost:8444 (Nginx HTTPS entry).'}
    Write-Output 'Stop that process before starting a new one.'
    return
  }
  throw 'Port 8443 is already in use, but readiness is not UP. Inspect .local/app.log and .local/app-error.log.'
}

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
  if(Test-AppHealth){$healthy=$true;break}
  Start-Sleep -Milliseconds 1000
}
if(-not $healthy){throw "Application readiness timed out (pid=$($p.Id)). The process may still be starting; inspect .local/app.log and .local/app-error.log."}
Start-Proxy
if($NoProxy){Write-Output 'Application ready: https://localhost:8443 (direct HTTPS).'}
else{Write-Output 'Application ready: https://localhost:8444 (Nginx HTTPS entry).'}

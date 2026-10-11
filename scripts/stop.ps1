$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$nginxRoot=Join-Path $projectRoot '.local/nginx-1.30.5'
if(Test-Path -LiteralPath "$nginxRoot/logs/nginx.pid"){
 & "$nginxRoot/nginx.exe" -p ($nginxRoot.Replace('\','/')+'/') -c "$projectRoot/deploy/nginx.conf" -s quit
 if($LASTEXITCODE -ne 0){throw 'Nginx shutdown failed'}
}
$pidFile=Join-Path $projectRoot '.local/app.pid'
if(Test-Path -LiteralPath $pidFile){
 $appProcessId=[int](Get-Content -LiteralPath $pidFile)
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$appProcessId"
 if($process){
  $expected=Join-Path $projectRoot 'server/target/campus-buddy-server-2.0.0.jar'
  if(-not ($process.CommandLine.Contains($expected) -or $process.CommandLine.Contains((Join-Path $projectRoot '.local/run/campus-buddy-server-2.0.0.jar')))){throw 'PID now belongs to a different process; refusing to stop it'}
  Stop-Process -Id $appProcessId
 }
 Remove-Item -LiteralPath $pidFile
}
Write-Output 'Project application stopped.'

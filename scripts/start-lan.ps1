param(
  [string]$Address,
  [switch]$OpenFirewall
)
# 局域网演示模式：手机与电脑连同一网络后，通过 https://<电脑IP>:8444 访问。
# 本脚本不修改任何既有文件，只在 .local/ 下生成局域网专用的证书与 Nginx 配置。
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$localDir=Join-Path $projectRoot '.local'
$nginxRoot=Join-Path $localDir 'nginx-1.30.5'
$ruleName='CampusBuddy LAN 8444'

if($PSVersionTable.PSEdition -ne 'Core'){throw 'Need PowerShell 7 (pwsh); Windows PowerShell 5.1 does not support the required .NET APIs'}
if(-not (Test-Path -LiteralPath (Join-Path $localDir 'settings.json'))){throw 'Run scripts/setup.ps1 first: .local/settings.json is missing'}
if(-not (Test-Path -LiteralPath (Join-Path $nginxRoot 'nginx.exe'))){throw "Nginx not found at $nginxRoot; see README step 3 of 移到另一台机器"}

function Get-CandidateAddress{
  $list=@()
  foreach($nic in Get-CimInstance Win32_NetworkAdapterConfiguration -Filter 'IPEnabled=True'){
    $gateway=$null
    if($nic.DefaultIPGateway){$gateway=[string]$nic.DefaultIPGateway[0]}
    foreach($ip in @($nic.IPAddress)){
      if($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$'){continue}
      $octets=[int[]]($ip -split '\.')
      if($octets[0] -eq 127){continue}
      if($octets[0] -eq 169 -and $octets[1] -eq 254){continue}
      $private=($octets[0] -eq 10) -or ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or ($octets[0] -eq 192 -and $octets[1] -eq 168)
      $list+=[pscustomobject]@{IP=$ip;Adapter=[string]$nic.Description;Gateway=$gateway;Private=$private}
    }
  }
  return $list
}

# 优先选择：私有网段 > 有默认网关（真实网卡通常有，虚拟机网卡通常没有）> 非 x.x.x.1 结尾
$all=@(Get-CandidateAddress)
$ranked=@($all | Where-Object {$_.Private} | Sort-Object -Property @{Expression={[int](-not [bool]$_.Gateway)}},@{Expression={[int]($_.IP -match '\.1$')}})

if($Address){
  if($Address -notmatch '^\d{1,3}(\.\d{1,3}){3}$'){throw "-Address must be an IPv4 address, got: $Address"}
  if(-not ($all | Where-Object {$_.IP -eq $Address})){Write-Output "警告：$Address 不在本机网卡地址中，手机可能连不上。"}
}elseif($ranked.Count -eq 0){
  throw 'No private IPv4 address found; connect to a network (or a phone hotspot) and retry, or pass -Address explicitly'
}elseif($ranked.Count -eq 1){
  $Address=$ranked[0].IP
  Write-Output "自动选择地址：$Address（$($ranked[0].Adapter)）"
}else{
  Write-Output '本机有多个候选地址，请选择手机将要访问的那一个：'
  for($i=0;$i -lt $ranked.Count;$i++){"  [$($i+1)] $($ranked[$i].IP)  网卡：$($ranked[$i].Adapter)  网关：$(if($ranked[$i].Gateway){$ranked[$i].Gateway}else{'无'})"}
  $pick=Read-Host "输入序号（默认 1）"
  if(-not $pick){$pick='1'}
  $index=[int]$pick-1
  if($index -lt 0 -or $index -ge $ranked.Count){throw "Invalid selection: $pick"}
  $Address=$ranked[$index].IP
}

function New-LanCertificate([string]$ip){
  $crt=Join-Path $localDir 'lan.crt'
  $key=Join-Path $localDir 'lan.key'
  $meta=Join-Path $localDir 'lan.json'
  if((Test-Path -LiteralPath $crt) -and (Test-Path -LiteralPath $key) -and (Test-Path -LiteralPath $meta)){
    $previous=Get-Content -Raw -LiteralPath $meta | ConvertFrom-Json
    if($previous.address -eq $ip -and ([datetime]$previous.notAfter) -gt (Get-Date).AddDays(7)){
      Write-Output "复用已有局域网证书（SAN 含 $ip，$(([datetime]$previous.notAfter).ToString('yyyy-MM-dd')) 到期）"
      return
    }
  }
  $rsa=[System.Security.Cryptography.RSA]::Create(3072)
  try{
    $request=[System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
      "CN=$ip",
      $rsa,
      [System.Security.Cryptography.HashAlgorithmName]::SHA256,
      [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $san=[System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName('localhost')
    $san.AddIpAddress([System.Net.IPAddress]::Parse('127.0.0.1'))
    $san.AddIpAddress([System.Net.IPAddress]::Parse($ip))
    $request.CertificateExtensions.Add($san.Build())
    $usage=[System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
    $request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($usage,$false))
    $certificate=$request.CreateSelfSigned([DateTimeOffset]::Now.AddDays(-1),[DateTimeOffset]::Now.AddDays(365))
    $notAfter=$certificate.NotAfter
    try{
      [System.IO.File]::WriteAllText($crt,$certificate.ExportCertificatePem())
      [System.IO.File]::WriteAllText($key,$rsa.ExportRSAPrivateKeyPem())
    }finally{$certificate.Dispose()}
    @{address=$ip;notAfter=$notAfter.ToString('o');createdAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $meta -Encoding utf8
    Write-Output "已生成本地局域网证书（SAN：localhost / 127.0.0.1 / $ip，有效期 365 天）"
  }finally{$rsa.Dispose()}
}

function New-LanNginxConf([string]$ip){
  $source=Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'deploy/nginx.conf')
  $posixLocal=($localDir -replace '\\','/')
  $posixNginx=($nginxRoot -replace '\\','/')
  # 只做「精确匹配 + 必须唯一」的替换，匹配数不为 1 就报错退出，避免生成错误的配置。
  $steps=@(
    @{Name='listen';Pattern='listen 127\.0\.0\.1:8444 ssl;';Replacement="listen ${ip}:8444 ssl;"},
    @{Name='mime.types';Pattern='include "[^"]*mime\.types";';Replacement="include `"$posixNginx/conf/mime.types`";"},
    @{Name='ssl_certificate_key';Pattern='ssl_certificate_key "[^"]*localhost\.key";';Replacement="ssl_certificate_key `"$posixLocal/lan.key`";"},
    @{Name='ssl_certificate';Pattern='ssl_certificate "[^"]*localhost\.crt";';Replacement="ssl_certificate `"$posixLocal/lan.crt`";"},
    @{Name='proxy_ssl_trusted_certificate';Pattern='proxy_ssl_trusted_certificate "[^"]*localhost\.crt";';Replacement="proxy_ssl_trusted_certificate `"$posixLocal/localhost.crt`";"}
  )
  $result=$source
  foreach($step in $steps){
    $found=[regex]::Matches($result,$step.Pattern)
    if($found.Count -ne 1){throw "deploy/nginx.conf 中 $($step.Name) 的匹配数为 $($found.Count)，预期 1；已停止，未生成局域网配置"}
    $result=[regex]::Replace($result,$step.Pattern,$step.Replacement)
  }
  Set-Content -LiteralPath (Join-Path $localDir 'nginx-lan.conf') -Value $result -Encoding utf8 -NoNewline
  Write-Output '已生成 .local/nginx-lan.conf（对外监听改用局域网地址，后端仍走 127.0.0.1:8443）'
}

function Confirm-Firewall([string]$ip){
  $existing=$null
  try{$existing=Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue}catch{$existing=$null}
  if($existing){Write-Output "防火墙规则已存在：$ruleName";return $true}
  $command="New-NetFirewallRule -DisplayName `"$ruleName`" -Direction Inbound -Protocol TCP -LocalPort 8444 -Action Allow -Profile Private -RemoteAddress LocalSubnet"
  if($OpenFirewall){
    try{
      New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort 8444 -Action Allow -Profile Private -RemoteAddress LocalSubnet | Out-Null
      Write-Output "已添加防火墙规则：$ruleName（仅限专用网络、仅限本地子网）"
      return $true
    }catch{
      Write-Output "添加防火墙规则失败（通常需要管理员权限）：$($_.Exception.Message)"
    }
  }
  Write-Output ''
  Write-Output '注意：本机防火墙可能拦截手机的连接。如需放行，请用【管理员】PowerShell 执行：'
  Write-Output "  $command"
  Write-Output "（或重新运行本脚本时加上 -OpenFirewall）"
  return $false
}

Write-Output "局域网演示地址：https://${Address}:8444"
New-LanCertificate $Address
New-LanNginxConf $Address
$firewallReady=Confirm-Firewall $Address

# 清理上次异常退出留下的失效 pid 文件，否则 stop.ps1 会找不到进程而报错
$nginxPidFile=Join-Path $nginxRoot 'logs/nginx.pid'
if(Test-Path -LiteralPath $nginxPidFile){
  $recorded=(Get-Content -LiteralPath $nginxPidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
  $alive=$false
  if("$recorded" -match '^\d+$'){$alive=[bool](Get-Process -Id ([int]$recorded) -ErrorAction SilentlyContinue)}
  if(-not $alive){Remove-Item -LiteralPath $nginxPidFile -Force;Write-Output '清理了失效的 Nginx pid 文件'}
}
# 先完全停止，避免旧的 Nginx 仍占着 8444
try{& "$PSScriptRoot/stop.ps1" | Out-Null}catch{Write-Output "停止旧进程时出现提示（可忽略）：$($_.Exception.Message)"}
Write-Output '正在启动应用（后端仍只监听 127.0.0.1:8443）…'
& "$PSScriptRoot/start.ps1" -NoProxy

$prefix=($nginxRoot -replace '\\','/')+'/'
$conf=Join-Path $localDir 'nginx-lan.conf'
& "$nginxRoot/nginx.exe" -p $prefix -c $conf -t
if($LASTEXITCODE -ne 0){throw 'Nginx configuration test failed'}
Start-Process -FilePath "$nginxRoot/nginx.exe" -ArgumentList @('-p',$prefix,'-c',$conf) -WorkingDirectory $nginxRoot -WindowStyle Hidden

$ready=$false
for($i=0;$i -lt 20;$i++){
  try{$health=Invoke-RestMethod "https://${Address}:8444/actuator/health/readiness" -SkipCertificateCheck -TimeoutSec 2;if($health.status -eq 'UP'){$ready=$true;break}}catch{}
  Start-Sleep -Milliseconds 1000
}
Write-Output ''
Write-Output "本机自测（经局域网地址访问）：$(if($ready){'通过，readiness = UP'}else{'未通过，请查看 .local/nginx-1.30.5/logs/error.log'})"
Write-Output ''
Write-Output '手机访问步骤：'
Write-Output "  1. 让手机连上与电脑相同的网络（最省事：用手机开热点，电脑连这个热点）"
Write-Output "  2. 手机浏览器打开：https://${Address}:8444"
Write-Output "  3. 提示证书不受信任属正常（本地自签证书），选择继续访问"
if(-not $firewallReady){Write-Output '  4. 如果一直转圈打不开，多半是防火墙：按上面的命令放行 8444 端口'}
Write-Output ''
Write-Output '安全提示：本机自测通过不代表手机一定能连（防火墙、校园网的 AP 隔离都可能拦截）。'
Write-Output '当前是 demo 身份适配器，任何能打开该地址的人都能直接选「平台管理员」登录；演示结束请立即执行 scripts\stop.ps1。'

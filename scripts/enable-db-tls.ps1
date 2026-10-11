param([string]$AdminPassword,[switch]$Restore)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/env.ps1"
if(-not $AdminPassword){$secure=Read-Host 'PostgreSQL postgres password' -AsSecureString;$AdminPassword=[System.Net.NetworkCredential]::new('',$secure).Password}
$env:PGPASSWORD=$AdminPassword
$backup=Join-Path $projectRoot '.local/pg-tls-before.json'
try {
 if(-not (Test-Path -LiteralPath $backup)){
  $prior=[ordered]@{}
  foreach($key in @('ssl','ssl_cert_file','ssl_key_file')){$prior[$key]=& "$env:PG_BIN/psql.exe" -X -h localhost -U postgres -d postgres -Atc "show $key";if($LASTEXITCODE -ne 0){throw 'Could not inspect PostgreSQL TLS settings'}}
  $prior | ConvertTo-Json | Set-Content -LiteralPath $backup -Encoding utf8
 }
 if($Restore){$values=Get-Content -Raw -LiteralPath $backup | ConvertFrom-Json -AsHashtable}
 else{$values=@{ssl='on';ssl_cert_file=(Join-Path $projectRoot '.local/localhost.crt').Replace('\','/');ssl_key_file=(Join-Path $projectRoot '.local/localhost.key').Replace('\','/')}}
 foreach($key in $values.Keys){$value=([string]$values[$key]).Replace("'","''");"ALTER SYSTEM SET $key = '$value';" | & "$env:PG_BIN/psql.exe" -X -h localhost -U postgres -d postgres -v ON_ERROR_STOP=1 -f -;if($LASTEXITCODE -ne 0){throw 'TLS configuration failed'}}
 & "$env:PG_BIN/psql.exe" -X -h localhost -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'SELECT pg_reload_conf();'
 if($LASTEXITCODE -ne 0){throw 'PostgreSQL reload failed'}
 if(-not $Restore){
  $env:PGPASSWORD=$env:DB_PASSWORD;$env:PGSSLMODE='verify-full';$env:PGSSLROOTCERT=Join-Path $projectRoot '.local/localhost.crt'
  Start-Sleep -Milliseconds 1000
  $ssl=& "$env:PG_BIN/psql.exe" -X -h localhost -U $env:DB_USER -d campus_buddy_mvp2 -Atc 'select ssl from pg_stat_ssl where pid=pg_backend_pid()'
  if($LASTEXITCODE -ne 0 -or $ssl -ne 't'){throw 'Verified PostgreSQL TLS connection failed; settings snapshot preserved for -Restore'}
  $configFile=Join-Path $projectRoot '.local/settings.json';$config=Get-Content -Raw $configFile | ConvertFrom-Json
  $cert=(Join-Path $projectRoot '.local/localhost.crt').Replace('\','/')
  $config.DB_URL="jdbc:postgresql://localhost:5432/campus_buddy_mvp2?sslmode=verify-full&sslrootcert=$cert"
  $config.TEST_DB_URL="jdbc:postgresql://localhost:5432/campus_buddy_mvp2_test?sslmode=verify-full&sslrootcert=$cert"
  $config | Add-Member -NotePropertyName PGSSLMODE -NotePropertyValue 'verify-full' -Force
  $config | Add-Member -NotePropertyName PGSSLROOTCERT -NotePropertyValue $cert -Force
  $config | ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding utf8
  Write-Output 'PostgreSQL TLS verified with certificate and hostname validation.'
 }
}finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}

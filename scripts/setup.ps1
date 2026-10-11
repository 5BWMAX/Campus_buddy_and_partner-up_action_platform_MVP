param([string]$AdminPassword,[string]$PgBin='D:\PostgreSQL18.6\bin')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath "$projectRoot\.local")) {New-Item -ItemType Directory "$projectRoot\.local" | Out-Null}
$configFile="$projectRoot\.local\settings.json"
if (Test-Path -LiteralPath $configFile) {$config=Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json} else {
 function New-Key { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
 $config=[ordered]@{DB_URL='jdbc:postgresql://localhost:5432/campus_buddy_mvp2';DB_USER='campus_mvp2_app';DB_PASSWORD=[guid]::NewGuid().ToString('N');TEST_DB_URL='jdbc:postgresql://localhost:5432/campus_buddy_mvp2_test';TEST_DB_USER='campus_mvp2_test';TEST_DB_PASSWORD=[guid]::NewGuid().ToString('N');APP_CRYPTO_KEY=(New-Key);APP_CURSOR_KEY=(New-Key);TLS_PASSWORD=[guid]::NewGuid().ToString('N');PG_BIN=$PgBin}
 $config | ConvertTo-Json | Set-Content -LiteralPath $configFile -Encoding utf8
 $config=[pscustomobject]$config
}
if (-not $AdminPassword) {$secure=Read-Host 'PostgreSQL postgres password' -AsSecureString;$AdminPassword=[System.Net.NetworkCredential]::new('',$secure).Password}
$env:PGPASSWORD=$AdminPassword
try {
 foreach($entry in @(@{User=$config.DB_USER;Password=$config.DB_PASSWORD;Db='campus_buddy_mvp2'},@{User=$config.TEST_DB_USER;Password=$config.TEST_DB_PASSWORD;Db='campus_buddy_mvp2_test'},@{User=$config.TEST_DB_USER;Password=$config.TEST_DB_PASSWORD;Db='campus_buddy_mvp2_restore'})) {
 $role=$entry.User;$db=$entry.Db;$password=$entry.Password
 $exists=& "$PgBin\psql.exe" -h localhost -p 5432 -U postgres -d postgres -Atc "select 1 from pg_roles where rolname='$role'"
 if($LASTEXITCODE -ne 0){throw 'PostgreSQL authentication failed'}
 if($exists -ne '1'){ "CREATE ROLE $role LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE;" | & "$PgBin\psql.exe" -X -h localhost -U postgres -d postgres -v ON_ERROR_STOP=1 -f -;if($LASTEXITCODE -ne 0){throw 'Role creation failed'} }
 $exists=& "$PgBin\psql.exe" -h localhost -U postgres -d postgres -Atc "select 1 from pg_database where datname='$db'"
 if($exists -ne '1'){ & "$PgBin\createdb.exe" -h localhost -U postgres -O $role $db;if($LASTEXITCODE -ne 0){throw 'Database creation failed'} }
 & "$PgBin\psql.exe" -X -h localhost -U postgres -d postgres -v ON_ERROR_STOP=1 -c "REVOKE CONNECT ON DATABASE $db FROM PUBLIC; GRANT CONNECT ON DATABASE $db TO $role;"
 if($LASTEXITCODE -ne 0){throw 'Database permissions failed'}
 }
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
$java=(Get-Command java).Source;$keytool=Join-Path (Split-Path -Parent $java) 'keytool.exe'
if(-not (Test-Path -LiteralPath "$projectRoot\.local\localhost.p12")) {
 & $keytool -genkeypair -alias localhost -keyalg RSA -keysize 3072 -validity 365 -dname 'CN=localhost,OU=Local Demo,O=Campus Buddy,C=CN' -ext 'SAN=dns:localhost,ip:127.0.0.1' -ext 'BC=ca:true' -keystore "$projectRoot\.local\localhost.p12" -storetype PKCS12 -storepass $config.TLS_PASSWORD -noprompt
 if($LASTEXITCODE -ne 0){throw 'Local TLS certificate generation failed'}
 & $keytool -exportcert -rfc -alias localhost -keystore "$projectRoot\.local\localhost.p12" -storepass $config.TLS_PASSWORD -file "$projectRoot\.local\localhost.crt"
 if($LASTEXITCODE -ne 0){throw 'Certificate export failed'}
}
Write-Output 'Dedicated demo/test/restore databases and local HTTPS certificate are ready.'

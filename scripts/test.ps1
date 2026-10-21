$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
 . "$PSScriptRoot/env.ps1"
 if(-not $env:TEST_AUTH_DB_URL){throw 'TEST_AUTH_DB_URL is required: create the isolated campus_buddy_auth_test database (see docs/formal-auth.md).'}
 $start=Get-Date
 @{passed=$false;status="RUNNING";startedAt=$start.ToString("o")} | ConvertTo-Json | Set-Content docs/evidence/tests.json -Encoding utf8
 & .\mvnw.cmd -B '-Dmaven.repo.local=.local/m2' '-Dspring.test.context.cache.maxSize=2' test
 if($LASTEXITCODE -ne 0){throw 'Tests failed; no success report generated'}
 $reports=@(Get-ChildItem server/target/surefire-reports/TEST-*.xml)
 if($reports.Count -eq 0){throw 'No test reports: refusing false positive'}
 $suites=@();$total=0
 foreach($file in $reports){
  if($file.LastWriteTime -lt $start.AddSeconds(-2)){throw "Stale test report: $($file.Name)"}
  [xml]$xml=Get-Content -Raw -LiteralPath $file.FullName
  $suite=$xml.testsuite
  if([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw "Incomplete test suite: $($suite.name)"}
  $total+=[int]$suite.tests
  $suites+=@{name=[string]$suite.name;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped;seconds=[double]$suite.time}
 }
 @{passed=$true;status="PASSED";executedAt=(Get-Date).ToString('o');databases=@('campus_buddy_mvp2_test','campus_buddy_auth_test');total=$total;skipped=0;suites=$suites} | ConvertTo-Json -Depth 5 | Set-Content docs/evidence/tests.json -Encoding utf8
 Write-Output "PASS: $total tests executed; zero skips; current XML reports verified."
} catch {
 @{passed=$false;status="FAILED";finishedAt=(Get-Date).ToString("o");error=$_.Exception.Message} | ConvertTo-Json | Set-Content docs/evidence/tests.json -Encoding utf8
 throw
} finally {Pop-Location}

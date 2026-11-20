# Start the pinned private Ditto stack and wait for an authenticated native read (Windows PowerShell).
param([switch]$Fresh)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..\deploy\ditto')
try {
  if ($Fresh) { docker compose down -v }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  docker compose up -d
  $api = if ($env:DITTO_API_BASE) { $env:DITTO_API_BASE } else { 'http://127.0.0.1:8090/api/2' }
  $pw = if ($env:TWIN_DITTO_READER_PASSWORD) { $env:TWIN_DITTO_READER_PASSWORD } else { 'test-only-reader-secret' }
  $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("naruprof-twin-reader:$pw"))
  while ($true) {
    try { Invoke-RestMethod -Uri "$api/whoami" -Headers @{ Authorization = $auth } | Out-Null; break } catch { }
    if ($sw.Elapsed.TotalSeconds -gt 400) { throw 'Ditto not ready after 400s' }
    Start-Sleep -Seconds 1
  }
  "Ditto 3.9.8 ready after {0:N0}s at {1}" -f $sw.Elapsed.TotalSeconds, $api
} finally { Pop-Location }

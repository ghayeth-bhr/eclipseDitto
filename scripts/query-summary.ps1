# Windows variant of query-summary.sh:  .\scripts\query-summary.ps1 -Mode twin|vertx|kong [-Expected 3]
param([ValidateSet('twin','vertx','kong')][string]$Mode = 'twin', [string]$Expected)
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envFile = if ($env:TWIN_ENV) { $env:TWIN_ENV } else { Join-Path $root 'deploy\twin.env.example' }
Get-Content $envFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $k, $v = $_ -split '=', 2; if (-not (Test-Path "env:$k")) { Set-Item "env:$k" $v } }
if (-not $Expected) { try { $Expected = (Invoke-RestMethod 'http://127.0.0.1:9093/head').sequence } catch { } }
switch ($Mode) {
  'twin'  { $url = "http://127.0.0.1:$($env:TWIN_PORT)/internal/v1/profiles/$($env:NARUPROF_PROFILE_ID)/summary"; $h = @{ Authorization = "Bearer $($env:VERTX_TO_TWIN_TOKEN)" } }
  'vertx' { $url = "$(if ($env:VERTX_URL) { $env:VERTX_URL } else { 'http://127.0.0.1:8081' })/api/naruprof/twin/summary"; $h = @{ Authorization = "Bearer $($env:GATEWAY_TO_VERTX_TOKEN)" } }
  'kong'  { $o = if ($env:PUBLIC_ORIGIN) { $env:PUBLIC_ORIGIN } else { 'http://127.0.0.1:8080' }; $url = "$o/api/naruprof/twin/summary"; $h = @{ Origin = $o; 'Sec-Fetch-Site' = 'same-origin' } }
}
$r = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $h -SkipHttpErrorCheck -ErrorAction SilentlyContinue
"GET $url -> $($r.StatusCode)"; $r.Content
$got = ($r.Content | ConvertFrom-Json).sequence
if ($Expected) { if ($got -eq $Expected) { "OK: applied revision $got == feed revision $Expected" } else { "LAG: applied $got, feed $Expected"; exit 3 } }

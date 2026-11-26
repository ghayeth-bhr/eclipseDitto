# Integrated (real NARU) start for the Windows twin host. Validates the effective configuration
# (environment wins over .env, as in docker compose), starts the integrated stack WITHOUT any fake
# NARU, then verifies from INSIDE the twin container that NARU's gRPC port is reachable and that the
# published facade answers readiness. Never prints token values.
#
#   .\scripts\integrated-up.ps1               validate + docker compose up -d
#   .\scripts\integrated-up.ps1 -Build        also rebuild the twin image
#   .\scripts\integrated-up.ps1 -CheckOnly    validate configuration only
#   .\scripts\integrated-up.ps1 -RouteOnly    only re-run the in-container route + readiness checks
param([switch]$Build, [switch]$CheckOnly, [switch]$RouteOnly, [int]$ReadyTimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $root
try {
  $fromFile = @{}
  $envFile = Join-Path $root '.env'
  if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
      $line = ($_ -replace '\s+#.*$', '').Trim()
      if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') { $fromFile[$Matches[1]] = $Matches[2].Trim() }
    }
  }
  function Get-Setting([string]$name) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v)) { $v = $fromFile[$name] }
    if ($null -eq $v) { return '' } else { return $v.Trim() }
  }

  $problems = New-Object System.Collections.Generic.List[string]
  $target = Get-Setting 'NARU_GRPC_TARGET'
  $naruHost = $null; $naruPort = $null
  if ($target -notmatch '^([^:\s]+):(\d{1,5})$') {
    $problems.Add('NARU_GRPC_TARGET must be <NARU_TS_IP>:9092 (it is blank or malformed)')
  } else {
    $naruHost = $Matches[1]; $naruPort = [int]$Matches[2]
    if ($naruHost -match '^(fake-naru|localhost|127\.|0\.0\.0\.0)') {
      $problems.Add("NARU_GRPC_TARGET host '$naruHost' is local/fake; integrated mode needs the real NARU tailnet address")
    }
  }
  $profileId = Get-Setting 'NARUPROF_PROFILE_ID'
  if ($profileId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
    $problems.Add('NARUPROF_PROFILE_ID must be the lowercase UUID already installed by NARU')
  }
  $tokens = @{}
  foreach ($t in 'VERTX_TO_TWIN_TOKEN', 'TWIN_TO_NARU_TOKEN', 'TWIN_MAINTENANCE_TOKEN') {
    $v = Get-Setting $t
    if ($v.Length -lt 16) { $problems.Add("$t is missing or shorter than 16 characters") }
    elseif ($v -match '^(test-only|change-me)') { $problems.Add("$t still holds a placeholder value; load the agreed value privately") }
    $tokens[$t] = $v
  }
  if (($tokens.Values | Where-Object { $_ } | Sort-Object -Unique).Count -ne ($tokens.Values | Where-Object { $_ }).Count) {
    $problems.Add('VERTX_TO_TWIN_TOKEN, TWIN_TO_NARU_TOKEN and TWIN_MAINTENANCE_TOKEN must be distinct')
  }
  $publish = Get-Setting 'TWIN_PUBLISH_ADDRESS'
  $publishPort = Get-Setting 'TWIN_PUBLISH_PORT'
  if (-not $publishPort) { $publishPort = '8083' }
  if (-not $publish) { $problems.Add('TWIN_PUBLISH_ADDRESS must be set (Windows Tailscale IP, or 127.0.0.1 with TWIN_PUBLISH_PORT=18083)') }
  elseif ($publish -eq '0.0.0.0') { $problems.Add('TWIN_PUBLISH_ADDRESS=0.0.0.0 would expose the facade on every interface') }

  if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "CONFIG: $_" -ForegroundColor Red }
    throw 'Integrated configuration incomplete; nothing was started.'
  }
  Write-Host "config ok: source=${target} profile=${profileId} facade=${publish}:${publishPort} (tokens loaded, not shown)"
  if ($CheckOnly) { return }

  if (-not $RouteOnly) {
    $upArgs = @('compose', 'up', '-d')
    if ($Build) { $upArgs += '--build' }
    & docker @upArgs
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
  }

  # 1) the ACTUAL consumer's route: TCP connect to NARU from inside the twin container
  $probe = "timeout 5 bash -c '</dev/tcp/$naruHost/$naruPort' && echo reachable"
  $ErrorActionPreference = 'Continue'   # native stderr must not abort the diagnostics (PS 5.1)
  $route = & docker compose exec -T twin bash -c $probe 2>&1 | ForEach-Object { "$_" }
  if ($LASTEXITCODE -eq 0 -and "$route" -match 'reachable') {
    Write-Host "container route to NARU ${naruHost}:${naruPort}: reachable"
  } else {
    Write-Host "container route to NARU ${naruHost}:${naruPort}: NOT reachable ($route)" -ForegroundColor Red
    Write-Host '  check: Tailscale up on this host, NARU listening on its tailnet IP, tailnet grant ditto->naru tcp:9092' -ForegroundColor Red
  }

  # 2) readiness through the published facade (bounded wait; core learning never waits for this)
  $readyUrl = "http://${publish}:${publishPort}/internal/health/ready"
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $last = ''
  while ($sw.Elapsed.TotalSeconds -lt $ReadyTimeoutSeconds) {
    $last = & curl.exe -s --max-time 5 -o NUL -w '%{http_code}' $readyUrl
    if ($last -eq '200') { break }
    Start-Sleep -Seconds 2
  }
  Write-Host "readiness $readyUrl -> $last after $([int]$sw.Elapsed.TotalSeconds)s"
  if ($last -ne '200') {
    Write-Host 'not ready yet: see "docker compose logs --tail 80 twin" (look for source failure / ditto failure)' -ForegroundColor Yellow
  }
} finally { Pop-Location }

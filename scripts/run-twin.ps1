# Standalone twin service (Windows). Settings from $env:TWIN_ENV or deploy\twin.env.example (test-only).
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envFile = if ($env:TWIN_ENV) { $env:TWIN_ENV } else { Join-Path $root 'deploy\twin.env.example' }
Get-Content $envFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
  $k, $v = $_ -split '=', 2; if (-not (Test-Path "env:$k")) { Set-Item -Path "env:$k" -Value $v }
}
java -jar (Join-Path $root 'twin-app\target\naruprof-twin-exec.jar')

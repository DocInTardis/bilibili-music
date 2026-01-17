$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot) | Out-Null
try {
  $root = Get-Location
  $compose = Join-Path $root "docker-compose.dev.yml"
  if (-not (Test-Path $compose)) {
    throw "Missing docker-compose.dev.yml at $compose"
  }

  docker compose -f $compose up -d

  Write-Host ""
  Write-Host "Dev services are up."
  Write-Host "Next:"
  Write-Host "  1) Init DB schema:"
  Write-Host "     Get-Content backup.sql | docker compose -f docker-compose.dev.yml exec -T mysql mysql -uroot -proot bilibili"
  Write-Host "  2) Start app:"
  Write-Host "     `$env:MYSQL_PASSWORD='root'"
  Write-Host "     mvn spring-boot:run"
  Write-Host ""
  Write-Host "Optional observability stack:"
  Write-Host "  docker compose -f docker-compose.dev.yml -f observability/docker-compose.observability.yml up -d"
} finally {
  Pop-Location | Out-Null
}


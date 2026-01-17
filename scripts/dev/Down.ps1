$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot) | Out-Null
try {
  docker compose -f docker-compose.dev.yml down
} finally {
  Pop-Location | Out-Null
}


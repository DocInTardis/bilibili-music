# Dev setup (Windows / Docker)

This repo supports running the infra locally via Docker Compose (MySQL + Redis), then starting the Spring Boot app with Maven.

## 1) Start MySQL + Redis

```powershell
docker compose -f docker-compose.dev.yml up -d
```

Or one-liner:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/Up.ps1
```

## 2) Initialize database schema

```powershell
Get-Content backup.sql | docker compose -f docker-compose.dev.yml exec -T mysql mysql -uroot -proot bilibili
```

## 3) Start the app

```powershell
$env:MYSQL_PASSWORD="root"
mvn spring-boot:run
```

Open: `http://localhost:8080`

## Optional: observability stack

```powershell
docker compose -f docker-compose.dev.yml -f observability/docker-compose.observability.yml up -d
```

Grafana: `http://localhost:3000` (admin / admin)


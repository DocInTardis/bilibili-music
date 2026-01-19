# Observability (OpenTelemetry + Prometheus + Grafana)

This project exposes:

- **Tracing (OpenTelemetry)** via Micrometer Tracing (trace/span IDs are added to logs and WebSocket payloads)
- **Metrics (Prometheus)** via Spring Boot Actuator

## Quick start (metrics)

Start the app, then open:

- Prometheus scrape endpoint: `http://localhost:8083/actuator/prometheus`
- Actuator metrics endpoint: `http://localhost:8083/actuator/metrics`
- Tracing is disabled by default (`TRACING_ENABLED=false`). Set `TRACING_ENABLED=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to enable.

## Tracing (OTLP export)

By default the OTLP HTTP endpoint is:

- `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (default: `http://localhost:4318/v1/traces`)

To run a local collector (example), point it to your collector and set:

```powershell
$env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:4318/v1/traces"
```

## Log / WS correlation fields

The app correlates these fields across logs / spans / WS messages (when available):

- `traceId`, `spanId`
- `sessionId` (conversationId when available)
- `executionId` (graph execution id)
- `nodeName`
- `promptVersion`

WS messages include this under `payload.trace`.

## Prometheus + Grafana (docker compose)

Files:

- `observability/docker-compose.observability.yml`
- `observability/prometheus/prometheus.yml`
- `observability/grafana/`

Run:

```powershell
docker compose -f observability/docker-compose.observability.yml up -d
```

Then open Grafana:

- `http://localhost:3000` (admin / admin)

# Windows Desktop One-Click Start

Goal: put a launcher on your Desktop so you can double-click to start the service and open the UI.

## Option A (recommended): create a Desktop shortcut

Run from repo root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/windows/Install-Desktop-Shortcut.ps1
```

This creates `Bilibili Music.lnk` on your Desktop.

## Option B: copy the launcher to Desktop

Copy `scripts/windows/Start-Bilibili-Music.cmd` to your Desktop and double-click it.

## Optional: configure env vars via `.env.desktop`

1. Copy `.env.desktop.example` to `.env.desktop`
2. Fill in `OLLAMA_*` / `REDIS_*` / `MYSQL_*` as needed
3. Launch again

## Launcher logic (Windows)

- Auto-starts Ollama when `OLLAMA_BASE_URL` points to localhost (disable with `BILIBILI_MUSIC_OLLAMA_AUTO_START=false`).
- Rebuilds when sources change, repo is dirty, or jar is missing; otherwise runs the latest jar.

## Notes

- First run may require Playwright browser install (see `USAGE.md`).
- Default port is `8080` (override with `SERVER_PORT`).

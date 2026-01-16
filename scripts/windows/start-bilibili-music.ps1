$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    $here = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $here "..\\..")).Path
}

function Import-EnvFile([string]$path) {
    if (-not (Test-Path $path)) {
        return
    }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0) { return }
        if ($line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if (($value.StartsWith("\"") -and $value.EndsWith("\"")) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($key.Length -gt 0) {
            [System.Environment]::SetEnvironmentVariable($key, $value, "Process")
        }
    }
}

function Ensure-Command([string]$name, [string]$help) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "Missing required command '$name'. $help"
    }
}

function Get-JarPath([string]$repoRoot) {
    $target = Join-Path $repoRoot "target"
    if (-not (Test-Path $target)) { return $null }
    $jar = Get-ChildItem -Path $target -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { return $null }
    return $jar.FullName
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot

Import-EnvFile (Join-Path $repoRoot ".env.desktop")

if (-not $env:JAVA_HOME) {
    # ok; rely on PATH
}
Ensure-Command "java" "Install JDK 17 and ensure 'java' is on PATH."

$serverPort = 8080
if ($env:SERVER_PORT) {
    [void][int]::TryParse($env:SERVER_PORT, [ref]$serverPort)
}

$forceRebuild = $false
if ($env:BILIBILI_MUSIC_FORCE_REBUILD) {
    $v = $env:BILIBILI_MUSIC_FORCE_REBUILD.ToLowerInvariant()
    $forceRebuild = ($v -eq "1" -or $v -eq "true" -or $v -eq "yes")
}

$jarPath = Get-JarPath $repoRoot
if ($forceRebuild -or -not $jarPath) {
    Ensure-Command "mvn" "Install Maven and ensure 'mvn' is on PATH."
    Write-Host "[DesktopStart] Building jar (skip tests)..."
    mvn -q -DskipTests package
    $jarPath = Get-JarPath $repoRoot
    if (-not $jarPath) {
        throw "Build succeeded but no jar found under 'target/'."
    }
}

Write-Host "[DesktopStart] Starting: $jarPath"
Write-Host "[DesktopStart] Port: $serverPort"
Write-Host "[DesktopStart] Tip: set env vars via .env.desktop"

$openBrowser = $true
if ($env:BILIBILI_MUSIC_OPEN_BROWSER) {
    $v = $env:BILIBILI_MUSIC_OPEN_BROWSER.ToLowerInvariant()
    $openBrowser = -not ($v -eq "0" -or $v -eq "false" -or $v -eq "no")
}

$browserJob = $null
if ($openBrowser) {
    $browserJob = Start-Job -ArgumentList @($serverPort) -ScriptBlock {
        param([int]$port)
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            try {
                $ok = Test-NetConnection -ComputerName "127.0.0.1" -Port $port -WarningAction SilentlyContinue
                if ($ok.TcpTestSucceeded) {
                    Start-Process ("http://localhost:$port/") | Out-Null
                    return
                }
            } catch {
            }
            Start-Sleep -Milliseconds 250
        }
    }
}

Write-Host "[DesktopStart] Close this window to stop the app."
try {
    & java -jar $jarPath
} finally {
    if ($browserJob) {
        Stop-Job $browserJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $browserJob -Force -ErrorAction SilentlyContinue | Out-Null
    }
}

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
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
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

function Get-LatestSourceTime([string]$repoRoot) {
    $paths = @(
        (Join-Path $repoRoot "src"),
        (Join-Path $repoRoot "pom.xml")
    )
    $latest = $null
    foreach ($p in $paths) {
        if (-not (Test-Path $p)) { continue }
        $items = Get-ChildItem -Path $p -Recurse -File -ErrorAction SilentlyContinue
        if (-not $items) { continue }
        $candidate = $items | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($candidate) {
            if (-not $latest -or $candidate.LastWriteTime -gt $latest) {
                $latest = $candidate.LastWriteTime
            }
        }
    }
    return $latest
}

function Get-GitStatusDirty([string]$repoRoot) {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { return $false }
    if (-not (Test-Path (Join-Path $repoRoot ".git"))) { return $false }
    $status = & git -C $repoRoot status --porcelain
    if ($LASTEXITCODE -ne 0) { return $false }
    return -not [string]::IsNullOrWhiteSpace($status)
}

function Get-HeadCommitTime([string]$repoRoot) {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { return $null }
    $ts = & git -C $repoRoot show -s --format=%ct HEAD
    if ($LASTEXITCODE -ne 0) { return $null }
    if ([string]::IsNullOrWhiteSpace($ts)) { return $null }
    return [DateTimeOffset]::FromUnixTimeSeconds([int64]$ts).UtcDateTime
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

function Test-OllamaRunning([string]$baseUrl) {
    try {
        $pingUrl = ($baseUrl.TrimEnd("/") + "/api/tags")
        $null = Invoke-RestMethod -Uri $pingUrl -Method Get -TimeoutSec 2
        return $true
    } catch {
        return $false
    }
}

function Start-OllamaIfNeeded([string]$baseUrl, [string]$model) {
    $uri = $null
    try {
        $uri = [uri]$baseUrl
    } catch {
        return
    }
    if ($uri.Host -ne "localhost" -and $uri.Host -ne "127.0.0.1") {
        return
    }

    $auto = $true
    if ($env:BILIBILI_MUSIC_OLLAMA_AUTO_START) {
        $v = $env:BILIBILI_MUSIC_OLLAMA_AUTO_START.ToLowerInvariant()
        $auto = -not ($v -eq "0" -or $v -eq "false" -or $v -eq "no")
    }
    if (-not $auto) {
        return
    }

    if (Test-OllamaRunning $baseUrl) {
        Write-Host "[DesktopStart] Ollama already running."
        return
    }

    Ensure-Command "ollama" "Install Ollama and ensure 'ollama' is on PATH."
    Write-Host "[DesktopStart] Starting Ollama..."
    Start-Process -FilePath "ollama" -ArgumentList "serve" -WindowStyle Minimized | Out-Null

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (Test-OllamaRunning $baseUrl) {
            Write-Host "[DesktopStart] Ollama is ready."
            return
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "[DesktopStart] Warning: Ollama did not become ready in time."
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

$ollamaBase = "http://localhost:11434"
if ($env:OLLAMA_BASE_URL) {
    $ollamaBase = $env:OLLAMA_BASE_URL
}
$ollamaModel = "qwen:7b"
if ($env:OLLAMA_MODEL) {
    $ollamaModel = $env:OLLAMA_MODEL
}
Start-OllamaIfNeeded $ollamaBase $ollamaModel

$jarPath = Get-JarPath $repoRoot
$dirty = Get-GitStatusDirty $repoRoot
$headCommitTime = Get-HeadCommitTime $repoRoot
$latestSourceTime = Get-LatestSourceTime $repoRoot
$jarTime = $null
if ($jarPath) {
    $jarTime = (Get-Item $jarPath).LastWriteTime
}

$needsRebuild = $forceRebuild -or -not $jarPath
if (-not $needsRebuild -and $dirty) {
    $needsRebuild = $true
}
if (-not $needsRebuild -and $headCommitTime -and $jarTime -and $jarTime.ToUniversalTime() -lt $headCommitTime) {
    $needsRebuild = $true
}
if (-not $needsRebuild -and $latestSourceTime -and $jarTime -and $latestSourceTime -gt $jarTime) {
    $needsRebuild = $true
}

if ($needsRebuild) {
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

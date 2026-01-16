$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\\..")).Path
$targetCmd = (Resolve-Path (Join-Path $scriptDir "Start-Bilibili-Music.cmd")).Path

$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "Bilibili Music.lnk"

$wsh = New-Object -ComObject WScript.Shell
$shortcut = $wsh.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $targetCmd
$shortcut.WorkingDirectory = $repoRoot
$shortcut.WindowStyle = 1
$shortcut.Description = "Start bilibili-music (Spring Boot)"
$shortcut.Save()

Write-Host "Created desktop shortcut: $shortcutPath"


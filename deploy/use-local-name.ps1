<#
.SYNOPSIS
    Point a name at this PC's own hosts file, so MURRAY PTS answers at http://murraypts.mn:8080.

.DESCRIPTION
    The hosts file is the only way to give a machine a private domain name without registering one,
    and Windows protects it: Administrators only. That is why this script exists - run it once from
    an elevated window, and run it again with -Action remove to undo.

    A hosts entry is per-machine. Nothing else on the network (or the internet) learns the name, so
    colleagues keep using http://<this pc>:8080 or their own copy of this script. It maps to
    127.0.0.1 rather than 192.168.x.x on purpose: loopback keeps the app reachable only from this
    computer, which is what a "local domain" means here.

    The app needs no change at all. Ktor binds PTS_HOST:PTS_PORT and ignores which name the browser
    used, so the name is purely a resolution question. A hosts file cannot carry a port, so the
    entry is the bare name and :8080 stays in the URL.

    Keep this file pure ASCII: Windows PowerShell 5.1 reads an extension-less-BOM .ps1 as ANSI and
    turns typographic dashes into parser errors.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File deploy\use-local-name.ps1
    # adds:  127.0.0.1    murraypts.mn

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File deploy\use-local-name.ps1 -Action remove
#>
[CmdletBinding()]
param(
    [ValidateSet('add', 'remove', 'status')]
    [string]$Action = 'add',
    [string]$Name = 'murraypts.mn',
    [string]$Address = '127.0.0.1',
    # Absolute path, not TEMP: an elevated run is a different process and the caller must be able to
    # read the result back from a place that does not depend on which profile loaded.
    [string]$LogPath = 'C:\Users\user\Documents\Qoder\2026-10-05\d459db4c\f3690155\deploy\local-name.log'
)

$ErrorActionPreference = 'Stop'
$hostsFile = Join-Path $env:WINDIR 'System32\drivers\etc\hosts'
$lines = [System.Collections.Generic.List[string]]([string[]](Get-Content -LiteralPath $hostsFile))

function Write-Log([string]$text) {
    $line = (Get-Date).ToString('s') + ' ' + $text
    $logDir = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
    Add-Content -LiteralPath $LogPath -Value $line -Encoding ascii
    Write-Host $line
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-Resolves([string]$name) {
    $ips = @(Resolve-DnsName -Name $name -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress } | ForEach-Object IPAddress)
    if ($ips.Count) { $ips -join ', ' } else { 'NOTHING YET' }
}

# Only uncommented lines count; a "# 127.0.0.1 murraypts.mn" note in the file does nothing.
$pattern = '(^|\s)' + [regex]::Escape($Name) + '(\s|$)'
$existing = @($lines | Where-Object { $_ -notmatch '^\s*#' -and $_ -match $pattern })

Write-Log ('action   : ' + $Action + ' | name: ' + $Name + ' | admin: ' + (Test-Admin))

if ($Action -eq 'status') {
    Write-Log ('entries  : ' + $(if ($existing.Count) { $existing -join ' | ' } else { 'none' }))
    Write-Log ('resolves : ' + (Get-Resolves $Name))
    exit 0
}

if (-not (Test-Admin)) {
    Write-Log ('REFUSED  : editing ' + $hostsFile + ' needs Administrator rights. Re-run elevated.')
    exit 1
}

# One timestamped copy per run, next to the log, so nothing accumulates inside Windows.
$dir = Split-Path -Parent $LogPath
$backup = Join-Path $dir ('hosts.before-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.bak')
Copy-Item -LiteralPath $hostsFile -Destination $backup -Force
Write-Log ('backup   : ' + $backup)

if ($Action -eq 'add') {
    if ($existing.Count) {
        Write-Log ('already  : ' + ($existing -join ' | ') + ' - nothing written')
    } else {
        $lines.Add($Address + "`t" + $Name)
        Write-Log ('added    : ' + $Address + ' ' + $Name)
    }
} else {
    $kept = @($lines | Where-Object { -not ($_ -notmatch '^\s*#' -and $_ -match $pattern) })
    $lines.Clear()
    $lines.AddRange([string[]]$kept)
    Write-Log ('removed  : every active line for ' + $Name)
}

# CRLF, no BOM: notepad and the resolver both expect that shape.
[System.IO.File]::WriteAllLines($hostsFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Log ('written  : ' + $hostsFile + ' [' + $lines.Count + ' lines]')

# The resolver caches, so without this the new name can keep answering NXDOMAIN for minutes.
& ipconfig.exe /flushdns | Out-Null
Write-Log 'flushed  : DNS resolver cache'
Write-Log ('resolves : ' + (Get-Resolves $Name))

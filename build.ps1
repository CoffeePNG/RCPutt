<#
.SYNOPSIS
    Build helper for Windows: RCPuttPutt targets Java 25, which is usually not the shell default.

.DESCRIPTION
    The PowerShell counterpart to build.sh. Finds a JDK 25 rather than hardcoding a path, since
    Windows installs them under a different vendor directory for every JDK distribution.

    Override explicitly:  $env:JAVA_HOME_25 = 'C:\Program Files\Java\jdk-25'; .\build.ps1 package

.EXAMPLE
    .\build.ps1 package
.EXAMPLE
    .\build.ps1 --java-home      # just report the JDK found, for install-deps.ps1
#>
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $MavenArgs)

$ErrorActionPreference = 'Stop'

function Test-Jdk25 {
    # Not $Home: that is a read-only automatic variable in PowerShell and binding it as a
    # parameter fails at runtime, long after the script has parsed cleanly.
    param([string] $JdkHome)
    if ([string]::IsNullOrWhiteSpace($JdkHome)) { return $false }
    # PowerShell 7 also runs on Linux and macOS, where javac has no .exe suffix.
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    if (-not (Test-Path $javac)) { $javac = Join-Path $JdkHome 'bin/javac' }
    if (-not (Test-Path $javac)) { return $false }
    # javac writes its version to stdout on modern JDKs and stderr on older ones; merge both.
    $version = & $javac -version 2>&1 | Out-String
    return $version -match '\s25\.'
}

$found = $null

# 1. Explicit override always wins.
if ($env:JAVA_HOME_25) {
    if (-not (Test-Jdk25 $env:JAVA_HOME_25)) {
        # A plain message and a non-zero exit, not a PowerShell error record: this is a
        # configuration mistake to read and fix, not a stack trace to debug.
        Write-Host "build.ps1: JAVA_HOME_25 ($env:JAVA_HOME_25) is not a JDK 25." -ForegroundColor Red
        exit 1
    }
    $found = $env:JAVA_HOME_25
}

# 2. An already-correct JAVA_HOME is left alone.
if (-not $found -and (Test-Jdk25 $env:JAVA_HOME)) {
    $found = $env:JAVA_HOME
}

# 3. The usual install roots. Every vendor picks its own folder, so glob rather than guess.
if (-not $found) {
    $roots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\BellSoft",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        foreach ($dir in Get-ChildItem $root -Directory -ErrorAction SilentlyContinue) {
            if ($dir.Name -match '25' -and (Test-Jdk25 $dir.FullName)) {
                $found = $dir.FullName
                break
            }
        }
        if ($found) { break }
    }
}

if (-not $found) {
    Write-Host "build.ps1: could not find a JDK 25." -ForegroundColor Red
    Write-Host "  RCPuttPutt targets Java 25 (Purpur 26.2)."
    Write-Host "  winget install EclipseAdoptium.Temurin.25.JDK    then re-run"
    Write-Host "  Or point at one directly:"
    Write-Host "    `$env:JAVA_HOME_25 = 'C:\path\to\jdk-25'; .\build.ps1 $MavenArgs"
    exit 1
}

$env:JAVA_HOME = $found

# `.\build.ps1 --java-home` just reports the JDK it found, so install-deps.ps1 can build the RC
# dependencies with the same one rather than whatever happens to be on PATH.
if ($MavenArgs -and $MavenArgs.Count -ge 1 -and $MavenArgs[0] -eq '--java-home') {
    Write-Output $found
    exit 0
}

Write-Host "build.ps1: using JAVA_HOME=$env:JAVA_HOME"

# mvn ships as a .cmd on Windows, which PowerShell will not resolve as a bare command name.
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Host "build.ps1: Maven is not on PATH." -ForegroundColor Red
    Write-Host "  winget install Apache.Maven    then reopen this shell"
    exit 1
}
& $mvn.Source @MavenArgs
exit $LASTEXITCODE

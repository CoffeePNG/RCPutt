<#
.SYNOPSIS
    Installs RCPuttPutt's RC dependencies into your local .m2 (Windows).

.DESCRIPTION
    The PowerShell counterpart to install-deps.sh.

    None of RCPlatform, RCUI or RCParties is published to a public Maven repository, so a plain
    `mvn package` fails with "Could not find artifact net.republicraft:RCUI / rcparties-api".
    They have to be built locally first, and in this order: RCPlatform provides rcplatform-api,
    which RCUI needs.

    RCPlatform and RCUI are Bobo's, part of the wider RepubliCraft framework - this only builds
    them, it never modifies them. Point the *Url parameters at whichever remote you use.

.EXAMPLE
    .\install-deps.ps1
.EXAMPLE
    .\install-deps.ps1 -WorkDir C:\src -RcPartiesBranch legacy/1.21.11
#>
[CmdletBinding()]
param(
    [string] $RcPlatformUrl = 'https://github.com/CoffeePNG/rcplatform',
    [string] $RcUiUrl       = 'https://github.com/CoffeePNG/rcui',
    [string] $RcPartiesUrl  = 'https://github.com/CoffeePNG/rcparties',

    # RCParties is branched per target. Its API is compiled to each branch's bytecode level, so the
    # 26.2 artifact will not load on Java 21 and vice versa. Both branches install to the same
    # coordinates, so whichever you build LAST is the one in your .m2.
    [string] $RcPartiesBranch = 'claude/running-agentic-i3mb79',

    # Clones land beside the RCPuttPutt checkout, not inside it.
    [string] $WorkDir = (Split-Path -Parent $PSScriptRoot),

    # Override if your Maven uses a non-default local repository.
    [string] $LocalRepo = (Join-Path $env:USERPROFILE '.m2\repository')
)

$ErrorActionPreference = 'Stop'

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Host "install-deps.ps1: Maven is not on PATH." -ForegroundColor Red
    Write-Host "  winget install Apache.Maven    then reopen this shell"
    exit 1
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "install-deps.ps1: git is not on PATH." -ForegroundColor Red
    exit 1
}

# All three dependencies target Java 25, same as RCPuttPutt. Reuse build.ps1's JDK detection so they
# are not built with whatever JDK happens to be on PATH - a mismatch here produces an artifact that
# fails at runtime rather than at build time.
$jdk25 = & (Join-Path $PSScriptRoot 'build.ps1') --java-home
if ($LASTEXITCODE -ne 0 -or -not $jdk25) {
    exit 1   # build.ps1 has already explained what is missing and how to fix it.
}
$env:JAVA_HOME = $jdk25.Trim()
Write-Host "Using JAVA_HOME=$env:JAVA_HOME"

# Where each dependency lands, so a build can be checked rather than assumed. The version comes
# from RCPuttPutt's own pom, so the check answers the question that actually matters: will the
# plugin build against what is now installed?
$PomVersions = @{}
$pomPath = Join-Path $PSScriptRoot 'pom.xml'
if (Test-Path $pomPath) {
    $pomXml = [xml](Get-Content $pomPath -Raw)
    foreach ($key in 'rcui.version', 'rcplatform.version', 'rcparties.version') {
        $PomVersions[$key] = $pomXml.project.properties.$key
    }
}

$ArtifactPaths = @{
    'rcplatform' = @{ Path = 'net\republicraft\platform\rcplatform-api'; Key = 'rcplatform.version'; Artifact = 'rcplatform-api' }
    'rcui'       = @{ Path = 'net\republicraft\RCUI';                     Key = 'rcui.version';       Artifact = 'RCUI' }
    'rcparties'  = @{ Path = 'gg\rc\rcparties-api';                       Key = 'rcparties.version';  Artifact = 'rcparties-api' }
}

# RCPuttPutt needs only RCParties' API module. Building the whole reactor also builds its plugin,
# which pulls in its own pinned RCUI - a version that need not match the RCUI you have, and whose
# failure would stop the install for a module nothing here consumes.
$ModuleArgs = @{ 'rcparties' = @('-pl', 'rcparties-api', '-am') }

<#
Removes Maven's cached "could not find this artifact" markers for the RC coordinates.

A plain `mvn package` before these are installed fails, and Maven remembers the miss - that is the
"was not found ... during a previous attempt. This failure was cached in the local repository and
resolution is not reattempted until the update interval has elapsed" message. Installing the
artifact afterwards does not always clear it, so a build can keep failing on a dependency that is
now sitting in the local repository. Deleting the markers costs nothing: they are a cache, and
Maven rewrites them as needed.
#>
function Clear-ResolverCache {
    if (-not (Test-Path $LocalRepo)) { return }
    $roots = @('net\republicraft', 'gg\rc') |
        ForEach-Object { Join-Path $LocalRepo $_ } |
        Where-Object { Test-Path $_ }
    if (-not $roots) { return }

    $stale = Get-ChildItem -Path $roots -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '*.lastUpdated' -or $_.Name -eq 'resolver-status.properties' }
    if ($stale) {
        Write-Host "==> clearing $($stale.Count) cached resolution failure(s) from $LocalRepo"
        $stale | Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

# A build that "succeeds" without producing the artifact leaves the next dependency failing on a
# missing jar, several confusing steps away from the cause. Check here instead.
function Assert-Installed {
    param([string] $Name)

    $spec = $ArtifactPaths[$Name]
    if (-not $spec) { return }
    $wanted = $PomVersions[$spec.Key]
    if (-not $wanted) { return }

    # The EXACT version, not merely some jar in the tree: an old build of a different version
    # sitting in the local repository would otherwise read as success and push the real failure
    # downstream, which is precisely the confusion this check exists to prevent.
    $jar = Join-Path (Join-Path (Join-Path $LocalRepo $spec.Path) $wanted) "$($spec.Artifact)-$wanted.jar"
    if (Test-Path $jar) {
        Write-Host "    installed: $jar" -ForegroundColor DarkGray
        return
    }

    $dir = Join-Path $LocalRepo $spec.Path
    $others = if (Test-Path $dir) {
        (Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name) -join ', '
    } else { '(nothing)' }

    Write-Host ""
    Write-Host "install-deps.ps1: $Name did not install the version RCPuttPutt asks for." -ForegroundColor Red
    Write-Host "  wanted:  $($spec.Artifact) $wanted"
    Write-Host "  present: $others"
    Write-Host ""
    Write-Host "  Your clone of $Name builds a different version. Either point RCPuttPutt at the one"
    Write-Host "  you have, which needs no edit to any file:"
    Write-Host "      .\build.ps1 package -D$($spec.Key)=<the version above>" -ForegroundColor Cyan
    Write-Host "  or check out the revision of $Name that builds $wanted."
    exit 1
}

function Build-Dependency {
    param([string] $Name, [string] $Url, [string] $Branch)

    $dir = Join-Path $WorkDir $Name
    if (Test-Path (Join-Path $dir '.git')) {
        # Deliberately NOT pulling: this may be a working clone with local changes, and silently
        # updating someone's checkout is worse than building what they have. But report exactly
        # what is being built, because installing a stale artifact fails much later and much more
        # confusingly than it does here.
        $head  = (git -C $dir rev-parse --short HEAD 2>$null)
        $desc  = (git -C $dir rev-parse --abbrev-ref HEAD 2>$null)
        $dirty = if (git -C $dir status --porcelain 2>$null) { ' (uncommitted changes)' } else { '' }
        Write-Host "==> $Name`: using existing clone at $dir"
        Write-Host "    building $desc @ $head$dirty - not pulled; update it yourself if that is stale"
    }
    else {
        Write-Host "==> $Name`: cloning into $dir"
        git clone $Url $dir
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to clone $Url" }
    }

    if ($Branch) {
        git -C $dir fetch origin $Branch
        git -C $dir checkout $Branch
        if ($LASTEXITCODE -ne 0) { Write-Error "Failed to check out $Branch in $dir" }
    }

    # NOT -q: quiet mode suppresses download and module progress, so a first build (which fetches
    # paper-api and a dozen modules) sits silent for minutes and looks like a hang. -B keeps the
    # output non-interactive and free of ANSI progress bars.
    Write-Host "==> $Name`: mvn install (first run downloads a lot; this can take several minutes)"
    $extra = @()
    if ($ModuleArgs.ContainsKey($Name)) { $extra = $ModuleArgs[$Name] }

    Push-Location $dir
    try {
        & $mvn.Source -B install -DskipTests @extra
        if ($LASTEXITCODE -ne 0) {
            Write-Host ""
            Write-Host "install-deps.ps1: $Name failed to build (see the Maven output above)." -ForegroundColor Red
            exit 1
        }
    }
    finally { Pop-Location }

    Assert-Installed -Name $Name
}

# Before anything else, drop any cached resolution failures left by an earlier `mvn package`.
Clear-ResolverCache

# Order matters: RCUI compiles against rcplatform-api.
Build-Dependency -Name 'rcplatform' -Url $RcPlatformUrl
Build-Dependency -Name 'rcui'       -Url $RcUiUrl
Build-Dependency -Name 'rcparties'  -Url $RcPartiesUrl -Branch $RcPartiesBranch

Write-Host ''
Write-Host 'Done. RCPuttPutt should now build with .\build.ps1 package' -ForegroundColor Green

# Sets up a Fabric Minecraft server with the Tailcat mod on Windows, from
# nothing. Installs a JDK if there isn't one, fetches the Fabric server, drops
# the mod in, and starts it.
#
#   powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula
#   powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula -GameVersion 1.20.1
#   powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula -Loader neoforge
#
# -AcceptEula records that you accept the Minecraft EULA (https://aka.ms/MinecraftEULA).
# Without it the script stops and leaves eula.txt for you to edit yourself.
param(
    [string]$GameVersion = '1.21.1',
    [string]$Directory = "$PWD\tailcat-server",
    [int]$Port = 25565,
    [string]$Motd = 'A Tailcat Server',
    [string]$Repo = 'Vivialdi/mc-tailcat',
    [ValidateSet('fabric', 'neoforge')][string]$Loader = 'fabric',
    [switch]$AcceptEula,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

# --- 1. The localhost trap, checked before anything else -----------------
# tailcat proxies to the name "localhost" using Go's own resolver, which does
# not ask Windows. If the hosts file does not define it and your DNS server has
# no record for it, every player is refused and the server still looks healthy.
Step 'Checking that localhost resolves'
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
$hasLocalhost = $false
if (Test-Path $hosts) {
    foreach ($line in Get-Content $hosts) {
        $active = ($line -split '#')[0]
        if ($active -split '\s+' | Where-Object { $_.ToLower() -eq 'localhost' }) {
            $hasLocalhost = $true; break
        }
    }
}
if ($hasLocalhost) {
    Write-Host "  OK - hosts file defines localhost" -ForegroundColor Green
} else {
    Write-Host "  WARNING - no 'localhost' entry in $hosts" -ForegroundColor Yellow
    Write-Host "  If players cannot connect, run this in an ADMIN PowerShell:" -ForegroundColor Yellow
    Write-Host "      Add-Content -Path `"`$env:SystemRoot\System32\drivers\etc\hosts`" -Value `"`n127.0.0.1 localhost`""
    Write-Host "  Continuing - it only matters if your DNS server refuses 'localhost'." -ForegroundColor Yellow
}

# --- 2. Java -------------------------------------------------------------
Step 'Looking for Java 17 or newer'
function Find-Java {
    # Windows PowerShell turns a native command's stderr into ErrorRecords, and
    # `java -version` writes to stderr. Under -ErrorAction Stop that throws, so
    # relax it here and read the version from a file instead of the pipeline.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $seen = @()
        $onPath = Get-Command java -ErrorAction SilentlyContinue
        if ($onPath) { $seen += $onPath.Source }
        foreach ($root in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java',
                            'C:\Program Files\Microsoft', "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
            if (Test-Path $root) {
                $seen += Get-ChildItem $root -Filter 'java.exe' -Recurse -ErrorAction SilentlyContinue |
                    Where-Object { $_.DirectoryName -like '*\bin' } | ForEach-Object { $_.FullName }
            }
        }

        $probe = Join-Path $env:TEMP 'tailcat-java-probe.txt'
        foreach ($candidate in ($seen | Where-Object { $_ } | Select-Object -Unique)) {
            try {
                Start-Process -FilePath $candidate -ArgumentList '-version' -NoNewWindow -Wait `
                    -RedirectStandardError $probe -RedirectStandardOutput "$probe.out"
                $out = (Get-Content $probe -Raw -ErrorAction SilentlyContinue) +
                       (Get-Content "$probe.out" -Raw -ErrorAction SilentlyContinue)
                if ($out -match 'version "(\d+)' -and [int]$Matches[1] -ge 17) { return $candidate }
            } catch { }
        }
        return $null
    } finally {
        $ErrorActionPreference = $previous
        Remove-Item "$env:TEMP\tailcat-java-probe.txt","$env:TEMP\tailcat-java-probe.txt.out" `
            -Force -ErrorAction SilentlyContinue
    }
}

$java = Find-Java
if ($java) {
    Write-Host "  found: $java" -ForegroundColor Green
} else {
    Write-Host "  none found. Installing Temurin 21 via winget..."
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "No Java and no winget. Install Java 21 from https://adoptium.net and re-run."
    }
    winget install --id EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements --silent
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
    $java = Find-Java
    if (-not $java) { throw "Java still not found after install. Open a new terminal and re-run." }
    Write-Host "  installed: $java" -ForegroundColor Green
}

# --- 3. Server files -----------------------------------------------------
Step "Setting up a $Loader $GameVersion server in $Directory"
New-Item -ItemType Directory -Force -Path "$Directory\mods" | Out-Null

if ($Loader -eq 'fabric') {
    # Windows PowerShell hands the JSON array back wrapped inside another array,
    # so indexing it without unwrapping gives you every loader version at once.
    $builds = Invoke-RestMethod "https://meta.fabricmc.net/v2/versions/loader/$GameVersion"
    if ($builds -is [array] -and $builds.Count -ge 1 -and $builds[0] -is [array]) { $builds = $builds[0] }
    $builds = @($builds)
    if ($builds.Count -eq 0) { throw "Minecraft $GameVersion is not a version Fabric supports" }
    $stable = @($builds | Where-Object { $_.loader.stable })
    $loaderVer = if ($stable.Count -gt 0) { $stable[0].loader.version } else { $builds[0].loader.version }
    if (-not $loaderVer -or $loaderVer -isnot [string]) {
        throw "Could not determine a Fabric loader version for Minecraft $GameVersion"
    }
    Write-Host "  Fabric loader $loaderVer"
    Invoke-WebRequest -UseBasicParsing -OutFile "$Directory\fabric-server-launch.jar" `
        -Uri "https://meta.fabricmc.net/v2/versions/loader/$GameVersion/$loaderVer/1.1.2/server/jar"
    $launch = @('-jar', 'fabric-server-launch.jar', 'nogui')
    $jarPattern = '^tailcat-[0-9][^/]*\.jar$'
} else {
    # NeoForge versions track Minecraft: 1.21.1 is the 21.1.x line. Newest wins.
    if ($GameVersion -notmatch '^1\.(\d+)\.(\d+)$') { throw "Cannot map Minecraft $GameVersion to a NeoForge line" }
    $line = "$($Matches[1]).$($Matches[2])."
    [xml]$meta = (Invoke-WebRequest -UseBasicParsing 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml').Content
    $nf = @($meta.metadata.versioning.versions.version | Where-Object { $_ -like "$line*" -and $_ -notmatch 'beta' }) | Select-Object -Last 1
    if (-not $nf) { throw "No NeoForge release found for Minecraft $GameVersion" }
    Write-Host "  NeoForge $nf"
    $installer = Join-Path $env:TEMP "neoforge-$nf-installer.jar"
    Invoke-WebRequest -UseBasicParsing -OutFile $installer `
        -Uri "https://maven.neoforged.net/releases/net/neoforged/neoforge/$nf/neoforge-$nf-installer.jar"
    & $java -jar $installer --install-server $Directory | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0) { throw "NeoForge installer failed with exit code $LASTEXITCODE" }
    $launch = @("@libraries/net/neoforged/neoforge/$nf/win_args.txt", 'nogui')
    $jarPattern = '^tailcat-neoforge-[0-9][^/]*\.jar$'
}

# The mod jar, in order of preference: next to this script, built from a clone
# of this repo, or downloaded from the latest release. The last case is the one
# that matters for someone who found this on GitHub and has nothing else.
$modJar = Get-ChildItem $PSScriptRoot -Filter 'tailcat-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match $jarPattern -and $_.Name -notlike '*-gui-*' -and $_.Name -notlike '*-sources*' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $modJar) {
    $buildDir = if ($Loader -eq 'fabric') { "$PSScriptRoot\fabric\build\libs" } else { "$PSScriptRoot\neoforge\build\libs" }
    $modJar = Get-ChildItem $buildDir -Filter 'tailcat-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $jarPattern -and $_.Name -notlike '*-gui-*' -and $_.Name -notlike '*-sources*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

if ($modJar) {
    Copy-Item $modJar.FullName "$Directory\mods\" -Force
    Write-Host "  mod: $($modJar.Name)"
} else {
    Write-Host "  no local jar; fetching the latest release from $Repo"
    try {
        $release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" `
            -Headers @{ 'User-Agent' = 'mc-tailcat-setup' }
    } catch {
        throw ("No tailcat jar here and no published release to fall back on. Either put " +
               "tailcat-*.jar next to this script, or clone the repo and run .\gradlew build.")
    }
    # The release also carries the GUI companion, which is client-only and
    # useless on a server. Match the main jar by its versioned name and never
    # rely on which asset the API happens to list first.
    $asset = @($release.assets) |
        Where-Object { $_.name -match $jarPattern -and $_.name -notlike '*-gui-*' -and $_.name -notlike '*-sources*' } |
        Select-Object -First 1
    if (-not $asset) {
        throw "Release $($release.tag_name) has no tailcat jar attached."
    }
    $target = Join-Path "$Directory\mods" $asset.name
    Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $target
    if ((Get-Item $target).Length -lt 1000) { throw "The downloaded jar looks truncated." }
    Write-Host "  mod: $($asset.name) from release $($release.tag_name)"
}

if (-not (Test-Path "$Directory\server.properties")) {
    @(
        "server-port=$Port"
        "motd=$Motd"
        'online-mode=true'
        'max-players=20'
    ) | Set-Content "$Directory\server.properties" -Encoding ASCII
}

# --- 4. EULA -------------------------------------------------------------
Step 'Minecraft EULA'
if ($AcceptEula) {
    'eula=true' | Set-Content "$Directory\eula.txt" -Encoding ASCII
    Write-Host "  recorded your acceptance of https://aka.ms/MinecraftEULA" -ForegroundColor Green
} else {
    'eula=false' | Set-Content "$Directory\eula.txt" -Encoding ASCII
    Write-Host "  NOT accepted. Read https://aka.ms/MinecraftEULA, then either set" -ForegroundColor Yellow
    Write-Host "  eula=true in $Directory\eula.txt, or re-run this script with -AcceptEula." -ForegroundColor Yellow
    Write-Host "`nSetup is otherwise complete. The server will not start until the EULA is accepted."
    return
}

# --- 5. Go ---------------------------------------------------------------
if ($NoStart) {
    Step 'Done (not starting, -NoStart was given)'
    Write-Host "  Start it with, from inside ${Directory}:  & `"$java`" -Xmx2G $($launch -join ' ')"
    return
}

Step 'Starting the server'
Write-Host "  First start downloads Minecraft and tailcat; give it a minute."
Write-Host "  When it is up, hand players this file:"
Write-Host "      $Directory\tailcat-network.json" -ForegroundColor Green
Write-Host "  They drop it into their config/ folder, or you ship it in your modpack."
Write-Host ""
Push-Location $Directory
try { & $java -Xmx2G @launch }
finally { Pop-Location }

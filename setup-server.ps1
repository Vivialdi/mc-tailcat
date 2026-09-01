# Sets up a Fabric Minecraft server with the Tailcat mod on Windows, from
# nothing. Installs a JDK if there isn't one, fetches the Fabric server, drops
# the mod in, and starts it.
#
#   powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula
#   powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula -GameVersion 1.20.1
#
# -AcceptEula records that you accept the Minecraft EULA (https://aka.ms/MinecraftEULA).
# Without it the script stops and leaves eula.txt for you to edit yourself.
param(
    [string]$GameVersion = '1.21.1',
    [string]$Directory = "$PWD\tailcat-server",
    [int]$Port = 25565,
    [string]$Motd = 'A Tailcat Server',
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
Step "Setting up a Fabric $GameVersion server in $Directory"
New-Item -ItemType Directory -Force -Path "$Directory\mods" | Out-Null

# Windows PowerShell hands the JSON array back wrapped inside another array, so
# indexing it without unwrapping gives you every loader version at once.
$builds = Invoke-RestMethod "https://meta.fabricmc.net/v2/versions/loader/$GameVersion"
if ($builds -is [array] -and $builds.Count -ge 1 -and $builds[0] -is [array]) { $builds = $builds[0] }
$builds = @($builds)
if ($builds.Count -eq 0) { throw "Minecraft $GameVersion is not a version Fabric supports" }
$stable = @($builds | Where-Object { $_.loader.stable })
$loader = if ($stable.Count -gt 0) { $stable[0].loader.version } else { $builds[0].loader.version }
if (-not $loader -or $loader -isnot [string]) {
    throw "Could not determine a Fabric loader version for Minecraft $GameVersion"
}
Write-Host "  Fabric loader $loader"

Invoke-WebRequest -UseBasicParsing -OutFile "$Directory\fabric-server-launch.jar" `
    -Uri "https://meta.fabricmc.net/v2/versions/loader/$GameVersion/$loader/1.1.2/server/jar"

# The mod jar: next to this script, or built from this repo.
$modJar = Get-ChildItem $PSScriptRoot -Filter 'tailcat-*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $modJar) {
    $modJar = Get-ChildItem "$PSScriptRoot\fabric\build\libs" -Filter 'tailcat-*.jar' `
        -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $modJar) {
    throw "No tailcat-*.jar found. Put the mod jar next to this script, or run .\gradlew build first."
}
Copy-Item $modJar.FullName "$Directory\mods\" -Force
Write-Host "  mod: $($modJar.Name)"

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
    Write-Host "  Start it with:  & `"$java`" -Xmx2G -jar `"$Directory\fabric-server-launch.jar`" nogui"
    return
}

Step 'Starting the server'
Write-Host "  First start downloads Minecraft and tailcat; give it a minute."
Write-Host "  When it is up, hand players this file:"
Write-Host "      $Directory\tailcat-network.json" -ForegroundColor Green
Write-Host "  They drop it into their config/ folder, or you ship it in your modpack."
Write-Host ""
Push-Location $Directory
try { & $java -Xmx2G -jar 'fabric-server-launch.jar' nogui }
finally { Pop-Location }

# Copies the server-side subset of a modpack's mods folder into a server's.
#
#   powershell -ExecutionPolicy Bypass -File make-server-mods.ps1 `
#       -From "$env:USERPROFILE\curseforge\minecraft\Instances\My Pack\mods" `
#       -To   "$env:USERPROFILE\tailcat-server\mods"
#
# Client-only mods -- renderers, HUD tweaks, minimaps, animations -- are left
# out: on a dedicated server they are at best dead weight and at worst a crash
# at startup. Everything else, including tailcat, is copied. The exclusion list
# covers the common client-only mods; add your own with -AlsoExclude. Only
# exclude a mod that nothing else hard-depends on -- Simply Tooltips looks
# client-only but Simply More refuses to load without it.
param(
    [Parameter(Mandatory = $true)][string]$From,
    [Parameter(Mandatory = $true)][string]$To,
    [string[]]$AlsoExclude = @(),
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

# Matched against the start of the filename, case-insensitively.
$clientOnly = @(
    'sodium-', 'iris-', 'iris-flywheel-compat', 'reeses-sodium-options', 'oculus', 'rubidium', 'embeddium',
    'entityculling', 'lambdynamiclights', 'dynamiclights', 'notenoughanimations', 'justzoom', 'zoomify',
    'xaeroworldmap', 'xaerominimap', 'journeymap', 'ambientsounds', 'immediatelyfast', 'modelfix',
    'mousetweaks', 'enchdesc', 'overflowingbars', 'betteradvancements', 'legendarytooltips',
    'emi_enchanting', 'emi_loot', 'emi_ores', 'distanthorizons', 'controlling', 'betterf3', 'drippyloadingscreen',
    'fancymenu', 'presencefootsteps', 'shulkerboxtooltip', 'blur', 'skinlayers3d', 'bettermounthud',
    'continuity', 'cullleaves', 'entitytexturefeatures', 'entitymodelfeatures', 'moreculling'
) + $AlsoExclude

if (-not (Test-Path $From)) { throw "no such folder: $From" }
if ($Clean -and (Test-Path $To)) { Remove-Item "$To\*.jar" -Force }
New-Item -ItemType Directory -Force -Path $To | Out-Null

$jars = Get-ChildItem $From -Filter '*.jar' | Sort-Object Name
$kept = @(); $dropped = @()
foreach ($j in $jars) {
    $n = $j.Name.ToLowerInvariant()
    $isClient = $false
    foreach ($p in $clientOnly) { if ($n.StartsWith($p.ToLowerInvariant())) { $isClient = $true; break } }
    if ($isClient) { $dropped += $j.Name; continue }
    # -LiteralPath: a filename like "[Neoforge]ctov-3.6.3.jar" is otherwise read
    # as a wildcard pattern, matches nothing, and is silently not copied.
    Copy-Item -LiteralPath $j.FullName -Destination (Join-Path $To $j.Name) -Force
    if (-not (Test-Path -LiteralPath (Join-Path $To $j.Name))) { throw "copy failed: $($j.Name)" }
    $kept += $j.Name
}

Write-Host ("copied {0} server-side mods to {1}" -f $kept.Count, $To)
Write-Host ("left off as client-only: {0}" -f $dropped.Count)
$dropped | ForEach-Object { Write-Host "    $_" }
$tail = $kept | Where-Object { $_ -like 'tailcat-*' }
if ($tail) { Write-Host "tailcat present: $tail" -ForegroundColor Green }
else { Write-Host "WARNING: no tailcat jar among the copied mods" -ForegroundColor Yellow }

param(
    [string]$ModsDir = "C:\Users\esnar\AppData\Roaming\PrismLauncher\instances\1.21.1(1)\.minecraft\mods",

    [string]$NeoForgeModsDir = "C:\Users\esnar\AppData\Roaming\PrismLauncher\instances\1.21.1neo\minecraft\mods",
    [string]$FabricModsDir = "C:\Users\esnar\AppData\Roaming\PrismLauncher\instances\1.21.1(1)\.minecraft\mods",

    [string]$Version = "2.0.1",

    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptRoot


if (-not $SkipBuild) {
    Write-Host "Building project..."
    $previousReleaseVersion = $env:RELEASE_VERSION
    $env:RELEASE_VERSION = $Version
    try {
        & .\gradlew.bat build --continue -x test -x check -x checkstyleTest -x checkstyleMain
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        $env:RELEASE_VERSION = $previousReleaseVersion
    }
}


# Determine target directories for each jar
$targetNeoForgeDir = if ($NeoForgeModsDir) { $NeoForgeModsDir } else { $ModsDir }
$targetFabricDir = if ($FabricModsDir) { $FabricModsDir } else { $ModsDir }

# Ensure both directories exist
foreach ($dir in @($targetNeoForgeDir, $targetFabricDir)) {
    if (-not (Test-Path -Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
}



# Remove old neoforge jars from NeoForge dir
Get-ChildItem -Path $targetNeoForgeDir -Filter "refinedstorage-neoforge-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
# Remove old fabric jars from Fabric dir
Get-ChildItem -Path $targetFabricDir -Filter "refinedstorage-fabric-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force


# Define both jar paths and their destinations
$jars = @(
    @{ Name = "neoforge"; Path = Join-Path $scriptRoot "refinedstorage-neoforge\build\libs\refinedstorage-neoforge-$Version.jar"; DestDir = $targetNeoForgeDir },
    @{ Name = "fabric"; Path = Join-Path $scriptRoot "refinedstorage-fabric\build\libs\refinedstorage-fabric-$Version.jar"; DestDir = $targetFabricDir }
)

foreach ($jar in $jars) {
    $jarPath = $jar.Path
    $name = $jar.Name
    $destDir = $jar.DestDir
    if (-not (Test-Path -Path $jarPath)) {
        throw "Built jar not found: $jarPath"
    }
    $destination = Join-Path $destDir (Split-Path -Leaf $jarPath)
    Copy-Item -Path $jarPath -Destination $destination -Force
    Write-Host "Copied $name jar to: $destination"
}
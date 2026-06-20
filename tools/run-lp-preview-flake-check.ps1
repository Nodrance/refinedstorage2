param(
    [int]$Iterations = 10,
    [string]$TestFilter = ":refinedstorage-autocrafting-api:test --tests com.refinedmods.refinedstorage.api.autocrafting.lp.PortedPreviewTest.shouldExhaustAllPossibleIngredientsWhenRunningOutInSingleRootPatternAndMultipleIngredients --tests com.refinedmods.refinedstorage.api.autocrafting.lp.PortedPreviewTest.shouldNotCalculateForSingleRootPatternSingleChildPatternWSingleIngredientAndAlmostAllResourcesAreAvailable"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleCmd = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradleCmd)) {
    throw "Gradle wrapper not found: $gradleCmd"
}

$failurePattern = '(?m)^\s*([^\r\n]+ > [^\r\n]+\(\) FAILED)\s*$'

$allFailures = New-Object System.Collections.Generic.List[string]
$failedTestNames = New-Object System.Collections.Generic.HashSet[string]

for ($attempt = 1; $attempt -le $Iterations; $attempt++) {
    Write-Host "Attempt $attempt/$Iterations"
    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    try {
        $argList = $TestFilter -split ' '
        $process = Start-Process -FilePath $gradleCmd -ArgumentList $argList -WorkingDirectory $repoRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

        $stdout = Get-Content -Path $stdoutPath -Raw
        $stderr = Get-Content -Path $stderrPath -Raw
        $output = ($stdout + "`n" + $stderr)
    } finally {
        Remove-Item -Path $stdoutPath, $stderrPath -ErrorAction SilentlyContinue
    }

    $matches = [System.Text.RegularExpressions.Regex]::Matches($output, $failurePattern)
    if ($matches.Count -gt 0) {
        foreach ($match in $matches) {
            $failureLine = $match.Groups[1].Value.Trim()
            $allFailures.Add("Attempt ${attempt}: $failureLine")
            [void]$failedTestNames.Add($failureLine)
        }
    }
}

Write-Host ""
Write-Host "=== Flake Check Summary ==="
if ($allFailures.Count -eq 0) {
    Write-Host "No failed tests detected across $Iterations attempts."
} else {
    Write-Host "Failed tests detected (by attempt):"
    $allFailures | Sort-Object -Unique | ForEach-Object { Write-Host $_ }
    Write-Host ""
    Write-Host "Failed test names across all attempts:"
    $failedTestNames | Sort-Object | ForEach-Object { Write-Host $_ }
}

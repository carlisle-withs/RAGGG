$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$StressTestDir = $PSScriptRoot
$TestDataDir = Join-Path $ProjectRoot "test-data"
$ResultsDir = Join-Path $StressTestDir "results"

Write-Host "=== RAG Stress Test Runner ===" -ForegroundColor Cyan

$JMeterHome = $env:JMETER_HOME
if (-not $JMeterHome) {
    $JMeterLocations = @(
        "C:\Program Files\apache-jmeter",
        "C:\jmeter",
        "$env:LOCALAPPDATA\apache-jmeter",
        "C:\apache-jmeter"
    )
    foreach ($loc in $JMeterLocations) {
        if (Test-Path $loc) {
            $JMeterHome = $loc
            break
        }
    }
}

if (-not $JMeterHome) {
    Write-Host "ERROR: JMeter not found. Please install JMeter or set JMETER_HOME environment variable." -ForegroundColor Red
    Write-Host "Download from: https://jmeter.apache.org/download_jmeter.cgi" -ForegroundColor Yellow
    exit 1
}

$JMeterBin = Join-Path $JMeterHome "bin"
$JMeterScript = if ($IsWindows) { "jmeter.bat" } else { "jmeter" }
$JMeterCmd = Join-Path $JMeterBin $JMeterScript

Write-Host "Using JMeter: $JMeterCmd" -ForegroundColor Green

if (-not (Test-Path $JMeterCmd)) {
    Write-Host "ERROR: JMeter executable not found at $JMeterCmd" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $TestDataDir)) {
    Write-Host "Test data not found. Running data preparation..." -ForegroundColor Yellow
    & "$StressTestDir\prepare-test-data.ps1"
}

$JmxFile = Join-Path $StressTestDir "document-upload-test.jmx"
if (-not (Test-Path $JmxFile)) {
    Write-Host "ERROR: Test plan not found at $JmxFile" -ForegroundColor Red
    exit 1
}

if (Test-Path $ResultsDir) {
    Write-Host "Cleaning previous results..." -ForegroundColor Yellow
    Remove-Item -Path "$ResultsDir\*" -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $ResultsDir -Force | Out-Null
}

Write-Host "`n=== Starting Stress Test ===" -ForegroundColor Cyan
Write-Host "Test Plan: document-upload-test.jmx"
Write-Host "Results Directory: $ResultsDir"
Write-Host ""

$env:TEST_FILE_PATH = $TestDataDir

$Cmd = "$JMeterCmd -n -t `"$JmxFile`" -l `"$ResultsDir\results.jtl`" -e -o `"$ResultsDir\html-report`" -j `"$ResultsDir\jmeter.log`""

Write-Host "Command: $Cmd" -ForegroundColor Gray
Write-Host ""

try {
    Invoke-Expression $Cmd

    if (Test-Path "$ResultsDir\results.jtl") {
        Write-Host "`n=== Test Completed ===" -ForegroundColor Green
        Write-Host "Results:" -ForegroundColor Cyan
        Write-Host "  - Raw Results: $ResultsDir\results.jtl"
        Write-Host "  - HTML Report: $ResultsDir\html-report\index.html"
        Write-Host "  - Log: $ResultsDir\jmeter.log"

        Write-Host "`n=== Summary ===" -ForegroundColor Cyan
        $Results = Import-Csv "$ResultsDir\results.jtl" -Delimiter "`t"
        if ($Results) {
            $SuccessCount = ($Results | Where-Object { $_.success -eq "true" }).Count
            $FailCount = ($Results | Where-Object { $_.success -eq "false" }).Count
            $TotalCount = $Results.Count
            Write-Host "Total Requests: $TotalCount"
            Write-Host "Successful: $SuccessCount" -ForegroundColor Green
            if ($FailCount -gt 0) {
                Write-Host "Failed: $FailCount" -ForegroundColor Red
            }
        }
    }
}
catch {
    Write-Host "ERROR: Test execution failed: $_" -ForegroundColor Red
    exit 1
}

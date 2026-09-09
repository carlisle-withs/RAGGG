$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$TestDataDir = Join-Path $ProjectRoot "test-data"

Write-Host "=== RAG Stress Test Data Generator ===" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot"
Write-Host "Test Data Dir: $TestDataDir"

if (Test-Path $TestDataDir) {
    Write-Host "Cleaning existing test data..." -ForegroundColor Yellow
    Remove-Item -Path "$TestDataDir\*" -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $TestDataDir -Force | Out-Null
    Write-Host "Created test data directory" -ForegroundColor Green
}

function Create-TestFile {
    param (
        [string]$FileName,
        [int]$SizeMB
    )
    $FilePath = Join-Path $TestDataDir $FileName
    Write-Host "Creating $FileName ($SizeMB MB)..." -NoNewline

    $BufferSize = 1MB
    $Buffer = New-Object byte[] $BufferSize
    $Rand = [Random]::new()
    $Rand.NextBytes($Buffer)

    $FileStream = [System.IO.File]::Create($FilePath)
    try {
        for ($i = 0; $i -lt $SizeMB; $i++) {
            $Rand.NextBytes($Buffer)
            $FileStream.Write($Buffer, 0, $Buffer.Length)
        }
        Write-Host " Done" -ForegroundColor Green
    }
    finally {
        $FileStream.Close()
    }
}

Write-Host "`nGenerating test files..." -ForegroundColor Cyan
Create-TestFile -FileName "test_1mb.pdf" -SizeMB 1
Create-TestFile -FileName "test_5mb.pdf" -SizeMB 5
Create-TestFile -FileName "test_10mb.pdf" -SizeMB 10

Write-Host "`nTest files created:" -ForegroundColor Cyan
Get-ChildItem $TestDataDir | Format-Table Name, @{Label="Size";Expression={" {0:N2} MB" -f ($_.Length / 1MB)}}, LastWriteTime -AutoSize

Write-Host "`nTest data generation completed!" -ForegroundColor Green
Write-Host "Location: $TestDataDir"

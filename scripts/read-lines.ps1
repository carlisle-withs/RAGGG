$lines = Get-Content "D:\Workspace\RAGGG\pom.xml"
$total = $lines.Count
Write-Host "Total lines: $total"
for ($i = 0; $i -lt $total; $i++) {
    Write-Host "$i`: $($lines[$i])"
}

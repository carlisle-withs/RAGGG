$lines = Get-Content "D:\Workspace\RAGGG\pom.xml"
$total = $lines.Count
Write-Host "Total lines: $total"
for ($i = 200; $i -lt 270; $i++) {
    Write-Host "$i`: $($lines[$i])"
}

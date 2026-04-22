$lines = Get-Content "D:\Workspace\RAGGG\pom_backup.xml"
$total = $lines.Count
Write-Host "Total lines: $total"
for ($i = 220; $i -lt 270; $i++) {
    Write-Host "$i`: $($lines[$i])"
}

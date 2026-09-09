$content = Get-Content "D:\Workspace\RAGGG\pom.xml" -Raw
$lines = $content -split "`n"
Write-Host "Total lines: $($lines.Count)"
for ($i = 225; $i -lt 260; $i++) {
    Write-Host "$i`: $($lines[$i])"
}

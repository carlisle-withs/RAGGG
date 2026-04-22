$lines = Get-Content "D:\Workspace\RAGGG\pom.xml"
for ($i = 212; $i -lt 270; $i++) {
    Write-Host "$i`: $($lines[$i])"
}

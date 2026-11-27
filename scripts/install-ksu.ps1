$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
function Invoke-Phone([string[]]$Arguments) {
    $result = @(& adb.exe @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {throw ($result -join "`n")}
    return ($result -join "`n").Trim()
}
$expected = (Get-Content -LiteralPath (Join-Path $project 'backup\device-id.txt') -Raw).Trim()
if ((Invoke-Phone @('get-serialno')) -ne $expected) {throw 'This installation is prepared for another phone.'}
$version = (Select-String -LiteralPath (Join-Path $project 'ksu-module\module.prop') -Pattern '^version=([0-9.]+)$').Matches.Groups[1].Value
$packageName = "quiet-meridian-ksu-$version.zip"
$remotePackage = "/data/local/tmp/$packageName"
Write-Output (Invoke-Phone @('push',(Join-Path $project "dist\$packageName"),$remotePackage))
Write-Output (Invoke-Phone @('shell','su','-c',"/data/adb/ksud module install $remotePackage"))
Write-Output (Invoke-Phone @('push',(Join-Path $PSScriptRoot 'activate-ksu-now.sh'),'/data/local/tmp/regionguard-activate-now.sh'))
Write-Output (Invoke-Phone @('shell','su','-c','sh /data/local/tmp/regionguard-activate-now.sh'))

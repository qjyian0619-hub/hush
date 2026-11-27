param([switch]$Restore)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$targets = @('com.anthropic.claude','com.anthropic.aibo','com.android.chrome','com.microsoft.emmx','mark.via','com.quark.browser')
$operations = @('COARSE_LOCATION','FINE_LOCATION','WIFI_SCAN','READ_PHONE_STATE','READ_PHONE_NUMBERS','BLUETOOTH_SCAN')
$backupPath = Join-Path $project 'backup\settings-backup.json'
function Adb([string[]]$Arguments) {
    $result = @(& adb.exe @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n").Trim()
}
$deviceIdPath = Join-Path $project 'backup\device-id.txt'
$connectedSerial = Adb @('get-serialno')
if (Test-Path -LiteralPath $deviceIdPath) {
    $expectedSerial = (Get-Content -LiteralPath $deviceIdPath -Raw).Trim()
    if ($expectedSerial -ne $connectedSerial) {throw 'This backup belongs to a different phone. No settings changed.'}
} elseif ($Restore) {throw 'Device identity backup missing. Verify the target phone before restoring.'}
else {$connectedSerial | Set-Content -LiteralPath $deviceIdPath -Encoding utf8}
if ($Restore) {
    if (!(Test-Path -LiteralPath $backupPath)) { throw 'No settings backup found.' }
    $backup = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json -AsHashtable
    # Browser processes must be stopped while changing their on-disk preferences.
    foreach ($browser in @('com.android.chrome','com.microsoft.emmx')) { $null = Adb @('shell','am','force-stop',$browser) }
    $null = Adb @('push',(Join-Path $project 'dist\regionguard.apk'),'/data/local/tmp/regionguard-tools.apk')
    $browserBackup = Join-Path $project 'backup\browser-backup.json'
    if (Test-Path -LiteralPath $browserBackup) {
        $null = Adb @('push',$browserBackup,'/data/local/tmp/regionguard-browser-restore.json')
        Write-Output (Adb @('shell','su','-c','CLASSPATH=/data/local/tmp/regionguard-tools.apk app_process /system/bin dev.local.regionguard.DeviceSetup restore /data/local/tmp/regionguard-browser-restore.json'))
    }
    foreach ($pkg in $backup.Keys) {
        foreach ($op in $backup[$pkg].ops.Keys) {
            $null = Adb @('shell','su','-c',"cmd appops set --user 0 $pkg $op $($backup[$pkg].ops[$op])")
        }
        $previous = $backup[$pkg].locales
        if ($previous) { $null = Adb @('shell','su','-c',"cmd locale set-app-locales $pkg --user 0 --locales $previous") }
        else { $null = Adb @('shell','su','-c',"cmd locale set-app-locales $pkg --user 0") }
        Write-Output "$pkg restored"
    }
    Write-Output 'Also turn off Quiet Meridian in LSPosed, then reopen target apps. This script does not reboot or launch them.'
    exit
}
$backup = if (Test-Path -LiteralPath $backupPath) {Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json -AsHashtable} else {@{}}
foreach ($pkg in $targets) {
    $installed = Adb @('shell','pm','path',$pkg)
    if (!$installed.StartsWith('package:')) {continue}
    if (!$backup.ContainsKey($pkg)) {
        $localeOutput = Adb @('shell','cmd','locale','get-app-locales',$pkg,'--user','0')
        $locales = if ($localeOutput -match '\[([^\]]*)\]') {$Matches[1]} else {throw "Cannot parse locale for $pkg"}
        $backup[$pkg] = @{locales=$locales;ops=@{}}
        foreach ($op in $operations) {
            $state = Adb @('shell','cmd','appops','get','--user','0',$pkg,$op)
            $mode = if ($state -match "(?m)^\s*${op}:\s*(allow|ignore|deny|default|foreground|errored)") {$Matches[1]} elseif ($state -match 'No operations|Uid mode:') {'default'} else {throw "Cannot parse operation $op for $pkg : $state"}
            $backup[$pkg].ops[$op] = $mode
        }
        $backup | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $backupPath -Encoding utf8
    }
    foreach ($op in $operations) {$null = Adb @('shell','su','-c',"cmd appops set --user 0 $pkg $op ignore")}
    $null = Adb @('shell','su','-c',"cmd locale set-app-locales $pkg --user 0 --locales en-US")
    Write-Output "$pkg : permissions restricted, per-app language English"
}
foreach ($browser in @('com.android.chrome','com.microsoft.emmx')) { $null = Adb @('shell','am','force-stop',$browser) }
$null = Adb @('push',(Join-Path $project 'dist\regionguard.apk'),'/data/local/tmp/regionguard-tools.apk')
Write-Output (Adb @('shell','su','-c','CLASSPATH=/data/local/tmp/regionguard-tools.apk app_process /system/bin dev.local.regionguard.DeviceSetup apply /data/local/tmp/regionguard-browser-backup.json'))
$null = Adb @('shell','su','-c','chmod 600 /data/local/tmp/regionguard-browser-backup.json')
$null = Adb @('shell','su','-c','cp /data/local/tmp/regionguard-browser-backup.json /data/local/tmp/regionguard-browser-export.json')
$null = Adb @('shell','su','-c','chmod 644 /data/local/tmp/regionguard-browser-export.json')
$null = Adb @('pull','/data/local/tmp/regionguard-browser-export.json',(Join-Path $project 'backup\browser-backup.json'))
Write-Output 'Applied. No phone reboot, radio/hotspot/VPN changes or target-app launches performed.'

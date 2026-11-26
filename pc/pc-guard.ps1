<#
.SYNOPSIS
  Region Guard (PC): set the Windows time zone and pin Chrome's language to English.

.DESCRIPTION
  -Action Status (default)  Read-only report.
  -Action Apply             Back up the current values once, then set the time zone and the Chrome policies.
  -Action Revert            Restore the backed-up time zone and Chrome policies.

  Apply and Revert write machine-wide Chrome policies (HKLM), so they need administrator rights.
  When started without them, the script asks for elevation itself (a UAC prompt) and then prints the result.

  Chrome policies set by Apply:
    ApplicationLocaleValue     = en-US  Chrome UI language, navigator.language and Intl default locale.
    ForcedLanguages            = en-US  Accept-Language header and navigator.languages.
    DefaultGeolocationSetting  = 2      Sites cannot ask for the device location.
  Chrome shows "Managed by your organization" while these policies exist. Restart Chrome after Apply or Revert.
#>
param(
    [ValidateSet('Status', 'Apply', 'Revert')] [string]$Action = 'Status',
    [string]$TimeZone = 'Pacific Standard Time',
    [string]$Language = 'en-US',
    [string]$StateDir = (Join-Path $env:LOCALAPPDATA 'RegionGuardPC')
)
$ErrorActionPreference = 'Stop'
$googleKey = 'HKLM:\SOFTWARE\Policies\Google'
$chromeKey = "$googleKey\Chrome"
$forcedKey = "$chromeKey\ForcedLanguages"
$backupFile = Join-Path $StateDir 'backup.json'
$logFile = Join-Path $StateDir 'last-run.log'
$policies = [ordered]@{
    ApplicationLocaleValue    = @{ Kind = 'String'; Value = $Language }
    DefaultGeolocationSetting = @{ Kind = 'DWord';  Value = 2 }
}

function Test-Admin {
    ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-PolicyValue([string]$Name) {
    $item = Get-ItemProperty -LiteralPath $chromeKey -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $null }
    return $item.$Name
}

function Get-ForcedLanguages {
    if (!(Test-Path -LiteralPath $forcedKey)) { return $null }
    $key = Get-Item -LiteralPath $forcedKey
    # List policies are numbered values: "1", "2", ...
    return @($key.Property | Sort-Object { [int]$_ } | ForEach-Object { $key.GetValue($_) })
}

function Show-Status {
    $zone = Get-TimeZone
    $iana = $null
    # .NET 6+ (PowerShell 7) only; Windows PowerShell 5.1 shows the Windows ID alone.
    try { [void][TimeZoneInfo]::TryConvertWindowsIdToIanaId($zone.Id, [ref]$iana) } catch { $iana = $null }
    $offset = $zone.GetUtcOffset([DateTime]::UtcNow)
    $sign = if ($offset -lt [TimeSpan]::Zero) { '-' } else { '+' }
    $auto = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\tzautoupdate' -ErrorAction SilentlyContinue).Start
    "时区：$($zone.Id)" + $(if ($iana) { " / $iana" } else { '' }) + "，当前 UTC$sign$($offset.Duration().ToString('hh\:mm'))"
    "自动设置时区：" + $(if ($auto -eq 4) { '关' } elseif ($null -eq $auto) { '未知' } else { '开（可能把时区改回去）' })
    "Chrome 界面语言策略 ApplicationLocaleValue：" + $(if ($v = Get-PolicyValue 'ApplicationLocaleValue') { $v } else { '未设置' })
    "Chrome 网页语言策略 ForcedLanguages：" + $(if ($v = Get-ForcedLanguages) { $v -join ', ' } else { '未设置' })
    "Chrome 定位策略 DefaultGeolocationSetting：" + $(switch (Get-PolicyValue 'DefaultGeolocationSetting') { 2 { '2（禁止网站获取位置）' } $null { '未设置' } default { $_ } })
    "备份：" + $(if (Test-Path -LiteralPath $backupFile) { $backupFile } else { '无（未应用过，或已还原）' })
    if (Get-Process chrome -ErrorAction SilentlyContinue) { "Chrome 正在运行：完全退出后重新打开，新设置才会生效。" }
}

function Save-Backup {
    if (Test-Path -LiteralPath $backupFile) { return }  # Keep the values from before the first Apply.
    $values = [ordered]@{}
    foreach ($name in $policies.Keys) {
        $item = Get-ItemProperty -LiteralPath $chromeKey -Name $name -ErrorAction SilentlyContinue
        $values[$name] = if ($null -eq $item) { $null } else {
            @{ Value = $item.$name; Kind = (Get-Item -LiteralPath $chromeKey).GetValueKind($name).ToString() }
        }
    }
    $backup = [ordered]@{
        createdAt       = (Get-Date).ToString('o')
        timeZone        = (Get-TimeZone).Id
        googleKeyExists = Test-Path -LiteralPath $googleKey
        chromeKeyExists = Test-Path -LiteralPath $chromeKey
        values          = $values
        forcedLanguages = Get-ForcedLanguages
    }
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    $backup | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $backupFile -Encoding utf8
}

function Set-ForcedLanguages([string[]]$Languages) {
    if (Test-Path -LiteralPath $forcedKey) { Remove-Item -LiteralPath $forcedKey -Recurse -Force }
    if (!$Languages) { return }
    New-Item -Path $forcedKey -Force | Out-Null
    for ($i = 0; $i -lt $Languages.Count; $i++) {
        New-ItemProperty -LiteralPath $forcedKey -Name ($i + 1) -Value $Languages[$i] -PropertyType String -Force | Out-Null
    }
}

function Invoke-Apply {
    if (!(Get-TimeZone -ListAvailable | Where-Object Id -eq $TimeZone)) { throw "Unknown Windows time zone: $TimeZone" }
    Save-Backup
    Set-TimeZone -Id $TimeZone
    New-Item -Path $chromeKey -Force | Out-Null
    foreach ($name in $policies.Keys) {
        New-ItemProperty -LiteralPath $chromeKey -Name $name -Value $policies[$name].Value -PropertyType $policies[$name].Kind -Force | Out-Null
    }
    Set-ForcedLanguages @($Language)
}

function Invoke-Revert {
    if (!(Test-Path -LiteralPath $backupFile)) { throw "No backup at $backupFile. Nothing to revert." }
    $backup = Get-Content -LiteralPath $backupFile -Raw | ConvertFrom-Json
    Set-TimeZone -Id $backup.timeZone
    if (Test-Path -LiteralPath $chromeKey) {
        foreach ($name in $policies.Keys) {
            $old = $backup.values.$name
            if ($null -eq $old) { Remove-ItemProperty -LiteralPath $chromeKey -Name $name -ErrorAction SilentlyContinue }
            else { New-ItemProperty -LiteralPath $chromeKey -Name $name -Value $old.Value -PropertyType $old.Kind -Force | Out-Null }
        }
        Set-ForcedLanguages @($backup.forcedLanguages | Where-Object { $_ })
        # Remove the keys only if this script created them and nothing else lives there.
        foreach ($entry in @(@{ Key = $chromeKey; Existed = $backup.chromeKeyExists }, @{ Key = $googleKey; Existed = $backup.googleKeyExists })) {
            $key = Get-Item -LiteralPath $entry.Key -ErrorAction SilentlyContinue
            if ($key -and !$entry.Existed -and $key.ValueCount -eq 0 -and $key.SubKeyCount -eq 0) { Remove-Item -LiteralPath $entry.Key }
        }
    }
    Remove-Item -LiteralPath $backupFile
}

if ($Action -eq 'Status') { Show-Status; exit 0 }

if (!(Test-Admin)) {
    # Run this same script elevated, wait for it, then show what it logged.
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    Remove-Item -LiteralPath $logFile -ErrorAction SilentlyContinue
    $quoted = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-Action', $Action,
        '-TimeZone', $TimeZone, '-Language', $Language, '-StateDir', $StateDir) | ForEach-Object { '"' + $_ + '"' }
    try { $process = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $quoted -Verb RunAs -Wait -PassThru }
    catch { Write-Error "需要管理员权限，UAC 提示被取消或失败：$($_.Exception.Message)"; exit 1 }
    if (Test-Path -LiteralPath $logFile) { Get-Content -LiteralPath $logFile }
    exit $process.ExitCode
}

try {
    if ($Action -eq 'Apply') { Invoke-Apply; $done = "已应用：时区 $TimeZone，Chrome 语言 $Language，禁止网站定位。" }
    else { Invoke-Revert; $done = '已还原到应用之前的时区和 Chrome 策略。' }
    @($done, '') + @(Show-Status) | Set-Content -LiteralPath $logFile -Encoding utf8
    Get-Content -LiteralPath $logFile
    exit 0
} catch {
    "失败：$($_.Exception.Message)" | Set-Content -LiteralPath $logFile -Encoding utf8
    Get-Content -LiteralPath $logFile
    exit 1
}

# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# Finishes a .msi made by jpackage, where jpackage has no option for it (and the Compose plugin empties jpackage's
# resource folder before running it, so its WiX templates cannot be replaced either). Run by desktopApp/build.gradle.kts
# after packageMsi:
# - VizMini2's pictures instead of WiX's generic ones: the banner of each page (493x58) and the side picture of the
#   first and last pages (493x312), two rows of the Binary table;
# - installing the same version again replaces the installed one, as a newer version does (jpackage only replaces older
#   ones, so two builds of the same version ended up both installed, sharing the folder): the "version maximum
#   inclusive" bit of the upgrade row.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/desktop-installer/msi-postprocess.ps1 -Msi X.msi -Banner banner.bmp -Dialog dialog.bmp
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$Banner,
    [Parameter(Mandatory = $true)][string]$Dialog
)
$ErrorActionPreference = 'Stop'

# The Windows Installer COM objects only work through late binding from PowerShell.
# (A method without arguments takes $null, not an empty array.)
function Call($obj, $name, $argsList) { $obj.GetType().InvokeMember($name, 'InvokeMethod', $null, $obj, $argsList) }
function Field($record, $i) { $record.GetType().InvokeMember('IntegerData', 'GetProperty', $null, $record, @($i)) }
function Query($db, $sql) { $v = Call $db 'OpenView' @($sql); Call $v 'Execute' $null | Out-Null; $r = Call $v 'Fetch' $null; Call $v 'Close' $null | Out-Null; $r }
function Run($db, $sql, $record) {
    $v = Call $db 'OpenView' @($sql)
    if ($null -eq $record) { Call $v 'Execute' $null | Out-Null } else { Call $v 'Execute' @($record) | Out-Null }
    Call $v 'Close' $null | Out-Null
}

$installer = New-Object -ComObject WindowsInstaller.Installer
$db = Call $installer 'OpenDatabase' @((Resolve-Path $Msi).Path, 1)  # 1: msiOpenDatabaseModeTransact

foreach ($row in @(@('WixUI_Bmp_Banner', $Banner), @('WixUI_Bmp_Dialog', $Dialog))) {
    $record = Call $installer 'CreateRecord' @(1)
    Call $record 'SetStream' @(1, (Resolve-Path $row[1]).Path) | Out-Null
    Run $db "UPDATE ``Binary`` SET ``Data`` = ? WHERE ``Name`` = '$($row[0])'" $record
}

# msidbUpgradeAttributesVersionMaxInclusive (0x200) on the row that finds and removes older versions. Attributes is part
# of the Upgrade table's key, which UPDATE cannot change: the row is deleted and inserted again with it.
$columns = '`UpgradeCode`, `VersionMin`, `VersionMax`, `Language`, `Attributes`, `Remove`, `ActionProperty`'
$r = Query $db "SELECT $columns FROM ``Upgrade`` WHERE ``ActionProperty`` = 'JP_UPGRADABLE_FOUND'"
if ($null -eq $r) { throw "No JP_UPGRADABLE_FOUND row in the Upgrade table of $Msi" }
$row = Call $installer 'CreateRecord' @(7)
foreach ($i in 1..7) {
    $value = $r.GetType().InvokeMember('StringData', 'GetProperty', $null, $r, @($i))
    $row.GetType().InvokeMember('StringData', 'SetProperty', $null, $row, @($i, $value)) | Out-Null
}
$row.GetType().InvokeMember('IntegerData', 'SetProperty', $null, $row, @(5, ((Field $r 5) -bor 0x200))) | Out-Null
Run $db "DELETE FROM ``Upgrade`` WHERE ``ActionProperty`` = 'JP_UPGRADABLE_FOUND'" $null
Run $db "INSERT INTO ``Upgrade`` ($columns) VALUES (?, ?, ?, ?, ?, ?, ?)" $row

Call $db 'Commit' $null | Out-Null
Write-Output "Installer pictures and same-version upgrades set in $Msi"

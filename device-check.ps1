param([string]$ToolchainRoot = (Join-Path $PSScriptRoot '..\..\work\toolchain'))
$ErrorActionPreference = 'Stop'
$adb = Join-Path (Resolve-Path -LiteralPath $ToolchainRoot).Path 'platform-tools\adb.exe'
$inventory = & $adb devices -l
$inventory | Write-Output
$ready = @($inventory | Where-Object { $_ -match '^\S+\s+device\s' })
if ($ready.Count -eq 0) { Write-Output 'No authorized Android device is connected.'; exit 2 }
if ($ready.Count -ne 1) { throw 'Multiple devices connected; select the intended phone explicitly.' }
$serial = ($ready[0] -split '\s+')[0]
foreach ($property in @('ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.display.id','ro.boot.flash.locked','ro.boot.verifiedbootstate')) {
    $value = & $adb -s $serial shell getprop $property
    Write-Output "$property = $value"
}
& $adb -s $serial shell pm list features
& $adb -s $serial shell dumpsys battery
& $adb -s $serial shell dumpsys usb
& $adb -s $serial shell settings get global bluetooth_on

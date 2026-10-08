param([switch]$AutoStart)
$ErrorActionPreference='Stop'
$helper=Join-Path $PSScriptRoot 'PocketDock-ImeBridge.exe'
if(!(Test-Path -LiteralPath $helper)){& (Join-Path $PSScriptRoot 'build-ime-bridge.ps1')}
if($AutoStart){
    $startup=[Environment]::GetFolderPath('Startup')
    $wsh=New-Object -ComObject WScript.Shell
    $link=$wsh.CreateShortcut((Join-Path $startup 'PocketDock 输入法桥接.lnk'))
    $link.TargetPath=$helper;$link.WorkingDirectory=$PSScriptRoot;$link.WindowStyle=7;$link.Save()
}
Start-Process -FilePath $helper -WindowStyle Hidden

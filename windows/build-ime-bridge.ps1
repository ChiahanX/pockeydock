$ErrorActionPreference='Stop'
$csc=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $csc /nologo /target:winexe /platform:x64 ('/out:'+(Join-Path $PSScriptRoot 'PocketDock-ImeBridge.exe')) /r:System.Windows.Forms.dll /r:System.Drawing.dll (Join-Path $PSScriptRoot 'PocketDock-ImeBridge.cs')
if($LASTEXITCODE -ne 0){throw 'IME bridge compilation failed'}

param([string]$ToolchainRoot = (Join-Path $PSScriptRoot '..\..\work\toolchain'))
$ErrorActionPreference = 'Stop'
$javaBin = Join-Path (Resolve-Path -LiteralPath $ToolchainRoot).Path 'jdk\bin'
$build = Join-Path $PSScriptRoot '.build\tests'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\com\pocketdock\core') -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += Join-Path $PSScriptRoot 'tests\CoreTests.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d $build @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& (Join-Path $javaBin 'java.exe') -cp $build CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }

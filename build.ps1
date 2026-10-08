param([string]$ToolchainRoot = (Join-Path $PSScriptRoot '..\..\work\toolchain'))
$ErrorActionPreference = 'Stop'
$tools = (Resolve-Path -LiteralPath $ToolchainRoot).Path
$javaBin = Join-Path $tools 'jdk\bin'
$androidJar = Join-Path $tools 'platform\android.jar'
$buildTools = Join-Path $tools 'build-tools'
$build = Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Force -Path $build,(Join-Path $build 'classes'),(Join-Path $build 'dex') | Out-Null
$env:JAVA_HOME = Join-Path $tools 'jdk'
$env:PATH = $javaBin + ';' + $env:PATH
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d (Join-Path $build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& (Join-Path $javaBin 'jar.exe') cf (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
& (Join-Path $buildTools 'd8.bat') --min-api 28 --lib $androidJar --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
$unsigned = Join-Path $build 'unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link -o $unsigned --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -I $androidJar --min-sdk-version 28 --target-sdk-version 35
if ($LASTEXITCODE -ne 0) { throw 'APK packaging failed' }
& (Join-Path $javaBin 'jar.exe') uf $unsigned -C (Join-Path $build 'dex') classes.dex
$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$keyDir = Join-Path $tools 'keys'
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
$key = Join-Path $keyDir 'pocketdock-dev.p12'
if (!(Test-Path -LiteralPath $key)) {
    & (Join-Path $javaBin 'keytool.exe') -genkeypair -keystore $key -storetype PKCS12 -storepass android -keypass android -alias pocketdock -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=PocketDock Development'
    if ($LASTEXITCODE -ne 0) { throw 'Development signing key creation failed' }
}
$output = Join-Path $PSScriptRoot 'PocketDock-dev.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-key-alias pocketdock --ks-pass pass:android --key-pass pass:android --out $output $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $output
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
Get-Item -LiteralPath $output | Select-Object FullName,Length

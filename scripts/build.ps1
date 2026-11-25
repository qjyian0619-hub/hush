param([string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk", [string]$BuildRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'build'))
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$app = Join-Path $project 'app'
$tools = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$ndk = Join-Path $Sdk 'ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin'
function Run([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Failed ($LASTEXITCODE): $Exe" }
}
New-Item -ItemType Directory -Force -Path $BuildRoot,(Join-Path $project 'dist') | Out-Null
$BuildRoot = (New-Item -ItemType Directory -Path (Join-Path $BuildRoot ([System.IO.Path]::GetRandomFileName()))).FullName
try {
Copy-Item -LiteralPath (Join-Path $app 'src'),(Join-Path $app 'res'),(Join-Path $app 'assets'),(Join-Path $app 'native') -Destination $BuildRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $app 'AndroidManifest.xml'),(Join-Path $app 'ProbeManifest.xml') -Destination $BuildRoot -Force
Copy-Item -LiteralPath (Join-Path $project 'deps\xposed-api-82.jar') -Destination $BuildRoot -Force
New-Item -ItemType Directory -Force -Path (Join-Path $BuildRoot 'classes'),(Join-Path $BuildRoot 'dex'),(Join-Path $BuildRoot 'lib\arm64-v8a') | Out-Null
$sources = Get-ChildItem -LiteralPath (Join-Path $BuildRoot 'src') -Filter '*.java' -Recurse | ForEach-Object {$_.FullName}
Run 'javac' (@('-J-Duser.language=en','-source','8','-target','8','-encoding','UTF-8','-classpath',"$androidJar;$(Join-Path $BuildRoot 'xposed-api-82.jar')",'-d',(Join-Path $BuildRoot 'classes')) + $sources)
Run 'jar' @('--create','--file',(Join-Path $BuildRoot 'classes.jar'),'-C',(Join-Path $BuildRoot 'classes'),'.')
Run (Join-Path $tools 'd8.bat') @('--min-api','28','--lib',$androidJar,'--lib',(Join-Path $BuildRoot 'xposed-api-82.jar'),'--output',(Join-Path $BuildRoot 'dex'),(Join-Path $BuildRoot 'classes.jar'))
Run (Join-Path $ndk 'aarch64-linux-android28-clang++.cmd') @('-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-z,max-page-size=16384','-static-libstdc++',(Join-Path $BuildRoot 'native\regionguard.cpp'),'-ldl','-o',(Join-Path $BuildRoot 'lib\arm64-v8a\libregionguard.so'))
# Zygisk part (Chrome/Edge renderer time zone); package_outputs.py places it at zygisk/arm64-v8a.so in the KSU ZIP.
Run (Join-Path $ndk 'aarch64-linux-android28-clang++.cmd') @('-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-fno-exceptions','-fno-rtti','-Wl,-z,max-page-size=16384','-Wl,--gc-sections','-s','-fno-threadsafe-statics','-nostdlib++',(Join-Path $BuildRoot 'native\renderer_zone.cpp'),'-llog','-o',(Join-Path $project 'dist\regionguard-zygisk-arm64-v8a.so'))
Get-FileHash -LiteralPath (Join-Path $project 'dist\regionguard-zygisk-arm64-v8a.so') -Algorithm SHA256 | Select-Object Path,Hash
$keystore = Join-Path $project 'signing\local.jks'
New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
if (!(Test-Path -LiteralPath $keystore)) {
    Run 'keytool' @('-genkeypair','-keystore',$keystore,'-alias','regionguard','-keyalg','RSA','-keysize','3072','-validity','10000','-storepass','android','-keypass','android','-dname','CN=Local Region Guard, OU=Local, O=Personal, C=US')
}
foreach ($variant in @(@{Name='regionguard';Manifest='AndroidManifest.xml'},@{Name='regionguard-probe';Manifest='ProbeManifest.xml'})) {
    $unsigned = Join-Path $BuildRoot ($variant.Name + '-unsigned.apk')
    $packageDir = Join-Path $BuildRoot ('pack-' + $variant.Name)
    New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $BuildRoot $variant.Manifest) -Destination (Join-Path $packageDir 'AndroidManifest.xml') -Force
    Run (Join-Path $tools 'aapt.exe') @('package','-f','-M',(Join-Path $packageDir 'AndroidManifest.xml'),'-S',(Join-Path $BuildRoot 'res'),'-A',(Join-Path $BuildRoot 'assets'),'-I',$androidJar,'-F',$unsigned)
    $zipCode = @'
import pathlib, sys, zipfile
p = pathlib.Path(sys.argv[1]); target = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(target, 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for f in (p/'dex').glob('*.dex'): z.write(f, f.name)
    if target.name.startswith('regionguard-unsigned'):
        for f in (p/'lib').rglob('*.so'): z.write(f, f.relative_to(p).as_posix(), compress_type=zipfile.ZIP_STORED)
'@
    Run 'python' @('-c',$zipCode,$BuildRoot,$unsigned)
    $aligned = Join-Path $BuildRoot ($variant.Name + '-aligned.apk')
    Run (Join-Path $tools 'zipalign.exe') @('-f','-P','16','4',$unsigned,$aligned)
    $output = Join-Path $project ('dist\' + $variant.Name + '.apk')
    Run (Join-Path $tools 'apksigner.bat') @('sign','--ks',$keystore,'--ks-key-alias','regionguard','--ks-pass','pass:android','--key-pass','pass:android','--out',$output,$aligned)
    Run (Join-Path $tools 'apksigner.bat') @('verify',$output)
    Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Path,Hash
}
} finally {
    # Intermediate files are only needed while building: remove this run's folder, and the parent if it is now empty.
    $parent = Split-Path -Parent $BuildRoot
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force -ErrorAction SilentlyContinue
    if (!(Get-ChildItem -LiteralPath $parent -Force -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $parent -ErrorAction SilentlyContinue }
}

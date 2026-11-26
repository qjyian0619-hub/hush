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
$keystore = Join-Path $project 'signing\local.jks'
if (!(Test-Path -LiteralPath $keystore -PathType Leaf)) { throw 'The existing signing/local.jks is required.' }
New-Item -ItemType Directory -Force -Path $BuildRoot,(Join-Path $project 'dist') | Out-Null
$buildParent = (Resolve-Path -LiteralPath $BuildRoot).Path
$BuildRoot = (New-Item -ItemType Directory -Path (Join-Path $buildParent ([System.IO.Path]::GetRandomFileName()))).FullName
if ((Split-Path -Parent $BuildRoot) -ne $buildParent) { throw 'Unexpected intermediate build directory.' }
try {
Copy-Item -LiteralPath (Join-Path $app 'src'),(Join-Path $app 'res'),(Join-Path $app 'META-INF'),(Join-Path $app 'native') -Destination $BuildRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $app 'AndroidManifest.xml'),(Join-Path $app 'ProbeManifest.xml') -Destination $BuildRoot -Force
$extractAar = @'
from pathlib import Path
from sys import argv
from zipfile import ZipFile

with ZipFile(argv[1]) as aar:
    Path(argv[2]).write_bytes(aar.read('classes.jar'))
'@
foreach ($library in @('api','service','interface')) {
    $name = "libxposed-$library-102.0.0"
    Run 'python' @('-c',$extractAar,(Join-Path $project "deps\$name.aar"),(Join-Path $BuildRoot "$name.jar"))
}
$apiJar = Join-Path $BuildRoot 'libxposed-api-102.0.0.jar'
$serviceJars = @((Join-Path $BuildRoot 'libxposed-service-102.0.0.jar'),(Join-Path $BuildRoot 'libxposed-interface-102.0.0.jar'))
New-Item -ItemType Directory -Force -Path (Join-Path $BuildRoot 'lib\arm64-v8a') | Out-Null
Run (Join-Path $ndk 'aarch64-linux-android28-clang++.cmd') @('-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-Wl,-z,max-page-size=16384','-static-libstdc++',(Join-Path $BuildRoot 'native\regionguard.cpp'),'-ldl','-o',(Join-Path $BuildRoot 'lib\arm64-v8a\libregionguard.so'))
# Zygisk part (Chrome/Edge renderer time zone); package_outputs.py places it at zygisk/arm64-v8a.so in the KSU ZIP.
Run (Join-Path $ndk 'aarch64-linux-android28-clang++.cmd') @('-shared','-fPIC','-std=c++17','-O2','-fvisibility=hidden','-fno-exceptions','-fno-rtti','-Wl,-z,max-page-size=16384','-Wl,--gc-sections','-s','-fno-threadsafe-statics','-nostdlib++',(Join-Path $BuildRoot 'native\renderer_zone.cpp'),'-llog','-o',(Join-Path $project 'dist\regionguard-zygisk-arm64-v8a.so'))
Get-FileHash -LiteralPath (Join-Path $project 'dist\regionguard-zygisk-arm64-v8a.so') -Algorithm SHA256 | Select-Object Path,Hash
$sourceDir = Join-Path $BuildRoot 'src\dev\local\regionguard'
$moduleSources = @(Get-ChildItem -LiteralPath (Join-Path $BuildRoot 'src') -Filter '*.java' -Exclude 'ProbeActivity.java' -Recurse | ForEach-Object {$_.FullName})
$probeSource = Join-Path $sourceDir 'ProbeActivity.java'
foreach ($variant in @(@{Name='regionguard';Manifest='AndroidManifest.xml';Module=$true},@{Name='regionguard-probe';Manifest='ProbeManifest.xml';Module=$false})) {
    $unsigned = Join-Path $BuildRoot ($variant.Name + '-unsigned.apk')
    $packageDir = Join-Path $BuildRoot ('pack-' + $variant.Name)
    $classes = Join-Path $packageDir 'classes'
    $dex = Join-Path $packageDir 'dex'
    $classesJar = Join-Path $packageDir 'classes.jar'
    New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
    $classpath = $androidJar
    $sources = @($probeSource)
    $d8Args = @('--min-api','28','--lib',$androidJar,'--output',$dex,$classesJar)
    if ($variant.Module) {
        $classpath = (@($androidJar,$apiJar) + $serviceJars) -join ';'
        $sources = $moduleSources
        # The framework supplies the API at runtime. Only the service implementation and its AIDL are program inputs.
        $d8Args += @('--lib',$apiJar) + $serviceJars
    }
    Run 'javac' (@('-J-Duser.language=en','-source','8','-target','8','-encoding','UTF-8','-classpath',$classpath,'-d',$classes) + $sources)
    Run 'jar' @('--create','--file',$classesJar,'-C',$classes,'.')
    Run (Join-Path $tools 'd8.bat') $d8Args
    Copy-Item -LiteralPath (Join-Path $BuildRoot $variant.Manifest) -Destination (Join-Path $packageDir 'AndroidManifest.xml') -Force
    Run (Join-Path $tools 'aapt.exe') @('package','-f','-M',(Join-Path $packageDir 'AndroidManifest.xml'),'-S',(Join-Path $BuildRoot 'res'),'-I',$androidJar,'-F',$unsigned)
    $zipCode = @'
import pathlib, sys, zipfile
p, target, dex, notices = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(target, 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for f in dex.glob('*.dex'): z.write(f, f.name)
    if target.name == 'regionguard-unsigned.apk':
        for f in (p/'lib').rglob('*.so'): z.write(f, f.relative_to(p).as_posix(), compress_type=zipfile.ZIP_STORED)
        for f in (p/'META-INF'/'xposed').glob('*'): z.write(f, f.relative_to(p).as_posix())
        z.write(notices, 'META-INF/THIRD_PARTY_NOTICES.txt')
'@
    Run 'python' @('-c',$zipCode,$BuildRoot,$unsigned,$dex,(Join-Path $project 'THIRD_PARTY_NOTICES.txt'))
    $aligned = Join-Path $BuildRoot ($variant.Name + '-aligned.apk')
    Run (Join-Path $tools 'zipalign.exe') @('-f','-P','16','4',$unsigned,$aligned)
    $output = Join-Path $project ('dist\' + $variant.Name + '.apk')
    Run (Join-Path $tools 'apksigner.bat') @('sign','--ks',$keystore,'--ks-key-alias','regionguard','--ks-pass','pass:android','--key-pass','pass:android','--out',$output,$aligned)
    Run (Join-Path $tools 'apksigner.bat') @('verify',$output)
    Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Path,Hash
}
} finally {
    # Intermediate files are only needed while building: remove this run's folder, and the parent if it is now empty.
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force -ErrorAction SilentlyContinue
    if (!(Get-ChildItem -LiteralPath $buildParent -Force -ErrorAction SilentlyContinue)) { Remove-Item -LiteralPath $buildParent -ErrorAction SilentlyContinue }
}

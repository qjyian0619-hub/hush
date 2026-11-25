import hashlib
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent.parent
dist = root / "dist"
properties = (root / "ksu-module/module.prop").read_text(encoding="utf-8").splitlines()
version = next(line.removeprefix("version=") for line in properties if line.startswith("version="))
ksu_package = f"regionguard-ksu-{version}.zip"
source_files = []
# Source archive: code and docs only. No phone backups, evidence or signing key.
for folder in ("app", "ksu-module", "scripts", "tools"):
    source_files += [p for p in (root/folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
source_files += [root/p for p in ("README.md", "THIRD_PARTY_NOTICES.txt", ".gitignore", "deps/xposed-api-82.jar")]
with ZipFile(dist/ksu_package, "w", ZIP_DEFLATED) as archive:
    for path in (root/"ksu-module").rglob("*"):
        if path.is_file():
            archive.write(path,path.relative_to(root/"ksu-module").as_posix())
    archive.write(dist/"regionguard.apk","backend.apk")
    # Loaded by Zygisk Next at boot: Chrome/Edge renderer time zone.
    archive.write(dist/"regionguard-zygisk-arm64-v8a.so","zygisk/arm64-v8a.so")
with ZipFile(dist/"regionguard-source.zip", "w", ZIP_DEFLATED) as archive:
    for path in source_files:
        archive.write(path, path.relative_to(root).as_posix())
with ZipFile(dist/"regionguard-recovery.zip", "w", ZIP_DEFLATED) as archive:
    for relative in ("scripts/setup.ps1", "README.md", "dist/regionguard.apk", "backup/settings-backup.json", "backup/browser-backup.json", "backup/device-id.txt"):
        archive.write(root/relative, relative)
checksums = []
for name in ("regionguard.apk", "regionguard-probe.apk", "regionguard-zygisk-arm64-v8a.so", ksu_package, "regionguard-source.zip", "regionguard-recovery.zip"):
    path=dist/name
    checksums.append(hashlib.sha256(path.read_bytes()).hexdigest()+"  "+name)
(dist/"SHA256SUMS.txt").write_text("\n".join(checksums)+"\n",encoding="ascii")
print("Packaged APK, source ZIP, phone-specific recovery ZIP and SHA256SUMS.txt")

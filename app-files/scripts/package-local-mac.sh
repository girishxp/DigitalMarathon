#!/bin/bash
# Owner packaging tool. Normal launch needs no Python, build tools or Terminal.
set -euo pipefail
PACK_ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
PACK_OUTPUT="${1:-$(dirname "$PACK_ROOT")}"
[ "$#" -le 1 ] || { printf 'Usage: package-local-mac.sh [existing output folder]\n' >&2; exit 2; }
command -v python3 >/dev/null || { printf 'Python 3 is required for owner packaging.\n' >&2; exit 1; }
python3 - "$PACK_ROOT" "$PACK_OUTPUT" <<'PY'
from pathlib import Path
from collections import Counter
import hashlib, json, os, plistlib, re, stat, struct, subprocess, sys, tempfile, zipfile

package=Path(sys.argv[1]).resolve(strict=True)
output=Path(sys.argv[2]).resolve(strict=True)
if sys.platform!='darwin': raise SystemExit('Local Mac package verification must run on a Mac.')
if package.name!='digital-marathon': raise SystemExit('The shared package folder must be named digital-marathon.')
if not output.is_dir() or output==package or package in output.parents:
    raise SystemExit('Output must be an existing folder outside the launch package.')
version=(package/'app-files/VERSION').read_text().strip()
if not re.fullmatch(r'(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})',version):
    raise SystemExit('VERSION must contain a semantic version.')
zip_path=output/f'digital-marathon-cross-platform-v{version}-click-to-launch.zip'
checksum=output/f'SHA256SUMS-v{version}.txt'
if zip_path.exists() or checksum.exists(): raise SystemExit('ZIP/checksum already exists; preserve the previous build first.')
app=package/'Digital Marathon.app'
required=[package/'.gitignore',package/'.gitattributes',
          package/'app-files/scripts/publish-github.sh',package/'app-files/scripts/publish-github.ps1',
          app/'Contents/Info.plist',app/'Contents/MacOS/Digital Marathon',
          app/'Contents/app/Digital Marathon.cfg',app/'Contents/app/digital-marathon.jar',
          app/'Contents/app/libInputActivityMacHook.dylib',app/'Contents/Resources/Digital Marathon.icns',
          app/'Contents/runtime/Contents/MacOS/libjli.dylib',app/'Contents/runtime/Contents/Home/lib/server/libjvm.dylib',
          package/'Start Digital Marathon - Windows.bat',package/'app-files/app/digital-marathon.jar',
          package/'app-files/lib/jnativehook-2.2.2.jar',package/'app-files/runtime/windows-x64/bin/java.exe',
          package/'app-files/runtime/windows-x64/bin/javaw.exe',package/'app-files/runtime/windows-x64/bin/server/jvm.dll',
          package/'app-files/docs/Digital-Marathon-Product-Guide.pdf']
for path in required:
    if not path.is_file() or path.stat().st_size==0: raise SystemExit('Required launch component missing: '+str(path.relative_to(package)))
mac_publisher=(package/'app-files/scripts/publish-github.sh').read_text()
windows_publisher=(package/'app-files/scripts/publish-github.ps1').read_text()
if not re.search(r'^VERSION='+re.escape(version)+r'$',mac_publisher,re.M) or not re.search(r"^\$Version = '"+re.escape(version)+r"'$",windows_publisher,re.M):
    raise SystemExit('Packaged publisher version differs from the launch package.')
if 'FEED_NAME="digital-marathon-update.json"' not in mac_publisher or "$FeedName = 'digital-marathon-update.json'" not in windows_publisher:
    raise SystemExit('The combined ZIP must include its self-contained update descriptor generators.')
info=plistlib.loads((app/'Contents/Info.plist').read_bytes())
if info.get('CFBundleIdentifier')!='com.girishgupta.inputactivitytracker' or any(info.get(key)!=version for key in ('CFBundleVersion','CFBundleShortVersionString')):
    raise SystemExit('Mac bundle product/version mismatch.')
if '-Djpackage.app-version='+version not in (app/'Contents/app/Digital Marathon.cfg').read_text():
    raise SystemExit('Mac launcher version mismatch.')
jar=package/'app-files/app/digital-marathon.jar'
if jar.read_bytes()!=(app/'Contents/app/digital-marathon.jar').read_bytes(): raise SystemExit('Mac and Windows JARs differ.')
with zipfile.ZipFile(jar) as archive:
    if archive.testzip(): raise SystemExit('Application JAR CRC failed.')
    manifest=archive.read('META-INF/MANIFEST.MF').decode().replace('\r','')
    if 'Implementation-Version: '+version+'\n' not in manifest or 'Main-Class: com.inputactivitytracker.Main\n' not in manifest:
        raise SystemExit('Application JAR manifest mismatch.')
    if archive.read('Digital-Marathon-Product-Guide.pdf')!=(package/'app-files/docs/Digital-Marathon-Product-Guide.pdf').read_bytes():
        raise SystemExit('Embedded PDF differs from the included guide.')
    for resource in (package/'app-files/resources').iterdir():
        if resource.is_file() and archive.read(resource.name)!=resource.read_bytes():
            raise SystemExit('Embedded resource mismatch: '+resource.name)
if (app/'Contents/Resources/Digital Marathon.icns').read_bytes()!=(package/'app-files/resources/app-icon.icns').read_bytes():
    raise SystemExit('Mac icon differs from approved resource.')
for path in (app/'Contents/MacOS/Digital Marathon',app/'Contents/app/libInputActivityMacHook.dylib',app/'Contents/runtime/Contents/MacOS/libjli.dylib'):
    data=path.read_bytes()[:8]
    if data[:4]!=bytes.fromhex('cffaedfe') or struct.unpack('<I',data[4:8])[0]!=0x0100000c:
        raise SystemExit('Expected arm64 Mach-O component: '+str(path.relative_to(package)))
for path in (package/'app-files/runtime/windows-x64/bin/java.exe',package/'app-files/runtime/windows-x64/bin/javaw.exe',package/'app-files/runtime/windows-x64/bin/server/jvm.dll'):
    with path.open('rb') as stream:
        header=stream.read(64)
        if header[:2]!=b'MZ': raise SystemExit('Invalid Windows runtime binary.')
        stream.seek(struct.unpack('<I',header[60:64])[0]); pe=stream.read(6)
        if pe[:4]!=b'PE\0\0' or struct.unpack('<H',pe[4:6])[0]!=0x8664: raise SystemExit('Expected Windows x64 runtime.')
for relative in ('Digital Marathon.app/Contents/MacOS/Digital Marathon','REPAIR_MAC_PERMISSIONS.command','Publish Digital Marathon.command','Start Screen Recorder - Linux.sh','app-files/scripts/package-local-mac.sh'):
    if not (package/relative).stat().st_mode&stat.S_IXUSR: raise SystemExit('Launcher is not executable: '+relative)
private=re.compile(r'(^|/)(?:__MACOSX|\.DS_Store|\.git|\.env[^/]*|\.digital_marathon|qa|logs|history|userdata|recordings|credentials[^/]*|secrets[^/]*|activity-buckets\.dat|analytics-settings\.json|update-preferences\.json|update-session\.json|\.update-session-[^/]*|preferences\.json|hs_err_pid[^/]*)(?:/|$)|\.(?:log|csv|jpg|jpeg|pem|key|p12|pfx|hprof|jfr)$',re.I)
files={}
for path in package.rglob('*'):
    name=path.relative_to(package).as_posix()
    if path.is_symlink(): raise SystemExit('Symlinks are not permitted in this shared local package: '+name)
    if path.is_file():
        if private.search(name): raise SystemExit('Private/temporary file refused: '+name)
        if path.name.lower()=='digital-marathon-update.json':
            raise SystemExit('Do not embed a whole-ZIP digest descriptor in itself; the included publishers generate it after verification.')
        files[name]=path
subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict','--verbose=2',str(app)],check=True)
attrs=subprocess.check_output(['/usr/bin/xattr','-r',str(app)],text=True)
if 'com.apple.quarantine' in attrs: raise SystemExit('The local build has download quarantine; it was not changed automatically.')
with tempfile.TemporaryDirectory(prefix='digital-marathon-local-package-') as temporary:
    temporary=Path(temporary)
    candidate=temporary/zip_path.name
    subprocess.run(['/usr/bin/ditto','-c','-k','--keepParent','--norsrc',str(package),str(candidate)],check=True)
    with zipfile.ZipFile(candidate) as archive:
        names=archive.namelist()
        if archive.testzip(): raise SystemExit('ZIP CRC failed.')
        if {n.split('/')[0] for n in names}!={'digital-marathon'} or any(c>1 for c in Counter(names).values()):
            raise SystemExit('ZIP must contain one shared digital-marathon folder without duplicate entries.')
        seen=set()
        for entry in archive.infolist():
            if entry.is_dir(): continue
            if '\\' in entry.filename or '..' in Path(entry.filename).parts: raise SystemExit('Unsafe ZIP path.')
            name=Path(entry.filename).relative_to('digital-marathon').as_posix(); seen.add(name)
            if name not in files or archive.read(entry)!=files[name].read_bytes(): raise SystemExit('ZIP content mismatch: '+name)
            if stat.S_IMODE(entry.external_attr>>16)!=stat.S_IMODE(files[name].stat().st_mode): raise SystemExit('ZIP permission mismatch: '+name)
        if seen!=set(files): raise SystemExit('ZIP inventory mismatch.')
    extracted=temporary/'extracted'; extracted.mkdir()
    subprocess.run(['/usr/bin/ditto','-x','-k',str(candidate),str(extracted)],check=True)
    extracted_app=extracted/'digital-marathon/Digital Marathon.app'
    subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict','--verbose=2',str(extracted_app)],check=True)
    if 'com.apple.quarantine' in subprocess.check_output(['/usr/bin/xattr','-r',str(extracted_app)],text=True):
        raise SystemExit('Fresh local extraction unexpectedly has download quarantine.')
    digest=hashlib.sha256(candidate.read_bytes()).hexdigest()
    # Exclusive creation preserves previous versions rather than overwriting.
    with zip_path.open('xb') as target, candidate.open('rb') as source:
        import shutil
        shutil.copyfileobj(source,target)
    with checksum.open('x') as stream: stream.write(digest+'  '+zip_path.name+'\n')
print(json.dumps({'version':version,'zip':str(zip_path),'bytes':zip_path.stat().st_size,'sha256':digest,
                  'single_folder':'digital-marathon','mac_app_inside_zip':True,'windows_launcher_inside_zip':True,
                  'fresh_local_extraction_signature':'valid','distribution_trust':'local development; not Apple notarized'},indent=2))
PY

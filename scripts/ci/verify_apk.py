#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def verify():
    app = ROOT / 'src/android/app'
    metadata = json.loads((app / 'build/outputs/apk/release/output-metadata.json').read_text())
    if metadata['applicationId'] != 'com.openminis.perf120':
        raise RuntimeError('Unexpected applicationId: ' + metadata['applicationId'])
    element = metadata['elements'][0]
    build_config = (app / 'build.gradle.kts').read_text()
    expected_version = re.search(r'versionName\s*=\s*"([^"]+)"', build_config).group(1)
    expected_code = int(re.search(r'versionCode\s*=\s*(\d+)', build_config).group(1))
    if element['versionName'] != expected_version or element['versionCode'] != expected_code:
        raise RuntimeError('APK version does not match the current source configuration.')
    apk = app / 'build/outputs/apk/release' / element['outputFile']
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools/35.0.0'
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    fingerprint = re.search(r'SHA-256 digest: ([0-9a-f]+)', signature).group(1)
    if os.environ.get('CI_SIGNED', 'false') == 'true':
        expected = 'af29344161194d6b1e4c1785766c4a2dd74d04c2128acdd9c664a5e9001990e4'
        if fingerprint != expected:
            raise RuntimeError('Signing certificate differs from the dedicated perf120 key.')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        profiles = [name for name in names if name.startswith('assets/dexopt/')]
        if 'assets/dexopt/baseline.prof' not in profiles:
            raise RuntimeError('Baseline Profile missing from APK.')
        abis = sorted({name.split('/')[1] for name in names if name.startswith('lib/')})
        if abis != ['arm64-v8a']:
            raise RuntimeError('Unexpected ABI list: ' + str(abis))
    manifest = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'xmltree', str(apk),
                                       '--file', 'AndroidManifest.xml'], text=True)
    authorities = [line.strip() for line in manifest.splitlines() if 'android:authorities' in line]
    if not authorities or any('com.openminis.perf120.' not in line for line in authorities):
        raise RuntimeError('Provider authorities are not isolated to the new package.')
    directory = ROOT / 'ci-output'
    directory.mkdir(exist_ok=True)
    kind = 'coexist' if os.environ.get('CI_SIGNED', 'false') == 'true' else 'temporary-debug-signature'
    filename = 'MinisApp-' + element['versionName'] + '-' + kind + '-arm64-v8a.apk'
    shutil.copy2(apk, directory / filename)
    summary = {'applicationId': metadata['applicationId'], 'versionName': element['versionName'],
               'versionCode': element['versionCode'], 'signerSHA256': fingerprint,
               'bytes': apk.stat().st_size, 'profiles': profiles, 'authorities': authorities}
    (directory / 'verification.txt').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    report = os.environ.get('GITHUB_STEP_SUMMARY')
    if report:
        with open(report, 'a') as out:
            out.write('## Android build\n\n- APK: `' + filename + '`\n- Package: `' + metadata['applicationId']
                      + '`\n- Signer SHA-256: `' + fingerprint + '`\n- Baseline Profile included.\n')

if __name__ == '__main__':
    verify()

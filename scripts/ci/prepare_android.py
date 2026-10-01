#!/usr/bin/env python3
"""Restore the bundled, unchanged ARM64 native artifacts for CI."""
from pathlib import Path
import io
import json
import os
import tarfile

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'ci/native'
ALLOWED = ('src/android/app/src/main/jniLibs/arm64-v8a/',
           'src/android/app/src/main/assets/', 'src/android/app/libs/')
REQUIRED = [
    'src/android/app/libs/rclone-official.jar',
    'src/android/app/src/main/assets/proot-aarch64',
    'src/android/app/src/main/assets/alpine-minirootfs.tar',
] + ['src/android/app/src/main/jniLibs/arm64-v8a/' + name for name in (
    'libc++_shared.so', 'libgojni.so', 'libjieba_jni.so', 'libminis_crash_handler.so',
    'libproot-loader.so', 'libproot-loader32.so', 'libproot.so', 'libpty_bridge.so')]

def restore():
    manifest = BUNDLE / 'manifest.json'
    if manifest.exists():
        config = json.loads(manifest.read_text())
        payload = bytearray()
        for name in config['parts']:
            if Path(name).name != name:
                raise ValueError('Invalid native bundle filename')
            payload.extend((BUNDLE / name).read_bytes())
        with tarfile.open(fileobj=io.BytesIO(payload), mode='r:gz') as archive:
            for member in archive.getmembers():
                dest = (ROOT / member.name).resolve()
                if (not dest.is_relative_to(ROOT) or not member.name.startswith(ALLOWED)
                        or not member.isfile()):
                    raise ValueError('Unexpected native bundle entry: ' + member.name)
                dest.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError('Missing payload')
                with dest.open('wb') as output:
                    while block := source.read(1024 * 1024):
                        output.write(block)
                dest.chmod(member.mode & 0o777)
    missing = [name for name in REQUIRED if not (ROOT / name).is_file()]
    if missing:
        raise RuntimeError('Native resources missing: ' + ', '.join(missing))
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk or not (Path(sdk) / 'platforms/android-36/android.jar').is_file():
        raise RuntimeError('Android SDK platform 36 is not installed')
    escaped = str(Path(sdk)).replace('\\', '\\\\').replace(':', '\\:')
    (ROOT / 'src/android/local.properties').write_text('sdk.dir=' + escaped + '\n')
    (ROOT / 'src/android/gradlew').chmod(0o755)
    print('ARM64 native resources and Android SDK path prepared.')

if __name__ == '__main__':
    restore()

#!/usr/bin/env python3
"""Optional bootstrap for the small-file GitHub web upload package."""
from pathlib import Path
import io
import json
import tarfile

ROOT = Path(__file__).resolve().parents[2]

def restore():
    config = ROOT / 'ci/source/manifest.json'
    if not config.is_file():
        if (ROOT / 'src/android/app/build.gradle.kts').is_file():
            print('Using the repository Android source.')
            return
        raise RuntimeError('Android source missing. Upload the ci/source parts or the full source tree.')
    data = bytearray()
    for name in json.loads(config.read_text())['parts']:
        if Path(name).name != name:
            raise ValueError('Invalid source part name')
        data.extend((config.parent / name).read_bytes())
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive.getmembers():
            dest = (ROOT / member.name).resolve()
            if (not dest.is_relative_to(ROOT) or not member.isfile()
                    or not member.name.startswith(('src/', 'scripts/', 'docs/', 'deps/'))
                    or 'signing' in Path(member.name).parts
                    or member.name.endswith(('.jks', '.keystore', 'local.properties'))):
                raise ValueError('Unexpected source entry: ' + member.name)
            dest.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise ValueError('Source data missing')
            with dest.open('wb') as output:
                while block := source.read(1024 * 1024):
                    output.write(block)
            dest.chmod(member.mode & 0o777)
    if not (ROOT / 'src/android/app/build.gradle.kts').is_file():
        raise RuntimeError('Incomplete Android source snapshot')
    print('Android source snapshot restored.')

if __name__ == '__main__':
    restore()

#!/usr/bin/env python3
"""Restore private signing material without printing secrets."""
import base64
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def property_value(value):
    out = []
    for char in value:
        if char == '\\': out.append('\\\\')
        elif char == '\n': out.append('\\n')
        elif char == '\r': out.append('\\r')
        elif char == '\t': out.append('\\t')
        elif char in ' =:#!': out.append('\\' + char)
        elif ord(char) > 127:
            raw = char.encode('utf-16-be')
            out.extend('\\u' + raw[i:i+2].hex() for i in range(0, len(raw), 2))
        else: out.append(char)
    return ''.join(out)

def restore():
    encoded = os.environ.get('ANDROID_KEYSTORE_BASE64', '')
    password = os.environ.get('ANDROID_KEYSTORE_PASSWORD', '')
    if not encoded or not password:
        raise SystemExit('Missing Secrets: ANDROID_KEYSTORE_BASE64 and ANDROID_KEYSTORE_PASSWORD. '
                         'Add both, or disable signed when manually starting the workflow.')
    try:
        key = base64.b64decode(''.join(encoded.split()), validate=True)
    except ValueError:
        raise SystemExit('ANDROID_KEYSTORE_BASE64 is not valid Base64.') from None
    if not key:
        raise SystemExit('Signing key is empty.')
    directory = ROOT / 'src/android/signing'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    (directory / 'perf120.jks').write_bytes(key)
    props = ('storeFile=signing/perf120.jks\nkeyAlias=perf120\n'
             'storePassword=' + property_value(password) + '\n'
             'keyPassword=' + property_value(password) + '\n')
    (directory / 'perf120.properties').write_text(props, encoding='ascii')
    for file in directory.iterdir():
        file.chmod(0o600)
    print('Dedicated signing material restored.')

if __name__ == '__main__':
    restore()

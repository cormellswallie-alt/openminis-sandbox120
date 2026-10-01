#!/usr/bin/env python3
"""Local-only Python pipe throughput and /tmp stdlib checked-hash experiment."""
import compileall
import json
import os
import pathlib
import py_compile
import statistics
import subprocess
import sys
import sysconfig
import tempfile
import time

BASE = {k: v for k, v in os.environ.items() if not k.startswith('PYTHON')}
BASE['PYTHONDONTWRITEBYTECODE'] = '1'

def measure(code, extra, rounds=7):
    times = []
    size = 0
    for _ in range(rounds):
        start = time.perf_counter()
        p = subprocess.run([sys.executable, '-c', code], env=dict(BASE, **extra), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        times.append((time.perf_counter() - start) * 1000)
        size = len(p.stdout)
    return {'median_ms': round(statistics.median(times), 3), 'bytes': size}

def main():
    result = {'python': sys.version, 'throughput': {}}
    for label, code in {
        '200k_small_prints': "for i in range(200000): print('x' * 79)",
        '16MiB_single_print': "print('x' * (16 * 1024 * 1024))",
    }.items():
        buffered = measure(code, {})
        unbuffered = measure(code, {'PYTHONUNBUFFERED': '1'})
        result['throughput'][label] = {'buffered': buffered, 'unbuffered': unbuffered, 'ratio': round(unbuffered['median_ms'] / buffered['median_ms'], 2)}
    stdlib = pathlib.Path(sysconfig.get_path('stdlib'))
    sources = [p for p in stdlib.rglob('*.py') if not any(x in p.relative_to(stdlib).parts for x in ('site-packages', 'dist-packages', '__pycache__'))]
    # Prefix redirects all writes to disposable /tmp storage; never touch installed pyc.
    with tempfile.TemporaryDirectory(prefix='python-checked-hash-', dir='/tmp') as cache:
        old = sys.pycache_prefix
        sys.pycache_prefix = cache
        start = time.perf_counter()
        try:
            ok = all([compileall.compile_file(str(p), quiet=2, force=True, invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH) for p in sources])
        finally:
            sys.pycache_prefix = old
        build_ms = (time.perf_counter() - start) * 1000
        files = list(pathlib.Path(cache).rglob('*.pyc'))
        workloads = {
            'startup': 'pass',
            'stdlib_imports': 'import json, pathlib, argparse, asyncio, ssl, urllib.request, email, sqlite3, statistics',
        }
        timings = {}
        for name, code in workloads.items():
            # Empty prefix baseline ignores existing installed caches without writing new ones.
            timings[name] = {'no_cache': measure(code, {'PYTHONPYCACHEPREFIX': cache + '/absent'}, 15), 'checked_hash': measure(code, {'PYTHONPYCACHEPREFIX': cache}, 15)}
        result['checked_hash'] = {'compile_ok': ok, 'source_files': len(sources), 'cache_files': len(files), 'cache_bytes': sum(p.stat().st_size for p in files), 'build_ms': round(build_ms, 3), 'timings': timings, 'scope': 'host stdlib only; hot filesystem; disposable /tmp; no production enablement'}
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()

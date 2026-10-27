"""Require real Android vault and offline WebView fixture evidence; never contact a broker."""
from pathlib import Path
import re, subprocess, sys

out = Path('salida');out.mkdir(exist_ok=True)
package = 'com.liontrader.android'
screenshots = ('07_GRAFICO_PRUEBA.png', '08_ALERTA_PRUEBA.png')
for name in ('PRUEBA_KEYSTORE.txt', 'PRUEBA_GRAFICO_ALERTAS.txt', *screenshots):
    (out/name).unlink(missing_ok=True)

def adb(*args, binary=False):
    result = subprocess.run(['adb', *args], capture_output=True, text=not binary, timeout=100)
    if result.returncode:raise RuntimeError('ADB failed')
    return result.stdout if binary else result.stdout + result.stderr

def require_pass(output, marker):
    assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$', output, re.M), 'Native instrumentation failed'
    assert marker in output, 'Missing native phase pass marker'

for service in ('wifi', 'data'):adb('shell', 'svc', service, 'disable')
adb('install', '-r', sys.argv[1]);adb('install', '-r', sys.argv[2])
runner = package + '.test/' + package + '.TokenVaultInstrumentation'
try:
    for phase in ('default', 'persistence-write', 'persistence-read'):
        if phase == 'persistence-read':adb('shell', 'am', 'force-stop', package)
        args = ['shell', 'am', 'instrument', '-w', '-r']
        if phase != 'default':args += ['-e', 'phase', phase]
        output = adb(*args, runner)
        (out/('KEYSTORE_' + phase + '.txt')).write_text(output)
        require_pass(output, 'LION native vault tests: passed')
    (out/'PRUEBA_KEYSTORE.txt').write_text('PASS: Android Keystore AES-GCM, IV/corruption/failed persistence/clear and process restart.\n')
    adb('shell', 'am', 'force-stop', package)
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'phase', 'chart-alert', runner)
    (out/'GRAFICO_ALERTAS_NATIVE.txt').write_text(output)
    require_pass(output, 'LION native chart alert tests: passed')
    for name in screenshots:
        image = adb('exec-out', 'run-as', package, 'cat', 'files/lion-ui-fixture/' + name, binary=True)
        assert image.startswith(b'\x89PNG\r\n\x1a\n'), 'Missing actual native screenshot'
        (out/name).write_bytes(image)
    (out/'PRUEBA_GRAFICO_ALERTAS.txt').write_text('PASS: WebView real offline; DATOS DE PRUEBA; SVG OHLC/Entrada/SL/TP, M5/M15/H1/H4, refresh, identity, expiry, direction, fixed alert/dismissal and history/current readings. No financial requests.\n')
finally:
    # Never allow a failed fixture or leftover test credential to affect the navigation smoke.
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'pm', 'clear', package)

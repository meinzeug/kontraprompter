#!/usr/bin/env python3
"""Exercise shell-only pinned TLS with two Android devices, never app keys.

Usage: python3 scripts/test-cross-device-tls.py FIXTURE_JAR CAMERA_SERIAL PROMPTER_SERIAL [EXPECTED_SHA256]
The selected ADB devices must be connected by USB. Pairing pins are carried only
by this test process over ADB. This is not a productive signaling transport.
"""
import hashlib
import re
import select
import subprocess
import sys
import uuid

if len(sys.argv) not in (4, 5):
    raise SystemExit(__doc__)
path, camera, prompter = sys.argv[1:4]
expected = {
    '2d38c96454a7216ac30726251deb24e5846ca2c5421c1a40c579ae150f9996f2',
    '4741a4dd3e7ef76c3a34481f432bafbd3b5154e60a33a87859f4702c69a984cc',
}
if len(sys.argv) == 5:
    if not re.fullmatch(r'[a-f0-9]{64}', sys.argv[4]):
        raise SystemExit('Invalid expected SHA-256')
    expected = {sys.argv[4]}
with open(path, 'rb') as fixture:
    if hashlib.sha256(fixture.read()).hexdigest() not in expected:
        raise SystemExit('Unexpected fixture hash')

run_id = str(uuid.uuid4())
jar = '/data/local/tmp/kp-cross-tls-382f963.jar'
main = 'de.kontrakurs.kontraprompter.pairing.CrossDeviceTlsProbe'
devices = [(camera, 'camera'), (prompter, 'prompter')]
pushed, created = [], []
forward = reverse = False


def adb(device):
    return ['adb', '-s', device]


def probe(device, *args, timeout=40):
    command = f'CLASSPATH={jar} app_process /system/bin {main} ' + ' '.join(args)
    return subprocess.run(adb(device) + ['shell', command], capture_output=True, text=True, timeout=timeout)


def pin_from(output):
    match = re.search(r'CROSS_ALIAS=kp-cross-tls-fixture-[0-9a-f-]{36}-(?:camera|prompter) CROSS_PIN=([a-f0-9]{64})', output)
    if not match:
        raise RuntimeError('Test key fingerprint unavailable')
    return match.group(1)


def trial(label, host_pin, client_pin, expect_ok):
    command = f'CLASSPATH={jar} app_process /system/bin {main} host {run_id} camera 39810 {host_pin}'
    host = subprocess.Popen(adb(camera) + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        ready, _, _ = select.select([host.stdout], [], [], 20)
        if not ready or not host.stdout.readline().startswith('CROSS_LISTENING='):
            raise RuntimeError('Test host failed to listen')
        client = probe(prompter, 'client', run_id, 'prompter', '39851', client_pin, timeout=30)
        host_out, host_err = host.communicate(timeout=30)
        success = (host.returncode == 0 and client.returncode == 0 and
                   'CROSS_DEVICE_TLS13_OK=camera' in host_out and 'CROSS_DEVICE_TLS13_OK=prompter' in client.stdout)
        if expect_ok and not success:
            raise RuntimeError(f'{label}: connection not confirmed')
        if not expect_ok and (success or host.returncode == 0 or client.returncode == 0 or
                              'Gegenstelle nicht gepinnt' not in (host_err + client.stderr)):
            raise RuntimeError(f'{label}: pinned rejection not proven')
        print(label + ': OK', flush=True)
    finally:
        if host.poll() is None:
            host.terminate()
            host.wait(timeout=10)


try:
    output = subprocess.run(['adb', 'devices', '-l'], capture_output=True, text=True, check=True).stdout
    for device, _ in devices:
        lines = [line for line in output.splitlines() if line.startswith(device + ' ')]
        if len(lines) != 1 or ' device usb:' not in lines[0]:
            raise RuntimeError('Both serials must name USB-attached authorized devices')
    forwards = subprocess.run(['adb', 'forward', '--list'], capture_output=True, text=True, check=True).stdout
    reverses = subprocess.run(adb(prompter) + ['reverse', '--list'], capture_output=True, text=True, check=True).stdout
    if re.search(r'\btcp:39850\b', forwards) or re.search(r'\btcp:39851\b', reverses):
        raise RuntimeError('Fixture forwarding port is already in use')
    for device, role in devices:
        if subprocess.run(adb(device) + ['shell', 'test', '-e', jar]).returncode == 0:
            raise RuntimeError('Fixture path already exists')
        subprocess.run(adb(device) + ['push', path, jar], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        pushed.append(device)
        created.append((device, role))  # cleanup even if create partly fails
        result = probe(device, 'create', run_id, role)
        if result.returncode:
            raise RuntimeError(f'{role}: test key creation failed')
        if role == 'camera':
            camera_pin = pin_from(result.stdout)
        else:
            prompter_pin = pin_from(result.stdout)
    subprocess.run(adb(camera) + ['forward', 'tcp:39850', 'tcp:39810'], check=True, stdout=subprocess.DEVNULL)
    forward = True
    subprocess.run(adb(prompter) + ['reverse', 'tcp:39851', 'tcp:39850'], check=True, stdout=subprocess.DEVNULL)
    reverse = True
    trial('Wrong camera pin', prompter_pin, '0' * 64, False)
    trial('Wrong prompter pin', '0' * 64, camera_pin, False)
    trial('Cross-device mutual TLS 1.3', prompter_pin, camera_pin, True)
    trial('Fresh handshake using the same test identities', prompter_pin, camera_pin, True)
finally:
    for device, role in created:
        try:
            result = probe(device, 'cleanup', run_id, role, timeout=20)
            if result.returncode or 'CROSS_CLEANUP_OK=' not in result.stdout:
                print(f'{role}: test key cleanup NOT confirmed; fixture UUID {run_id}', file=sys.stderr)
        except Exception:
            print(f'{role}: test key cleanup NOT confirmed; fixture UUID {run_id}', file=sys.stderr)
    if reverse:
        subprocess.run(adb(prompter) + ['reverse', '--remove', 'tcp:39851'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if forward:
        subprocess.run(adb(camera) + ['forward', '--remove', 'tcp:39850'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for device in pushed:
        subprocess.run(adb(device) + ['shell', 'rm', jar], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

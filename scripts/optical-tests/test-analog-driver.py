"""Bounded front test, changing only CM6206 REG2 DRIVERON bit.

The user has connected the front amplifier and requested an audible test.
No full INIT, EEPROM write, SPDIF status change or global volume modification.
The original bit is journaled before submission and restored in finally.
CM6206 documentation and Linux CM6206 boot quirk:
https://github.com/torvalds/linux/blob/master/sound/usb/quirks.c
https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

import hid

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA = '2a3085c54fbcf801d8245c65222bc3959b4aa9fd3fdca737f2b221ff38620b48'
AC3_SOURCE_SHA = 'd8a9b917975b18c1357d3d9650fced77eaa8a262dd1412638204f9e5db6dfdd8'
SPOKEN_SOURCE_SHA = 'a927e03f9e5ba5c3b51c496f74f5178159e1610ac31e3a8b17a4b97aba829e72'
FOUR_SOURCE_SHA = '001b24ec6c2ba05490d32ac2ad310f0568ba098867cd163c857b6ca64b93a3ac'
REAR_MAP_SHA = '5063b200b9cf19d287484aa6218c0a2e1e657f1256904e2c78558b7a5f3e68c0'
ALL_MAP_SHA = 'f14db3d00b683823078bd048aa088d5f03c62bd2830f909225e172279e43c7e1'
HDMI_ENDPOINT = 'wasapi/{d83adb3c-7863-4a73-bfd8-b01de1fb9842}'
CAPTURE_ENDPOINT = '{0.0.1.00000000}.{7e729c77-2a58-45c2-8e4a-c88341a90744}'
MASK = 0x8000


def read_register(device, register):
    # Drain unrelated HID notifications before associating a reply with this read.
    for _ in range(16):
        if not device.read(4, 1):
            break
    if device.write(bytes((0, 0x30, 0, 0, register))) != 5:
        raise RuntimeError('Incomplete register read request')
    for _ in range(8):
        reply = device.read(4, 1000)
        if len(reply) == 4 and reply[0] == 0:
            reply = reply[1:]
        if len(reply) >= 3 and (reply[0] & 0xe0) == 0x20:
            return {'register': register, 'value': reply[1] | (reply[2] << 8),
                    'reply': reply, 'headphoneSense': bool(reply[0] & 0x10)}
    raise RuntimeError('No valid register response')


def save(path, report):
    with path.open('w', encoding='utf-8') as output:
        json.dump(report, output, indent=2)
        output.flush()
        os.fsync(output.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--render-endpoint', required=True)
    parser.add_argument('--relay-fronts', action='store_true',
                        help='Known AC-3 HDMI -> TV optical -> CM6206 -> decoded USB FL/FR, with DRIVERON held')
    parser.add_argument('--relay-six', action='store_true',
                        help='Same optical route, enabling all six decoded channels at equal low gain')
    parser.add_argument('--spoken-six', action='store_true',
                        help='Prepared local Portuguese speaker announcements, through the full optical route')
    parser.add_argument('--spoken-four', action='store_true',
                        help='Front and surround announcements only; FC/LFE muted, with CEN/BASS disconnected')
    parser.add_argument('--usb-rear-map', action='store_true',
                        help='Direct USB 7.1 pair comparison; only BL/BR and SL/SR, all other channels zero')
    parser.add_argument('--usb-all-map', action='store_true',
                        help='Direct USB 7.1 all-slot comparison, with reduced center and very quiet LFE')
    parser.add_argument('--volume', type=int, choices=range(1, 61), default=20,
                        help='Linear FL/FR gain percent in relay mode only (default 20); direct USB behavior is unchanged')
    args = parser.parse_args()
    if sum((args.relay_fronts, args.relay_six, args.spoken_six, args.spoken_four, args.usb_rear_map, args.usb_all_map)) > 1:
        parser.error('Choose one listening mode')
    if args.relay_fronts and args.volume > 25:
        parser.error('Front-only mode permits at most 25 percent linear gain')
    six_channel_mode = args.relay_six or args.spoken_six or args.spoken_four
    relay_mode = args.relay_fronts or six_channel_mode
    relay_seconds = 30 if args.spoken_six else (20 if args.spoken_four else 15)
    relay_timeout = 45 if args.spoken_six else (40 if args.spoken_four else 32)
    direct_mapping = args.usb_rear_map or args.usb_all_map
    if not args.name.isascii() or not args.name or any(not (c.isalnum() or c in '-_') for c in args.name):
        parser.error('Use an ASCII test name without path separators')
    # This bench session uses the explicitly verified analog render endpoint.
    if args.render_endpoint.lower() != 'wasapi/{87276929-efec-4166-b6b5-7fdde08a6a6e}':
        parser.error('Specify this session\'s verified CM6206 analog render endpoint')
    if relay_mode and len(args.name + '-relay') > 64:
        parser.error('Relay output name must fit the native helper\'s 64-character limit')
    source = ROOT / 'android-a34/artifacts/optical-2026-10-09/vectors' / (
        'tones-sequential-51.ac3' if relay_mode else 'pcm-tv-speakers-minus24dbfs-5s.wav')
    source_sha = AC3_SOURCE_SHA if relay_mode else SOURCE_SHA
    if args.spoken_six:
        source = ROOT / 'android-a34/artifacts/spoken-2026-10-09/spoken-channels-ready-02.ac3'
        source_sha = SPOKEN_SOURCE_SHA
    if args.spoken_four:
        source = ROOT / 'android-a34/artifacts/spoken-2026-10-09/spoken-four-satellites-03.ac3'
        source_sha = FOUR_SOURCE_SHA
    if args.usb_rear_map:
        source = ROOT / 'android-a34/artifacts/spoken-2026-10-09/spoken-rear-map-7point1-04.wav'
        source_sha = REAR_MAP_SHA
    if args.usb_all_map:
        source = ROOT / 'android-a34/artifacts/spoken-2026-10-09/spoken-all-map-7point1-05.wav'
        source_sha = ALL_MAP_SHA
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_sha:
        parser.error('Synthetic source identity differs')
    output = ROOT / 'android-a34/artifacts' / ('analog-driver-' + datetime.now(timezone.utc).strftime('%Y-%m-%d')) / args.name
    output.mkdir(parents=True, exist_ok=False)
    journal = output / 'session.json'
    report = {'kind': 'scoped_cm6206_analog_driver_front_test', 'startedAtUtc': datetime.now(timezone.utc).isoformat(),
              'mask': MASK, 'register': 2, 'sourceSha256': source_sha, 'frontAmplifierConnectedByUser': True,
              'relayFronts': args.relay_fronts, 'relaySix': six_channel_mode, 'spokenSix': args.spoken_six,
              'allAmplifiersConnectedByUser': (six_channel_mode and not args.spoken_four) or args.usb_all_map,
              'centerBassDisconnectedByUser': args.spoken_four,
              'directUsbRearMapping': args.usb_rear_map,
              'directUsbAllMapping': args.usb_all_map,
              'relayLinearVolumePercent': args.volume if relay_mode else None,
              'registerWriteSubmitted': False, 'restorationVerified': False,
              'defaultEndpointChanged': False, 'globalVolumeChanged': False,
              'driverOnTested': False, 'physicalAudioHeard': None, 'error': None}
    device = hid.device()
    opened = False
    before = None
    player = None
    relay = None
    relay_stop = None
    try:
        paths = [entry for entry in hid.enumerate(0x0d8c, 0x0102) if entry['interface_number'] == 3]
        if len(paths) != 1:
            raise RuntimeError('Require exactly one CM6206 HID interface')
        report['devicePath'] = paths[0]['path'].decode(errors='replace')
        device.open_path(paths[0]['path'])
        opened = True
        report['originalRegisters'] = [read_register(device, i) for i in range(6)]
        before = report['originalRegisters'][2]['value']
        if before & (0x7f8 if (six_channel_mode or direct_mapping) else 0x18):
            raise RuntimeError('Individual channel mute is active; this scoped test will not change it')
        target = before | MASK
        report['targetValue'] = target
        report['originalValue'] = before
        save(journal, report)
        if target != before:
            report['registerWriteSubmitted'] = True
            save(journal, report)  # Durable restore obligation before sending HID write.
            if device.write(bytes((0, 0x20, target & 255, target >> 8, 2))) != 5:
                raise RuntimeError('Incomplete DRIVERON write')
        after = read_register(device, 2)
        report['afterEnable'] = after
        if after['value'] != target:
            raise RuntimeError('Analog driver enable readback differs')
        report['driverOnTested'] = True
        save(journal, report)
        command = [str(ROOT / 'configuracao-pc/mpv-portatil/mpv.com'), '--no-config', '--load-scripts=no',
                   '--no-video', '--no-terminal', '--ao=wasapi', '--audio-exclusive=yes',
                   '--audio-fallback-to-null=no']
        if relay_mode:
            # Passthrough source only. Audible gain/channel restrictions are applied AFTER decoding by the native relay.
            command += ['--audio-device=' + HDMI_ENDPOINT, '--audio-channels=5.1', '--audio-spdif=ac3',
                        '--loop-file=1' if (args.spoken_six or args.spoken_four) else '--loop-file=2',
                        '--log-file=' + str(output / 'source-mpv.log'), str(source)]
            relay_name = args.name + '-relay'
            relay_directory = ROOT / 'android-a34/artifacts' / (
                'windows-optical-relay-' + datetime.now(timezone.utc).strftime('%Y-%m-%d'))
            relay_report = relay_directory / (relay_name + '-report.json')
            relay_stop = relay_directory / (relay_name + '.stop')
            relay_command = ['powershell.exe', '-STA', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                             '-File', str(ROOT / 'scripts/optical-tests/test-windows-spdif-relay.ps1'),
                             '-CaptureEndpointId', CAPTURE_ENDPOINT,
                             '-RenderEndpointId', '{0.0.0.00000000}.' + args.render_endpoint[len('wasapi/'):],
                             '-AudibleSatellites' if args.spoken_four else ('-AudibleSix' if six_channel_mode else '-AudibleFronts'),
                             '-Volume', str(args.volume), '-Seconds', str(relay_seconds), '-OutputName', relay_name]
            for suffix in ('.pcm', '-mpv.log', '.stop', '-report.json'):
                if (relay_directory / (relay_name + suffix)).exists():
                    raise RuntimeError('Native relay artifacts already exist; choose a unique --name')
            report.update({'relayCommand': relay_command, 'relayReportPath': str(relay_report),
                           'relayStopPath': str(relay_stop), 'relaySeconds': relay_seconds, 'relayExitCode': None,
                           'hdmiSourceEndpoint': HDMI_ENDPOINT, 'captureEndpoint': CAPTURE_ENDPOINT,
                           'physicalAudioHeard': None})
        else:
            command += ['--audio-device=' + args.render_endpoint,
                        '--audio-channels=7.1' if direct_mapping else '--audio-channels=stereo', '--audio-format=s16',
                        '--audio-samplerate=48000', '--audio-spdif=', '--volume=100', '--mute=no',
                        '--af=volume=0.50' if direct_mapping else '--af=volume=0.32',
                        '--loop-file=0' if direct_mapping else '--loop-file=2',
                        '--log-file=' + str(output / 'mpv.log'), str(source)]
        report['command'] = command
        save(journal, report)
        with (output / 'console.txt').open('xb') as console:
            player = subprocess.Popen(command, stdout=console, stderr=subprocess.STDOUT,
                                      creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            report['ownedPlayerPid'] = player.pid
            print(json.dumps({'playingFrontTest': True, 'relayFronts': args.relay_fronts,
                              'relaySix': six_channel_mode, 'spokenSix': args.spoken_six,
                              'driverOnReadback': hex(after['value'])}), flush=True)
            if relay_mode:
                time.sleep(0.5)
                if player.poll() is not None:
                    raise RuntimeError('HDMI source exited before the native relay started')
                with (output / 'relay-console.txt').open('xb') as relay_console:
                    relay = subprocess.Popen(relay_command, stdout=relay_console, stderr=subprocess.STDOUT,
                                             creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                    report['ownedRelayPid'] = relay.pid
                    save(journal, report)
                    relay.wait(timeout=relay_timeout)
                    report['relayExitCode'] = relay.returncode
                if relay.returncode != 0:
                    raise RuntimeError('Native optical-to-USB relay failed with exit code ' + str(relay.returncode))
                if not relay_report.is_file():
                    raise RuntimeError('Native relay did not save its verification report')
                native_report = json.loads(relay_report.read_text(encoding='utf-8-sig'))
                report['relayVerified'] = (native_report.get('Ok') is True
                    and native_report.get('FrontsOnlyOutput') is (not six_channel_mode)
                    and (not six_channel_mode or args.spoken_four or native_report.get('SixChannelsAudibleOutput') is True)
                    and (not args.spoken_four or (native_report.get('FourSatellitesAudibleOutput') is True
                         and native_report.get('CenterBassMutedOutput') is True))
                    and native_report.get('PlaybackMuted') is False
                    and abs(native_report.get('PlaybackLinearGain', -1) - args.volume / 100.0) < 1e-9
                    and native_report.get('CleanupComplete') is True)
                if not report['relayVerified']:
                    raise RuntimeError('Native relay report did not confirm successful transport and output')
                report['afterRelay'] = read_register(device, 2)
                if (report['afterRelay']['value'] & MASK) != MASK:
                    raise RuntimeError('DRIVERON was not held through the native relay')
                # DRIVERON remains held until the native child has released capture/render and the owned source finishes.
                player.wait(timeout=35)
            else:
                player.wait(timeout=40 if args.usb_all_map else 22)
            report['playerExitCode'] = player.returncode
            report['afterPlayback'] = read_register(device, 2)
            if player.returncode:
                raise RuntimeError('HDMI AC-3 source failed' if relay_mode else 'Direct PCM player failed')
    except Exception as error:
        report['error'] = str(error)
    finally:
        if relay is not None and relay.poll() is None:
            # Ask the native helper to perform its own bounded stream/process cleanup before restoring DRIVERON.
            try:
                if relay_stop is not None:
                    relay_stop.parent.mkdir(parents=True, exist_ok=True)
                    relay_stop.touch(exist_ok=True)
                relay.wait(timeout=8)
            except Exception as error:
                report['relayCleanupError'] = str(error)
                try:
                    relay.terminate()
                    try:
                        relay.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        relay.kill()
                        relay.wait(timeout=3)
                except Exception as cleanup_error:
                    report['relayTerminationError'] = str(cleanup_error)
            report['relayExitCode'] = relay.returncode
        if player is not None and player.poll() is None:
            try:
                player.terminate()
                try:
                    player.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    player.kill()
                    player.wait(timeout=3)
            except Exception as error:
                report['playerCleanupError'] = str(error)
                if report['error'] is None:
                    report['error'] = 'Owned source cleanup failed: ' + str(error)
        if player is not None:
            report['playerExitCode'] = player.returncode
        if opened and before is not None and report['registerWriteSubmitted']:
            try:
                current = read_register(device, 2)['value']
                restored = (current & ~MASK) | (before & MASK)
                if current != restored:
                    if device.write(bytes((0, 0x20, restored & 255, restored >> 8, 2))) != 5:
                        raise RuntimeError('Incomplete restore write')
                actual = read_register(device, 2)
                report['restoredRegister'] = actual
                report['restorationVerified'] = (actual['value'] & MASK) == (before & MASK)
            except Exception as error:
                report['restorationError'] = str(error)
        elif before is not None:
            report['restorationVerified'] = True
        if opened:
            try:
                device.close()
            except Exception as error:
                report['hidCloseError'] = str(error)
                if report['error'] is None:
                    report['error'] = 'HID close failed: ' + str(error)
        report['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
        save(journal, report)
    print(json.dumps({k: report.get(k) for k in ('driverOnTested', 'relayFronts', 'relayExitCode', 'relayVerified',
                                               'playerExitCode', 'restorationVerified', 'physicalAudioHeard', 'error')}))
    return 0 if report['error'] is None and report['restorationVerified'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

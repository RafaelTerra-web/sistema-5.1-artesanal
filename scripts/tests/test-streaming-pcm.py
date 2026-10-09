"""Bounded synthetic WAV pipe probe. Output is null or PCM files, never a DAC."""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import threading
import time


def wave_header():
    fmt = struct.pack('<HHIIHHHHI', 0xfffe, 6, 48000, 48000 * 24, 24, 32, 22, 32, 0x3f)
    fmt += bytes.fromhex('0300000000001000800000aa00389b71')
    return (b'RIFF' + struct.pack('<I', 0xffffffff) + b'WAVEfmt '
            + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', 0xffffffff))


def run_case(args, graph, name, output, filter_on=True, readahead=0, demux_thread=True):
    log = args.out / (name + '.mpv.log')
    commands = [str(args.mpv), '--no-config', '--no-video', '--no-terminal',
                '--audio-channels=7.1', '--audio-format=s16', '--audio-samplerate=48000',
                '--audio-buffer=0.040', '--cache=no', '--load-scripts=no',
                '--media-controls=no', '--input-media-keys=no', '--osc=no',
                '--demuxer=lavf', '--demuxer-lavf-format=wav', '--demuxer-lavf-probe-info=no',
                '--demuxer-lavf-o=ignore_length=1,max_size=11520', '--demuxer-lavf-buffersize=4096',
                '--stream-buffer-size=8192', '--demuxer-readahead-secs=' + str(readahead),
                '--demuxer-max-bytes=64KiB', '--demuxer-thread=' + ('yes' if demux_thread else 'no'),
                '--log-file=' + str(log), '--ao=' + output]
    if filter_on:
        commands.append('--af=lavfi=[' + graph + ']')
    if output == 'pcm':
        commands += ['--ao-pcm-waveheader=no', '--ao-pcm-file=' + str(args.out / (name + '.s16le'))]
    else:
        commands += ['--ao-null-buffer=0.010', '--ao-null-outburst=480']
    commands.append('-')
    creationflags = subprocess.CREATE_NO_WINDOW if hasattr(subprocess, 'CREATE_NO_WINDOW') else 0
    with (args.out / (name + '.console.log')).open('wb') as console:
        process = subprocess.Popen(commands, stdin=subprocess.PIPE, stdout=console, stderr=console,
                                   bufsize=0, creationflags=creationflags)
        started = time.perf_counter()
        packet = struct.pack('<6f', .05, -.04, .03, -.02, .01, -.01) * 480
        measurements = []
        failure = []
        state = {'write_started': None, 'written_packets': 0}

        def writer():
            try:
                process.stdin.write(wave_header())
                for index in range(round(args.seconds * 100)):
                    due = started + index / 100
                    if due > time.perf_counter():
                        time.sleep(due - time.perf_counter())
                    before = time.perf_counter()
                    state['write_started'] = before
                    # FileIO on Windows may write less than one packet.
                    view = memoryview(packet)
                    while view:
                        count = process.stdin.write(view)
                        if not count:
                            raise BrokenPipeError('No write progress')
                        view = view[count:]
                    after = time.perf_counter()
                    measurements.append((before - started, (after - before) * 1000))
                    state['written_packets'] += 1
                    state['write_started'] = None
            except (OSError, ValueError) as error:
                failure.append(type(error).__name__)
            finally:
                try:
                    process.stdin.close()
                except (OSError, ValueError):
                    pass

        thread = threading.Thread(target=writer, daemon=True)
        thread.start()
        stalled = False
        deadline = started + args.seconds + 4
        while thread.is_alive() and process.poll() is None and time.perf_counter() < deadline:
            before = state['write_started']
            if before is not None and time.perf_counter() - before > 2:
                stalled = True
                break
            time.sleep(.02)
        if thread.is_alive():
            process.kill()
        thread.join(timeout=2)
        try:
            process.wait(timeout=4)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)
        text = log.read_text(errors='replace') if log.exists() else ''
        ao = re.search(r'\[\s*([\d.]+)\].*AO: \[', text)
        report = {'name': name, 'ao': output, 'filter': filter_on,
                  'readahead_secs': readahead, 'demux_thread': demux_thread,
                  'exit_code': process.returncode, 'stalled_over_2s': stalled,
                  'written_packets': state['written_packets'], 'writer_error': failure,
                  'ao_ready_seconds': float(ao.group(1)) if ao else None,
                  'maximum_write_ms': max((duration for _, duration in measurements), default=0),
                  'maximum_write_after_1s_ms': max((duration for start, duration in measurements if start >= 1), default=0),
                  'writes_over_750ms': sum(duration > 750 for _, duration in measurements),
                  'elapsed_seconds': time.perf_counter() - started,
                  'physical_audio_opened': False}
        if output == 'pcm':
            path = args.out / (name + '.s16le')
            report['output_frames'] = path.stat().st_size // 16 if path.exists() else 0
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mpv', required=True, type=Path)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--seconds', type=float, default=4)
    args = parser.parse_args()
    if not .1 <= args.seconds <= 30:
        parser.error('--seconds must be between .1 and 30')
    args.out.mkdir(parents=True, exist_ok=True)
    graph = next(line[len('af=lavfi=['):-1] for line in args.config.read_text().splitlines()
                 if line.startswith('af=lavfi=['))
    cases = [('raw-null', 'null', False, 0, True),
             ('graph-null', 'null', True, 0, True),
             ('graph-pcm', 'pcm', True, 0, True),
             ('graph-null-ahead', 'null', True, .1, True),
             ('graph-null-single-thread', 'null', True, 0, False)]
    reports = []
    for name, output, enabled, ahead, demux_thread in cases:
        report = run_case(args, graph, name, output, enabled, ahead, demux_thread)
        reports.append(report)
        print(json.dumps(report), flush=True)
    (args.out / 'report.json').write_text(json.dumps(reports, indent=2))
    if any(report['stalled_over_2s'] or report['exit_code'] or report['writer_error']
           or report['written_packets'] != round(args.seconds * 100) for report in reports):
        raise SystemExit(1)
    pcm = next(report for report in reports if report['ao'] == 'pcm')
    if pcm['output_frames'] != round(args.seconds * 100) * 480:
        raise SystemExit('PCM output frame count differs from input')


if __name__ == '__main__':
    main()

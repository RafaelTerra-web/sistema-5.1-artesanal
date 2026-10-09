"""Owned CM6206 session: HID journal, bounded workers, readiness and clean stop.

Local configuration/state contain machine-specific IDs and remain ignored.
Never infer stereo from channel silence; the six-slot PCM mix is preserved.
"""
import argparse
import ctypes
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def read_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig'))


def save_json(path, data):
    path = Path(path)
    temporary = path.with_name(path.name + '.tmp')
    with temporary.open('w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, path)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class MpvIpcUnavailable(RuntimeError):
    pass


class MpvIpc:
    def __init__(self, pipe):
        self.pipe = pipe
        self.request_id = 0

    def request(self, command, timeout=0.8):
        # Peek before read: a blocked renderer must not block the supervisor.
        import msvcrt
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.PeekNamedPipe.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32,
                                        ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
        self.request_id += 1
        with open(self.pipe, 'r+b', buffering=0) as f:
            f.write((json.dumps({'command': command, 'request_id': self.request_id})+'\n').encode())
            handle = msvcrt.get_osfhandle(f.fileno())
            deadline = time.monotonic() + timeout
            pending = b''
            while time.monotonic() < deadline:
                available = ctypes.c_uint32()
                if not kernel.PeekNamedPipe(handle, None, 0, None, ctypes.byref(available), None):
                    raise OSError(ctypes.get_last_error(), 'mpv IPC pipe unavailable')
                if available.value:
                    pending += f.read(min(available.value, 65536))
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        response = json.loads(line)
                        if response.get('request_id') == self.request_id:
                            if response.get('error') != 'success':
                                raise MpvIpcUnavailable('mpv IPC: ' + str(response.get('error')))
                            return response.get('data')
                else:
                    time.sleep(0.01)
        raise TimeoutError('mpv IPC response timed out')


class Session:
    def __init__(self, launch):
        self.launch = launch
        self.config_path = Path(launch['ConfigPath'])
        self.cfg = read_json(self.config_path)
        self.run = Path(launch['RunDirectory'])
        self.state_path = Path(launch['StatePath'])
        self.stop = self.run/'system.stop'
        self.children = []
        self.routes = None
        self.hid = None
        self.original = None
        self.restore_needed = False
        self.journal = self.run/'hid-journal.json'
        self.state = dict(launch['InitialState'])
        self.state.update(RunnerId=os.getpid(), Ligado=False, Estado='Inicializando',
                          UltimoErro='', Gain=self.cfg['Gain'], Muted=bool(self.cfg.get('Muted', False)))
        self.ipc = MpvIpc(launch['IpcPath'])
        self.last_volume = None

    def publish(self, **fields):
        self.state.update(fields, AtualizadoEm=utc_now())
        save_json(self.state_path, self.state)

    def volume_tool(self, *args):
        subprocess.run([self.cfg['SoundVolumeViewPath'], *map(str, args)],
                       check=True, timeout=8, creationflags=NO_WINDOW)

    def route(self):
        snapshot = self.run/'routes-before.json'
        self.volume_tool('/sjson', snapshot)
        items = read_json(snapshot)
        def selected(column):
            values = [x['Item ID'] for x in items if x.get('Type') == 'Device' and x.get(column) == 'Render']
            if len(values) != 1:
                raise RuntimeError('Dispositivo padrão ambíguo: '+column)
            return values[0]
        console, multimedia = selected('Default'), selected('Default Multimedia')
        # A virtual route left behind by a failed session is not a useful fallback.
        fallback = self.cfg.get('FallbackEndpointId', self.cfg.get('HdmiEndpointId'))
        source = self.cfg['CaptureEndpointId']
        self.routes = {'console': fallback if console == source else console,
                       'multimedia': fallback if multimedia == source else multimedia}
        save_json(self.run/'restore-routes.json', self.routes)
        if self.state['Modo'] == 'Pcm' or self.cfg.get('EncodePcToHdmi', True):
            self.volume_tool('/SetDefault', source, 0)
            self.volume_tool('/SetDefault', source, 1)
            self.volume_tool('/SetAppDefault', source, 0, self.cfg.get('Application', 'opera.exe'))
            self.volume_tool('/SetAppDefault', source, 1, self.cfg.get('Application', 'opera.exe'))

    def analog_on(self):
        import hid
        spec = importlib.util.spec_from_file_location('cm6206_analog', ROOT/'scripts/optical-tests/test-analog-driver.py')
        self.helpers = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.helpers)
        last_error = None
        # Opening/closing WASAPI can temporarily invalidate the control interface.
        # Retry reads only; never submit a blind register write.
        for attempt in range(4):
            try:
                devices = [d for d in hid.enumerate(0x0d8c, 0x0102) if d.get('interface_number') == 3]
                if len(devices) != 1:
                    raise RuntimeError('É necessária uma única CM6206 HID conectada.')
                self.hid = hid.device()
                self.hid.open_path(devices[0]['path'])
                self.original = self.helpers.read_register(self.hid, 2)['value']
                break
            except Exception as error:
                last_error = error
                if self.hid:
                    self.hid.close()
                    self.hid = None
                time.sleep(0.5)
        if self.original is None:
            raise RuntimeError('CM6206 não respondeu ao controle USB: '+str(last_error))
        state = {'originalReg2': self.original, 'restoreMask': 0x8000,
                 'pid': os.getpid(), 'writeMayHaveCompleted': False, 'restorationVerified': False}
        self.helpers.save(self.journal, state)
        if not self.original & 0x8000:
            self.restore_needed = True
            state['writeMayHaveCompleted'] = True
            self.helpers.save(self.journal, state)
            target = self.original | 0x8000
            if self.hid.write(bytes((0, 0x20, target & 255, target >> 8, 2))) != 5:
                raise RuntimeError('Escrita DRIVERON incompleta; recuperação permanece no journal.')
        current = self.helpers.read_register(self.hid, 2)['value']
        if current != (self.original | 0x8000):
            raise RuntimeError('Leitura DRIVERON não confirmou o estado solicitado.')
        state['enabledReg2'] = current
        self.helpers.save(self.journal, state)

    def start_child(self, script, args, name):
        stdout = (self.run/(name+'-console.txt')).open('wb')
        stderr = (self.run/(name+'-errors.txt')).open('wb')
        try:
            child = subprocess.Popen(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                      '-File', str(ROOT/script), *map(str, args)],
                                     stdout=stdout, stderr=stderr, creationflags=NO_WINDOW)
        finally:
            stdout.close(); stderr.close()
        self.children.append((name, child))
        return child

    def start_workers(self):
        delays = self.cfg.get('DelaySamples', [3686,3686,278,278,3408,3408])
        if len(delays) != 6 or any(type(n) is not int or not 0 <= n <= 96000 for n in delays):
            raise RuntimeError('São necessários seis atrasos inteiros válidos em amostras.')
        delay_csv = ','.join(map(str, delays))
        self.state['DelaySamples'] = delays
        self.state['AtrasosMs'] = self.cfg.get('RequestedDelaysMs',
            dict(zip(('FL','FR','CEN','LFE','SL','SR'), (76.8,76.8,5.8,5.8,71.0,71.0))))
        common = ['-MpvPath', self.cfg['MpvPath'], '-RenderEndpointId', self.cfg['RenderEndpointId']]
        if self.state['Modo'] == 'Pcm':
            args = common + ['-SoundVolumeViewPath', self.cfg['SoundVolumeViewPath'],
                '-CaptureEndpointId', self.cfg['CaptureEndpointId'], '-OutputDirectory', self.run,
                '-InputMode', self.state['InputMode'], '-Gain', self.cfg['Gain'],
                '-CenterTrimDb', self.cfg.get('CenterTrimDb', -12), '-DelaySamplesCsv', delay_csv,
                '-IpcPath', self.launch['IpcPath'], '-SkipRouting']
            if self.cfg.get('Shared', False): args += ['-Shared']
            if self.cfg.get('SwapCenterLfe', False): args += ['-SwapCenterLfe']
            if self.cfg.get('Muted', False): args += ['-Muted']
            self.start_child('scripts/pc-cm6206-pcm.ps1', args, 'pcm')
        else:
            # Use the same native DSP on the decoded optical input. Upmix (when
            # explicitly requested for known stereo) happens only in the encoder.
            dsp_dir = self.run/'native-dsp'
            prepare = ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',
                str(ROOT/'scripts/pc-cm6206-pcm.ps1'), *common,
                '-SoundVolumeViewPath', self.cfg['SoundVolumeViewPath'],
                '-CaptureEndpointId', self.cfg['CaptureEndpointId'], '-OutputDirectory', str(dsp_dir),
                '-InputMode', 'Native', '-Gain', '1', '-DelaySamplesCsv', delay_csv,
                '-CenterTrimDb', str(self.cfg.get('CenterTrimDb', -12)), '-SkipRouting', '-ValidateOnly']
            if self.cfg.get('SwapCenterLfe', False): prepare += ['-SwapCenterLfe']
            subprocess.run(list(map(str, prepare)), check=True, timeout=15,
                           capture_output=True, creationflags=NO_WINDOW)
            args = common + ['-CaptureEndpointId', self.cfg['SpdifCaptureEndpointId'], '-Gain', self.cfg['Gain'],
                '-StopPath', self.run/'optical.stop', '-LogPath', self.run/'optical.log',
                '-StatusPath', self.run/'optical-status.json', '-DelaySamplesCsv', delay_csv,
                '-ConfigPath', dsp_dir/'pcm.conf', '-IpcPath', self.launch['IpcPath']]
            if self.cfg.get('Shared', False): args += ['-Shared']
            if self.cfg.get('Muted', False): args += ['-Muted']
            if self.cfg.get('KeepOpticalDeviceAlive', False): args += ['-KeepDeviceAlive']
            self.start_child('scripts/pc-cm6206-optical.ps1', args, 'optical')
            if self.cfg.get('EncodePcToHdmi', True):
                self.start_child('scripts/pc-cm6206-encoder.ps1', [
                    '-MpvPath', self.cfg['MpvPath'], '-CaptureEndpointId', self.cfg['CaptureEndpointId'],
                    '-RenderEndpointId', self.cfg['HdmiEndpointId'], '-StopPath', self.run/'encoder.stop',
                    '-LogPath', self.run/'encoder.log', '-StatusPath', self.run/'encoder-status.json',
                    '-InputMode', self.state['InputMode'], '-Bitrate', self.cfg.get('Bitrate', 640)], 'encoder')

    def live_progress(self):
        if self.state['Modo'] == 'Optical':
            s = read_json(self.run/'optical-status.json')
            if s.get('Status') == 'failed':
                raise RuntimeError(s.get('Error') or 'Receptor óptico falhou.')
            if self.cfg.get('EncodePcToHdmi', True):
                encoder = read_json(self.run/'encoder-status.json')
                if encoder.get('Status') == 'failed':
                    raise RuntimeError(encoder.get('Error') or 'Encoder HDMI falhou.')
                if not encoder.get('Ready'):
                    return None
            self.state.update(PlayerId=s.get('OwnedMpvPid', 0), SourceChannels=s.get('SourceChannels'),
                              CarrierBytesSent=s.get('CarrierBytesSent', 0),
                              CrcRejectedFrames=s.get('CrcRejectedFrames', 0), DecoderLogClean=s.get('DecoderLogClean', True))
            if not s.get('Ready'):
                return None
        else:
            log = (self.run/'pcm.log').read_text(encoding='utf-8-sig', errors='replace')
            ao = (self.run/'pcm.log.mpv.log').read_text(encoding='utf-8-sig', errors='replace')
            if 'ERROR ' in log:
                raise RuntimeError('Relay PCM falhou; consulte o log da sessão.')
            pid = re.search(r'mpvPid=(\d+)', log)
            if pid: self.state['PlayerId'] = int(pid.group(1))
            counts = re.findall(r'sentFrames=(\d+)', log)
            drops = re.findall(r'droppedFrames=(\d+)', log)
            self.state.update(SentFrames=int(counts[-1]) if counts else 0,
                              DroppedFrames=int(drops[-1]) if drops else 0, SourceChannels=6)
            if 'AO: [wasapi]' not in ao or '8ch' not in ao or 'Thread Unpause' not in ao or not counts:
                return None
        position = self.ipc.request(['get_property', 'time-pos'])
        if position is None: return None
        position = float(position)
        return position if math.isfinite(position) and position >= 0 else None

    def volume_update(self):
        cfg = read_json(self.config_path)
        gain = float(cfg['Gain'])
        muted = bool(cfg.get('Muted', False))
        if not math.isfinite(gain) or not 0 <= gain <= 1:
            raise RuntimeError('Ganho DSP inválido na configuração local.')
        requested = (gain, muted)
        if requested != self.last_volume:
            self.ipc.request(['set_property', 'volume', 100*gain**(1/3)])
            self.ipc.request(['set_property', 'mute', muted])
            self.last_volume = requested
        self.state.update(Gain=gain, Muted=muted)

    def monitor(self):
        deadline = time.monotonic()+45
        previous = None
        advances = 0
        first_position = None
        ever_ready = False
        last_progress = time.monotonic()
        while not self.stop.exists():
            for name, child in self.children:
                if child.poll() is not None:
                    status_name = name+'-status.json'
                    try:
                        child_status = read_json(self.run/status_name)
                        if child_status.get('Error'): raise RuntimeError(child_status['Error'])
                    except (FileNotFoundError, ValueError): pass
                    raise RuntimeError(name+' encerrou com código '+str(child.returncode))
            try:
                current = self.live_progress()
            except (FileNotFoundError, OSError, ValueError, TimeoutError, MpvIpcUnavailable):
                current = None
            if current is not None and previous is not None and current > previous+0.1:
                advances += 1
                last_progress = time.monotonic()
            if current is not None:
                if first_position is None: first_position = current
                if previous is not None and current < previous:
                    first_position = current; advances = 0
                previous = current
            ready = (current is not None and advances >= 2 and
                     current-first_position >= 1.0 and time.monotonic()-last_progress <= 3)
            if ready:
                ever_ready = True
                self.volume_update()
                self.publish(Estado='Em execução', Ligado=True, UltimoErro='', PlaybackSeconds=current)
            else:
                self.publish(Estado='Aguardando áudio USB', Ligado=False)
                if not ever_ready and time.monotonic()>deadline:
                    raise TimeoutError('Saída não iniciou em 45 s; não foi declarada ligada.')
                if ever_ready and self.state['Modo'] == 'Pcm' and time.monotonic()-last_progress > 6:
                    raise RuntimeError('Saída USB parou de avançar; sessão não está reproduzindo.')
            time.sleep(1)

    def cleanup(self):
        errors = []
        workers_closed = True
        for filename in ('pcm.stop', 'encoder.stop', 'optical.stop'):
            (self.run/filename).write_text('stop', encoding='ascii')
        for name, child in self.children:
            try:
                try: child.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    # Ask the owned player to exit before terminating its host.
                    try: self.ipc.request(['quit'])
                    except Exception: pass
                    child.terminate()
                    try: child.wait(timeout=4)
                    except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=4)
            except Exception as error:
                errors.append(name+': '+str(error))
                workers_closed = False
        # A shell terminated before its finally block can leave its mpv child.
        # Check only PIDs journaled by this session and verify log-path identity.
        owned = {int(self.state.get('PlayerId') or 0)}
        for filename in ('optical-status.json', 'encoder-status.json'):
            try: owned.add(int(read_json(self.run/filename).get('OwnedMpvPid') or 0))
            except (OSError, ValueError): pass
        try:
            for match in re.findall(r'mpvPid=(\d+)', (self.run/'pcm.log').read_text(errors='replace')):
                owned.add(int(match))
        except OSError: pass
        owned.discard(0)
        if owned:
            escaped = str(self.run).replace("'", "''")
            ids = ','.join(map(str, sorted(owned)))
            check = ("$ids=@("+ids+"); foreach($id51 in $ids){"
                "$p51=Get-CimInstance Win32_Process -Filter ('ProcessId='+$id51);"
                "if($p51 -and $p51.Name -eq 'mpv.exe' -and $p51.CommandLine.Contains('"+escaped+"')){"
                "Stop-Process -Id $id51 -Force -ErrorAction Stop;"
                "$proc51=Get-Process -Id $id51 -ErrorAction SilentlyContinue;"
                "if($proc51 -and -not $proc51.WaitForExit(3000)){throw 'Renderer próprio não encerrou.'}}}")
            try:
                subprocess.run(['powershell.exe','-NoProfile','-Command',check],
                    capture_output=True, check=True, timeout=10, creationflags=NO_WINDOW)
            except Exception as error:
                errors.append('Encerramento do renderer: '+str(error)); workers_closed = False
        if self.hid:
            try:
                if not workers_closed:
                    raise RuntimeError('Áudio não liberado; restauração HID pendente no journal.')
                journal = read_json(self.journal)
                if self.restore_needed:
                    current = self.helpers.read_register(self.hid, 2)['value']
                    target = (current & ~0x8000) | (self.original & 0x8000)
                    if self.hid.write(bytes((0, 0x20, target & 255, target >> 8, 2))) != 5:
                        raise RuntimeError('Restauração DRIVERON incompleta.')
                verified = self.helpers.read_register(self.hid, 2)['value']
                if (verified & 0x8000) != (self.original & 0x8000):
                    raise RuntimeError('Restauração DRIVERON não confirmada.')
                journal['restoredReg2'] = verified
                journal['restorationVerified'] = True
                self.helpers.save(self.journal, journal)
            except Exception as error:
                errors.append(str(error))
            finally:
                try: self.hid.close()
                except Exception as error: errors.append('HID close: '+str(error))
        if self.routes:
            try:
                snapshot = self.run/'routes-cleanup.json'
                self.volume_tool('/sjson', snapshot)
                current = read_json(snapshot)
                source = self.cfg['CaptureEndpointId']
                for role, key, column in ((0, 'console', 'Default'), (1, 'multimedia', 'Default Multimedia')):
                    owned = any(x.get('Type') == 'Device' and x.get(column) == 'Render' and x.get('Item ID') == source for x in current)
                    if owned and self.routes[key]: self.volume_tool('/SetDefault', self.routes[key], role)
                    self.volume_tool('/SetAppDefault', 'DefaultRenderDevice', role, self.cfg.get('Application', 'opera.exe'))
            except Exception as error: errors.append('Rota: '+str(error))
        if errors:
            raise RuntimeError('; '.join(errors))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--launch', type=Path, required=True)
    args = parser.parse_args()
    launch = read_json(args.launch)
    owner = Path(launch['RunDirectory'])/'owner.json'
    deadline = time.monotonic()+5
    while not owner.exists() and time.monotonic()<deadline: time.sleep(0.05)
    launch['InitialState'].update(read_json(owner))
    session = Session(launch)
    error = ''
    try:
        session.publish()
        session.analog_on()
        session.route()
        session.start_workers()
        session.monitor()
    except Exception as failure:
        error = str(failure)
        session.publish(Estado='Falha', Ligado=False, UltimoErro=error)
    finally:
        cleanup_error = ''
        try: session.cleanup()
        except Exception as failure:
            cleanup_error = str(failure)
            error = (error+'; '+cleanup_error).strip('; ')
        session.publish(Estado='Falha' if error else 'Desligado', Ligado=False, Solicitado=False,
                        UltimoErro=error, CleanupComplete=not cleanup_error, PlayerId=0)
    return 1 if error else 0


if __name__ == '__main__':
    raise SystemExit(main())

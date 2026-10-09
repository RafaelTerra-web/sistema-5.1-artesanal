"""CM6206 supervisor tests using only temporary files and mocked IPC/HID/processes."""
import importlib.util
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

ENGINE_PATH = Path(__file__).resolve().parents[1] / 'pc-cm6206-engine.py'
SPEC = importlib.util.spec_from_file_location('cm6206_engine_tests_target', ENGINE_PATH)
engine = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(engine)


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.run = Path(self.temporary.name)
        self.cfg_path = self.run / 'config.json'
        self.cfg = {'Gain': .1, 'Muted': False, 'CaptureEndpointId': 'virtual',
                    'RenderEndpointId': 'usb', 'HdmiEndpointId': 'hdmi',
                    'FallbackEndpointId': 'fallback', 'MpvPath': 'mock-mpv',
                    'SoundVolumeViewPath': 'mock-volume', 'SpdifCaptureEndpointId': 'optical',
                    'Application': 'opera.exe', 'EncodePcToHdmi': False, 'UseNativeHidGuard': False}
        engine.save_json(self.cfg_path, self.cfg)
        self.launch = {'ConfigPath': str(self.cfg_path), 'RunDirectory': str(self.run),
                       'StatePath': str(self.run / 'state.json'), 'IpcPath': 'mock-pipe',
                       'InitialState': {'Modo': 'Pcm', 'InputMode': 'Auto', 'Solicitado': True}}
        self.session = engine.Session(self.launch)
        self.session.ipc = Mock()
        # A missed mock must fail instead of opening the real local peripheral.
        hid_guard=patch.dict(sys.modules,{'hid':None})
        hid_guard.start();self.addCleanup(hid_guard.stop)

    def configure(self, **changes):
        self.cfg.update(changes)
        engine.save_json(self.cfg_path, self.cfg)

    def pcm_logs(self, resumed=True, error=False):
        (self.run / 'pcm.log').write_text('mpvPid=123\nsentFrames=480 droppedFrames=0\n'
                                         + ('ERROR worker failed\n' if error else ''), encoding='utf-8')
        (self.run / 'pcm.log.mpv.log').write_text('AO: [wasapi] 48000Hz 7.1 8ch s16\n'
                                                + ('Thread Unpause\n' if resumed else 'Thread Resume\nThread Reset\nThread Pause\n'),
                                                encoding='utf-8')

    def helpers(self, original=0x6004, read_values=(0xe014, 0x6014)):
        self.session.original = original
        self.session.restore_needed = not bool(original & 0x8000)
        self.session.hid = Mock()
        self.session.hid.write.return_value = 5
        helper = types.SimpleNamespace(read_register=Mock(side_effect=[{'value': x} for x in read_values]),
                                       save=engine.save_json)
        self.session.helpers = helper
        engine.save_json(self.session.journal, {'originalReg2': original, 'restorationVerified': False})
        engine.save_json(self.run/'routes-cleanup.json', [
            {'Type':'Device','Default':'Render','Default Multimedia':'Render','Item ID':self.cfg['CaptureEndpointId']},
            {'Type':'Application','Direction':'Render','Device State':'Active','Process Path':'C:/Opera/opera.exe',
             'Item ID':self.cfg['CaptureEndpointId']+'|opera-session'}])
        return helper

    def monitor_values(self, values):
        clock = [0.0]
        pending = list(values)
        states = []

        def progress():
            value = pending.pop(0)
            if not pending:
                self.session.stop.write_text('stop')
            return value

        self.session.live_progress = Mock(side_effect=progress)
        self.session.volume_update = Mock()
        self.session.publish = Mock(side_effect=lambda **fields: states.append(fields))
        with patch.object(engine.time, 'monotonic', side_effect=lambda: clock[0]), \
             patch.object(engine.time, 'sleep', side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)):
            self.session.monitor()
        return states

    def test_json_bom_unicode_roundtrip_and_atomic_replace(self):
        path = self.run / 'utf8.json'
        path.write_text('\ufeff{"rota":"óptica"}', encoding='utf-8')
        self.assertEqual(engine.read_json(path), {'rota': 'óptica'})
        engine.save_json(path, {'rota': 'central'})
        self.assertEqual(engine.read_json(path), {'rota': 'central'})
        self.assertFalse(path.with_name(path.name + '.tmp').exists())
        with patch.object(engine.os, 'replace', side_effect=OSError('simulated replace failure')):
            with self.assertRaises(OSError):engine.save_json(path, {'rota': 'failed'})
        self.assertEqual(engine.read_json(path), {'rota': 'central'})

    def test_json_replace_retries_transient_windows_sharing(self):
        path=self.run/'shared-state.json';engine.save_json(path,{'generation':1})
        replace=engine.os.replace;attempts=[0]
        def transient(source,destination):
            attempts[0]+=1
            if attempts[0]<3:raise PermissionError('temporary reader denied delete sharing')
            return replace(source,destination)
        with patch.object(engine.os,'replace',side_effect=transient),patch.object(engine.time,'sleep'):
            engine.save_json(path,{'generation':2})
        self.assertEqual(engine.read_json(path),{'generation':2})
        self.assertEqual(list(self.run.glob('shared-state.json.*.tmp')),[])

    def test_status_write_failure_never_stops_audio(self):
        with patch.object(engine,'save_json',side_effect=PermissionError('dashboard lock')):
            self.session.publish(Ligado=True,Estado='Em execução')
        self.assertTrue(self.session.state['Ligado'])
        self.assertIn('dashboard lock',(self.run/'status-write-errors.log').read_text())

    def test_publish_persists_utc_and_state(self):
        self.session.publish(Estado='Teste', Ligado=False)
        state = engine.read_json(self.session.state_path)
        self.assertEqual(state['Estado'], 'Teste')
        self.assertTrue(state['AtualizadoEm'].endswith('+00:00'))
        self.assertFalse(state['Ligado'])

    def test_volume_uses_cube_root_for_mpv_percent(self):
        for gain in (0, .001, .125, .5, 1):
            with self.subTest(gain=gain):
                self.configure(Gain=gain)
                self.session.last_volume = None
                self.session.ipc.reset_mock()
                self.session.volume_update()
                command = self.session.ipc.request.call_args_list[0].args[0]
                self.assertEqual(command[:2], ['set_property', 'volume'])
                self.assertAlmostEqual((command[2] / 100) ** 3, gain)
                self.assertEqual(self.session.ipc.request.call_args_list[1].args[0], ['set_property', 'mute', False])
                self.assertEqual(self.session.state['Gain'], gain)

    def test_volume_unchanged_does_not_resubmit_controls(self):
        self.session.volume_update()
        self.session.volume_update()
        self.assertEqual(self.session.ipc.request.call_count, 2)

    def test_volume_invalid_never_contacts_ipc(self):
        for gain in (-.1, 1.1, math.nan, math.inf):
            with self.subTest(gain=gain):
                self.configure(Gain=gain)
                self.session.ipc.reset_mock()
                with self.assertRaises(RuntimeError):
                    self.session.volume_update()
                self.session.ipc.request.assert_not_called()

    def test_volume_failed_mute_does_not_acknowledge_configuration(self):
        self.configure(Gain=.125, Muted=True)
        self.session.ipc.request.side_effect = [None, TimeoutError('mock failure')]
        with self.assertRaises(TimeoutError):
            self.session.volume_update()
        self.assertIsNone(self.session.last_volume)
        self.assertEqual(self.session.state['Gain'], .1)

    def test_pcm_requires_unpause_not_just_ao_description(self):
        self.pcm_logs(resumed=False)
        self.assertIsNone(self.session.live_progress())
        self.session.ipc.request.assert_not_called()
        self.pcm_logs(resumed=True)
        self.session.ipc.request.return_value = 2.5
        self.assertEqual(self.session.live_progress(), 2.5)
        self.assertEqual(self.session.state['SentFrames'], 480)
        self.assertEqual(self.session.state['PlayerId'], 123)

    def test_pcm_worker_error_never_contacts_ipc(self):
        self.pcm_logs(error=True)
        with self.assertRaises(RuntimeError):
            self.session.live_progress()
        self.session.ipc.request.assert_not_called()

    def test_optical_requires_carrier_ready_before_ipc(self):
        self.session.state['Modo'] = 'Optical'
        path = self.run / 'optical-status.json'
        engine.save_json(path, {'Ready': False, 'SourceChannels': 6, 'OwnedMpvPid': 123})
        self.assertIsNone(self.session.live_progress())
        self.session.ipc.request.assert_not_called()
        engine.save_json(path, {'Ready': True, 'SourceChannels': 6, 'OwnedMpvPid': 123})
        self.session.ipc.request.return_value = 4
        self.assertEqual(self.session.live_progress(), 4)

    def test_worker_unknown_pcm_count_does_not_select_stereo(self):
        self.session.start_child = Mock()
        self.session.start_workers()
        args = self.session.start_child.call_args.args[1]
        self.assertEqual(args[args.index('-InputMode') + 1], 'Auto')
        self.assertNotIn('Stereo', args)

    def test_route_virtual_stale_defaults_uses_fallback_snapshot(self):
        rows = [{'Type': 'Device', 'Item ID': 'virtual', 'Default': 'Render', 'Default Multimedia': 'Render'}]

        def sound(command, *args):
            if command == '/sjson':
                engine.save_json(args[0], rows)

        self.session.volume_tool = Mock(side_effect=sound)
        self.session.route()
        self.assertEqual(self.session.routes['console'], 'fallback')
        self.assertEqual(self.session.routes['multimedia'], 'fallback')
        self.assertEqual(self.session.routes['applications'][0]['process'], 'opera.exe')
        self.assertFalse(self.session.routes['applications'][0]['preferenceKnown'])
        self.assertEqual(engine.read_json(self.run / 'restore-routes.json'), self.session.routes)

    def test_route_ambiguous_defaults_changes_nothing(self):
        self.session.volume_tool = Mock(side_effect=lambda command, path: engine.save_json(path, []))
        with self.assertRaises(RuntimeError):
            self.session.route()
        self.assertEqual(self.session.volume_tool.call_count, 1)
        self.assertIsNone(self.session.routes)

    def test_monitor_requires_two_full_seconds_of_audio_progress(self):
        states = self.monitor_values([0, .11, .22])
        self.assertFalse(any(state.get('Ligado') for state in states),
                         'Two tiny position increments are not two seconds of rendering')

    def test_monitor_never_reports_currently_missing_progress_as_playing(self):
        states = self.monitor_values([0, 1, 2, None])
        self.assertFalse(states[-1].get('Ligado', False), 'Missing IPC progress was reported as playing')

    def test_monitor_normal_two_seconds_becomes_ready(self):
        states = self.monitor_values([0, 1, 2])
        self.assertFalse(states[0]['Ligado'])
        self.assertFalse(states[1]['Ligado'])
        self.assertTrue(states[2]['Ligado'])

    def test_cleanup_restores_only_owned_bit_preserving_other_changes(self):
        helper = self.helpers()
        self.session.cleanup()
        self.session.hid.write.assert_called_once_with(bytes((0, 0x20, 0x14, 0x60, 2)))
        self.assertEqual(helper.read_register.call_count, 2)
        journal = engine.read_json(self.session.journal)
        self.assertTrue(journal['restorationVerified'])
        self.assertEqual(journal['restoredReg2'], 0x6014)

    def analog_mocks(self, values):
        device = Mock()
        device.write.return_value = 5
        hid = types.SimpleNamespace(enumerate=Mock(return_value=[{'interface_number': 3, 'path': b'mock'}]),
                                    device=Mock(return_value=device))
        helper = types.SimpleNamespace(read_register=Mock(side_effect=values), save=engine.save_json)
        spec = Mock()
        return device, hid, helper, spec

    def test_analog_enable_journals_before_the_single_owned_bit_write(self):
        device, hid, helper, spec = self.analog_mocks([{'value': 0x6004}, {'value': 0xe004}])

        def write(report):
            journal = engine.read_json(self.session.journal)
            self.assertEqual(journal['originalReg2'], 0x6004)
            self.assertTrue(journal['writeMayHaveCompleted'])
            self.assertFalse(journal['restorationVerified'])
            self.assertEqual(report, bytes((0, 0x20, 4, 0xe0, 2)))
            return 5

        device.write.side_effect = write
        with patch.dict(sys.modules, {'hid': hid}), \
             patch.object(engine.importlib.util, 'spec_from_file_location', return_value=spec), \
             patch.object(engine.importlib.util, 'module_from_spec', return_value=helper):
            self.session.analog_on()
        device.write.assert_called_once()
        self.assertTrue(self.session.restore_needed)
        self.assertEqual(engine.read_json(self.session.journal)['enabledReg2'], 0xe004)

    def test_native_guard_requires_its_own_held_status(self):
        self.configure(UseNativeHidGuard=True)
        self.session.cfg['UseNativeHidGuard']=True
        child=Mock(pid=321);child.poll.return_value=None
        engine.save_json(self.run/'analog-status.json',{'Pid':321,'Status':'held','Ready':True,'Error':''})
        with patch.object(engine.subprocess,'Popen',return_value=child):
            self.session.analog_on()
        self.assertIs(self.session.native_guard,child)
        self.assertIsNone(self.session.hid)

    def test_native_guard_rejects_another_process_status(self):
        self.configure(UseNativeHidGuard=True)
        self.session.cfg['UseNativeHidGuard']=True
        child=Mock(pid=321);child.poll.return_value=0
        engine.save_json(self.run/'analog-status.json',{'Pid':999,'Status':'held','Ready':True,'Error':''})
        with patch.object(engine.subprocess,'Popen',return_value=child):
            with self.assertRaises(RuntimeError):self.session.analog_on()

    def test_native_guard_cleanup_requires_restoration_ack(self):
        self.session.native_guard=Mock(pid=321)
        def released(timeout):
            self.assertTrue((self.run/'analog.stop').exists())
            engine.save_json(self.run/'analog-status.json',{'Pid':321,'Status':'stopped','Ready':False,'RestorationVerified':True})
        self.session.native_guard.wait.side_effect=released
        self.session.cleanup()
        self.session.native_guard.wait.assert_called_once_with(timeout=8)

    def test_native_guard_cleanup_failure_is_not_success(self):
        self.session.native_guard=Mock(pid=321)
        engine.save_json(self.run/'analog-status.json',{'Pid':321,'Status':'failed','Ready':False,'RestorationVerified':False,'Error':'readback failed'})
        with self.assertRaisesRegex(RuntimeError,'readback failed'):self.session.cleanup()

    def test_analog_read_failures_never_submit_blind_register_write(self):
        device, hid, helper, spec = self.analog_mocks([TimeoutError('mock read failure')] * 4)
        with patch.dict(sys.modules, {'hid': hid}), \
             patch.object(engine.importlib.util, 'spec_from_file_location', return_value=spec), \
             patch.object(engine.importlib.util, 'module_from_spec', return_value=helper), \
             patch.object(engine.time, 'sleep'):
            with self.assertRaises(RuntimeError):
                self.session.analog_on()
        device.write.assert_not_called()
        self.assertEqual(device.close.call_count, 4)
        self.assertFalse(self.session.journal.exists())

    def test_analog_partial_write_retains_journal_for_cleanup_recovery(self):
        device, hid, helper, spec = self.analog_mocks([{'value': 0x6004}])
        device.write.return_value = 4
        with patch.dict(sys.modules, {'hid': hid}), \
             patch.object(engine.importlib.util, 'spec_from_file_location', return_value=spec), \
             patch.object(engine.importlib.util, 'module_from_spec', return_value=helper):
            with self.assertRaises(RuntimeError):
                self.session.analog_on()
        self.assertTrue(self.session.restore_needed)
        journal = engine.read_json(self.session.journal)
        self.assertTrue(journal['writeMayHaveCompleted'])
        self.assertFalse(journal['restorationVerified'])
        device.write.assert_called_once()

    def test_cleanup_readback_failure_never_marks_restored_but_still_restores_routes(self):
        self.helpers(read_values=(0xe014, 0xe014))
        self.session.routes = {'console': 'before-console', 'multimedia': 'before-media'}
        self.session.volume_tool = Mock()
        with self.assertRaises(RuntimeError):
            self.session.cleanup()
        self.assertFalse(engine.read_json(self.session.journal)['restorationVerified'])
        self.session.hid.close.assert_called_once()
        self.assertEqual(self.session.volume_tool.call_count, 3)

    def test_cleanup_original_driveron_still_requires_verification(self):
        helper = self.helpers(original=0xe004, read_values=(0x6004,))
        with self.assertRaises(RuntimeError):
            self.session.cleanup()
        self.assertGreater(helper.read_register.call_count, 0)
        self.assertFalse(engine.read_json(self.session.journal)['restorationVerified'])

    def test_cleanup_child_wait_failure_still_attempts_hid_and_routes(self):
        helper = self.helpers()
        child = Mock()
        child.wait.side_effect = OSError('mock child unavailable')
        self.session.children = [('pcm', child)]
        self.session.routes = {'console': 'before-console', 'multimedia': 'before-media'}
        self.session.volume_tool = Mock()
        with self.assertRaises((OSError, RuntimeError)):
            self.session.cleanup()
        self.assertEqual(helper.read_register.call_count, 0, 'HID must not be restored before a worker is known to have released audio')
        self.session.hid.close.assert_called_once()
        self.assertFalse(engine.read_json(self.session.journal)['restorationVerified'])
        self.assertEqual(self.session.volume_tool.call_count, 3, 'Child failure prevented route cleanup')

    def test_cleanup_hid_close_failure_still_attempts_routes(self):
        self.helpers()
        self.session.hid.close.side_effect = OSError('mock close failure')
        self.session.routes = {'console': 'before-console', 'multimedia': 'before-media'}
        self.session.volume_tool = Mock()
        with self.assertRaises((OSError, RuntimeError)):
            self.session.cleanup()
        self.assertEqual(self.session.volume_tool.call_count, 3, 'HID close failure prevented route cleanup')

    def test_main_cleanup_failure_sets_error_and_cleanup_incomplete(self):
        launch_path = self.run / 'launch.json'
        engine.save_json(launch_path, self.launch)
        engine.save_json(self.run / 'owner.json', {'RunnerId': 123, 'RunnerStartedUtc': 'mock-owner'})
        session = Mock()
        session.cleanup.side_effect = RuntimeError('mock restoration failed')
        with patch.object(engine, 'Session', return_value=session), \
             patch.object(sys, 'argv', ['engine', '--launch', str(launch_path)]):
            self.assertEqual(engine.main(), 1)
        state = session.publish.call_args.kwargs
        self.assertFalse(state['CleanupComplete'])
        self.assertFalse(state['Ligado'])
        self.assertEqual(state['Estado'], 'Falha')
        self.assertIn('mock restoration failed', state['UltimoErro'])


if __name__ == '__main__':
    unittest.main(verbosity=2)

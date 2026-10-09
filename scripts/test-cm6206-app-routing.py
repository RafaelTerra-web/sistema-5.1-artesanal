"""Pure routing tests: mocks replace every command; no audio, process or HID."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cm6206_engine', Path(__file__).with_name('pc-cm6206-engine.py'))
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
SOURCE = '{0.0.0.00000000}.{11111111-1111-1111-1111-111111111111}'
OUTPUT = '{0.0.0.00000000}.{22222222-2222-2222-2222-222222222222}'
OTHER = '{0.0.0.00000000}.{33333333-3333-3333-3333-333333333333}'


def device(endpoint, console=False, multimedia=False):
    return {'Type': 'Device', 'Direction': 'Render', 'Item ID': endpoint,
            'Default': 'Render' if console else '', 'Default Multimedia': 'Render' if multimedia else ''}


def app(process, endpoint, active=True):
    return {'Type': 'Application', 'Direction': 'Render', 'Device State': 'Active' if active else 'Inactive',
            'Process Path': 'C:\\Apps\\'+process, 'Item ID': endpoint+'|session'}


class AppRoutingTest(unittest.TestCase):
    def session(self, folder, cfg, before, cleanup):
        session = engine.Session.__new__(engine.Session)
        session.cfg = {'CaptureEndpointId': SOURCE, 'FallbackEndpointId': OUTPUT, **cfg}
        session.run = Path(folder)
        session.state = {'Modo': 'Pcm', 'PlayerId': 0}
        session.children = []
        session.hid = None
        session.native_guard = None
        session.routes = None
        calls = []

        def volume_tool(*args):
            calls.append(args)
            if args[0] == '/sjson':
                engine.save_json(args[1], before if Path(args[1]).name == 'routes-before.json' else cleanup)
        session.volume_tool = volume_tool
        return session, calls

    def test_app_list_backcompat_discovery_and_feedback_rejection(self):
        self.assertEqual(engine.routing_applications({}, [app('msedge.exe', OUTPUT), app('chrome.exe', OUTPUT, False), app('mpv.exe', OUTPUT)]), ['opera.exe', 'msedge.exe'])
        self.assertEqual(engine.routing_applications({'Applications': ['VLC.exe', 'vlc.exe'], 'DiscoverActiveApplications': False}, [app('opera.exe', OUTPUT)]), ['vlc.exe'])
        for value in ('mpv.exe', 'FxSound.exe', 'python.exe', 'C:\\Apps\\opera.exe', 'opera.exe & whoami'):
            with self.assertRaises(ValueError):
                engine.routing_applications({'Applications': [value]}, [])
        with self.assertRaises(ValueError):
            engine.routing_applications({'Applications': 'opera.exe'}, [])

    def test_observed_endpoint_prefers_current_session_over_stale_session(self):
        self.assertEqual(engine.observed_app_endpoints([app('opera.exe', SOURCE), app('opera.exe', OUTPUT, False)], 'opera.exe'), {SOURCE.lower()})
        self.assertEqual(engine.observed_app_endpoints([app('opera.exe', SOURCE), app('opera.exe', OUTPUT)], 'opera.exe'), {SOURCE.lower(), OUTPUT.lower()})
        self.assertEqual(engine.observed_app_endpoints([], 'opera.exe'), set())

    def test_route_journals_all_apps_without_claiming_exact_preference_backup(self):
        before = [device(OUTPUT, True, True), app('opera.exe', OUTPUT), app('msedge.exe', OTHER)]
        with tempfile.TemporaryDirectory() as folder:
            session, calls = self.session(folder, {'Applications': ['opera.exe', 'msedge.exe']}, before, [])
            session.route()
            app_commands = [x for x in calls if x[0] == '/SetAppDefault']
            self.assertEqual(app_commands, [('/SetAppDefault', SOURCE, role, process) for process in ('opera.exe', 'msedge.exe') for role in (0, 1)])
            saved = engine.read_json(Path(folder)/'restore-routes.json')
            self.assertTrue(all(x['routed'] and not x['preferenceKnown'] for x in saved['applications']))
            self.assertEqual(saved['applications'][1]['previousObservedEndpoint'], OTHER.lower())
            self.assertEqual(session.state['RoutedApplications'], ['opera.exe', 'msedge.exe'])

    def test_cleanup_restores_only_still_owned_defaults_and_app_sessions(self):
        before = [device(OUTPUT, True, True), app('opera.exe', OUTPUT), app('msedge.exe', OTHER)]
        current = [device(SOURCE, True), device(OTHER, multimedia=True), app('opera.exe', SOURCE), app('msedge.exe', OTHER)]
        with tempfile.TemporaryDirectory() as folder:
            session, calls = self.session(folder, {'Applications': ['opera.exe', 'msedge.exe']}, before, current)
            session.route()
            calls.clear()
            session.cleanup()
            self.assertIn(('/SetDefault', OUTPUT, 0), calls)
            self.assertNotIn(('/SetDefault', OUTPUT, 1), calls)
            self.assertEqual([x for x in calls if x[0] == '/SetAppDefault'], [('/SetAppDefault', OUTPUT.lower(), role, 'opera.exe') for role in (0, 1)])
            self.assertTrue(any('msedge.exe' in text and 'ignorada' in text for text in session.state['RouteWarnings']))

    def test_unknown_original_uses_default_only_with_owned_session_evidence(self):
        before = [device(OUTPUT, True, True)]
        current = [device(SOURCE, True, True), app('vlc.exe', SOURCE)]
        with tempfile.TemporaryDirectory() as folder:
            session, calls = self.session(folder, {'Applications': ['vlc.exe', 'spotify.exe']}, before, current)
            session.route()
            calls.clear()
            session.cleanup()
            self.assertEqual([x for x in calls if x[0] == '/SetAppDefault'], [('/SetAppDefault', 'DefaultRenderDevice', role, 'vlc.exe') for role in (0, 1)])
            self.assertTrue(any('spotify.exe' in text and 'ignorada' in text for text in session.state['RouteWarnings']))


if __name__ == '__main__':
    unittest.main()

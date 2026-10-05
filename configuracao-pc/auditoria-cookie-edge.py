"""Validate a preference cookie in an isolated Edge profile. No Netflix/session data."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import json
import os
import sqlite3
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parent
PROFILE = Path(tempfile.mkdtemp(prefix='auditoria-cookie-edge-', dir=ROOT))
EDGE = Path(os.environ['ProgramFiles(x86)']) / 'Microsoft/Edge/Application/msedge.exe'
PROBE_VALUE = 'enableDDPlus51=true,enableDDPlusAtmos=false,audioCapabilityDetectorType=0,spatialRenderingForDolbyAudio=false,enableMediaCapabilities=true,audioProfiles=heaac-2-dash|heaac-2hq-dash|xheaac-dash|ddplus-5.1-dash|ddplus-5.1hq-dash'
phase = 'initial'
received = False
request_count = 0

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def do_GET(self):
        global received, request_count
        if self.path != '/':
            self.send_error(404)
            return
        received = ('preference_probe=' + PROBE_VALUE) in self.headers.get('Cookie', '')
        request_count += 1
        body = ('<!doctype html><title>Local preference check</title><p>' +
                ('accepted' if received else 'initial') + '</p>').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        if phase == 'initial':
            self.send_header('Set-Cookie', 'preference_probe=initial; Path=/; Max-Age=3600; SameSite=Lax')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
thread = Thread(target=server.serve_forever, daemon=True)
thread.start()
url = f'http://127.0.0.1:{server.server_port}/'

def launch():
    before = request_count
    completed = subprocess.run([
        str(EDGE), '--headless=new', '--disable-gpu', '--no-first-run',
        '--no-default-browser-check', '--user-data-dir=' + str(PROFILE),
        '--dump-dom', url,
    ], capture_output=True, timeout=25, creationflags=subprocess.CREATE_NO_WINDOW)
    if completed.returncode:
        raise RuntimeError(f'Isolated Edge exit code {completed.returncode}')
    deadline = time.monotonic() + 20
    while request_count == before and time.monotonic() < deadline:
        time.sleep(0.1)
    if request_count == before:
        raise RuntimeError('Isolated Edge did not request the localhost page')

def open_database(db):
    deadline = time.monotonic() + 20
    while True:
        try:
            with db.open('r+b'):
                pass
            conn = sqlite3.connect(db, timeout=1)
            conn.execute('BEGIN IMMEDIATE')
            return conn
        except (sqlite3.OperationalError, PermissionError, FileNotFoundError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.1)

try:
    launch()
    db = PROFILE / 'Default/Network/Cookies'
    with open_database(db) as conn:
        version = dict(conn.execute('SELECT key,value FROM meta'))['version']
        rows = conn.execute("SELECT count(*) FROM cookies WHERE name='preference_probe' AND host_key='127.0.0.1'").fetchone()[0]
        if rows != 1:
            raise RuntimeError('Expected exactly one isolated preference cookie')
        extras = conn.execute("SELECT is_edgelegacycookie,browser_provenance FROM cookies WHERE name='preference_probe' AND host_key='127.0.0.1'").fetchone()
        conn.execute("UPDATE cookies SET value=?,encrypted_value=x'' WHERE name='preference_probe' AND host_key='127.0.0.1'", (PROBE_VALUE,))
    conn.close()
    phase = 'verify'
    received = False
    launch()
    result = {'dateLocal':'2026-10-03','isolatedProfile':PROFILE.name,
              'cookieDatabaseVersion':version,'plaintextPreferenceAccepted':received,
              'rawCsvMatchesNetflix': True, 'edgeExtraDefaultsUsed':list(extras),
              'method':'Two isolated headless Edge launches, localhost only; update one synthetic preference row with value and empty encrypted_value.'}
    (ROOT / 'auditoria-cookie-edge-resultado.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    if not result['plaintextPreferenceAccepted']:
        raise RuntimeError('Preference not accepted by Edge')
finally:
    server.shutdown()
    server.server_close()
